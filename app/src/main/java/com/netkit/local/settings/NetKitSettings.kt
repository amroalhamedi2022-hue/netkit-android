package com.netkit.local.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** All settings here are persistent. A setting is only exposed as enabled when applied by the backend. */
class NetKitSettings(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("netkit_settings_v1", Context.MODE_PRIVATE)
    var theme by mutableStateOf(store.getInt("theme", 0).coerceIn(0, 2)); private set
    var motion by mutableStateOf(store.getBoolean("motion", true)); private set
    var readOnly by mutableStateOf(store.getBoolean("readonly", true)); private set
    var maxTransfers by mutableStateOf(store.getInt("transfers", 2).coerceIn(1, 4)); private set
    var confirmReceives by mutableStateOf(store.getBoolean("confirm", true)); private set
    var shareExpirationMinutes by mutableStateOf(store.getInt("expiration", 30).coerceIn(5, 1440)); private set
    var hotspotPreferredName by mutableStateOf(store.getString("hotspot_ssid", "NetKit") ?: "NetKit"); private set
    var hotspotPreferredBand by mutableStateOf(store.getString("hotspot_band", "Auto") ?: "Auto"); private set
    var timeoutSeconds by mutableStateOf(store.getInt("timeout", 10).coerceIn(2, 60)); private set
    var reduceMotion by mutableStateOf(store.getBoolean("reduce_motion", false)); private set
    var backgroundTransfers by mutableStateOf(store.getBoolean("background", true)); private set

    fun theme(v: Int) { theme = v.coerceIn(0, 2); store.edit().putInt("theme", theme).apply() }
    fun motion(v: Boolean) { motion = v; store.edit().putBoolean("motion", v).apply() }
    fun reduceMotion(v: Boolean) { reduceMotion = v; store.edit().putBoolean("reduce_motion", v).apply() }
    fun readOnly(v: Boolean) { readOnly = v; store.edit().putBoolean("readonly", v).apply() }
    fun maxTransfers(v: Int) { maxTransfers = v.coerceIn(1,4); store.edit().putInt("transfers",maxTransfers).apply() }
    fun confirmReceives(v: Boolean) { confirmReceives = v; store.edit().putBoolean("confirm",v).apply() }
    fun shareExpiration(v: Int) { shareExpirationMinutes = v.coerceIn(5,1440); store.edit().putInt("expiration",shareExpirationMinutes).apply() }
    fun hotspotName(v: String) { hotspotPreferredName=v.take(32); store.edit().putString("hotspot_ssid",hotspotPreferredName).apply() }
    fun hotspotBand(v: String) { hotspotPreferredBand= if(v in listOf("Auto","2.4 GHz","5 GHz")) v else "Auto"; store.edit().putString("hotspot_band",hotspotPreferredBand).apply() }
    fun timeout(v: Int) { timeoutSeconds=v.coerceIn(2,60); store.edit().putInt("timeout",timeoutSeconds).apply() }
    fun background(v: Boolean) { backgroundTransfers=v; store.edit().putBoolean("background",v).apply() }
}
