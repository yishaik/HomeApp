package com.yishaik.homeapp.sync

import com.yishaik.homeapp.BuildConfig
import com.yishaik.homeapp.data.JsonCodec
import com.yishaik.homeapp.domain.AppUser
import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.security.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ActivationApiResult {
    data class Success(val session: AuthSession) : ActivationApiResult
    data class ApprovalRequired(val requestId: String) : ActivationApiResult
}

class SupabaseApi(
    private val baseUrl: String = BuildConfig.SUPABASE_URL,
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
            redactHeader("apikey")
        })
        .build()

    val configured: Boolean get() = baseUrl.startsWith("https://") && publishableKey.isNotBlank()

    suspend fun activate(
        code: String,
        deviceId: String,
        recoveryCode: String? = null,
        requestId: String? = null,
        checkStatus: Boolean = false,
    ): ActivationApiResult = withContext(Dispatchers.IO) {
        check(configured) { "Supabase is not configured" }
        val payload = JSONObject()
            .put("action", if (checkStatus) "status" else "activate")
            .put("activationCode", code)
            .put("deviceFingerprint", deviceId)
            .put("appVersion", BuildConfig.VERSION_NAME)
        recoveryCode?.takeIf(String::isNotBlank)?.let { payload.put("recoveryCode", it) }
        requestId?.takeIf(String::isNotBlank)?.let { payload.put("requestId", it) }

        val response = client.newCall(
            publicRequestBuilder("/functions/v1/activation")
                .post(payload.toString().toRequestBody(JSON))
                .build()
        ).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            val json = body.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            if (it.code == 409 || it.code == 202) {
                return@withContext ActivationApiResult.ApprovalRequired(json.optString("requestId"))
            }
            if (!it.isSuccessful) throw apiError("Activation", it.code, json)
            val auth = exchangeTokenHash(json.getString("tokenHash"))
            ActivationApiResult.Success(
                auth.copy(
                    userId = json.getString("userId"),
                    householdId = json.getString("householdId"),
                    displayName = json.getString("displayName"),
                    avatar = json.optString("avatar"),
                    accentArgb = json.optLong("accentArgb", 0xFF5A5BD7),
                )
            )
        }
    }

    suspend fun refreshSession(session: AuthSession): AuthSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("refresh_token", session.refreshToken).toString().toRequestBody(JSON)
        val request = publicRequestBuilder("/auth/v1/token?grant_type=refresh_token").post(body).build()
        val json = executeJson(request)
        session.copy(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token", session.refreshToken),
            expiresAtEpochSeconds = json.optLong("expires_at", System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)),
        )
    }

    suspend fun fetchProfiles(session: AuthSession): List<AppUser> = withContext(Dispatchers.IO) {
        val request = authenticatedRequestBuilder(
            "/rest/v1/homeapp_profiles?household_id=eq.${session.householdId}&select=id,display_name,avatar,accent_argb&order=created_at.asc",
            session.accessToken,
        ).get().build()
        val array = executeArray(request)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            AppUser(
                id = row.getString("id"),
                displayName = row.getString("display_name"),
                avatar = row.optString("avatar"),
                accentArgb = row.optLong("accent_argb", 0xFF5A5BD7),
            )
        }
    }

    suspend fun fetchItems(session: AuthSession): List<HomeItem> = withContext(Dispatchers.IO) {
        val request = authenticatedRequestBuilder(
            "/rest/v1/homeapp_items?household_id=eq.${session.householdId}&select=payload&order=updated_at.asc",
            session.accessToken,
        ).get().build()
        val array = executeArray(request)
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val payload = array.getJSONObject(index).get("payload")
                JsonCodec.decode(if (payload is JSONObject) payload.toString() else payload.toString())
            }.getOrNull()
        }
    }

    suspend fun upsertItem(item: HomeItem, session: AuthSession): HomeItem = withContext(Dispatchers.IO) {
        val row = JSONObject().apply {
            put("id", item.id)
            put("household_id", item.householdId)
            put("creator_id", item.creatorId)
            put("type", item.type.name)
            put("status", item.status.name)
            put("edit_policy", item.editPolicy.name)
            put("payload", JSONObject(JsonCodec.encode(item)))
            put("revision", item.revision)
            put("created_at", item.createdAt.toString())
            put("updated_at", item.updatedAt.toString())
        }
        val request = authenticatedRequestBuilder("/rest/v1/homeapp_items?on_conflict=id", session.accessToken)
            .header("Prefer", "resolution=merge-duplicates,return=representation")
            .post(JSONArray().put(row).toString().toRequestBody(JSON))
            .build()
        val result = executeArray(request)
        if (result.length() == 0) item else {
            val payload = result.getJSONObject(0).get("payload")
            JsonCodec.decode(payload.toString())
        }
    }

    fun openRealtime(session: AuthSession, onChange: () -> Unit): WebSocket? {
        if (!configured) return null
        val wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://") +
            "/realtime/v1/websocket?apikey=$publishableKey&vsn=1.0.0"
        return client.newWebSocket(
            Request.Builder().url(wsUrl).header("Authorization", "Bearer ${session.accessToken}").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val config = JSONObject()
                        .put("broadcast", JSONObject().put("self", false))
                        .put("presence", JSONObject().put("key", session.userId))
                        .put("postgres_changes", JSONArray().put(JSONObject()
                            .put("event", "*")
                            .put("schema", "public")
                            .put("table", "homeapp_items")
                            .put("filter", "household_id=eq.${session.householdId}"))
                    val join = JSONObject()
                        .put("topic", "realtime:homeapp:${session.householdId}")
                        .put("event", "phx_join")
                        .put("payload", JSONObject().put("config", config).put("access_token", session.accessToken))
                        .put("ref", "1")
                    webSocket.send(join.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("postgres_changes") || text.contains("INSERT") || text.contains("UPDATE")) onChange()
                }
            },
        )
    }

    private fun exchangeTokenHash(tokenHash: String): AuthSession {
        val body = JSONObject().put("token_hash", tokenHash).put("type", "email").toString().toRequestBody(JSON)
        val json = executeJson(publicRequestBuilder("/auth/v1/verify").post(body).build())
        val userId = json.getJSONObject("user").getString("id")
        return AuthSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtEpochSeconds = json.optLong("expires_at", System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)),
            userId = userId,
            householdId = "",
            displayName = "",
            avatar = "",
            accentArgb = 0xFF5A5BD7,
        )
    }

    private fun publicRequestBuilder(path: String): Request.Builder = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .header("apikey", publishableKey)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")

    private fun authenticatedRequestBuilder(path: String, accessToken: String): Request.Builder =
        publicRequestBuilder(path).header("Authorization", "Bearer $accessToken")

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val content = response.body?.string().orEmpty()
        val json = content.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        if (!response.isSuccessful) throw apiError("Supabase", response.code, json)
        json
    }

    private fun executeArray(request: Request): JSONArray = client.newCall(request).execute().use { response ->
        val content = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val json = runCatching { JSONObject(content) }.getOrDefault(JSONObject())
            throw apiError("Supabase", response.code, json)
        }
        if (content.isBlank()) JSONArray() else JSONArray(content)
    }

    private fun apiError(context: String, code: Int, json: JSONObject): IOException {
        val message = json.optString("msg").ifBlank { json.optString("message") }.ifBlank { json.optString("error") }.ifBlank { "request failed" }
        return IOException("$context $code: $message")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
