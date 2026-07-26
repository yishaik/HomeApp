package com.yishaik.homeapp.data

import com.yishaik.homeapp.domain.AppNotification
import com.yishaik.homeapp.domain.AppUser
import com.yishaik.homeapp.domain.ChecklistEntry
import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.domain.ItemStatus
import com.yishaik.homeapp.domain.ItemType
import com.yishaik.homeapp.domain.ReadReceipt
import com.yishaik.homeapp.domain.Reminder
import com.yishaik.homeapp.domain.SampleData
import com.yishaik.homeapp.security.AuthSession
import com.yishaik.homeapp.security.SessionStore
import com.yishaik.homeapp.sync.SupabaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import java.time.Instant
import java.time.ZoneId

class HomeRepository(
    private val database: LocalDatabase,
    private val connectivity: ConnectivityObserver,
    private val api: SupabaseApi,
    private val sessionStore: SessionStore,
    private val preferences: PreferencesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards the *network* phase of a sync so two syncs never interleave their fetches. */
    private val syncMutex = Mutex()

    /**
     * Guards every mutation of the local SQLite cache, the pending-push set and [_items], so that
     * a save and a sync's destructive `replaceAll` can never interleave (see audit N2/N3).
     *
     * Rules — Kotlin's [Mutex] is NOT reentrant, so they matter:
     *  - never acquire it while already holding it (helpers suffixed `Locked` assume the caller holds it),
     *  - never hold it across a network call (fetch/push always happen outside),
     *  - it is always the innermost lock: `syncMutex` may be held while taking it, never the reverse.
     */
    private val dbMutex = Mutex()
    private var realtime: WebSocket? = null
    private var realtimeToken: String? = null

    private val _items = MutableStateFlow<List<HomeItem>>(emptyList())
    val items: StateFlow<List<HomeItem>> = _items.asStateFlow()
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _currentUser = MutableStateFlow(sessionStore.load()?.user() ?: SampleData.userOne)
    val currentUser: StateFlow<AppUser> = _currentUser.asStateFlow()
    private val _users = MutableStateFlow<Map<String, AppUser>>(mapOf(_currentUser.value.id to _currentUser.value))
    val users: StateFlow<Map<String, AppUser>> = _users.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()
    private val _pendingIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of items with local changes not yet confirmed pushed — drives the per-item "pending" badge. */
    val pendingIds: StateFlow<Set<String>> = _pendingIds.asStateFlow()
    private val _notificationsSeenAt = MutableStateFlow(preferences.notificationsSeenAt())

    /**
     * Notifications derived from real signals — see [deriveNotifications]. An item is "unread"
     * while its timestamp is after the current user's notifications-seen timestamp.
     */
    val notifications: StateFlow<List<AppNotification>> =
        combine(_items, _currentUser, _notificationsSeenAt) { items, me, seenAt ->
            deriveNotifications(items, me.id, seenAt)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = notifications
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    fun markAllNotificationsRead() {
        preferences.markNotificationsSeen()
        _notificationsSeenAt.value = preferences.notificationsSeenAt()
    }

    init {
        scope.launch {
            dbMutex.withLock {
                _pendingIds.value = pendingIdsLocked()
                val cached = database.readItems().filter { it.status != ItemStatus.CANCELLED }
                if (cached.isEmpty() && !api.configured) {
                    val seed = SampleData.items()
                    database.upsertAll(seed)
                    _items.value = seed
                } else {
                    _items.value = cached
                }
            }
        }
        scope.launch {
            connectivity.online.collect { value ->
                _online.value = value
                if (value && sessionStore.hasSession()) syncNow()
            }
        }
    }

    suspend fun activateSession(session: AuthSession) {
        sessionStore.save(session)
        _currentUser.value = session.user()
        _users.value = mapOf(session.userId to session.user())
        syncNow()
    }

    // Items with local changes not yet confirmed pushed to the backend. Persisted across restarts
    // so a change is never lost if a push fails (bad session, offline, server hiccup).
    // ALL FOUR HELPERS BELOW ASSUME THE CALLER ALREADY HOLDS [dbMutex] — they never take it
    // themselves, so they can be composed inside a larger locked section without deadlocking.
    private fun pendingIdsLocked(): Set<String> = PendingSet.decode(database.getMetadata("pending_push"))

    private fun setPendingLocked(ids: Set<String>) {
        database.setMetadata("pending_push", PendingSet.encode(ids))
        _pendingIds.value = ids
    }

    private fun markPendingLocked(id: String) = setPendingLocked(PendingSet.add(database.getMetadata("pending_push"), id))

    private fun clearPendingLocked(id: String) = setPendingLocked(PendingSet.remove(database.getMetadata("pending_push"), id))

    /**
     * Best-effort push of every locally-changed item to the backend; clears the pending flag on
     * success. The local read and the flag decision happen under [dbMutex]; every network call
     * happens outside it.
     */
    suspend fun pushPending() {
        if (!api.configured || !sessionStore.hasSession()) return
        val ids = dbMutex.withLock { pendingIdsLocked() }
        for (id in ids) {
            // Re-check under the lock: the item may have been pushed or removed since the snapshot.
            val local = dbMutex.withLock {
                if (id !in pendingIdsLocked()) null
                else database.readItem(id).also { if (it == null) clearPendingLocked(id) }
            } ?: continue
            runCatching { api.upsertItem(local, validSession()) }
                .onSuccess { dbMutex.withLock { clearPendingLocked(id) }; _lastSyncError.value = null }
                .onFailure { _lastSyncError.value = it.message ?: "Sync pending" }
        }
    }

    suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!api.configured || !_online.value || !sessionStore.hasSession()) return@withContext Result.success(Unit)
        runCatching {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val session = validSession()
                    // Network phase — deliberately outside dbMutex, so a save() is never blocked
                    // behind a fetch and the lock is never held across IO.
                    val remoteUsers = api.fetchProfiles(session)
                    val remoteItems = api.fetchItems(session)
                    // Merge: keep local (unsynced) versions of pending items so a background push can
                    // still deliver them; everything else takes the remote copy. This stops sync from
                    // resurrecting a locally-deleted item or clobbering a local edit. Reading the
                    // pending snapshot and running the destructive replaceAll must be atomic with
                    // respect to save(), otherwise a row written between the two is deleted (N3).
                    dbMutex.withLock {
                        val pending = pendingIdsLocked()
                        val localById = database.readItems().associateBy { it.id }
                        val merged = remoteItems.filter { it.id !in pending } + pending.mapNotNull { localById[it] }
                        database.replaceAll(merged)
                        _items.value = merged.filter { it.status != ItemStatus.CANCELLED }.sortedBy { it.updatedAt }
                    }
                    _users.value = remoteUsers.associateBy { it.id }.ifEmpty { mapOf(session.userId to session.user()) }
                    _currentUser.value = _users.value[session.userId] ?: session.user()
                    _lastSyncError.value = null
                    ensureRealtime(session)
                } finally {
                    _syncing.value = false
                }
            }
            pushPending()
        }.onFailure { _lastSyncError.value = it.message ?: "Sync failed" }
    }

    /** Stamps ownership/revision/updatedAt onto an item about to be written locally. */
    private fun normalize(item: HomeItem): HomeItem {
        val session = sessionStore.load()
        return item.copy(
            householdId = session?.householdId?.takeIf { it.isNotBlank() } ?: item.householdId,
            creatorId = item.creatorId.takeIf { it.isNotBlank() && it != "u1" && it != "u2" }
                ?: session?.userId ?: item.creatorId,
            revision = item.revision + 1,
            updatedAt = Instant.now(),
        )
    }

    /**
     * The one and only local write path: reads the current copy of [id], applies [block] to it and
     * atomically persists the result to SQLite, the pending set and [_items] — all under [dbMutex],
     * with no network call inside. Returns the written item, or null when [block] declines.
     */
    private suspend fun commitLocal(id: String, block: (HomeItem?) -> HomeItem?): HomeItem? = dbMutex.withLock {
        val next = block(_items.value.firstOrNull { it.id == id })?.let(::normalize) ?: return@withLock null
        database.upsert(next)
        markPendingLocked(next.id)
        _items.value = (_items.value.filterNot { it.id == next.id } + next)
            .filter { it.status != ItemStatus.CANCELLED }
            .sortedBy { it.updatedAt }
        next
    }

    /** Pushes one already-persisted item. Never called while holding [dbMutex]. */
    private suspend fun pushLocal(item: HomeItem) {
        if (!api.configured || sessionStore.load() == null) {
            dbMutex.withLock { clearPendingLocked(item.id) }
            return
        }
        runCatching { api.upsertItem(item, validSession()) }
            .onSuccess { dbMutex.withLock { clearPendingLocked(item.id) }; _lastSyncError.value = null }
            .onFailure { _lastSyncError.value = it.message ?: "Save queued (will retry)" }
    }

    suspend fun save(item: HomeItem): Result<HomeItem> = withContext(Dispatchers.IO) {
        // Local-first: persist the change to the device immediately (so it survives a restart and
        // shows instantly), then push to the backend in the background. A failed push keeps the item
        // flagged pending and is retried on the next sync — the change is never silently lost.
        runCatching {
            val saved = commitLocal(item.id) { item } ?: error("Save failed")
            pushLocal(saved)
            saved
        }.onFailure { _lastSyncError.value = it.message ?: "Save failed" }
    }

    suspend fun archive(id: String): Result<Unit> = mutate(id) { it.copy(status = ItemStatus.ARCHIVED) }
    suspend fun complete(id: String): Result<Unit> = mutate(id) { it.copy(status = ItemStatus.COMPLETED) }
    suspend fun cancel(id: String): Result<Unit> = mutate(id) { it.copy(status = ItemStatus.CANCELLED) }

    suspend fun toggleChecklist(itemId: String, entryId: String): Result<Unit> = mutate(itemId) { item ->
        val updated = item.checklist.map { if (it.id == entryId) it.copy(completed = !it.completed) else it }
        item.copy(
            checklist = updated,
            status = if (updated.isNotEmpty() && updated.all { it.completed }) ItemStatus.COMPLETED else item.status,
        )
    }

    suspend fun addChecklistEntry(itemId: String, title: String): Result<Unit> = mutate(itemId) { item ->
        val next = item.checklist + ChecklistEntry(title = title.trim(), orderIndex = item.checklist.size)
        item.copy(checklist = next, status = if (item.status == ItemStatus.COMPLETED) ItemStatus.ACTIVE else item.status)
    }

    suspend fun removeChecklistEntry(itemId: String, entryId: String): Result<Unit> = mutate(itemId) { item ->
        item.copy(checklist = item.checklist.filterNot { it.id == entryId })
    }

    suspend fun renameChecklistEntry(itemId: String, entryId: String, title: String): Result<Unit> = mutate(itemId) { item ->
        item.copy(checklist = item.checklist.map { if (it.id == entryId) it.copy(title = title.trim()) else it })
    }

    /** Sets [userId]'s reminders on an item while preserving the other member's reminders. */
    suspend fun setReminders(itemId: String, userId: String, reminders: List<Reminder>): Result<HomeItem> = withContext(Dispatchers.IO) {
        runCatching {
            val saved = commitLocal(itemId) { current ->
                current?.copy(reminders = current.reminders.filter { it.userId != userId } + reminders.map { it.copy(userId = userId) })
            } ?: throw NoSuchElementException(itemId)
            pushLocal(saved)
            saved
        }.onFailure { if (it !is NoSuchElementException) _lastSyncError.value = it.message ?: "Save failed" }
    }

    /**
     * Soft-deletes an item. The backend forbids hard DELETE via RLS (homeapp_items_no_delete),
     * so we mark the row CANCELLED through the normal save/upsert path (which the DB does allow)
     * and hide CANCELLED items everywhere. This persists across devices and survives a sync,
     * unlike a hard DELETE which the server silently ignores.
     */
    suspend fun delete(id: String): Result<Unit> = mutate(id) { it.copy(status = ItemStatus.CANCELLED) }

    suspend fun markNoteRead(itemId: String, userId: String): Result<Unit> = mutate(itemId) { item ->
        item.copy(readReceipts = item.readReceipts.filterNot { it.userId == userId } + ReadReceipt(userId, Instant.now()))
    }

    suspend fun publishScheduledNotes(now: Instant = Instant.now()) {
        _items.value
            .filter { it.type == ItemType.NOTE && it.scheduledPublishAt?.isAfter(now) == false && it.readReceipts.isEmpty() }
            .forEach { note -> save(note.copy(readReceipts = _users.value.keys.map { ReadReceipt(it, null) })) }
    }

    fun itemsForDate(date: java.time.LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<HomeItem> =
        _items.value.filter { it.status != ItemStatus.ARCHIVED && (it.dateIn(zoneId) == date || it.isOverdue(Instant.now())) }

    fun setCurrentUser(user: AppUser) {
        _currentUser.value = user
        _users.value = _users.value + (user.id to user)
    }

    /** Renames the current user in memory and persists the display name to the local session so it survives reloads. */
    fun renameCurrentUser(displayName: String) = setProfile(displayName, _currentUser.value.avatar, _currentUser.value.accentArgb)

    /** Updates the current user's display name, avatar glyph and accent colour: locally + persisted
     *  to homeapp_profiles. Without the remote write, the next sync's fetchProfiles() would pull the
     *  old row and silently revert the change — this was exactly that bug. */
    fun setProfile(displayName: String, avatar: String, accentArgb: Long) {
        val name = displayName.trim().ifBlank { return }
        val glyph = avatar.trim().take(2).ifBlank { _currentUser.value.avatar }
        val updated = _currentUser.value.copy(displayName = name, avatar = glyph, accentArgb = accentArgb)
        setCurrentUser(updated)
        sessionStore.load()?.let { sessionStore.save(it.copy(displayName = name, avatar = glyph, accentArgb = accentArgb)) }
        if (api.configured && sessionStore.load() != null) {
            scope.launch {
                runCatching { api.updateProfile(validSession(), name, glyph, accentArgb) }
                    .onSuccess { _lastSyncError.value = null }
                    .onFailure { _lastSyncError.value = it.message ?: "Profile update queued (will retry)" }
            }
        }
    }

    /**
     * Ends the session and wipes every trace of the member from this device: items AND metadata
     * (preferences, notifications-seen, pending ids). The wipe runs under [dbMutex] so an in-flight
     * save cannot re-create a row after it. Callers must also clear the PIN and cancel the
     * scheduled alarms (see HomeAppRoot) — those live outside this class.
     */
    fun logout() {
        realtime?.cancel()
        realtime = null
        realtimeToken = null
        sessionStore.clear()
        _items.value = emptyList()
        _notificationsSeenAt.value = null
        _pendingIds.value = emptySet()
        scope.launch {
            dbMutex.withLock {
                database.clearEverything()
                _items.value = emptyList()
                _pendingIds.value = emptySet()
            }
            preferences.reload()
        }
    }

    private suspend fun validSession(): AuthSession {
        val current = sessionStore.load() ?: error("No authenticated session")
        if (!current.expiresSoon()) return current
        val refreshed = api.refreshSession(current)
        sessionStore.save(refreshed)
        return refreshed
    }

    private fun ensureRealtime(session: AuthSession) {
        if (realtimeToken == session.accessToken && realtime != null) return
        realtime?.cancel()
        realtimeToken = session.accessToken
        realtime = api.openRealtime(session) { scope.launch { syncNow() } }
    }

    /**
     * Read-modify-write of a single item, applied to the *freshly read* row inside [dbMutex] so two
     * rapid edits on the same device (e.g. ticking two checklist boxes) cannot lose each other (N4).
     */
    private suspend fun mutate(id: String, block: (HomeItem) -> HomeItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val saved = commitLocal(id) { current -> current?.let(block) } ?: throw NoSuchElementException(id)
            pushLocal(saved)
        }.onFailure { if (it !is NoSuchElementException) _lastSyncError.value = it.message ?: "Save failed" }
    }
}

/**
 * Notifications derived from real signals: items updated by the OTHER user, task/list completions
 * by the other user, new comments authored by the other user, and items the author explicitly
 * flagged with "הודע למשתמש השני" ([HomeItem.notifyOtherUser]) — those always notify the other
 * member, whatever the update heuristics say. An item is "unread" while its timestamp is after the
 * current user's notifications-seen timestamp.
 *
 * Top-level and internal so it can be unit-tested without an Android SQLite instance.
 */
internal fun deriveNotifications(items: List<HomeItem>, meId: String, seenAt: Instant?): List<AppNotification> {
    val forcedIds = items.filter { it.notifyOtherUser && it.creatorId != meId }.map { it.id }.toSet()
    return items.asSequence()
        .filter { it.status != ItemStatus.ARCHIVED }
        .filter { it.creatorId != meId || it.comments.any { c -> c.authorId != meId } }
        .mapNotNull { item ->
            val otherComment = item.comments.filter { it.authorId != meId }.maxByOrNull { it.createdAt }
            val fromOther = item.creatorId != meId
            // Explicitly requested by the author, and I am not the author → always notify me.
            val forced = fromOther && item.notifyOtherUser
            val (title, detail, at) = when {
                fromOther && item.status == ItemStatus.COMPLETED ->
                    Triple("הושלם: ${item.title}", "המשימה סומנה כהושלמה", item.updatedAt)
                otherComment != null && (fromOther || otherComment.createdAt >= item.updatedAt.minusSeconds(1)) ->
                    Triple("תגובה חדשה: ${item.title}", otherComment.text, otherComment.createdAt)
                forced ->
                    Triple("התראה: ${item.title}", labelFor(item), item.updatedAt)
                fromOther ->
                    Triple("עודכן: ${item.title}", labelFor(item), item.updatedAt)
                else -> return@mapNotNull null
            }
            AppNotification(
                recipientId = meId,
                itemId = item.id,
                title = title,
                detail = detail,
                read = seenAt != null && !at.isAfter(seenAt),
                createdAt = at,
            )
        }
        // Explicitly-flagged unread items are never squeezed out by the 30-item cap.
        .sortedWith(
            compareByDescending<AppNotification> { it.itemId.orEmpty() in forcedIds && !it.read }
                .thenByDescending { it.createdAt }
        )
        .take(30)
        .toList()
}

private fun labelFor(item: HomeItem): String = when (item.type) {
    ItemType.EVENT -> "אירוע עודכן"
    ItemType.TASK -> "משימה עודכנה"
    ItemType.LIST -> "רשימה עודכנה"
    ItemType.NOTE -> "פתק חדש"
}
