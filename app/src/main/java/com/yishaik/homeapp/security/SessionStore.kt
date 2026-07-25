package com.yishaik.homeapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.yishaik.homeapp.domain.AppUser
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val householdId: String,
    val displayName: String,
    val avatar: String,
    val accentArgb: Long,
) {
    fun user() = AppUser(userId, displayName, avatar, accentArgb)
    fun expiresSoon(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAtEpochSeconds <= nowEpochSeconds + 120
}

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val alias = "homeapp_session_key"

    fun load(): AuthSession? = prefs.getString("session", null)?.let { encrypted ->
        runCatching { decode(String(decrypt(encrypted), Charsets.UTF_8)) }.getOrNull()
    }

    fun save(session: AuthSession) {
        val raw = JSONObject().apply {
            put("access_token", session.accessToken)
            put("refresh_token", session.refreshToken)
            put("expires_at", session.expiresAtEpochSeconds)
            put("user_id", session.userId)
            put("household_id", session.householdId)
            put("display_name", session.displayName)
            put("avatar", session.avatar)
            put("accent_argb", session.accentArgb)
        }.toString().toByteArray(Charsets.UTF_8)
        prefs.edit().putString("session", encrypt(raw)).apply()
    }

    fun clear() = prefs.edit().clear().apply()
    fun hasSession(): Boolean = load() != null

    private fun decode(raw: String): AuthSession = JSONObject(raw).let { json ->
        AuthSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtEpochSeconds = json.getLong("expires_at"),
            userId = json.getString("user_id"),
            householdId = json.getString("household_id"),
            displayName = json.getString("display_name"),
            avatar = json.getString("avatar"),
            accentArgb = json.getLong("accent_argb"),
        )
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
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
        require(data.size > 12) { "Invalid encrypted session" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }
}
