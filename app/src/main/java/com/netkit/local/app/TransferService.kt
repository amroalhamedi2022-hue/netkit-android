package com.netkit.local.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps explicitly started LAN sharing/hotspot visible and alive after Activity is closed.
 *  Does not restart secretly after reboot/process death. Stops via notification or in-app UI.
 */
class TransferService : Service() {
    companion object {
        private const val CHANNEL = "netkit_local_session"
        private const val ID = 204
        private const val START_SHARE = "com.netkit.local.START_SHARE"
        private const val STOP_SHARE = "com.netkit.local.STOP_SHARE"
        private const val START_HOTSPOT = "com.netkit.local.START_HOTSPOT"
        private const val STOP_HOTSPOT = "com.netkit.local.STOP_HOTSPOT"
        private const val STOP_ALL = "com.netkit.local.STOP_ALL"

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, TransferService::class.java).setAction(action)
            if (foreground) context.startForegroundService(intent) else context.startService(intent)
        }
        fun startSharing(context: Context) = send(context, START_SHARE, true)
        fun stopSharing(context: Context) = send(context, STOP_SHARE, false)
        fun startHotspot(context: Context) = send(context, START_HOTSPOT, true)
        fun stopHotspot(context: Context) = send(context, STOP_HOTSPOT, false)
    }

    private val core get() = (application as NetKitApplication).core
    private var requestedShare = false
    private var requestedHotspot = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "NetKit local session", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Local file sharing and local-only hotspot" })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_SHARE -> {
                requestedShare = true
                showNotification()
                core.startShare(onFailure = {
                    requestedShare = false
                    finishIfIdle()
                })
            }
            START_HOTSPOT -> {
                requestedHotspot = true
                showNotification()
                core.hotspot.onUnavailable = {
                    requestedHotspot = false
                    finishIfIdle()
                }
                core.hotspot.start()
            }
            STOP_SHARE -> {
                requestedShare = false
                core.stopShare()
                finishIfIdle()
            }
            STOP_HOTSPOT -> {
                requestedHotspot = false
                core.hotspot.onUnavailable = null
                core.hotspot.stop()
                finishIfIdle()
            }
            STOP_ALL -> {
                requestedShare = false
                requestedHotspot = false
                core.stopShare()
                core.hotspot.onUnavailable = null
                core.hotspot.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun finishIfIdle() {
        if (!requestedShare && !requestedHotspot) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else showNotification()
    }

    private fun showNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = when {
            requestedShare && requestedHotspot -> "File sharing and local Hotspot are active"
            requestedShare -> "Local file sharing is active"
            else -> "Local-only Hotspot is active"
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("NetKit · Local session")
            .setContentText(summary)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop all", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(ID, notification)
    }

    override fun onDestroy() {
        core.stopShare()
        core.hotspot.onUnavailable = null
        core.hotspot.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
