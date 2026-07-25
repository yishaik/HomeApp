package com.yishaik.homeapp.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object SampleData {
    val userOne = AppUser("u1", "ישי", "י", 0xFF5A5BD7)
    val userTwo = AppUser("u2", "מעיין", "מ", 0xFFE06A9B)

    fun items(zoneId: ZoneId = ZoneId.systemDefault()): List<HomeItem> {
        val today = LocalDate.now(zoneId)
        fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant =
            LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zoneId).toInstant()

        return listOf(
            HomeItem(
                type = ItemType.NOTE,
                title = "לא לשכוח להביא את הספר",
                body = "צריך להביא את הספר לשיעור ביום ראשון.",
                creatorId = userTwo.id,
                pinned = true,
                readReceipts = listOf(ReadReceipt(userOne.id, null), ReadReceipt(userTwo.id, null)),
            ),
            HomeItem(
                type = ItemType.NOTE,
                title = "קישור לטופס הטיול",
                link = "https://example.com/trip-form",
                creatorId = userOne.id,
                readReceipts = listOf(ReadReceipt(userOne.id, null), ReadReceipt(userTwo.id, null)),
            ),
            HomeItem(
                type = ItemType.EVENT,
                title = "יום הולדת לסבתא",
                creatorId = userOne.id,
                assignee = Assignee.BOTH,
                participantIds = setOf(userOne.id, userTwo.id),
                startAt = at(today, 0),
                endAt = at(today.plusDays(1), 0),
                allDay = true,
                tags = setOf("משפחה"),
            ),
            HomeItem(
                type = ItemType.TASK,
                title = "להחזיר ספר לספרייה",
                creatorId = userOne.id,
                assignee = Assignee.USER_TWO,
                dueAt = at(today.minusDays(1), 18),
            ),
            HomeItem(
                type = ItemType.EVENT,
                title = "שיעור פסנתר",
                creatorId = userTwo.id,
                assignee = Assignee.USER_TWO,
                participantIds = setOf(userTwo.id),
                startAt = at(today, 14),
                endAt = at(today, 14, 30),
                locationLabel = "מרכז המוזיקה",
                mapsUrl = "https://maps.google.com/?q=music+center",
                reminders = listOf(Reminder(minutesBefore = 15, userId = userTwo.id)),
            ),
            HomeItem(
                type = ItemType.TASK,
                title = "להתקשר לרופא",
                creatorId = userOne.id,
                assignee = Assignee.USER_ONE,
                dueAt = at(today, 15),
                reminders = listOf(Reminder(minutesBefore = 0, userId = userOne.id)),
            ),
            HomeItem(
                type = ItemType.LIST,
                title = "רשימת קניות",
                creatorId = userTwo.id,
                assignee = Assignee.BOTH,
                dueAt = at(today, 18),
                checklist = listOf(
                    ChecklistEntry(title = "חלב", completed = true, orderIndex = 0),
                    ChecklistEntry(title = "לחם", orderIndex = 1),
                    ChecklistEntry(title = "פירות", orderIndex = 2),
                ),
            ),
            HomeItem(
                type = ItemType.TASK,
                title = "לשלוח טופס לבית הספר",
                creatorId = userOne.id,
                assignee = Assignee.USER_ONE,
                dueAt = at(today, 9),
                status = ItemStatus.COMPLETED,
                updatedAt = Instant.now().minus(2, ChronoUnit.HOURS),
            ),
        )
    }
}
