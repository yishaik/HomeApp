package com.yishaik.homeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yishaik.homeapp.HomeApplication
import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.security.ActivationOutcome
import com.yishaik.homeapp.ui.components.OfflineBanner
import com.yishaik.homeapp.ui.screens.ActivationScreen
import com.yishaik.homeapp.ui.screens.CalendarScreen
import com.yishaik.homeapp.ui.screens.ItemDetailScreen
import com.yishaik.homeapp.ui.screens.ListsScreen
import com.yishaik.homeapp.ui.screens.NotesScreen
import com.yishaik.homeapp.ui.screens.NotificationsScreen
import com.yishaik.homeapp.ui.screens.PinSetupScreen
import com.yishaik.homeapp.ui.screens.PinUnlockScreen
import com.yishaik.homeapp.ui.screens.QuickAddSheet
import com.yishaik.homeapp.ui.screens.SearchScreen
import com.yishaik.homeapp.ui.screens.SettingsScreen
import com.yishaik.homeapp.ui.screens.TasksScreen
import com.yishaik.homeapp.ui.screens.TodayScreen
import kotlinx.coroutines.launch

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("today", "היום", Icons.Default.Today),
    Destination("calendar", "לוח שנה", Icons.Default.CalendarMonth),
    Destination("tasks", "משימות", Icons.Default.TaskAlt),
    Destination("lists", "רשימות", Icons.Default.FormatListBulleted),
    Destination("notes", "פתקים", Icons.Default.StickyNote2),
    Destination("search", "חיפוש", Icons.Default.Search),
)

@Composable
fun HomeAppRoot(app: HomeApplication) {
    var activated by remember { mutableStateOf(app.activationManager.activated) }
    val pinTimeout = remember { app.preferencesStore.read().pinTimeoutMinutes }
    var unlocked by remember { mutableStateOf(!app.pinVault.shouldLock(pinTimeout)) }
    var needsPinSetup by remember { mutableStateOf(activated && !app.pinVault.hasPin()) }
    var activationLoading by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }
    var approvalRequestId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activated) {
        if (activated) app.repository.syncNow()
    }

    when {
        !activated -> ActivationScreen(
            loading = activationLoading,
            error = activationError,
            approvalRequestId = approvalRequestId,
            onActivate = { code, recoveryCode ->
                scope.launch {
                    activationLoading = true
                    activationError = null
                    app.activationManager.activate(code, recoveryCode).fold(
                        onSuccess = { outcome ->
                            when (outcome) {
                                is ActivationOutcome.Success -> {
                                    app.repository.activateSession(outcome.session)
                                    activated = true
                                    needsPinSetup = !app.pinVault.hasPin()
                                    unlocked = !needsPinSetup
                                    approvalRequestId = null
                                }
                                is ActivationOutcome.ApprovalRequired -> approvalRequestId = outcome.requestId
                            }
                        },
                        onFailure = { activationError = it.message ?: "ההפעלה נכשלה" },
                    )
                    activationLoading = false
                }
            },
            onCheckApproval = { code, requestId ->
                scope.launch {
                    activationLoading = true
                    activationError = null
                    app.activationManager.checkApproval(code, requestId).fold(
                        onSuccess = { outcome ->
                            when (outcome) {
                                is ActivationOutcome.Success -> {
                                    app.repository.activateSession(outcome.session)
                                    activated = true
                                    needsPinSetup = !app.pinVault.hasPin()
                                    unlocked = !needsPinSetup
                                    approvalRequestId = null
                                }
                                is ActivationOutcome.ApprovalRequired -> approvalRequestId = outcome.requestId
                            }
                        },
                        onFailure = { activationError = it.message ?: "הבקשה עדיין לא אושרה" },
                    )
                    activationLoading = false
                }
            },
        )
        needsPinSetup -> PinSetupScreen { pin ->
            app.pinVault.setPin(pin)
            needsPinSetup = false
            unlocked = true
        }
        !unlocked -> PinUnlockScreen { pin -> if (app.pinVault.verify(pin)) unlocked = true }
        else -> MainApp(app, onLogout = {
            app.repository.logout()
            activated = false
            unlocked = false
            needsPinSetup = false
            approvalRequestId = null
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(app: HomeApplication, onLogout: () -> Unit) {
    val items by app.repository.items.collectAsStateWithLifecycle()
    val online by app.repository.online.collectAsStateWithLifecycle()
    val user by app.repository.currentUser.collectAsStateWithLifecycle()
    val users by app.repository.users.collectAsStateWithLifecycle()
    val notifications by app.repository.notifications.collectAsStateWithLifecycle()
    val unreadCount by app.repository.unreadCount.collectAsStateWithLifecycle()
    val preferences by app.preferencesStore.prefs.collectAsStateWithLifecycle()
    var hasPin by remember { mutableStateOf(app.pinVault.hasPin()) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route ?: preferences.startDestination
    var showAdd by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<HomeItem?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (selectedItem != null) {
        BackHandler { selectedItem = null }
        ItemDetailScreen(
            item = selectedItem!!,
            users = users,
            currentUser = user,
            online = online,
            onBack = { selectedItem = null },
            onSave = { changed -> scope.launch { app.repository.save(changed).onSuccess { app.reminderScheduler.schedule(it); selectedItem = it } } },
            onComplete = { scope.launch { app.repository.complete(selectedItem!!.id); selectedItem = null } },
            onArchive = { scope.launch { app.repository.archive(selectedItem!!.id); selectedItem = null } },
            onDelete = { scope.launch { app.repository.delete(selectedItem!!.id); selectedItem = null } },
            onOpenItem = { id -> selectedItem = items.firstOrNull { it.id == id } },
            onToggleChecklist = { entryId -> scope.launch { val id = selectedItem!!.id; app.repository.toggleChecklist(id, entryId); selectedItem = app.repository.items.value.firstOrNull { it.id == id } } },
            onAddChecklistEntry = { title -> scope.launch { val id = selectedItem!!.id; app.repository.addChecklistEntry(id, title); selectedItem = app.repository.items.value.firstOrNull { it.id == id } } },
            onRemoveChecklistEntry = { entryId -> scope.launch { val id = selectedItem!!.id; app.repository.removeChecklistEntry(id, entryId); selectedItem = app.repository.items.value.firstOrNull { it.id == id } } },
            onSetReminder = { minutes ->
                scope.launch {
                    val id = selectedItem!!.id
                    val reminders = if (minutes != null) listOf(com.yishaik.homeapp.domain.Reminder(minutesBefore = minutes, userId = user.id)) else emptyList()
                    app.repository.setReminders(id, reminders).onSuccess { saved ->
                        app.reminderScheduler.schedule(saved)
                        selectedItem = saved
                    }
                }
            },
        )
        return
    }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            currentUser = user,
            preferences = preferences,
            hasPin = hasPin,
            onBack = { showSettings = false },
            onRenameProfile = { name -> app.repository.renameCurrentUser(name) },
            onUpdatePreferences = { transform -> app.preferencesStore.update(transform) },
            onSetPin = { pin -> app.pinVault.setPin(pin); hasPin = true },
            onClearPin = { app.pinVault.clearPin(); hasPin = false },
            onLogout = { showSettings = false; onLogout() },
        )
        return
    }
    if (showNotifications) {
        BackHandler { showNotifications = false }
        NotificationsScreen(
            notifications = notifications,
            onBack = { showNotifications = false },
            onMarkAll = { app.repository.markAllNotificationsRead() },
            onOpenItem = { id -> selectedItem = items.firstOrNull { it.id == id }; showNotifications = false },
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                if (!online) OfflineBanner()
                CenterAlignedTopAppBar(
                    title = { Text(destinations.firstOrNull { it.route == current }?.label ?: "HomeApp") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.AccountCircle, null) }
                        IconButton(onClick = { showNotifications = true }) {
                            BadgedBox(badge = { if (unreadCount > 0) Badge { Text(unreadCount.toString()) } }) { Icon(Icons.Default.Notifications, null) }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination.route,
                        onClick = {
                            nav.navigate(destination.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, null) },
                        label = { Text(destination.label, maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add") }
        },
    ) { padding ->
        NavHost(nav, startDestination = preferences.startDestination.takeIf { d -> destinations.any { it.route == d } } ?: "today", modifier = Modifier.padding(padding)) {
            composable("today") { TodayScreen(items, users, user, onOpenItem = { selectedItem = it }) }
            composable("calendar") { CalendarScreen(items, users, onOpenItem = { selectedItem = it }) }
            composable("tasks") {
                TasksScreen(
                    items,
                    users,
                    user,
                    onOpenItem = { selectedItem = it },
                    onComplete = { scope.launch { app.repository.complete(it.id) } },
                )
            }
            composable("lists") {
                ListsScreen(
                    items,
                    users,
                    onOpenItem = { selectedItem = it },
                    onToggle = { item, entryId -> scope.launch { app.repository.toggleChecklist(item.id, entryId) } },
                )
            }
            composable("notes") {
                NotesScreen(
                    items,
                    users,
                    user,
                    onOpenItem = { selectedItem = it },
                    onRead = { scope.launch { app.repository.markNoteRead(it.id, user.id) } },
                )
            }
            composable("search") { SearchScreen(items, users, onOpenItem = { selectedItem = it }) }
        }
    }

    if (showAdd) {
        QuickAddSheet(
            currentUser = user,
            online = online,
            defaultEventReminderMinutes = preferences.defaultEventReminderMinutes.firstOrNull() ?: 30,
            defaultTaskReminderMinutes = preferences.defaultTaskReminderMinutes.firstOrNull() ?: 0,
            onDismiss = { showAdd = false },
            onSave = { item ->
                scope.launch {
                    app.repository.save(item).onSuccess { saved ->
                        app.reminderScheduler.schedule(saved)
                        showAdd = false
                    }
                }
            },
        )
    }
}
