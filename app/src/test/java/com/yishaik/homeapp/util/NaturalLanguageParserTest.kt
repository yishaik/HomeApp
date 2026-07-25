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
}
