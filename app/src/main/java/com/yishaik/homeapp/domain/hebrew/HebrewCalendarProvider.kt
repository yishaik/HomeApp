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

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // 2026 School Vacations and Israeli Holidays dataset
    private val entries2026: List<HolidayOrVacation> = listOf(
        HolidayOrVacation("חופשת פסח", "vacation", parseDate("2026-03-27"), parseDate("2026-04-09")),
        HolidayOrVacation("פסח - ערב חג", "holiday", parseDate("2026-04-01"), parseDate("2026-04-02")),
        HolidayOrVacation("חופשת יום העצמאות", "vacation", parseDate("2026-04-22"), parseDate("2026-04-23")),
        HolidayOrVacation("יום העצמאות", "holiday", parseDate("2026-04-22"), parseDate("2026-04-22")),
        HolidayOrVacation("חופשת שבועות", "vacation", parseDate("2026-05-21"), parseDate("2026-05-23")),
        HolidayOrVacation("שבועות", "holiday", parseDate("2026-05-22"), parseDate("2026-05-22")),
        HolidayOrVacation("החופש הגדול", "vacation", parseDate("2026-07-01"), parseDate("2026-08-31")),
        HolidayOrVacation("ראש השנה 2026", "holiday", parseDate("2026-09-12"), parseDate("2026-09-13")),
        HolidayOrVacation("יום כיפור 2026", "holiday", parseDate("2026-09-21"), parseDate("2026-09-21")),
        HolidayOrVacation("סוכות 2026", "holiday", parseDate("2026-09-26"), parseDate("2026-10-03")),
    )

    // 2027 (5787/5788) religious + national holidays, real dates from hebcal.com (Israel, geonameid
    // 293397, mod=on/i=on). Vacation windows below are NOT official Ministry-of-Education dates —
    // the Ministry has not published a school calendar this far ahead. They are an institutional-
    // pattern approximation anchored to the real chag dates (same heuristic as entries2026: Pesach
    // vacation ~= a few days before Pesach I through Pesach VII; Sukkot/RH vacation ~= Rosh Hashana
    // through Shmini Atzeret; big summer break = July 1 - Aug 31) and MUST be corrected once the
    // Ministry publishes the real dates for that year.
    private val entries2027: List<HolidayOrVacation> = listOf(
        HolidayOrVacation("ט\"ו בשבט", "holiday", parseDate("2027-01-23"), parseDate("2027-01-23")),
        HolidayOrVacation("פורים - ערב חג", "holiday", parseDate("2027-03-22"), parseDate("2027-03-22")),
        HolidayOrVacation("פורים", "holiday", parseDate("2027-03-23"), parseDate("2027-03-23")),
        HolidayOrVacation("שושן פורים", "holiday", parseDate("2027-03-24"), parseDate("2027-03-24")),
        HolidayOrVacation("חופשת פסח", "vacation", parseDate("2027-04-19"), parseDate("2027-04-28")),
        HolidayOrVacation("פסח - ערב חג", "holiday", parseDate("2027-04-21"), parseDate("2027-04-21")),
        HolidayOrVacation("פסח א'", "holiday", parseDate("2027-04-22"), parseDate("2027-04-22")),
        HolidayOrVacation("פסח - חול המועד", "holiday", parseDate("2027-04-23"), parseDate("2027-04-27")),
        HolidayOrVacation("פסח - שביעי של פסח", "holiday", parseDate("2027-04-28"), parseDate("2027-04-28")),
        HolidayOrVacation("יום העלייה", "holiday", parseDate("2027-04-17"), parseDate("2027-04-17")),
        HolidayOrVacation("יום השואה", "holiday", parseDate("2027-05-04"), parseDate("2027-05-04")),
        HolidayOrVacation("יום הזיכרון", "holiday", parseDate("2027-05-11"), parseDate("2027-05-11")),
        HolidayOrVacation("יום העצמאות", "holiday", parseDate("2027-05-12"), parseDate("2027-05-12")),
        HolidayOrVacation("פסח שני", "holiday", parseDate("2027-05-21"), parseDate("2027-05-21")),
        HolidayOrVacation("ל\"ג בעומר", "holiday", parseDate("2027-05-25"), parseDate("2027-05-25")),
        HolidayOrVacation("יום ירושלים", "holiday", parseDate("2027-06-04"), parseDate("2027-06-04")),
        HolidayOrVacation("שבועות - ערב חג", "holiday", parseDate("2027-06-10"), parseDate("2027-06-10")),
        HolidayOrVacation("שבועות", "holiday", parseDate("2027-06-11"), parseDate("2027-06-11")),
        HolidayOrVacation("החופש הגדול", "vacation", parseDate("2027-07-01"), parseDate("2027-08-31")),
        HolidayOrVacation("תשעה באב - ערב", "holiday", parseDate("2027-08-11"), parseDate("2027-08-11")),
        HolidayOrVacation("תשעה באב", "holiday", parseDate("2027-08-12"), parseDate("2027-08-12")),
        HolidayOrVacation("ט\"ו באב", "holiday", parseDate("2027-08-18"), parseDate("2027-08-18")),
        HolidayOrVacation("חופשת סוכות/ר\"ה", "vacation", parseDate("2027-10-01"), parseDate("2027-10-23")),
        HolidayOrVacation("ראש השנה - ערב חג", "holiday", parseDate("2027-10-01"), parseDate("2027-10-01")),
        HolidayOrVacation("ראש השנה", "holiday", parseDate("2027-10-02"), parseDate("2027-10-03")),
        HolidayOrVacation("יום כיפור - ערב", "holiday", parseDate("2027-10-10"), parseDate("2027-10-10")),
        HolidayOrVacation("יום כיפור", "holiday", parseDate("2027-10-11"), parseDate("2027-10-11")),
        HolidayOrVacation("סוכות - ערב חג", "holiday", parseDate("2027-10-15"), parseDate("2027-10-15")),
        HolidayOrVacation("סוכות א'", "holiday", parseDate("2027-10-16"), parseDate("2027-10-16")),
        HolidayOrVacation("סוכות - חול המועד", "holiday", parseDate("2027-10-17"), parseDate("2027-10-21")),
        HolidayOrVacation("הושענא רבה", "holiday", parseDate("2027-10-22"), parseDate("2027-10-22")),
        HolidayOrVacation("שמיני עצרת", "holiday", parseDate("2027-10-23"), parseDate("2027-10-23")),
        HolidayOrVacation("חנוכה", "holiday", parseDate("2027-12-24"), parseDate("2028-01-01")),
    )

    // 2028, same sourcing and vacation-heuristic caveat as entries2027 above.
    private val entries2028: List<HolidayOrVacation> = listOf(
        HolidayOrVacation("ט\"ו בשבט", "holiday", parseDate("2028-02-12"), parseDate("2028-02-12")),
        HolidayOrVacation("פורים - ערב חג", "holiday", parseDate("2028-03-11"), parseDate("2028-03-11")),
        HolidayOrVacation("פורים", "holiday", parseDate("2028-03-12"), parseDate("2028-03-12")),
        HolidayOrVacation("שושן פורים", "holiday", parseDate("2028-03-13"), parseDate("2028-03-13")),
        HolidayOrVacation("חופשת פסח", "vacation", parseDate("2028-04-08"), parseDate("2028-04-17")),
        HolidayOrVacation("פסח - ערב חג", "holiday", parseDate("2028-04-10"), parseDate("2028-04-10")),
        HolidayOrVacation("פסח א'", "holiday", parseDate("2028-04-11"), parseDate("2028-04-11")),
        HolidayOrVacation("פסח - חול המועד", "holiday", parseDate("2028-04-12"), parseDate("2028-04-16")),
        HolidayOrVacation("פסח - שביעי של פסח", "holiday", parseDate("2028-04-17"), parseDate("2028-04-17")),
        HolidayOrVacation("יום העלייה", "holiday", parseDate("2028-04-06"), parseDate("2028-04-06")),
        HolidayOrVacation("יום השואה", "holiday", parseDate("2028-04-24"), parseDate("2028-04-24")),
        HolidayOrVacation("יום הזיכרון", "holiday", parseDate("2028-05-01"), parseDate("2028-05-01")),
        HolidayOrVacation("יום העצמאות", "holiday", parseDate("2028-05-02"), parseDate("2028-05-02")),
        HolidayOrVacation("פסח שני", "holiday", parseDate("2028-05-10"), parseDate("2028-05-10")),
        HolidayOrVacation("ל\"ג בעומר", "holiday", parseDate("2028-05-14"), parseDate("2028-05-14")),
        HolidayOrVacation("יום ירושלים", "holiday", parseDate("2028-05-24"), parseDate("2028-05-24")),
        HolidayOrVacation("שבועות - ערב חג", "holiday", parseDate("2028-05-30"), parseDate("2028-05-30")),
        HolidayOrVacation("שבועות", "holiday", parseDate("2028-05-31"), parseDate("2028-05-31")),
        HolidayOrVacation("החופש הגדול", "vacation", parseDate("2028-07-01"), parseDate("2028-08-31")),
        HolidayOrVacation("תשעה באב - ערב", "holiday", parseDate("2028-07-31"), parseDate("2028-07-31")),
        HolidayOrVacation("תשעה באב", "holiday", parseDate("2028-08-01"), parseDate("2028-08-01")),
        HolidayOrVacation("ט\"ו באב", "holiday", parseDate("2028-08-07"), parseDate("2028-08-07")),
        HolidayOrVacation("חופשת סוכות/ר\"ה", "vacation", parseDate("2028-09-20"), parseDate("2028-10-12")),
        HolidayOrVacation("ראש השנה - ערב חג", "holiday", parseDate("2028-09-20"), parseDate("2028-09-20")),
        HolidayOrVacation("ראש השנה", "holiday", parseDate("2028-09-21"), parseDate("2028-09-22")),
        HolidayOrVacation("יום כיפור - ערב", "holiday", parseDate("2028-09-29"), parseDate("2028-09-29")),
        HolidayOrVacation("יום כיפור", "holiday", parseDate("2028-09-30"), parseDate("2028-09-30")),
        HolidayOrVacation("סוכות - ערב חג", "holiday", parseDate("2028-10-04"), parseDate("2028-10-04")),
        HolidayOrVacation("סוכות א'", "holiday", parseDate("2028-10-05"), parseDate("2028-10-05")),
        HolidayOrVacation("סוכות - חול המועד", "holiday", parseDate("2028-10-06"), parseDate("2028-10-10")),
        HolidayOrVacation("הושענא רבה", "holiday", parseDate("2028-10-11"), parseDate("2028-10-11")),
        HolidayOrVacation("שמיני עצרת", "holiday", parseDate("2028-10-12"), parseDate("2028-10-12")),
        HolidayOrVacation("חנוכה", "holiday", parseDate("2028-12-12"), parseDate("2028-12-20")),
    )

    private val entriesByYear: Map<Int, List<HolidayOrVacation>> = mapOf(
        2026 to entries2026,
        2027 to entries2027,
        2028 to entries2028,
    )

    fun getHolidaysAndVacationsFor2026(): List<HolidayOrVacation> = entries2026

    /** Returns every holiday/vacation entry covering [date]. Years with no populated dataset (e.g.
     *  2029+, until this table is topped up again) cleanly return an empty list rather than crash. */
    fun entriesOn(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<HolidayOrVacation> =
        entriesByYear[date.year]?.filter { it.covers(date, zoneId) } ?: emptyList()

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

    private fun parseDate(dateStr: String): Long = sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
}
