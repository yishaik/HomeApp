package com.yishaik.homeapp.data

import org.junit.Assert.*
import org.junit.Test

class PendingSetTest {
    @Test fun decodesEmptyAndBlankToEmptySet() {
        assertEquals(emptySet<String>(), PendingSet.decode(null))
        assertEquals(emptySet<String>(), PendingSet.decode(""))
        assertEquals(emptySet<String>(), PendingSet.decode(",,"))
    }

    @Test fun roundTripsThroughTheCommaJoinedForm() {
        val ids = setOf("a", "b", "c")
        assertEquals(ids, PendingSet.decode(PendingSet.encode(ids)))
    }

    @Test fun addIsIdempotentAndKeepsExistingIds() {
        val once = PendingSet.add("a,b", "c")
        assertEquals(setOf("a", "b", "c"), once)
        assertEquals(once, PendingSet.add(PendingSet.encode(once), "c"))
    }

    @Test fun removeDropsOnlyTheGivenIdAndToleratesUnknownIds() {
        assertEquals(setOf("a", "c"), PendingSet.remove("a,b,c", "b"))
        assertEquals(setOf("a", "b"), PendingSet.remove("a,b", "zz"))
        assertEquals(emptySet<String>(), PendingSet.remove("a", "a"))
    }

    @Test fun clearingOneIdDoesNotClearAnother() {
        // The N2 failure mode: two items pending, one push succeeds — the other must stay flagged.
        var raw = PendingSet.encode(PendingSet.add(PendingSet.encode(PendingSet.add(null, "A")), "B"))
        assertEquals(setOf("A", "B"), PendingSet.decode(raw))
        raw = PendingSet.encode(PendingSet.remove(raw, "A"))
        assertEquals(setOf("B"), PendingSet.decode(raw))
    }

    @Test fun encodingAnEmptySetProducesAValueThatDecodesBackToEmpty() {
        assertEquals(emptySet<String>(), PendingSet.decode(PendingSet.encode(emptySet())))
    }
}
