package com.netkit.local.remote

import android.content.Context
import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64

/** Remote file protocols operate only on explicitly chosen SAF streams; never on arbitrary app-private paths. */
data class RemoteEntry(val name: String, val directory: Boolean, val size: Long)
interface RemoteFileClient : Closeable {
    fun entries(path: String): List<RemoteEntry>
    fun download(remotePath: String, destination: Uri, context: Context, progress: (Long) -> Unit = {}): Boolean
    fun upload(source: Uri, remotePath: String, context: Context, progress: (Long) -> Unit = {}): Boolean
}

class FtpRemoteClient(host: String, port: Int, user: String, pass: String, timeoutMs: Int) : RemoteFileClient {
    private val ftp = FTPClient()
    init {
        require(port in 1..65535)
        ftp.connectTimeout = timeoutMs
        ftp.defaultTimeout = timeoutMs
        ftp.dataTimeout = java.time.Duration.ofMillis(timeoutMs.toLong())
        ftp.connect(host, port)
        try {
            check(ftp.login(user, pass)) { "FTP login rejected" }
            ftp.enterLocalPassiveMode()
            check(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "FTP binary mode rejected" }
        } catch (failure: Exception) { runCatching { ftp.disconnect() }; throw failure }
    }
    override fun entries(path: String): List<RemoteEntry> = ftp.listFiles(path).map {
        RemoteEntry(it.name, it.isDirectory, it.size)
    }.filter { it.name != "." && it.name != ".." }
    override fun download(remotePath: String, destination: Uri, context: Context, progress: (Long) -> Unit): Boolean {
        val output = context.contentResolver.openOutputStream(destination, "w") ?: error("No permission to write")
        ProgressOutputStream(output, progress).use { return ftp.retrieveFile(remotePath, it) }
    }
    override fun upload(source: Uri, remotePath: String, context: Context, progress: (Long) -> Unit): Boolean {
        val input = context.contentResolver.openInputStream(source) ?: error("No permission to read")
        ProgressInputStream(input, progress).use { return ftp.storeFile(remotePath, it) }
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
    init {
        require(port in 1..65535)
        require(trustedKeySha256.matches(Regex("SHA256:[a-zA-Z0-9+/]{43}=?"))) {
            "Enter an independently verified SHA256:... SSH host key fingerprint"
        }
        val jsch=JSch()
        val established=jsch.getSession(user,host,port)
        established.setPassword(pass)
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
    override fun entries(path:String):List<RemoteEntry> = sftp.ls(path).toList().mapNotNull { raw ->
        val item=raw as? ChannelSftp.LsEntry ?: return@mapNotNull null
        if(item.filename=="." || item.filename=="..") null else
            RemoteEntry(item.filename,item.attrs.isDir,item.attrs.size)
    }
    override fun download(remotePath:String,destination:Uri,context:Context,progress:(Long)->Unit):Boolean {
        val out=context.contentResolver.openOutputStream(destination,"w")?:error("Cannot write destination")
        ProgressOutputStream(out,progress).use { sftp.get(remotePath,it) }
        return true
    }
    override fun upload(source:Uri,remotePath:String,context:Context,progress:(Long)->Unit):Boolean {
        val input=context.contentResolver.openInputStream(source)?:error("Cannot read source")
        ProgressInputStream(input,progress).use { sftp.put(it,remotePath,ChannelSftp.OVERWRITE) }
        return true
    }
    override fun close() {runCatching{sftp.disconnect()};runCatching{session.disconnect()}}
}
