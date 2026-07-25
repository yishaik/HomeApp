package com.yishaik.homeapp.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

object RecurrenceEngine {
    fun nextOccurrence(after: LocalDate, rule: RecurrenceRule): LocalDate? {
        val candidate = when (rule.frequency) {
            RecurrenceRule.Frequency.DAILY -> after.plusDays(rule.interval.toLong())
            RecurrenceRule.Frequency.WEEKLY -> nextWeekly(after, rule)
            RecurrenceRule.Frequency.MONTHLY -> nextMonthly(after, rule)
            RecurrenceRule.Frequency.YEARLY -> after.plusYears(rule.interval.toLong())
        }
        return candidate.takeUnless { rule.until != null && it.isAfter(rule.until) }
    }

    private fun nextWeekly(after: LocalDate, rule: RecurrenceRule): LocalDate {
        if (rule.daysOfWeek.isEmpty()) return after.plusWeeks(rule.interval.toLong())
        for (offset in 1..(7 * rule.interval + 7)) {
            val date = after.plusDays(offset.toLong())
            if (date.dayOfWeek.value in rule.daysOfWeek) return date
        }
        return after.plusWeeks(rule.interval.toLong())
    }

    private fun nextMonthly(after: LocalDate, rule: RecurrenceRule): LocalDate {
        val targetMonth = YearMonth.from(after).plusMonths(rule.interval.toLong())
        rule.dayOfMonth?.let { return targetMonth.atDay(it.coerceAtMost(targetMonth.lengthOfMonth())) }
        val ordinal = rule.ordinal
        val day = rule.daysOfWeek.firstOrNull()
        if (ordinal != null && day != null) {
            val dow = DayOfWeek.of(day)
            return if (ordinal > 0) {
                targetMonth.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, dow))
            } else {
                targetMonth.atEndOfMonth().with(TemporalAdjusters.previousOrSame(dow))
            }
        }
        return targetMonth.atDay(after.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
    }
}
