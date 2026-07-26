package com.yishaik.homeapp.data

import com.yishaik.homeapp.domain.Comment
import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.domain.ItemStatus
import com.yishaik.homeapp.domain.ItemType
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NotificationDerivationTest {
    private val me = "me"
    private val other = "other"
    private val t0: Instant = Instant.parse("2026-07-26T10:00:00Z")

    private fun item(
        creatorId: String,
        notify: Boolean = false,
        status: ItemStatus = ItemStatus.ACTIVE,
        comments: List<Comment> = emptyList(),
        updatedAt: Instant = t0,
    ) = HomeItem(
        type = ItemType.TASK, title = "T", creatorId = creatorId, notifyOtherUser = notify,
        status = status, comments = comments, createdAt = updatedAt, updatedAt = updatedAt,
    )

    @Test fun flaggedItemFromTheOtherMemberAlwaysNotifiesMe() {
        val result = deriveNotifications(listOf(item(other, notify = true)), me, seenAt = null)
        assertEquals(1, result.size)
        assertTrue(result.first().title.startsWith("התראה:"))
        assertFalse(result.first().read)
    }

    @Test fun flaggedItemICreatedNeverNotifiesMe() {
        assertTrue(deriveNotifications(listOf(item(me, notify = true)), me, seenAt = null).isEmpty())
    }

    @Test fun flaggedItemIsReadOnceTheSeenTimestampPassesIt() {
        val items = listOf(item(other, notify = true, updatedAt = t0))
        assertFalse(deriveNotifications(items, me, seenAt = t0.minusSeconds(60)).first().read)
        assertTrue(deriveNotifications(items, me, seenAt = t0.plusSeconds(60)).first().read)
    }

    @Test fun flaggedUnreadItemSurvivesTheThirtyItemCap() {
        val noise = (1..40).map { item(other, updatedAt = t0.plusSeconds(it.toLong())) }
        val flagged = item(other, notify = true, updatedAt = t0.minusSeconds(9_999))
        val result = deriveNotifications(noise + flagged, me, seenAt = null)
        assertEquals(30, result.size)
        assertEquals(flagged.id, result.first().itemId)
    }

    @Test fun completionAndCommentStillWinOverTheGenericFlaggedTitle() {
        val completed = deriveNotifications(
            listOf(item(other, notify = true, status = ItemStatus.COMPLETED)), me, seenAt = null,
        ).first()
        assertTrue(completed.title.startsWith("הושלם:"))

        val commented = deriveNotifications(
            listOf(item(other, notify = true, comments = listOf(Comment(authorId = other, text = "היי")))), me, seenAt = null,
        ).first()
        assertTrue(commented.title.startsWith("תגובה חדשה:"))
    }

    @Test fun archivedItemsNeverNotify() {
        assertTrue(
            deriveNotifications(listOf(item(other, notify = true, status = ItemStatus.ARCHIVED)), me, seenAt = null).isEmpty()
        )
    }
}
