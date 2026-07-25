package com.yishaik.homeapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PinVault(context: Context) {
    private val prefs = context.getSharedPreferences("security", Context.MODE_PRIVATE)
    private val alias = "homeapp_pin_key"

    fun hasPin(): Boolean = prefs.contains("pin")

    fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit))
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        prefs.edit().putString("pin", encrypt(digest)).putLong("last_unlocked", System.currentTimeMillis()).apply()
    }

    fun verify(pin: String): Boolean {
        val stored = prefs.getString("pin", null) ?: return false
        val expected = decrypt(stored)
        val actual = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        val valid = MessageDigest.isEqual(expected, actual)
        if (valid) markUnlocked()
        return valid
    }

    fun markUnlocked() = prefs.edit().putLong("last_unlocked", System.currentTimeMillis()).apply()

    fun shouldLock(timeoutMinutes: Int): Boolean {
        if (!hasPin()) return false
        val last = prefs.getLong("last_unlocked", 0L)
        return System.currentTimeMillis() - last > timeoutMinutes * 60_000L
    }

    fun clearPin() = prefs.edit().remove("pin").apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val data = Base64.decode(value, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }
}
