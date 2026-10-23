package com.netkit.local.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.netkit.local.ui.NetKitProApp

/** Session ownership is in NetKitApplication + TransferService, NOT an Activity/ViewModel. */
class MainActivity : ComponentActivity() {
    private val core get() = (application as NetKitApplication).core
    private var pendingAfterNotification: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pendingAfterNotification
        pendingAfterNotification = null
        action?.invoke() // FGS remains usable even when permission is denied.
    }
    // API 26–32: explicitly request BOTH location permissions for legacy devices.
    // API 33+: nearby Wi-Fi access replaces the older location permission for LOHS.
    private val hotspotPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val permitted = requiredHotspotPermissions().all { permission ->
            grants[permission] == true ||
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        if (permitted) launchHotspot() else core.hotspot.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetKitProApp(core, ::requestHotspot,
                onStartShare = {
                    withOptionalNotificationPermission {
                        try { TransferService.startSharing(this) }
                        catch (e: Exception) { core.reportShareLaunchFailure(e) }
                    }
                },
                onStopShare = { TransferService.stopSharing(this) },
                onStopHotspot = { TransferService.stopHotspot(this) })
        }
    }

    private fun launchHotspot() {
        withOptionalNotificationPermission {
            try { TransferService.startHotspot(this) }
            catch (e: Exception) { core.hotspot.reportLaunchFailure(e) }
        }
    }

    private fun withOptionalNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterNotification = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }

    private fun requiredHotspotPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun requestHotspot() {
        val missing = requiredHotspotPermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) launchHotspot()
        else hotspotPermission.launch(missing.toTypedArray())
    }
}
