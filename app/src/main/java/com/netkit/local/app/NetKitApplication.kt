package com.netkit.local.app

import android.app.Application

/** The foreground service and UI share one in-process session owner. */
class NetKitApplication : Application() {
    val core: NetKitCore by lazy { NetKitCore(applicationContext) }
}
