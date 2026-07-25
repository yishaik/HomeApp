package com.yishaik.homeapp.data

import com.yishaik.homeapp.domain.AppUser
import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.domain.ItemStatus
import com.yishaik.homeapp.domain.ItemType
import com.yishaik.homeapp.domain.ReadReceipt
import com.yishaik.homeapp.domain.SampleData
import com.yishaik.homeapp.security.AuthSession
import com.yishaik.homeapp.security.SessionStore
import com.yishaik.homeapp.sync.SupabaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
