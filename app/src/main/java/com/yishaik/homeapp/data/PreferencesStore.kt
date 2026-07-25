package com.yishaik.homeapp.data

import com.yishaik.homeapp.domain.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Persists [UserPreferences] as JSON in the LocalDatabase metadata table, following the
 * same org.json pattern used by [JsonCodec]/[com.yishaik.homeapp.security.SessionStore].
 * Also stores the "notifications seen at" timestamp used to derive unread notifications.
 */
class PreferencesStore(private val database: LocalDatabase) {
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<UserPreferences> = _prefs.asStateFlow()

    fun read(): UserPreferences {
        val raw = database.getMetadata(KEY_PREFS) ?: return UserPreferences(userId = "")
        return runCatching { decode(raw) }.getOrDefault(UserPreferences(userId = ""))
    }

    fun update(transform: (UserPreferences) -> UserPreferences): UserPreferences {
        val next = transform(_prefs.value)
        database.setMetadata(KEY_PREFS, encode(next))
        _prefs.value = next
        return next
    }

    fun notificationsSeenAt(): Instant? =
        database.getMetadata(KEY_NOTIFICATIONS_SEEN)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun markNotificationsSeen(at: Instant = Instant.now()) {
        database.setMetadata(KEY_NOTIFICATIONS_SEEN, at.toString())
    }

    private fun encode(p: UserPreferences): String = JSONObject().apply {
        put("userId", p.userId)
        put("localeTag", p.localeTag ?: JSONObject.NULL)
        put("accentArgb", p.accentArgb)
        put("startDestination", p.startDestination)
        put("pinTimeoutMinutes", p.pinTimeoutMinutes)
        put("lockScreenContentVisible", p.lockScreenContentVisible)
        put("todaySections", JSONArray(p.todaySections))
        put("hiddenTodaySections", JSONArray(p.hiddenTodaySections.toList()))
        put("defaultEventReminderMinutes", JSONArray(p.defaultEventReminderMinutes))
        put("defaultTaskReminderMinutes", JSONArray(p.defaultTaskReminderMinutes))
        put("importantTags", JSONArray(p.importantTags.toList()))
        put("calendarLayers", JSONArray(p.calendarLayers.toList()))
    }.toString()

    private fun decode(raw: String): UserPreferences = JSONObject(raw).let { o ->
        val default = UserPreferences(userId = "")
        UserPreferences(
            userId = o.optString("userId", ""),
            localeTag = if (o.isNull("localeTag")) null else o.optString("localeTag", null),
            accentArgb = o.optLong("accentArgb", default.accentArgb),
            startDestination = o.optString("startDestination", default.startDestination),
            pinTimeoutMinutes = o.optInt("pinTimeoutMinutes", default.pinTimeoutMinutes),
            lockScreenContentVisible = o.optBoolean("lockScreenContentVisible", default.lockScreenContentVisible),
            todaySections = o.optJSONArray("todaySections").strings().ifEmpty { default.todaySections },
            hiddenTodaySections = o.optJSONArray("hiddenTodaySections").strings().toSet(),
            defaultEventReminderMinutes = o.optJSONArray("defaultEventReminderMinutes").ints().ifEmpty { default.defaultEventReminderMinutes },
            defaultTaskReminderMinutes = o.optJSONArray("defaultTaskReminderMinutes").ints().ifEmpty { default.defaultTaskReminderMinutes },
            importantTags = o.optJSONArray("importantTags").strings().toSet().ifEmpty { default.importantTags },
            calendarLayers = o.optJSONArray("calendarLayers").strings().toSet().ifEmpty { default.calendarLayers },
        )
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    companion object {
        private const val KEY_PREFS = "user_preferences"
        private const val KEY_NOTIFICATIONS_SEEN = "notifications_seen_at"
    }
}
