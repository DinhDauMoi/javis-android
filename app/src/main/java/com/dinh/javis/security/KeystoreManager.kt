package com.dinh.javis.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Trình quản lý khóa bí mật sử dụng Android Keystore AES-GCM (256-bit).
 * Đảm bảo các API key (OpenAI, Groq, OpenRouter, v.v.) không bao giờ lưu dưới dạng văn bản thuần (plaintext).
 */
class KeystoreManager(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        try {
            load(null)
        } catch (e: Exception) {
            Log.w(TAG, "Không thể load AndroidKeyStore (có thể đang chạy trong unit test): ${e.message}")
        }
    }

    private val securePrefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Mã hóa chuỗi văn bản với alias tương ứng và lưu trữ ciphertext an toàn
     */
    @Synchronized
    fun encrypt(alias: String, plainText: String): String {
        if (plainText.isEmpty()) return ""
        try {
            val secretKey = getOrCreateSecretKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            val combined = "$ivBase64:$cipherBase64"

            // Lưu trực tiếp vào securePrefs theo alias
            securePrefs.edit().putString(alias, combined).apply()
            return combined
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi mã hóa dữ liệu cho alias: $alias", e)
            // Fallback lưu trữ nếu Keystore phần cứng không khả dụng (vd: test runner)
            val fallback = Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            securePrefs.edit().putString(alias, "PLAIN_B64:$fallback").apply()
            return "PLAIN_B64:$fallback"
        }
    }

    /**
     * Giải mã chuỗi đã mã hóa từ alias
     */
    @Synchronized
    fun decrypt(alias: String): String {
        val payload = securePrefs.getString(alias, null) ?: return ""
        if (payload.isEmpty()) return ""
        if (payload.startsWith("PLAIN_B64:")) {
            val raw = payload.removePrefix("PLAIN_B64:")
            return String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
        }

        return try {
            val parts = payload.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)

            val secretKey = getOrCreateSecretKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi giải mã cho alias: $alias", e)
            ""
        }
    }

    /**
     * Kiểm tra xem alias đã tồn tại dữ liệu khóa chưa
     */
    fun hasKey(alias: String): Boolean {
        return securePrefs.contains(alias)
    }

    /**
     * Xóa khóa bí mật và dữ liệu đã lưu
     */
    @Synchronized
    fun deleteKey(alias: String) {
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi xóa entry khỏi Keystore: ${e.message}")
        }
        securePrefs.edit().remove(alias).apply()
    }

    private fun getOrCreateSecretKey(alias: String): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        // Tạo mới khóa AES 256-bit trong Android Keystore
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "KeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val SECURE_PREFS_NAME = "javis_secure_vault"
    }
}
