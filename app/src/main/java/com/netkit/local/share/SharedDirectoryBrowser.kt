package com.netkit.local.share

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

/**
 * Lazy, user-authorized directory browser for SAF trees and shared storage.
 * Never traverses private app data. All IDs are ephemeral, random server-side handles.
 */
class SharedDirectoryBrowser(private val context: Context) {
    private sealed interface Handle {
        data class Saf(val document: DocumentFile) : Handle
        data class Local(val file: File) : Handle
    }
    private val lock = Any()
    private val handles = ShareHandleRegistry<Handle>()
    private var root: Handle? = null
    var description: String = "لم يتم اختيار مجلد للمشاركة"
        private set

    init {
        val pref = context.getSharedPreferences("netkit_shared_folder", Context.MODE_PRIVATE)
        val mode = pref.getString("mode", "saf")
        if (mode == "shared" && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())) {
            runCatching { setSharedStorage() }
        } else if (mode == "saf") {
            val stored = pref.getString("uri", null)
            if (stored != null) {
                val uri = Uri.parse(stored)
                if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                    runCatching { setSafFolder(uri) }
                }
            }
        }
    }

    fun setSafFolder(uri: Uri) = synchronized(lock) {
        val folder = DocumentFile.fromTreeUri(context, uri)
        require(folder != null && folder.isDirectory && folder.canRead()) { "لا يمكن قراءة المجلد المختار" }
        root = Handle.Saf(folder)
        description = "مجلد: ${folder.name ?: "بدون اسم"} (مع المجلدات الفرعية)"
        context.getSharedPreferences("netkit_shared_folder", Context.MODE_PRIVATE)
            .edit().putString("uri", uri.toString()).putString("mode", "saf").apply()
        resetHandles()
    }

    fun setSharedStorage() = synchronized(lock) {
        require(Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            "يجب السماح بإدارة الملفات من إعدادات Android"
        }
        val volume = Environment.getExternalStorageDirectory().canonicalFile
        require(volume.isDirectory && volume.canRead()) { "مساحة التخزين المشتركة غير متاحة" }
        root = Handle.Local(volume)
        description = "مساحة التخزين الداخلية المشتركة (قراءة فقط)"
        context.getSharedPreferences("netkit_shared_folder", Context.MODE_PRIVATE)
            .edit().remove("uri").putString("mode", "shared").apply()
        resetHandles()
    }

    fun disable() = synchronized(lock) {
        root = null
        description = "مشاركة المجلدات متوقفة"
        context.getSharedPreferences("netkit_shared_folder", Context.MODE_PRIVATE)
            .edit().remove("uri").putString("mode", "none").apply()
        resetHandles()
    }

    fun hasRoot(): Boolean = synchronized(lock) { root != null }

    private fun resetHandles() {
        handles.reset(root)
    }

    private fun key(h: Handle): String = when (h) {
        is Handle.Saf -> "s:${h.document.uri}"
        is Handle.Local -> "l:${h.file.canonicalPath}"
    }

    private fun handleId(h: Handle): String {
        val key = key(h)
        return handles.register(key, h)
    }

    private fun localAllowed(file: File): Boolean {
        val base = (root as? Handle.Local)?.file?.canonicalFile ?: return false
        val resolved = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (resolved != base && !resolved.path.startsWith(base.path + File.separator)) return false
        val relative = resolved.relativeTo(base).invariantSeparatorsPath
        return relative != "Android/data" && relative != "Android/obb" &&
            !relative.startsWith("Android/data/") && !relative.startsWith("Android/obb/")
    }

    fun page(directoryId: String, offset: Int, size: Int): ShareBrowsePage = synchronized(lock) {
        val folder = handles.find(directoryId) ?: return ShareBrowsePage(emptyList(), null)
        val all: List<Handle> = when (folder) {
            is Handle.Saf -> if (folder.document.isDirectory) folder.document.listFiles()
                .filter { it.canRead() }.map { Handle.Saf(it) } else emptyList()
            is Handle.Local -> if (folder.file.isDirectory && localAllowed(folder.file))
                (folder.file.listFiles()?.filter { localAllowed(it) && it.canRead() }
                    ?.map { Handle.Local(it) } ?: emptyList()) else emptyList()
        }
        val ordered = all.sortedWith(compareBy<Handle> {
            when (it) { is Handle.Saf -> !it.document.isDirectory; is Handle.Local -> !it.file.isDirectory }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) {
            when (it) { is Handle.Saf -> it.document.name.orEmpty(); is Handle.Local -> it.file.name }
        })
        val from = offset.coerceIn(0, ordered.size)
        val to = (from + size.coerceIn(1, 200)).coerceAtMost(ordered.size)
        val entries = ordered.subList(from, to).map { h ->
            when (h) {
                is Handle.Saf -> ShareBrowseEntry(handleId(h), h.document.name.orEmpty(),
                    h.document.isDirectory, if (h.document.isFile) h.document.length() else null)
                is Handle.Local -> ShareBrowseEntry(handleId(h), h.file.name, h.file.isDirectory,
                    if (h.file.isFile) h.file.length() else null)
            }
        }
        ShareBrowsePage(entries, if (to < ordered.size) to else null)
    }

    fun file(id: String): Pair<ShareItem, SharedStream>? = synchronized(lock) {
        val node = handles.find(id) ?: return null
        when (node) {
            is Handle.Saf -> {
                if (!node.document.isFile || !node.document.canRead()) return null
                val stream = context.contentResolver.openInputStream(node.document.uri) ?: return null
                ShareItem(id, node.document.name.orEmpty(), node.document.length()) to
                    SharedStream(stream, node.document.length().takeIf { it > 0L })
            }
            is Handle.Local -> {
                if (!localAllowed(node.file) || !node.file.isFile || !node.file.canRead()) return null
                val stream = FileInputStream(node.file)
                ShareItem(id, node.file.name, node.file.length()) to SharedStream(stream, node.file.length())
            }
        }
    }

    fun item(id: String): ShareItem? = synchronized(lock) {
        when (val n = handles.find(id)) {
            is Handle.Saf -> if (n.document.isFile && n.document.canRead())
                ShareItem(id, n.document.name.orEmpty(), n.document.length()) else null
            is Handle.Local -> if (localAllowed(n.file) && n.file.isFile && n.file.canRead())
                ShareItem(id, n.file.name, n.file.length()) else null
            null -> null
        }
    }
}
