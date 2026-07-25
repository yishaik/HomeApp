package com.yishaik.homeapp.data

import com.yishaik.homeapp.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

internal object JsonCodec {
    fun encode(item: HomeItem): String = JSONObject().apply {
        put("id", item.id); put("householdId", item.householdId); put("type", item.type.name)
        put("title", item.title); put("body", item.body); putNullable("link", item.link)
        put("creatorId", item.creatorId); put("editPolicy", item.editPolicy.name)
        put("assignee", item.assignee.name); put("participants", JSONArray(item.participantIds.toList()))
        put("tags", JSONArray(item.tags.toList())); put("status", item.status.name)
        putInstant("startAt", item.startAt); putInstant("endAt", item.endAt); put("allDay", item.allDay)
        putInstant("dueAt", item.dueAt); putInstant("scheduledPublishAt", item.scheduledPublishAt)
        putNullable("locationLabel", item.locationLabel); putNullable("mapsUrl", item.mapsUrl)
        item.recurrence?.let { r -> put("recurrence", JSONObject().apply {
            put("frequency", r.frequency.name); put("interval", r.interval)
            put("daysOfWeek", JSONArray(r.daysOfWeek.toList())); putNullable("dayOfMonth", r.dayOfMonth)
            putNullable("ordinal", r.ordinal); putNullable("until", r.until?.toString())
        }) }
        put("reminders", JSONArray(item.reminders.map { r -> JSONObject().apply {
            put("id", r.id); put("minutesBefore", r.minutesBefore); put("userId", r.userId); put("enabled", r.enabled)
        }}))
        put("checklist", JSONArray(item.checklist.map { e -> JSONObject().apply {
            put("id", e.id); put("title", e.title); put("completed", e.completed); put("orderIndex", e.orderIndex)
        }}))
        put("comments", JSONArray(item.comments.map { c -> JSONObject().apply {
            put("id", c.id); put("authorId", c.authorId); put("text", c.text); putNullable("link", c.link)
            put("createdAt", c.createdAt.toString()); put("updatedAt", c.updatedAt.toString())
        }}))
        put("readReceipts", JSONArray(item.readReceipts.map { r -> JSONObject().apply {
            put("userId", r.userId); putInstant("readAt", r.readAt)
        }}))
        put("linkedItems", JSONArray(item.linkedItems.map { l -> JSONObject().apply {
            put("itemId", l.itemId); put("title", l.title); put("type", l.type.name)
        }}))
        put("pinned", item.pinned); put("notifyOtherUser", item.notifyOtherUser); put("revision", item.revision)
        put("createdAt", item.createdAt.toString()); put("updatedAt", item.updatedAt.toString())
    }.toString()

    fun decode(raw: String): HomeItem = JSONObject(raw).let { o ->
        HomeItem(
            id = o.getString("id"), householdId = o.optString("householdId", "home"),
            type = enumValueOf(o.getString("type")), title = o.getString("title"), body = o.optString("body"),
            link = o.optNullableString("link"), creatorId = o.getString("creatorId"),
            editPolicy = enumValueOf(o.optString("editPolicy", EditPolicy.SHARED_EDIT.name)),
            assignee = enumValueOf(o.optString("assignee", Assignee.NONE.name)),
            participantIds = o.optJSONArray("participants").strings().toSet(), tags = o.optJSONArray("tags").strings().toSet(),
            status = enumValueOf(o.optString("status", ItemStatus.ACTIVE.name)), startAt = o.instant("startAt"),
            endAt = o.instant("endAt"), allDay = o.optBoolean("allDay"), dueAt = o.instant("dueAt"),
            scheduledPublishAt = o.instant("scheduledPublishAt"), locationLabel = o.optNullableString("locationLabel"),
            mapsUrl = o.optNullableString("mapsUrl"), recurrence = o.optJSONObject("recurrence")?.let { r ->
                RecurrenceRule(
                    frequency = enumValueOf(r.getString("frequency")), interval = r.optInt("interval", 1),
                    daysOfWeek = r.optJSONArray("daysOfWeek").ints().toSet(), dayOfMonth = r.optIntOrNull("dayOfMonth"),
                    ordinal = r.optIntOrNull("ordinal"), until = r.optNullableString("until")?.let(LocalDate::parse)
                )
            },
            reminders = o.optJSONArray("reminders").objects().map { r -> Reminder(
                id = r.getString("id"), minutesBefore = r.getInt("minutesBefore"), userId = r.getString("userId"), enabled = r.optBoolean("enabled", true)
            )},
            checklist = o.optJSONArray("checklist").objects().map { e -> ChecklistEntry(
                id = e.getString("id"), title = e.getString("title"), completed = e.optBoolean("completed"), orderIndex = e.optInt("orderIndex")
            )},
            comments = o.optJSONArray("comments").objects().map { c -> Comment(
                id = c.getString("id"), authorId = c.getString("authorId"), text = c.getString("text"), link = c.optNullableString("link"),
                createdAt = Instant.parse(c.getString("createdAt")), updatedAt = Instant.parse(c.getString("updatedAt"))
            )},
            readReceipts = o.optJSONArray("readReceipts").objects().map { r -> ReadReceipt(r.getString("userId"), r.instant("readAt")) },
            linkedItems = o.optJSONArray("linkedItems").objects().map { l -> LinkedItem(l.getString("itemId"), l.getString("title"), enumValueOf(l.getString("type"))) },
            pinned = o.optBoolean("pinned"), notifyOtherUser = o.optBoolean("notifyOtherUser"), revision = o.optLong("revision"),
            createdAt = Instant.parse(o.getString("createdAt")), updatedAt = Instant.parse(o.getString("updatedAt"))
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { if (value == null) put(key, JSONObject.NULL) else put(key, value) }
    private fun JSONObject.putInstant(key: String, value: Instant?) = putNullable(key, value?.toString())
    private fun JSONObject.instant(key: String): Instant? = optNullableString(key)?.let(Instant::parse)
    private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
