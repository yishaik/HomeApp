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
            val cached = database.readItems()
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

    suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!api.configured || !_online.value || !sessionStore.hasSession()) return@withContext Result.success(Unit)
        runCatching {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val session = validSession()
                    val remoteUsers = api.fetchProfiles(session)
                    val remoteItems = api.fetchItems(session)
                    database.replaceAll(remoteItems)
                    _items.value = remoteItems.sortedBy { it.updatedAt }
                    _users.value = remoteUsers.associateBy { it.id }.ifEmpty { mapOf(session.userId to session.user()) }
                    _currentUser.value = _users.value[session.userId] ?: session.user()
                    _lastSyncError.value = null
                    ensureRealtime(session)
                } finally {
                    _syncing.value = false
                }
            }
        }.onFailure { _lastSyncError.value = it.message ?: "Sync failed" }
    }

    suspend fun save(item: HomeItem): Result<HomeItem> = withContext(Dispatchers.IO) {
        if (!_online.value && api.configured) return@withContext Result.failure(IllegalStateException("Offline mode is read-only"))
        runCatching {
            val now = Instant.now()
            val session = sessionStore.load()
            val normalized = item.copy(
                householdId = session?.householdId ?: item.householdId,
                creatorId = item.creatorId.takeIf { it.isNotBlank() && it != "u1" && it != "u2" }
                    ?: session?.userId ?: item.creatorId,
                revision = item.revision + 1,
                updatedAt = now,
            )
            val saved = if (api.configured && session != null) api.upsertItem(normalized, validSession()) else normalized
            database.upsert(saved)
            _items.value = (_items.value.filterNot { it.id == saved.id } + saved).sortedBy { it.updatedAt }
            saved
        }
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
     * Deletes an item locally + remotely. Remote delete uses a real DELETE when the API is
     * configured; if that call fails (or the API is not configured), we fall back to a
     * soft-delete (status = CANCELLED) so the item stays consistent across devices. The local
     * row is always removed so it disappears from the UI immediately.
     */
    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val item = _items.value.firstOrNull { it.id == id } ?: return@withContext Result.failure(NoSuchElementException(id))
        if (!_online.value && api.configured) return@withContext Result.failure(IllegalStateException("Offline mode is read-only"))
        runCatching {
            val session = sessionStore.load()
            if (api.configured && session != null) {
                runCatching { api.deleteItem(id, validSession()) }
                    .onFailure { api.upsertItem(item.copy(status = ItemStatus.CANCELLED, updatedAt = Instant.now()), validSession()) }
            }
            database.deleteItem(id)
            _items.value = _items.value.filterNot { it.id == id }
        }
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
    fun renameCurrentUser(displayName: String) {
        val name = displayName.trim().ifBlank { return }
        val updated = _currentUser.value.copy(displayName = name)
        setCurrentUser(updated)
        sessionStore.load()?.let { sessionStore.save(it.copy(displayName = name)) }
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
