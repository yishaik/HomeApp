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
    private val syncMutex = Mutex()
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
    private val _notificationsSeenAt = MutableStateFlow(preferences.notificationsSeenAt())

    /**
     * Notifications derived from real signals: items updated by the OTHER user, task/list
     * completions by the other user, and new comments authored by the other user. An item is
     * "unread" when its updatedAt is after the current user's notifications-seen timestamp.
     */
    val notifications: StateFlow<List<AppNotification>> =
        combine(_items, _currentUser, _notificationsSeenAt) { items, me, seenAt ->
            deriveNotifications(items, me.id, seenAt)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = notifications
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private fun deriveNotifications(items: List<HomeItem>, meId: String, seenAt: Instant?): List<AppNotification> =
        items.asSequence()
            .filter { it.status != ItemStatus.ARCHIVED }
            .filter { it.creatorId != meId || it.comments.any { c -> c.authorId != meId } }
            .mapNotNull { item ->
                val otherComment = item.comments.filter { it.authorId != meId }.maxByOrNull { it.createdAt }
                val fromOther = item.creatorId != meId
                val (title, detail, at) = when {
                    fromOther && item.status == ItemStatus.COMPLETED ->
                        Triple("הושלם: ${item.title}", "המשימה סומנה כהושלמה", item.updatedAt)
                    otherComment != null && (fromOther || otherComment.createdAt >= item.updatedAt.minusSeconds(1)) ->
                        Triple("תגובה חדשה: ${item.title}", otherComment.text, otherComment.createdAt)
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
            .sortedByDescending { it.createdAt }
            .take(30)
            .toList()

    private fun labelFor(item: HomeItem): String = when (item.type) {
        ItemType.EVENT -> "אירוע עודכן"
        ItemType.TASK -> "משימה עודכנה"
        ItemType.LIST -> "רשימה עודכנה"
        ItemType.NOTE -> "פתק חדש"
    }

    fun markAllNotificationsRead() {
        preferences.markNotificationsSeen()
        _notificationsSeenAt.value = preferences.notificationsSeenAt()
    }

    init {
        scope.launch {
            val cached = database.readItems().filter { it.status != ItemStatus.CANCELLED }
            if (cached.isEmpty() && !api.configured) {
                val seed = SampleData.items()
                database.upsertAll(seed)
                _items.value = seed
            } else {
                _items.value = cached
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
    private fun pendingIds(): Set<String> =
        database.getMetadata("pending_push")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    private fun markPending(id: String) = database.setMetadata("pending_push", (pendingIds() + id).joinToString(","))
    private fun clearPending(id: String) = database.setMetadata("pending_push", (pendingIds() - id).joinToString(","))

    /** Best-effort push of every locally-changed item to the backend; clears the pending flag on success. */
    suspend fun pushPending() {
        if (!api.configured || !sessionStore.hasSession()) return
        val byId = database.readItems().associateBy { it.id }
        for (id in pendingIds()) {
            val local = byId[id] ?: run { clearPending(id); continue }
            runCatching { api.upsertItem(local, validSession()) }
                .onSuccess { clearPending(id); _lastSyncError.value = null }
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
                    val remoteUsers = api.fetchProfiles(session)
                    val remoteItems = api.fetchItems(session)
                    // Merge: keep local (unsynced) versions of pending items so a background push can
                    // still deliver them; everything else takes the remote copy. This stops sync from
                    // resurrecting a locally-deleted item or clobbering a local edit.
                    val pending = pendingIds()
                    val localById = database.readItems().associateBy { it.id }
                    val merged = remoteItems.filter { it.id !in pending } + pending.mapNotNull { localById[it] }
                    database.replaceAll(merged)
                    _items.value = merged.filter { it.status != ItemStatus.CANCELLED }.sortedBy { it.updatedAt }
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

    suspend fun save(item: HomeItem): Result<HomeItem> = withContext(Dispatchers.IO) {
        // Local-first: persist the change to the device immediately (so it survives a restart and
        // shows instantly), then push to the backend in the background. A failed push keeps the item
        // flagged pending and is retried on the next sync — the change is never silently lost.
        runCatching {
            val now = Instant.now()
            val session = sessionStore.load()
            val normalized = item.copy(
                householdId = session?.householdId?.takeIf { it.isNotBlank() } ?: item.householdId,
                creatorId = item.creatorId.takeIf { it.isNotBlank() && it != "u1" && it != "u2" }
                    ?: session?.userId ?: item.creatorId,
                revision = item.revision + 1,
                updatedAt = now,
            )
            database.upsert(normalized)
            markPending(normalized.id)
            _items.value = (_items.value.filterNot { it.id == normalized.id } + normalized).filter { it.status != ItemStatus.CANCELLED }.sortedBy { it.updatedAt }
            if (api.configured && session != null) {
                runCatching { api.upsertItem(normalized, validSession()) }
                    .onSuccess { clearPending(normalized.id); _lastSyncError.value = null }
                    .onFailure { _lastSyncError.value = it.message ?: "Save queued (will retry)" }
            } else clearPending(normalized.id)
            val saved = normalized
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

    suspend fun setReminders(itemId: String, reminders: List<Reminder>): Result<HomeItem> {
        val item = _items.value.firstOrNull { it.id == itemId } ?: return Result.failure(NoSuchElementException(itemId))
        return save(item.copy(reminders = reminders))
    }

    /**
     * Soft-deletes an item. The backend forbids hard DELETE via RLS (homeapp_items_no_delete),
     * so we mark the row CANCELLED through the normal save/upsert path (which the DB does allow)
     * and hide CANCELLED items everywhere. This persists across devices and survives a sync,
     * unlike a hard DELETE which the server silently ignores.
     */
    suspend fun delete(id: String): Result<Unit> {
        val item = _items.value.firstOrNull { it.id == id } ?: return Result.failure(NoSuchElementException(id))
        return save(item.copy(status = ItemStatus.CANCELLED)).map { Unit }
            .also { if (it.isSuccess) _items.value = _items.value.filterNot { i -> i.id == id } }
    }

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

    /** Updates the current user's display name, avatar glyph and accent colour, persisting to the local session. */
    fun setProfile(displayName: String, avatar: String, accentArgb: Long) {
        val name = displayName.trim().ifBlank { return }
        val glyph = avatar.trim().take(2).ifBlank { _currentUser.value.avatar }
        val updated = _currentUser.value.copy(displayName = name, avatar = glyph, accentArgb = accentArgb)
        setCurrentUser(updated)
        sessionStore.load()?.let { sessionStore.save(it.copy(displayName = name, avatar = glyph, accentArgb = accentArgb)) }
    }

    fun logout() {
        realtime?.cancel()
        realtime = null
        realtimeToken = null
        sessionStore.clear()
        database.clearAll()
        _items.value = emptyList()
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

    private suspend fun mutate(id: String, block: (HomeItem) -> HomeItem): Result<Unit> {
        val item = _items.value.firstOrNull { it.id == id } ?: return Result.failure(NoSuchElementException(id))
        return save(block(item)).map { Unit }
    }
}
