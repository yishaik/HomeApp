package com.yishaik.homeapp.data

/**
 * Encoding of the pending-push id set, which lives in a single comma-joined `metadata` value.
 * Pure logic, kept apart from [HomeRepository] so the add/remove semantics are unit-testable
 * without an Android SQLite instance. All callers must serialize their read-modify-write
 * themselves (HomeRepository does it under `dbMutex`).
 */
internal object PendingSet {
    fun decode(raw: String?): Set<String> =
        raw?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun encode(ids: Set<String>): String = ids.joinToString(",")

    fun add(raw: String?, id: String): Set<String> = decode(raw) + id

    fun remove(raw: String?, id: String): Set<String> = decode(raw) - id
}
