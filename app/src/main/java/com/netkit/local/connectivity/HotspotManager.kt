package com.netkit.local.connectivity

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owns the reservation. Local-only hotspot != mobile-data tethering. */
class HotspotManager(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val prefs = HotspotSettingsStore(appContext)
    val supportsCustomSettings: Boolean get() = Build.VERSION.SDK_INT >= 36
    var preferredName by mutableStateOf(prefs.ssid())
        private set
    var preferredPassword by mutableStateOf(prefs.password())
        private set
    var preferredBand by mutableStateOf(prefs.band())
        private set

    init {
        if (preferredPassword.isBlank()) {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val random = java.security.SecureRandom()
            val password = (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            prefs.update(preferredName, password, preferredBand)
            preferredPassword = password
        }
    }

    fun updateConfiguration(name: String, password: String, band: String) {
        require(supportsCustomSettings) { "إعداد الشبكة المخصص يحتاج Android API 36 أو أحدث" }
        require(!state.active && !state.starting) { "أوقف Hotspot قبل تغيير الإعدادات" }
        prefs.update(name, password, band)
        preferredName = prefs.ssid()
        preferredPassword = prefs.password()
        preferredBand = prefs.band()
    }
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var requestPending = false
    /** Called on the main thread when Android refuses or unexpectedly stops the reservation. */
    var onUnavailable: (() -> Unit)? = null
    var state by mutableStateOf(HotspotUi())
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < 26) {
            state = HotspotUi(status = "غير مدعوم على إصدار Android الحالي")
            return
        }
        if (state.starting || reservation != null) return
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissions.any { appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionDenied()
            onUnavailable?.invoke()
            return
        }
        state = HotspotUi(status = "جاري تشغيل الشبكة المحلية…", starting = true)
        requestPending = true
        try {
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    if (!requestPending) { value.close(); return }
                    reservation = value
                    val pair: Pair<String, String> = if (Build.VERSION.SDK_INT >= 30) {
                        val cfg: SoftApConfiguration = value.softApConfiguration
                        Pair(cfg.ssid.orEmpty(), cfg.passphrase.orEmpty())
                    } else {
                        @Suppress("DEPRECATION") val cfg = value.wifiConfiguration
                        Pair(cfg?.SSID.orEmpty().trim('"'), cfg?.preSharedKey.orEmpty())
                    }
                    // Android only exposes public SoftApConfiguration channel/band metadata in API 36+.
                    val configuredBand = if (Build.VERSION.SDK_INT >= 36) {
                        describeConfiguredBand(value.softApConfiguration)
                    } else "لا يتيح Android عرض النطاق للتطبيقات العادية بهذا الإصدار"
                    state = HotspotUi(
                        active = true, ssid = pair.first, password = pair.second,
                        configuredBand = configuredBand,
                        status = "الشبكة المحلية جاهزة؛ بدون مشاركة إنترنت"
                    )
                }

                override fun onStopped() {
                    requestPending = false
                    reservation = null
                    state = HotspotUi(status = "تم إيقاف نقطة الاتصال المحلية")
                    onUnavailable?.invoke()
                }

                override fun onFailed(reason: Int) {
                    requestPending = false
                    reservation = null
                    val message = when (reason) {
                        ERROR_NO_CHANNEL -> "تعذّر اختيار قناة: جرّب إيقاف Hotspot الحالي أو تغيير موقع الجهاز"
                        ERROR_INCOMPATIBLE_MODE -> "وضع Wi-Fi الحالي لا يسمح بتشغيل نقطة محلية"
                        ERROR_TETHERING_DISALLOWED -> "النظام لا يسمح بإنشاء نقطة اتصال محلية"
                        else -> "تعذّر إنشاء نقطة الاتصال؛ رمز الخطأ: $reason"
                    }
                    state = HotspotUi(status = message)
                    onUnavailable?.invoke()
                }
            }
            if (Build.VERSION.SDK_INT >= 36) startWithUserConfiguration(callback)
            else wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        } catch (exception: Exception) {
            requestPending = false
            state = HotspotUi(status = "فشل تشغيل Hotspot: ${exception.localizedMessage.orEmpty()}")
            onUnavailable?.invoke()
        }
    }

    @TargetApi(36)
    private fun startWithUserConfiguration(callback: WifiManager.LocalOnlyHotspotCallback) {
        val config = SoftApConfiguration.Builder()
            .setSsid(preferredName)
            .setPassphrase(preferredPassword, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        when (preferredBand) {
            "2.4" -> config.setBand(SoftApConfiguration.BAND_2GHZ)
            "5" -> {
                require(wifi.is5GHzBandSupported) { "الجهاز لا يدعم 5GHz في إعدادات Wi-Fi هذه" }
                config.setBand(SoftApConfiguration.BAND_5GHZ)
            }
            else -> Unit // Android chooses the strongest available supported band.
        }
        wifi.startLocalOnlyHotspotWithConfiguration(config.build(), appContext.mainExecutor, callback)
    }

    @TargetApi(36)
    private fun describeConfiguredBand(config: SoftApConfiguration): String {
        val channels = config.channels
        val result = linkedSetOf<String>()
        for (i in 0 until channels.size()) {
            val bandMask = channels.keyAt(i)
            if (bandMask and SoftApConfiguration.BAND_2GHZ != 0) result += "2.4 GHz"
            if (bandMask and SoftApConfiguration.BAND_5GHZ != 0) result += "5 GHz"
            if (bandMask and SoftApConfiguration.BAND_6GHZ != 0) result += "6 GHz"
        }
        return result.ifEmpty { linkedSetOf("غير معروف") }.joinToString(" / ")
    }

    fun reportLaunchFailure(error: Exception) {
        state = HotspotUi(status = "تعذّر بدء خدمة Hotspot: ${error.localizedMessage.orEmpty()}")
    }

    fun permissionDenied() { state = HotspotUi(status = "تم رفض صلاحية الأجهزة القريبة؛ لا يمكن تشغيل Hotspot") }

    fun stop() {
        requestPending = false
        reservation?.close()
        reservation = null
        state = HotspotUi(status = "نقطة الاتصال متوقفة")
    }
}

data class HotspotUi(
    val active: Boolean = false,
    val starting: Boolean = false,
    val ssid: String = "",
    val password: String = "",
    val configuredBand: String = "غير معروف",
    val status: String = "جاهز لتشغيل Local-only Hotspot"
)
