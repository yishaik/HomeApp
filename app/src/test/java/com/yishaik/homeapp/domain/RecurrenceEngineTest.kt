package com.yishaik.homeapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecurrenceEngineTest {
    @Test fun everyTwoWeeks() {
        val start = LocalDate.of(2026, 7, 26)
        val rule = RecurrenceRule(RecurrenceRule.Frequency.WEEKLY, interval = 2)
        assertEquals(LocalDate.of(2026, 8, 9), RecurrenceEngine.nextOccurrence(start, rule))
    }

    @Test fun firstSundayOfNextMonth() {
        val start = LocalDate.of(2026, 7, 5)
        val rule = RecurrenceRule(RecurrenceRule.Frequency.MONTHLY, daysOfWeek = setOf(7), ordinal = 1)
        assertEquals(LocalDate.of(2026, 8, 2), RecurrenceEngine.nextOccurrence(start, rule))
    }
}
