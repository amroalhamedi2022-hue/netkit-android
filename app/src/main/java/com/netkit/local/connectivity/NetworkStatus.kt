package com.netkit.local.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

class NetworkStatus(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun summary(): String {
        val active = cm.activeNetwork ?: return "لا يوجد اتصال افتراضي — يمكن استخدام Hotspot محلي"
        val caps = cm.getNetworkCapabilities(active) ?: return "حالة الشبكة غير متاحة"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "متصل عبر Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "متصل عبر Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "اتصال بيانات جوال (ليس شبكة نقل محلية)"
            else -> "شبكة متصلة — تحقق من إمكانية الوصول المحلي"
        }
    }

    /** Display candidates, don't assume first IP can be reached from every hotspot client. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .distinct()
    }.getOrDefault(emptyList())
}
