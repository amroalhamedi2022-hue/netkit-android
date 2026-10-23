package com.netkit.local.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netkit.local.connectivity.HotspotManager
import com.netkit.local.connectivity.NetworkStatus
import com.netkit.local.connectivity.PeerDiscovery
import com.netkit.local.settings.NetKitSettings
import com.netkit.local.transfer.TransferQueue
import com.netkit.local.transfer.FileTransferJournal
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
    val settings = NetKitSettings(context)
    val discovery = PeerDiscovery(context)
    val transfers = TransferQueue(FileTransferJournal(java.io.File(context.filesDir, "netkit_transfer_queue.properties")))
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
    var browserUploadsEnabled by mutableStateOf(storage.browserUploadsEnabled)
        private set
    var maxBrowserUploadMiB by mutableIntStateOf(storage.maxBrowserUploadMiB)
        private set
    fun configureBrowserUploads(enabled: Boolean) {
        storage.setBrowserUploadsEnabled(enabled)
        browserUploadsEnabled = enabled
    }
    fun configureUploadLimit(mib: Int) {
        storage.setMaxBrowserUploadMiB(mib)
        maxBrowserUploadMiB = storage.maxBrowserUploadMiB
    }
    fun shareDirectory(uri: Uri) {
        stopShare() // Revoke any existing transfer session before broadening scope.
        sharedFolderStatus = runCatching {
            storage.shareFolder(uri)
            storage.sharedDescription
        }.getOrElse { "تعذّر مشاركة المجلد: ${it.localizedMessage.orEmpty()}" }
    }
    fun shareSharedStorage() {
        stopShare()
        sharedFolderStatus = runCatching {
            storage.shareSharedStorage()
            storage.sharedDescription
        }.getOrElse { "تعذّر مشاركة التخزين: ${it.localizedMessage.orEmpty()}" }
    }
    fun reportStoragePermissionDenied() {
        sharedFolderStatus = "لم يتم منح صلاحية مشاركة التخزين المشترك. اختر مجلدًا عبر SAF أو فعّل الصلاحية من النظام."
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
                                discovery.advertise(created.port)
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
                    // The saved duration actually revokes the existing token and closes sockets.
                    scope.launch {
                        kotlinx.coroutines.delay(settings.shareExpirationMinutes * 60_000L)
                        if (!disposed && generation == synchronized(serverLock) { sessionGeneration }) {
                            stopShare()
                            share = ShareUi(message = "انتهت صلاحية المشاركة المحلية، ابدأ جلسة جديدة")
                        }
                    }
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
        discovery.stopAdvertising()
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
        discovery.close()
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
