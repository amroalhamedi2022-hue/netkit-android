package com.netkit.local.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** All accesses are limited to explicit Storage Access Framework grants. */
class AndroidShareStorage(private val context: Context) : ShareStorage, DirectoryShareStorage {
    private val directoryBrowser = SharedDirectoryBrowser(context)
    private val transferSettings = context.getSharedPreferences("netkit_transfer_settings", Context.MODE_PRIVATE)
    private val uploadConsent = UploadConsentGate()
    val browserUploadsEnabled: Boolean get() = transferSettings.getBoolean("uploads_enabled", false)
    val maxBrowserUploadMiB: Int get() = transferSettings.getInt("max_upload_mib", 2048).coerceIn(128, 16384)
    fun setBrowserUploadsEnabled(enabled: Boolean) {
        uploadConsent.change {
            transferSettings.edit().putBoolean("uploads_enabled", enabled).apply()
        }
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
    // Reserve only names/commit operations, not the multi-GB SAF stream itself.
    private val uploadNames = UploadNameReservations()

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
    fun setUploadTree(uri: Uri) { uploadConsent.change { uploadTree = uri } }
    fun uploadFolderLabel(): String = uploadTree?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: "لم يتم تحديد مجلد"
    @Synchronized override fun items(): List<ShareItem> = documents.values.map { it.second }
    @Synchronized override fun open(id: String): SharedStream? {
        val value = documents[id] ?: return null
        val input = context.contentResolver.openInputStream(value.first) ?: return null
        return SharedStream(input, value.second.length)
    }
    override fun canUpload(): Boolean = uploadTree != null && browserUploadsEnabled

    private fun ensureUploadConsent(treeUri: Uri, ticket: Long) {
        // The generation also rejects disable/re-enable and A -> B -> A changes.
        uploadConsent.withCurrent(
            ticket, { browserUploadsEnabled && uploadTree == treeUri },
            { throw UploadPermissionRevokedException() }
        ) {}
    }

    override fun upload(
        fileName: String,
        body: InputStream,
        length: Long,
        expectedSha256: String?,
        progress: (Long) -> Unit
    ): String {
        val (consentTicket, treeUri) = uploadConsent.snapshot {
            val tree = uploadTree ?: throw UploadPermissionRevokedException()
            if (!browserUploadsEnabled) throw UploadPermissionRevokedException()
            tree
        }
        ensureUploadConsent(treeUri, consentTicket)
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: error("Folder grant unavailable")
        val reservedName = uploadNames.reserve(fileName) { folder.findFile(it) != null }
        var temp: DocumentFile? = null
        var completed = false
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        try {
            ensureUploadConsent(treeUri, consentTicket)
            temp = folder.createFile("application/octet-stream", ".netkit-${UUID.randomUUID()}.part")
                ?: error("Storage provider denied create")
            val currentTemp = temp
            val stream = context.contentResolver.openOutputStream(currentTemp.uri, "w")
                ?: error("Cannot open destination")
            stream.use { out ->
                var remaining = length
                var received = 0L
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    ensureUploadConsent(treeUri, consentTicket)
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
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) throw UploadHashMismatchException()
            }
            ensureUploadConsent(treeUri, consentTicket)
            // A different SAF client may have created the reserved name while
            // the upload was streaming. Recheck under the short commit lock.
            val finalName = uploadConsent.withCurrent(
                consentTicket,
                { browserUploadsEnabled && uploadTree == treeUri },
                { throw UploadPermissionRevokedException() }
            ) {
                uploadNames.commit(
                    reservedName, fileName,
                    exists = { folder.findFile(it) != null },
                    rename = { currentTemp.renameTo(it) }
                )
            }
            completed = true
            return finalName
        } finally {
            if (!completed) runCatching { temp?.delete() }
            uploadNames.release(reservedName)
        }
    }
}
