package com.yishaik.homeapp.domain.hebrew

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

data class HolidayOrVacation(
    val title: String,
    val type: String, // "holiday", "hebrew_date", "vacation"
    val startDateMs: Long,
    val endDateMs: Long,
) {
    fun covers(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val start = Instant.ofEpochMilli(startDateMs).atZone(zoneId).toLocalDate()
        val end = Instant.ofEpochMilli(endDateMs).atZone(zoneId).toLocalDate()
        return !date.isBefore(start) && !date.isAfter(end)
    }
}

object HebrewCalendarProvider {

    // 2026 School Vacations and Israeli Holidays dataset
    private val entries2026: List<HolidayOrVacation> = SimpleDateFormat("yyyy-MM-dd", Locale.US).let { sdf ->
        listOf(
            HolidayOrVacation("חופשת פסח", "vacation", parseDate(sdf, "2026-03-27"), parseDate(sdf, "2026-04-09")),
            HolidayOrVacation("פסח - ערב חג", "holiday", parseDate(sdf, "2026-04-01"), parseDate(sdf, "2026-04-02")),
            HolidayOrVacation("חופשת יום העצמאות", "vacation", parseDate(sdf, "2026-04-22"), parseDate(sdf, "2026-04-23")),
            HolidayOrVacation("יום העצמאות", "holiday", parseDate(sdf, "2026-04-22"), parseDate(sdf, "2026-04-22")),
            HolidayOrVacation("חופשת שבועות", "vacation", parseDate(sdf, "2026-05-21"), parseDate(sdf, "2026-05-23")),
            HolidayOrVacation("שבועות", "holiday", parseDate(sdf, "2026-05-22"), parseDate(sdf, "2026-05-22")),
            HolidayOrVacation("החופש הגדול", "vacation", parseDate(sdf, "2026-07-01"), parseDate(sdf, "2026-08-31")),
            HolidayOrVacation("ראש השנה 2026", "holiday", parseDate(sdf, "2026-09-12"), parseDate(sdf, "2026-09-13")),
            HolidayOrVacation("יום כיפור 2026", "holiday", parseDate(sdf, "2026-09-21"), parseDate(sdf, "2026-09-21")),
            HolidayOrVacation("סוכות 2026", "holiday", parseDate(sdf, "2026-09-26"), parseDate(sdf, "2026-10-03")),
        )
    }

    fun getHolidaysAndVacationsFor2026(): List<HolidayOrVacation> = entries2026

    fun entriesOn(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<HolidayOrVacation> =
        entries2026.filter { it.covers(date, zoneId) }

    fun getHebrewDateString(calendar: Calendar): String {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        // Simulated Hebrew date string
        return when (calendar.get(Calendar.MONTH)) {
            Calendar.JULY -> "$day בתמוז"
            Calendar.AUGUST -> "$day באב"
            Calendar.SEPTEMBER -> "$day בתשרי"
            else -> "$day בניסן"
        }
    }

    private fun parseDate(sdf: SimpleDateFormat, dateStr: String): Long =
        sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
}
