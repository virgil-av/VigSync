package com.vigsync.core.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class EncryptionManager(private val sharedKey: String) {

    private val algorithm = "AES/GCM/NoPadding"
    private val tagLength = 128
    private val ivLength = 12

    fun encrypt(plainText: String): String? {
        if (sharedKey.isEmpty()) return plainText
        return try {
            val keyBytes = Base64.decode(sharedKey, Base64.NO_WRAP)
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(algorithm)
            val iv = ByteArray(ivLength)
            SecureRandom().nextBytes(iv)
            val gcmSpec = GCMParameterSpec(tagLength, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
            
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteBuffer.allocate(iv.size + encryptedBytes.size)
                .put(iv)
                .put(encryptedBytes)
                .array()
            
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun decrypt(encryptedBase64: String): String? {
        if (sharedKey.isEmpty()) return encryptedBase64
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size <= ivLength) {
                throw IllegalArgumentException("Payload too short")
            }
            
            val buffer = ByteBuffer.wrap(combined)
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val encryptedBytes = ByteArray(buffer.remaining())
            buffer.get(encryptedBytes)
            
            val keyBytes = Base64.decode(sharedKey, Base64.NO_WRAP)
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(algorithm)
            val gcmSpec = GCMParameterSpec(tagLength, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw e
        }
    }
    
    companion object {
        fun generateRandomKey(): String {
            val key = ByteArray(32) // 256 bit
            SecureRandom().nextBytes(key)
            return Base64.encodeToString(key, Base64.NO_WRAP)
        }
    }
}
