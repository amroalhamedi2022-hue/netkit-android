package com.netkit.local.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netkit.local.connectivity.HotspotManager
import com.netkit.local.connectivity.NetworkStatus
import com.netkit.local.share.AndroidShareStorage
import com.netkit.local.share.AndroidTls
import com.netkit.local.share.TransferHttpEngine
import com.netkit.local.tools.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class NetKitCore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val hotspot = HotspotManager(context)
    val network = NetworkStatus(context)
    val storage = AndroidShareStorage(context)
    private val serverLock = Any()
    private var server: TransferHttpEngine? = null
    private var sessionGeneration = 0L
    private val lastProgressEmit = AtomicLong(0L)
    @Volatile private var disposed = false
    var share by mutableStateOf(ShareUi())
        private set
    var selectedFiles by mutableStateOf(emptyList<String>())
        private set
    var folder by mutableStateOf("لم يتم اختيار مجلد استقبال")
        private set
    var sharedFolderStatus by mutableStateOf(storage.sharedDescription)
        private set
    fun shareDirectory(uri: Uri) {
        stopShare() // Revoke any existing transfer session before broadening scope.
        storage.shareFolder(uri)
        sharedFolderStatus = storage.sharedDescription
    }
    fun shareSharedStorage() {
        stopShare()
        storage.shareSharedStorage()
        sharedFolderStatus = storage.sharedDescription
    }
    fun stopDirectoryShare() {
        stopShare()
        storage.stopFolderShare()
        sharedFolderStatus = storage.sharedDescription
    }
    var result by mutableStateOf("اختر DNS أو اختبار اتصال TCP")
        private set
    var busy by mutableStateOf(false)
        private set

    fun addFiles(uris: List<Uri>) {
        scope.launch(Dispatchers.IO) {
            storage.addDocuments(uris)
            val labels = storage.items().map { it.name }
            withContext(Dispatchers.Main) { selectedFiles = labels }
        }
    }
    fun chooseFolder(uri: Uri) {
        storage.setUploadTree(uri)
        folder = storage.uploadFolderLabel()
    }
    fun startShare(onFailure: (() -> Unit)? = null) {
        if (disposed || share.active || share.starting) return
        val generation = synchronized(serverLock) { ++sessionGeneration }
        share = ShareUi(starting = true, message = "جاري تجهيز خادم HTTPS المحلي…")
        scope.launch {
            try {
                val started = withContext(Dispatchers.IO) {
                    val tls = AndroidTls.loadOrCreate()
                    val created = TransferHttpEngine(tls.factory, storage) { done, total ->
                        // Avoid scheduling thousands of Compose state updates per second on fast LANs.
                        val now = System.nanoTime()
                        val last = lastProgressEmit.get()
                        if (done == total || (now - last > 100_000_000L && lastProgressEmit.compareAndSet(last, now))) {
                            scope.launch {
                                if (share.active && generation == synchronized(serverLock) { sessionGeneration }) {
                                    share = share.copy(
                                        uploadProgress = "$done / $total bytes",
                                        uploadFraction = if (total == 0L) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
                                    )
                                }
                            }
                        }
                    }
                    synchronized(serverLock) {
                        if (disposed || generation != sessionGeneration) {
                            created.close()
                            null
                        } else {
                            try {
                                created.start()
                                server = created
                                Pair(created, tls.fingerprint)
                            } catch (t: Exception) {
                                created.close()
                                throw t
                            }
                        }
                    }
                }
                if (started != null && !disposed && generation == synchronized(serverLock) { sessionGeneration }) {
                    val (engine, fingerprint) = started
                    share = ShareUi(
                        active = true, port = engine.port, token = engine.token,
                        fingerprint = fingerprint,
                        message = "الخادم شغّال داخل الشبكة المحلية — أوقفه بعد الانتهاء"
                    )
                }
            } catch (t: Exception) {
                if (!disposed) {
                    share = ShareUi(message = "فشل تشغيل الخادم: ${t.localizedMessage.orEmpty()}")
                    onFailure?.invoke()
                }
            }
        }
    }
    fun reportShareLaunchFailure(error: Exception) {
        share = ShareUi(message = "تعذّر بدء خدمة المشاركة: ${error.localizedMessage.orEmpty()}")
    }

    fun stopShare() {
        synchronized(serverLock) {
            sessionGeneration++
            server?.close()
            server = null
        }
        share = ShareUi(message = "تم إيقاف المشاركة ومسح رمز الجلسة")
    }
    fun runDns(host: String) = runDiagnostic { Diagnostics.dns(host) }
    fun runTcp(host: String, port: Int) = runDiagnostic { Diagnostics.tcp(host, port) }
    private fun runDiagnostic(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { "تعذّر الفحص: ${it.localizedMessage.orEmpty()}" }
            }
            busy = false
        }
    }
    fun close() {
        disposed = true
        stopShare()
        hotspot.stop()
        scope.cancel()
    }
}

data class ShareUi(
    val active: Boolean = false,
    val starting: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val fingerprint: String = "",
    val uploadProgress: String = "",
    val uploadFraction: Float = 0f,
    val message: String = "الخادم متوقف"
)
