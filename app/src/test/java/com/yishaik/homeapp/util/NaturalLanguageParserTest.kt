package com.yishaik.homeapp.util

import com.yishaik.homeapp.domain.ItemType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class NaturalLanguageParserTest {
    private val now = LocalDateTime.of(2026, 7, 25, 8, 0)

    @Test fun parsesHebrewTomorrowEvent() {
        val result = NaturalLanguageParser.parse("רופא שיניים מחר ב־16:00", now)
        assertEquals(ItemType.EVENT, result.type)
        assertEquals(LocalDateTime.of(2026, 7, 26, 16, 0), result.dateTime)
        assertTrue(result.confidence > .8f)
    }

    @Test fun parsesEnglishTask() {
        val result = NaturalLanguageParser.parse("task call school tomorrow at 09:30", now)
        assertEquals(ItemType.TASK, result.type)
        assertEquals(9, result.dateTime?.hour)
    }

    @Test fun parsesHebrewTomorrowWithBeshaaTime() {
        val result = NaturalLanguageParser.parse("פגישה מחר בשעה 10:00", now)
        assertEquals(ItemType.EVENT, result.type)
        assertEquals(LocalDateTime.of(2026, 7, 26, 10, 0), result.dateTime)
        assertTrue(result.confidence > .8f)
    }

    @Test fun parsesHebrewWeekdayReference() {
        // now is Saturday 2026-07-25, so the next Tuesday is 2026-07-28.
        val result = NaturalLanguageParser.parse("תור לרופא ביום שלישי", now)
        assertEquals(ItemType.EVENT, result.type)
        assertEquals(LocalDateTime.of(2026, 7, 28, 9, 0), result.dateTime)
    }

    @Test fun parsesHebrewShoppingListHint() {
        val result = NaturalLanguageParser.parse("קניות לחם וחלב", now)
        assertEquals(ItemType.LIST, result.type)
        assertEquals("קניות לחם וחלב", result.title)
        assertNull(result.dateTime)
        assertFalse(result.needsAiFallback)
    }

    @Test fun parsesHebrewDayAfterTomorrow() {
        val result = NaturalLanguageParser.parse("תור לרופא מחרתיים בשעה 9:00", now)
        assertEquals(ItemType.EVENT, result.type)
        assertEquals(LocalDateTime.of(2026, 7, 27, 9, 0), result.dateTime)
    }

    @Test fun parsesHebrewReminderAsTask() {
        val result = NaturalLanguageParser.parse("תזכורת להתקשר לאמא מחר", now)
        assertEquals(ItemType.TASK, result.type)
        assertEquals(LocalDateTime.of(2026, 7, 26, 9, 0), result.dateTime)
    }

    @Test fun fallsBackToNoteForPlainHebrew() {
        val result = NaturalLanguageParser.parse("לא לשכוח את הספר", now)
        assertEquals(ItemType.NOTE, result.type)
        assertNull(result.dateTime)
        assertTrue(result.needsAiFallback)
    }

    @Test fun parsesMixedHebrewEnglishInput() {
        val result = NaturalLanguageParser.parse("meeting עם דנה מחר at 14:00", now)
        assertEquals(ItemType.EVENT, result.type)
        assertEquals(LocalDateTime.of(2026, 7, 26, 14, 0), result.dateTime)
    }
}
