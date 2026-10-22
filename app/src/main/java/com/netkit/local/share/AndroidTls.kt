package com.netkit.local.share

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/** Dedicated, device-local TLS identity. Never ships a shared private key or password. */
object AndroidTls {
    private const val ALIAS = "netkit_browser_share_tls_v1"
    data class ServerTls(val factory: SSLServerSocketFactory, val fingerprint: String)
    fun loadOrCreate(): ServerTls {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
            ).setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=NetKit Local Share"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 3650L * 86_400_000L))
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        km.init(ks, null)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(km.keyManagers, null, null)
        val cert = ks.getCertificate(ALIAS).encoded
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString(":") { "%02X".format(it) }
        return ServerTls(ctx.serverSocketFactory, fingerprint)
    }
}
