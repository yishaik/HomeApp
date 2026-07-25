package com.yishaik.homeapp.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

enum class ItemType { EVENT, TASK, LIST, NOTE }
enum class ItemStatus { ACTIVE, COMPLETED, CANCELLED, ARCHIVED }
enum class EditPolicy { SHARED_EDIT, CREATOR_ONLY }
enum class Assignee { NONE, USER_ONE, USER_TWO, BOTH }
enum class CalendarView { DAY, WEEK, MONTH, YEAR }

data class AppUser(
    val id: String,
    val displayName: String,
    val avatar: String,
    val accentArgb: Long,
)

data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val minutesBefore: Int,
    val userId: String,
    val enabled: Boolean = true,
)

data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    val dayOfMonth: Int? = null,
    val ordinal: Int? = null,
    val until: LocalDate? = null,
) {
    enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }
}

data class ChecklistEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val completed: Boolean = false,
    val orderIndex: Int = 0,
)

data class Comment(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val text: String,
    val link: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class ReadReceipt(
    val userId: String,
    val readAt: Instant?,
)

data class LinkedItem(
    val itemId: String,
    val title: String,
    val type: ItemType,
)

data class HomeItem(
    val id: String = UUID.randomUUID().toString(),
    val householdId: String = "home",
    val type: ItemType,
    val title: String,
    val body: String = "",
    val link: String? = null,
    val creatorId: String,
    val editPolicy: EditPolicy = EditPolicy.SHARED_EDIT,
    val assignee: Assignee = Assignee.NONE,
    val participantIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val status: ItemStatus = ItemStatus.ACTIVE,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val allDay: Boolean = false,
    val dueAt: Instant? = null,
    val scheduledPublishAt: Instant? = null,
    val locationLabel: String? = null,
    val mapsUrl: String? = null,
    val recurrence: RecurrenceRule? = null,
    val reminders: List<Reminder> = emptyList(),
    val checklist: List<ChecklistEntry> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val readReceipts: List<ReadReceipt> = emptyList(),
    val linkedItems: List<LinkedItem> = emptyList(),
    val pinned: Boolean = false,
    val notifyOtherUser: Boolean = false,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun dateIn(zoneId: ZoneId): LocalDate? = when (type) {
        ItemType.EVENT -> startAt
        ItemType.TASK, ItemType.LIST -> dueAt
        ItemType.NOTE -> scheduledPublishAt ?: createdAt
    }?.atZone(zoneId)?.toLocalDate()

    fun isOverdue(now: Instant): Boolean =
        status == ItemStatus.ACTIVE && type in setOf(ItemType.TASK, ItemType.LIST) && dueAt?.isBefore(now) == true

    fun isVisibleTo(userId: String, now: Instant): Boolean =
        type != ItemType.NOTE || scheduledPublishAt == null || creatorId == userId || !scheduledPublishAt.isAfter(now)
}

data class UserPreferences(
    val userId: String,
    val localeTag: String? = null,
    val accentArgb: Long = 0xFF5A5BD7,
    val startDestination: String = "today",
    val pinTimeoutMinutes: Int = 5,
    val lockScreenContentVisible: Boolean = false,
    val todaySections: List<String> = listOf(
        "new_notes", "all_day", "unscheduled", "timeline", "completed", "read_notes"
    ),
    val hiddenTodaySections: Set<String> = emptySet(),
    val defaultEventReminderMinutes: List<Int> = listOf(30),
    val defaultTaskReminderMinutes: List<Int> = listOf(0),
    val importantTags: Set<String> = setOf("חשוב", "important"),
    val calendarLayers: Set<String> = setOf("israeli_holidays", "national_days", "hebrew_dates", "school_breaks"),
)

data class ChangeRecord(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val actorId: String,
    val changedFields: Map<String, Pair<String?, String?>>,
    val createdAt: Instant = Instant.now(),
)

data class AppNotification(
    val id: String = UUID.randomUUID().toString(),
    val recipientId: String,
    val itemId: String?,
    val title: String,
    val detail: String,
    val read: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

data class ParsedQuickAdd(
    val type: ItemType,
    val title: String,
    val dateTime: LocalDateTime? = null,
    val confidence: Float,
    val needsAiFallback: Boolean,
)
