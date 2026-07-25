package com.yishaik.homeapp.sync

import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.domain.ItemType
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConflictResolverTest {
    private val base = HomeItem(type = ItemType.NOTE, title = "A", body = "B", creatorId = "u1")

    @Test fun mergesDifferentFieldsWithoutConflict() {
        val local = base.copy(title = "Local", updatedAt = Instant.parse("2026-07-25T08:00:00Z"))
        val remote = base.copy(body = "Remote", updatedAt = Instant.parse("2026-07-25T08:01:00Z"))
        val result = ConflictResolver.merge(base, local, remote)
        assertEquals("Local", result.merged.title)
        assertEquals("Remote", result.merged.body)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test fun reportsSameFieldConflict() {
        val local = base.copy(title = "Local", updatedAt = Instant.parse("2026-07-25T08:00:00Z"))
        val remote = base.copy(title = "Remote", updatedAt = Instant.parse("2026-07-25T08:01:00Z"))
        val result = ConflictResolver.merge(base, local, remote)
        assertEquals("Remote", result.merged.title)
        assertEquals(1, result.conflicts.size)
    }
}
