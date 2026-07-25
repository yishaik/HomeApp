package com.yishaik.homeapp.util

import com.yishaik.homeapp.domain.ItemType
import com.yishaik.homeapp.domain.ParsedQuickAdd
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object NaturalLanguageParser {
    private val timeRegex = Regex("(?:בשעה\\s*|ב[-־]?|at\\s*)?(\\d{1,2})(?::(\\d{2}))?", RegexOption.IGNORE_CASE)
    private val taskHints = listOf("משימה", "תזכורת", "צריך", "להחזיר", "לקבוע", "להביא", "לסדר", "לשלוח", "todo", "task", "reminder")
    private val listHints = listOf("רשימה", "קניות", "ציוד", "סרטים", "לחם", "חלב", "list")
    private val eventHints = listOf("אירוע", "פגישה", "תור", "רופא", "שיעור", "פסנתר", "יום הולדת", "חופשה", "meeting", "appointment", "dentist")

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now(), zoneId: ZoneId = ZoneId.systemDefault()): ParsedQuickAdd {
        val normalized = text.trim()
        val lower = normalized.lowercase()
        val type = when {
            taskHints.any(lower::contains) -> ItemType.TASK
            listHints.any(lower::contains) -> ItemType.LIST
            eventHints.any(lower::contains) -> ItemType.EVENT
            else -> ItemType.NOTE
        }
        val date = parseDate(lower, now.toLocalDate())
        val time = parseTime(lower)
        val dateTime = when {
            date != null && time != null -> LocalDateTime.of(date, time)
            date != null -> LocalDateTime.of(date, LocalTime.of(9, 0))
            time != null -> LocalDateTime.of(now.toLocalDate(), time)
            else -> null
        }
        val cleaned = normalized
            .replace(Regex("(?i)\\b(today|tomorrow|in two days|next\\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)|at)\\b"), "")
            .replace(Regex("מחרתיים|מחר|היום|ב?יום\\s+(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת)|בשעה\\s*\\d{1,2}(?::\\d{2})?|ב[-־]?\\d{1,2}(?::\\d{2})?|בבוקר|בצהריים|בערב"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '־')
            .ifBlank { normalized }
        val confidence = when {
            dateTime != null -> 0.92f
            type != ItemType.NOTE -> 0.76f
            else -> 0.55f
        }
        return ParsedQuickAdd(type, cleaned, dateTime, confidence, confidence < 0.7f)
    }

    private fun parseDate(text: String, today: LocalDate): LocalDate? = when {
        "מחרתיים" in text || "in two days" in text || "day after tomorrow" in text -> today.plusDays(2)
        "מחר" in text || "tomorrow" in text -> today.plusDays(1)
        "היום" in text || "today" in text -> today
        else -> parseWeekday(text, today)
    }

    private fun parseWeekday(text: String, today: LocalDate): LocalDate? {
        val names = mapOf(
            "ראשון" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
            "שני" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
            "שלישי" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
            "רביעי" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "חמישי" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
            "שישי" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
            "שבת" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        )
        val entry = names.entries.firstOrNull { it.key in text } ?: return null
        return today.with(TemporalAdjusters.next(entry.value))
    }

    private fun parseTime(text: String): LocalTime? {
        val match = timeRegex.findAll(text).lastOrNull()
        if (match != null) {
            val hour = match.groupValues[1].toIntOrNull()
            val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (hour != null && hour in 0..23 && minute in 0..59) return LocalTime.of(hour, minute)
        }
        return when {
            "בבוקר" in text || "morning" in text -> LocalTime.of(9, 0)
            "בצהריים" in text || "noon" in text -> LocalTime.of(12, 0)
            "בערב" in text || "evening" in text -> LocalTime.of(19, 0)
            else -> null
        }
    }
}
