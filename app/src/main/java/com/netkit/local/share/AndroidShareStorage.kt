package com.netkit.local.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
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
    private val uploadDestinationSettings = context.getSharedPreferences("netkit_upload_destination", Context.MODE_PRIVATE)
    @Volatile private var uploadTree: Uri? = restoreUploadTree()
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
    /**
     * Snapshot only persisted flags so a failed selection can roll back permission
     * bits it added without revoking grants that already existed for this URI.
     */
    private fun persistedGrantFlags(uri: Uri): PersistedGrantFlags? = runCatching {
        context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
            PersistedGrantFlags(it.isReadPermission, it.isWritePermission)
        } ?: PersistedGrantFlags(read = false, write = false)
    }.getOrNull()

    @Synchronized fun setUploadTree(uri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?: error("لا يمكن فتح مجلد الاستقبال المحدد")
        require(folder.isDirectory && folder.canWrite()) {
            "مجلد الاستقبال لا يسمح بكتابة الملفات"
        }
        val previousGrant = persistedGrantFlags(uri)
            ?: error("تعذّر التحقق من صلاحيات مجلد الاستقبال")
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            val flags = persistedGrantFlags(uri)
                ?: error("تعذّر التحقق من صلاحيات مجلد الاستقبال بعد الحفظ")
            check(UploadDestinationPolicy.canRestore(
                uri.toString(), grant?.uri?.toString(),
                flags.read, flags.write, folder.isDirectory, folder.canWrite()
            )) { "تعذّر الاحتفاظ بصلاحية مجلد الاستقبال بعد إغلاق التطبيق" }
            check(uploadDestinationSettings.edit().putString("tree_uri", uri.toString()).commit()) {
                "تعذّر حفظ مجلد الاستقبال"
            }
            uploadTree = uri
        } catch (failure: Throwable) {
            // Roll back only flags acquired by this attempt. A provider/API failure
            // during rollback must be visible and must retain the original failure.
            FailedUploadGrantRollback.onFailure(failure) {
                val currentGrant = persistedGrantFlags(uri)
                    ?: throw IOException("Could not inspect persisted SAF grants during rollback")
                val added = UploadDestinationPolicy.newlyGranted(previousGrant, currentGrant)
                val releaseFlags =
                    (if (added.read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (added.write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (releaseFlags != 0) {
                    context.contentResolver.releasePersistableUriPermission(uri, releaseFlags)
                    val after = persistedGrantFlags(uri)
                        ?: throw IOException("Could not verify persisted SAF grants after rollback")
                    check(!added.read || !after.read) {
                        "Newly acquired SAF read grant remains after rollback"
                    }
                    check(!added.write || !after.write) {
                        "Newly acquired SAF write grant remains after rollback"
                    }
                }
            }
            throw failure
        }
    }

    private fun restoreUploadTree(): Uri? {
        val saved = uploadDestinationSettings.getString("tree_uri", null) ?: return null
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: run {
            uploadDestinationSettings.edit().remove("tree_uri").commit()
            return null
        }
        if (usableUploadFolder(uri, saved) == null) {
            uploadDestinationSettings.edit().remove("tree_uri").commit()
            return null
        }
        return uri
    }

    private fun usableUploadFolder(uri: Uri, savedUri: String?): DocumentFile? {
        val folder = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return null
        val grant = runCatching {
            context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        }.getOrNull()
        val valid = UploadDestinationPolicy.canRestore(
            savedUri, grant?.uri?.toString(),
            grant?.isReadPermission == true, grant?.isWritePermission == true,
            folder.isDirectory, folder.canWrite()
        )
        return folder.takeIf { valid }
    }

    @Synchronized private fun clearUploadDestinationIfCurrent(uri: Uri) {
        if (uploadTree == uri) {
            uploadTree = null
            uploadDestinationSettings.edit().remove("tree_uri").commit()
        }
    }

    fun uploadFolderLabel(): String {
        val uri = uploadTree ?: return "لم يتم تحديد مجلد استقبال"
        val folder = usableUploadFolder(uri, uploadDestinationSettings.getString("tree_uri", null))
        if (folder == null) {
            clearUploadDestinationIfCurrent(uri)
            return "انتهت صلاحية مجلد الاستقبال؛ اختر المجلد مجددًا"
        }
        return folder.name ?: "مجلد استقبال محدد"
    }
    @Synchronized override fun items(): List<ShareItem> = documents.values.map { it.second }
    @Synchronized override fun open(id: String): SharedStream? {
        val value = documents[id] ?: return null
        val input = context.contentResolver.openInputStream(value.first) ?: return null
        return SharedStream(input, value.second.length)
    }
    override fun canUpload(): Boolean {
        if (!browserUploadsEnabled) return false
        val uri = uploadTree ?: return false
        if (usableUploadFolder(uri, uploadDestinationSettings.getString("tree_uri", null)) != null) return true
        clearUploadDestinationIfCurrent(uri)
        return false
    }

    override fun upload(fileName: String, body: InputStream, length: Long, expectedSha256: String?, progress: (Long) -> Unit): String =
        uploadInternal(fileName, body, length, expectedSha256, ensureSessionValid = {}, progress = progress)

    override fun uploadGuarded(
        fileName: String,
        body: InputStream,
        length: Long,
        expectedSha256: String?,
        ensureSessionValid: () -> Unit,
        progress: (Long) -> Unit
    ): String = uploadInternal(fileName, body, length, expectedSha256, ensureSessionValid, progress)

    private fun uploadInternal(
        fileName: String,
        body: InputStream,
        length: Long,
        expectedSha256: String?,
        ensureSessionValid: () -> Unit,
        progress: (Long) -> Unit
    ): String = uploadLock.withLock {
        ensureSessionValid()
        val treeUri = uploadTree ?: error("No destination folder")
        val folder = usableUploadFolder(treeUri, uploadDestinationSettings.getString("tree_uri", null))
            ?: run {
                clearUploadDestinationIfCurrent(treeUri)
                error("Upload destination permission expired; choose the folder again")
            }
        val chosen = uniqueName(folder, fileName)
        // Write into a private-looking temporary document and rename only after full receipt.
        val temp = folder.createFile("application/octet-stream", ".netkit-${UUID.randomUUID()}.part")
            ?: error("Storage provider denied create")
        var completed = false
        var failure: Throwable? = null
        var publicationReturned = false
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        try {
            val stream = context.contentResolver.openOutputStream(temp.uri, "w") ?: error("Cannot open destination")
            stream.use { out ->
                var remaining = length
                var received = 0L
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    ensureSessionValid()
                    val n = readRequiredUploadChunk(body, buffer, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n < 0) error("Connection closed before complete upload")
                    // Do not let bytes received after lease expiry reach even the private SAF stage.
                    ensureSessionValid()
                    out.write(buffer, 0, n)
                    digest?.update(buffer, 0, n)
                    remaining -= n
                    received += n
                    progress(received)
                }
                out.flush()
            }
            // Never finalize a corrupted file: a .part is removed on mismatch or failure.
            ensureSessionValid()
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) throw UploadHashMismatchException()
            }
            // SAF rename may return a new document URI. If authorization expires
            // during publication, rollback must delete that exact published URI;
            // the pre-rename temp handle may no longer identify the document.
            GuardedPublication.publish(
                ensureAuthorized = ensureSessionValid,
                publish = {
                    val published = DocumentsContract.renameDocument(context.contentResolver, temp.uri, chosen)
                        ?: throw IOException("Provider cannot finalize this file (rename failed)")
                    publicationReturned = true
                    published
                },
                rollback = { publishedUri ->
                    if (!DocumentsContract.deleteDocument(context.contentResolver, publishedUri)) {
                        throw IOException("Provider refused rollback after share session expiry")
                    }
                }
            )
            completed = true
            return@withLock chosen
        } catch (transferFailure: Throwable) {
            failure = transferFailure
            throw transferFailure
        } finally {
            // A failed upload must not silently leave its private .part document behind.
            // After rename returns, GuardedPublication owns rollback of the exact published URI.
            if (!completed && !publicationReturned) {
                FailedUploadStageCleanup.onFailure(failure) { temp.delete() }
            }
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
