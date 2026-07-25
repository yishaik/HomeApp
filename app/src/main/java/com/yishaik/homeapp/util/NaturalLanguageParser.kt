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
    private val timeRegex = Regex("(?:ב[-־]?|at\\s*)?(\\d{1,2})(?::(\\d{2}))?", RegexOption.IGNORE_CASE)

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now(), zoneId: ZoneId = ZoneId.systemDefault()): ParsedQuickAdd {
        val normalized = text.trim()
        val lower = normalized.lowercase()
        val type = when {
            listOf("משימה", "צריך", "todo", "task").any(lower::contains) -> ItemType.TASK
            listOf("רשימה", "list").any(lower::contains) -> ItemType.LIST
            listOf("אירוע", "פגישה", "תור", "רופא", "meeting", "appointment", "dentist").any(lower::contains) -> ItemType.EVENT
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
            .replace(Regex("(?i)\\b(today|tomorrow|next\\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)|at)\\b"), "")
            .replace(Regex("היום|מחר|ביום\\s+(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת)|ב[-־]?\\d{1,2}(?::\\d{2})?"), "")
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
        val match = timeRegex.findAll(text).lastOrNull() ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }
}
