package com.netkit.local.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** All accesses are limited to explicit Storage Access Framework grants. */
class AndroidShareStorage(private val context: Context) : ShareStorage, DirectoryShareStorage {
    private val directoryBrowser = SharedDirectoryBrowser(context)
    private val transferSettings = context.getSharedPreferences("netkit_transfer_settings", Context.MODE_PRIVATE)
    val browserUploadsEnabled: Boolean get() = transferSettings.getBoolean("uploads_enabled", false)
    val maxBrowserUploadMiB: Int get() = transferSettings.getInt("max_upload_mib", 2048).coerceIn(128, 16384)
    fun setBrowserUploadsEnabled(enabled: Boolean) {
        transferSettings.edit().putBoolean("uploads_enabled", enabled).apply()
    }
    fun setMaxBrowserUploadMiB(size: Int) {
        require(size in listOf(512, 2048, 16384)) { "Unsupported limit" }
        transferSettings.edit().putInt("max_upload_mib", size).apply()
    }
    override fun maxUploadSize(): Long = maxBrowserUploadMiB.toLong() * 1024 * 1024
    val sharedDescription: String get() = directoryBrowser.description
    fun shareFolder(uri: Uri) = directoryBrowser.setSafFolder(uri)
    fun shareSharedStorage() = directoryBrowser.setSharedStorage()
    fun stopFolderShare() = directoryBrowser.disable()
    override fun hasSharedDirectory(): Boolean = directoryBrowser.hasRoot()
    override fun browseDirectory(directoryId: String, offset: Int, limit: Int): ShareBrowsePage =
        directoryBrowser.page(directoryId, offset, limit)
    override fun directoryItem(id: String): ShareItem? = directoryBrowser.item(id)
    override fun openDirectoryItem(id: String): SharedStream? = directoryBrowser.file(id)?.second
    private val documents = LinkedHashMap<String, Pair<Uri, ShareItem>>()
    @Volatile private var uploadTree: Uri? = null
    // Serialize only destination name reservation/commit. A slow multi-GB upload must
    // NOT block the synchronized items()/open() methods used by other download clients.
    private val uploadLock = ReentrantLock(true)

    @Synchronized fun addDocuments(uris: List<Uri>) {
        uris.forEach { uri ->
            if (documents.values.any { it.first == uri }) return@forEach
            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            val (name, size) = cursor?.use {
                if (it.moveToFirst()) Pair(it.getString(0) ?: "shared-file", if (it.isNull(1)) null else it.getLong(1).takeIf { size -> size >= 0L })
                else Pair("shared-file", null)
            } ?: Pair("shared-file", null)
            val id = UUID.randomUUID().toString()
            documents[id] = Pair(uri, ShareItem(id, name, size))
        }
    }
    fun setUploadTree(uri: Uri) { uploadTree = uri }
    fun uploadFolderLabel(): String = uploadTree?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: "لم يتم تحديد مجلد"
    @Synchronized override fun items(): List<ShareItem> = documents.values.map { it.second }
    @Synchronized override fun open(id: String): SharedStream? {
        val value = documents[id] ?: return null
        val input = context.contentResolver.openInputStream(value.first) ?: return null
        return SharedStream(input, value.second.length)
    }
    override fun canUpload(): Boolean = uploadTree != null && browserUploadsEnabled

    private fun ensureUploadConsent(treeUri: Uri) {
        // Changing the destination or turning browser uploads off must also revoke
        // a transfer already in progress, not merely block the next HTTP request.
        if (!browserUploadsEnabled || uploadTree != treeUri) {
            throw UploadPermissionRevokedException()
        }
    }

    override fun upload(fileName: String, body: InputStream, length: Long, expectedSha256: String?, progress: (Long) -> Unit): String = uploadLock.withLock {
        val treeUri = uploadTree ?: throw UploadPermissionRevokedException()
        ensureUploadConsent(treeUri)
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: error("Folder grant unavailable")
        val chosen = uniqueName(folder, fileName)
        // Write into a private-looking temporary document and rename only after full receipt.
        val temp = folder.createFile("application/octet-stream", ".netkit-${UUID.randomUUID()}.part")
            ?: error("Storage provider denied create")
        var completed = false
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        try {
            val stream = context.contentResolver.openOutputStream(temp.uri, "w") ?: error("Cannot open destination")
            stream.use { out ->
                var remaining = length
                var received = 0L
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    ensureUploadConsent(treeUri)
                    val n = StreamReadGuard.read(body, buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n < 0) error("Connection closed before complete upload")
                    out.write(buffer, 0, n)
                    digest?.update(buffer, 0, n)
                    remaining -= n
                    received += n
                    progress(received)
                }
                out.flush()
            }
            // Never finalize a corrupted file: a .part is removed on mismatch or failure.
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) throw UploadHashMismatchException()
            }
            ensureUploadConsent(treeUri)
            if (!temp.renameTo(chosen)) error("Provider cannot finalize this file (rename failed)")
            completed = true
            return@withLock chosen
        } finally {
            if (!completed) runCatching { temp.delete() }
        }
    }
    private fun uniqueName(folder: DocumentFile, filename: String): String {
        var candidate = filename
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var counter = 2
        while (folder.findFile(candidate) != null) {
            require(counter < 10000) { "Too many duplicate files" }
            candidate = "$base ($counter)$ext"
            counter++
        }
        return candidate
    }
}
