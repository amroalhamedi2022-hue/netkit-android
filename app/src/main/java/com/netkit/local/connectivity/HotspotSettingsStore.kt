package com.netkit.local.connectivity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private preferences; hotspot password at rest is encrypted with AndroidKeyStore. */
internal class HotspotSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("netkit_hotspot_prefs", Context.MODE_PRIVATE)
    private val alias = "netkit_local_hotspot_aes_v1"
    fun ssid(): String = prefs.getString("ssid", "NetKit-Local") ?: "NetKit-Local"
    fun band(): String = prefs.getString("band", "auto") ?: "auto"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun password(): String {
        val encoded = prefs.getString("pass_enc", null) ?: return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size < 14) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
    fun update(ssid: String, password: String, band: String) {
        require(ssid.trim().length in 1..32 && ssid.toByteArray(Charsets.UTF_8).size <= 32) { "اسم الشبكة يجب أن يكون من 1 إلى 32 بايت" }
        require(password.length in 8..63 && password.all { it.code in 32..126 }) { "كلمة المرور يجب أن تكون 8–63 حرفًا إنجليزيًا قابلًا للطباعة" }
        require(band in setOf("auto", "2.4", "5")) { "اختيار نطاق غير صحيح" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("ssid", ssid.trim()).putString("pass_enc", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("band", band).apply()
    }
}
