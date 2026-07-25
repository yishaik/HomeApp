package com.yishaik.homeapp.security

import android.content.Context
import android.provider.Settings
import com.yishaik.homeapp.sync.ActivationApiResult
import com.yishaik.homeapp.sync.SupabaseApi
import java.util.UUID

sealed interface ActivationOutcome {
    data class Success(val session: AuthSession) : ActivationOutcome
    data class ApprovalRequired(val requestId: String) : ActivationOutcome
}

class ActivationManager(
    private val context: Context,
    private val api: SupabaseApi,
    private val sessionStore: SessionStore,
) {
    val activated: Boolean get() = sessionStore.hasSession()
    val session: AuthSession? get() = sessionStore.load()

    suspend fun activate(code: String, recoveryCode: String? = null): Result<ActivationOutcome> = runCatching {
        require(code.length in 4..12 && code.all(Char::isDigit)) { "קוד ההפעלה אינו תקין" }
        when (val result = api.activate(code, deviceFingerprint(), recoveryCode = recoveryCode)) {
            is ActivationApiResult.Success -> {
                sessionStore.save(result.session)
                ActivationOutcome.Success(result.session)
            }
            is ActivationApiResult.ApprovalRequired -> ActivationOutcome.ApprovalRequired(result.requestId)
        }
    }

    suspend fun checkApproval(code: String, requestId: String): Result<ActivationOutcome> = runCatching {
        when (val result = api.activate(code, deviceFingerprint(), requestId = requestId, checkStatus = true)) {
            is ActivationApiResult.Success -> {
                sessionStore.save(result.session)
                ActivationOutcome.Success(result.session)
            }
            is ActivationApiResult.ApprovalRequired -> ActivationOutcome.ApprovalRequired(result.requestId)
        }
    }

    fun reset() = sessionStore.clear()

    private fun deviceFingerprint(): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
}
