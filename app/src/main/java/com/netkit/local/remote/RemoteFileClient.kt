package com.netkit.local.remote

import android.content.Context
import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.DigestInputStream
import java.util.Base64

/** Remote file protocols operate only on explicitly chosen SAF streams; never on arbitrary app-private paths. */
data class RemoteEntry(val name: String, val directory: Boolean, val size: Long)
interface RemoteFileClient : Closeable {
    fun entries(path: String): List<RemoteEntry>
    fun download(remotePath: String, destination: Uri, context: Context, progress: (Long) -> Unit = {}): Boolean
    fun upload(source: Uri, remotePath: String, context: Context,
        progress: (Long) -> Unit = {}, verifySha256: Boolean = false): Boolean
    /** Opt-in only: remote prefix must be readable, byte-identical, then SHA-256 verified. */
    fun resumeUpload(source: Uri, remotePath: String, context: Context,
        progress: (Long) -> Unit = {}): Boolean
    /** Opt-in app-private staging; validates complete remote SHA-256 before SAF publish. */
    fun verifiedDownload(remotePath: String, destination: Uri, context: Context,
        progress: (Long) -> Unit = {}): Boolean
}

/** Scoped cache key includes endpoint and remote path, never the password. */
private fun verifiedDownloadToSaf(
    context: Context, destination: Uri, identity: String, remotePath: String,
    expected: Long, progress: (Long) -> Unit,
    remoteStream: () -> InputStream, remoteSize: () -> Long
): Boolean {
    require(expected >= 0) { "Remote server must report file size for verified download" }
    val key = MessageDigest.getInstance("SHA-256")
        .digest((identity + "\n" + remotePath).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val directory = File(context.cacheDir, "netkit-verified-downloads")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create private download staging directory" }
    val part = File(directory, "$key.part")
    val lockFile = File(directory, "$key.lock")
    FileOutputStream(lockFile, true).channel.use { channel ->
        val lock = channel.tryLock() ?: error("Another verified download of this file is in progress")
        lock.use {
            val remaining = expected - (if (part.isFile) part.length() else 0L)
            check(remaining <= directory.usableSpace - 16L * 1024 * 1024) {
                "Not enough free app storage for verified staging; clear space or use regular download"
            }
            val digest = remoteStream().use { VerifiedDownloadStaging.stage(it, part, expected, progress) }
            check(remoteSize() == expected) { "Remote length changed during staged download" }
            remoteStream().use { VerifiedDownloadStaging.verifyReadback(it, expected, digest) }
            check(remoteSize() == expected) { "Remote length changed before SAF publication" }
            VerifiedDownloadStaging.publish(part, digest,
                { context.contentResolver.openOutputStream(destination, "w")
                    ?: error("Cannot write selected SAF document") },
                { context.contentResolver.openInputStream(destination)
                    ?: error("Cannot re-read selected SAF document for SHA-256 verification") })
        }
    }
    return true
}


class FtpRemoteClient(host: String, port: Int, user: String, pass: String, timeoutMs: Int) : RemoteFileClient {
    private val ftp = FTPClient()
    private val endpointIdentity = "ftp|" + RemotePathPolicy.host(host) + "|" + port + "|" + user
    init {
        require(port in 1..65535)
        ftp.connectTimeout = timeoutMs
        ftp.defaultTimeout = timeoutMs
        ftp.dataTimeout = java.time.Duration.ofMillis(timeoutMs.toLong())
        ftp.connect(RemotePathPolicy.host(host), port)
        try {
            check(ftp.login(RemotePathPolicy.credential(user), RemotePathPolicy.credential(pass, allowEmpty = true))) { "FTP login rejected" }
            ftp.enterLocalPassiveMode()
            check(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "FTP binary mode rejected" }
        } catch (failure: Exception) { runCatching { ftp.disconnect() }; throw failure }
    }
    override fun entries(path: String): List<RemoteEntry> = ftp.listFiles(RemotePathPolicy.path(path)).map {
        RemoteEntry(it.name, it.isDirectory, it.size)
    }.filter { RemotePathPolicy.safeEntryName(it.name) }
    override fun download(remotePath: String, destination: Uri, context: Context, progress: (Long) -> Unit): Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val expected = runCatching { ftp.mlistFile(safePath)?.size }.getOrNull()
        val output = context.contentResolver.openOutputStream(destination, "w") ?: error("No permission to write")
        ProgressOutputStream(output, progress).use {
            val ok = ftp.retrieveFile(safePath, it)
            if (ok) TransferLengthVerifier.requireMatch(expected, it.transferred)
            return ok
        }
    }
    override fun verifiedDownload(remotePath: String, destination: Uri, context: Context,
        progress: (Long) -> Unit): Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val expected = ftp.mlistFile(safePath)?.size
            ?: error("FTP MLST/SIZE required for verified download")
        return verifiedDownloadToSaf(context, destination,
            endpointIdentity,
            safePath, expected, progress,
            {
                val stream = ftp.retrieveFileStream(safePath)
                    ?: error("FTP server refused verified readback")
                object : FilterInputStream(stream) {
                    override fun close() {
                        super.close()
                        check(ftp.completePendingCommand()) { "FTP RETR control reply failed" }
                    }
                }
            },
            { ftp.mlistFile(safePath)?.size ?: error("FTP remote size became unavailable") })
    }
    override fun upload(source: Uri, remotePath: String, context: Context,
        progress: (Long) -> Unit, verifySha256: Boolean): Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val input = context.contentResolver.openInputStream(source) ?: error("No permission to read")
        val localDigest = MessageDigest.getInstance("SHA-256")
        ProgressInputStream(DigestInputStream(input, localDigest), progress).use {
            val ok = ftp.storeFile(safePath, it)
            if (ok) {
                val remoteBytes = runCatching { ftp.mlistFile(safePath)?.size }.getOrNull()
                TransferLengthVerifier.requireMatch(remoteBytes, it.transferred)
                if (verifySha256) {
                    // A readback is explicit: some FTP accounts are upload-only.
                    // retrieveFileStream requires completePendingCommand AFTER
                    // the data stream is closed, even on a fully consumed file.
                    val remote = ftp.retrieveFileStream(safePath)
                        ?: error("FTP server refused SHA-256 readback")
                    val remoteDigest = remote.use { stream -> RemoteDigestVerifier.digest(stream) }
                    check(ftp.completePendingCommand()) { "FTP readback completion rejected" }
                    RemoteDigestVerifier.requireMatch(localDigest.digest(), remoteDigest)
                }
            }
            return ok
        }
    }
    override fun resumeUpload(source: Uri, remotePath: String, context: Context,
        progress: (Long) -> Unit): Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val existing = ftp.mlistFile(safePath)?.size
            ?: error("FTP server must support MLST/SIZE to confirm the resume offset")
        require(existing > 0) { "No nonempty remote prefix; use normal upload instead" }
        val local = context.contentResolver.openInputStream(source) ?: error("No SAF read permission")
        DigestInputStream(local, MessageDigest.getInstance("SHA-256")).use { selected ->
            val remote = ftp.retrieveFileStream(safePath)
                ?: error("FTP server must permit reading the existing remote prefix")
            try {
                remote.use { VerifiedResumePrefix.verify(selected, it, existing) }
            } finally {
                // retrieveFileStream leaves a pending control reply after data socket close.
                check(ftp.completePendingCommand()) { "FTP prefix read did not complete" }
            }
            check(ftp.mlistFile(safePath)?.size == existing) {
                "Remote size changed during resume verification"
            }
            progress(existing)
            val suffix = ProgressInputStream(selected) { bytes ->
                progress(Math.addExact(existing, bytes))
            }
            // appendFile consumes the suffix but does not close the provided stream.
            val appended = ftp.appendFile(safePath, suffix)
            if (!appended) return false
            val total = Math.addExact(existing, suffix.transferred)
            TransferLengthVerifier.requireMatch(total, ftp.mlistFile(safePath)?.size
                ?: error("FTP server stopped reporting remote length"))
            // Mandatory full-file readback: partial remote prefixes must never be
            // trusted from SIZE alone, even after bytewise prefix verification.
            val readback = ftp.retrieveFileStream(safePath)
                ?: error("FTP server refused post-resume SHA-256 readback")
            val digest = try {
                readback.use { RemoteDigestVerifier.digest(it) }
            } finally {
                check(ftp.completePendingCommand()) { "FTP SHA-256 readback did not complete" }
            }
            RemoteDigestVerifier.requireMatch(selected.messageDigest.digest(), digest)
        }
        return true
    }
    override fun close() { runCatching { ftp.logout() }; if (ftp.isConnected) ftp.disconnect() }
}

/** Host-key pinning is mandatory: the caller must independently verify the SHA-256 fingerprint.
 *  No StrictHostKeyChecking=no and no hidden acceptance of unknown keys.
 */
class SftpRemoteClient(host: String, port: Int, user: String, pass: String,
    trustedKeySha256: String, timeoutMs: Int) : RemoteFileClient {
    private val session: Session
    private val sftp: ChannelSftp
    private val endpointIdentity = "sftp|" + RemotePathPolicy.host(host) + "|" + port + "|" + user + "|" + trustedKeySha256
    init {
        require(port in 1..65535)
        require(trustedKeySha256.matches(Regex("SHA256:[a-zA-Z0-9+/]{43}=?"))) {
            "Enter an independently verified SHA256:... SSH host key fingerprint"
        }
        val jsch=JSch()
        val established=jsch.getSession(RemotePathPolicy.credential(user),RemotePathPolicy.host(host),port)
        established.setPassword(RemotePathPolicy.credential(pass, allowEmpty = true))
        established.setConfig("StrictHostKeyChecking", "yes")
        // JSch's public-key repository expects canonical host keys. Trust only a key whose
        // SHA-256 matches explicit input; never accept every/unknown presented key.
        jsch.setHostKeyRepository(object : com.jcraft.jsch.HostKeyRepository {
            override fun getKnownHostsRepositoryID(): String = "NetKit pinned SHA-256"
            override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
            override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun check(host: String?, key: ByteArray?): Int {
                if(key == null) return com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED
                val hash=MessageDigest.getInstance("SHA-256").digest(key)
                val fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(hash)
                return if (fingerprint == trustedKeySha256.trim().removeSuffix("="))
                    com.jcraft.jsch.HostKeyRepository.OK else com.jcraft.jsch.HostKeyRepository.CHANGED
            }
        })
        established.connect(timeoutMs)
        session=established
        try {
            sftp=session.openChannel("sftp") as ChannelSftp
            sftp.connect(timeoutMs)
        } catch(ex:Exception) { session.disconnect(); throw ex }
    }
    override fun entries(path:String):List<RemoteEntry> = sftp.ls(RemotePathPolicy.path(path)).toList().mapNotNull { raw ->
        val item=raw as? ChannelSftp.LsEntry ?: return@mapNotNull null
        if(!RemotePathPolicy.safeEntryName(item.filename)) null else
            RemoteEntry(item.filename,item.attrs.isDir,item.attrs.size)
    }
    override fun download(remotePath:String,destination:Uri,context:Context,progress:(Long)->Unit):Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val expected = sftp.stat(safePath).size
        val out=context.contentResolver.openOutputStream(destination,"w")?:error("Cannot write destination")
        ProgressOutputStream(out,progress).use {
            sftp.get(safePath,it)
            TransferLengthVerifier.requireMatch(expected,it.transferred)
        }
        return true
    }
    override fun verifiedDownload(remotePath: String, destination: Uri, context: Context,
        progress: (Long) -> Unit): Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val expected = sftp.stat(safePath).size
        return verifiedDownloadToSaf(context, destination,
            endpointIdentity,
            safePath, expected, progress,
            { sftp.get(safePath) },
            { sftp.stat(safePath).size })
    }
    override fun upload(source:Uri,remotePath:String,context:Context,progress:(Long)->Unit,
        verifySha256:Boolean):Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val input=context.contentResolver.openInputStream(source)?:error("Cannot read source")
        val localDigest = MessageDigest.getInstance("SHA-256")
        ProgressInputStream(DigestInputStream(input, localDigest),progress).use {
            sftp.put(it,safePath,ChannelSftp.OVERWRITE)
            val remoteBytes = sftp.stat(safePath).size
            TransferLengthVerifier.requireMatch(remoteBytes,it.transferred)
            if (verifySha256) {
                val remoteDigest = sftp.get(safePath).use { stream ->
                    RemoteDigestVerifier.digest(stream)
                }
                RemoteDigestVerifier.requireMatch(localDigest.digest(), remoteDigest)
            }
        }
        return true
    }
    override fun resumeUpload(source: Uri, remotePath: String, context: Context,
        progress: (Long) -> Unit): Boolean {
        val safePath = RemotePathPolicy.path(remotePath)
        val existing = sftp.stat(safePath).size
        require(existing > 0) { "No nonempty remote prefix; use normal upload instead" }
        val local = context.contentResolver.openInputStream(source) ?: error("No SAF read permission")
        DigestInputStream(local, MessageDigest.getInstance("SHA-256")).use { selected ->
            sftp.get(safePath).use { remote ->
                VerifiedResumePrefix.verify(selected, remote, existing)
            }
            check(sftp.stat(safePath).size == existing) {
                "Remote size changed during resume verification"
            }
            progress(existing)
            val suffix = ProgressInputStream(selected) { bytes ->
                progress(Math.addExact(existing, bytes))
            }
            // APPEND is deliberate: JSch RESUME would skip the verified prefix
            // a second time, since [selected] is already positioned at offset.
            sftp.put(suffix, safePath, ChannelSftp.APPEND)
            val total = Math.addExact(existing, suffix.transferred)
            TransferLengthVerifier.requireMatch(total, sftp.stat(safePath).size)
            val digest = sftp.get(safePath).use { RemoteDigestVerifier.digest(it) }
            RemoteDigestVerifier.requireMatch(selected.messageDigest.digest(), digest)
        }
        return true
    }
    override fun close() {runCatching{sftp.disconnect()};runCatching{session.disconnect()}}
}
