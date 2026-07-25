package com.yishaik.homeapp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yishaik.homeapp.domain.*
import com.yishaik.homeapp.ui.components.UserAvatar
import com.yishaik.homeapp.ui.components.itemColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    item: HomeItem,
    users: Map<String, AppUser>,
    currentUser: AppUser,
    online: Boolean,
    onBack: () -> Unit,
    onSave: (HomeItem) -> Unit,
    onComplete: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onOpenItem: (String) -> Unit,
    onToggleChecklist: (String) -> Unit,
    onAddChecklistEntry: (String) -> Unit,
    onRemoveChecklistEntry: (String) -> Unit,
    onSetReminder: (Int?) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var title by remember(item.id) { mutableStateOf(item.title) }
    var body by remember(item.id) { mutableStateOf(item.body) }
    var startAt by remember(item.id) { mutableStateOf(item.startAt ?: item.dueAt) }
    var assignee by remember(item.id) { mutableStateOf(item.assignee) }
    var pinned by remember(item.id) { mutableStateOf(item.pinned) }
    var comment by remember { mutableStateOf("") }
    var newEntry by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val editable = item.editPolicy == EditPolicy.SHARED_EDIT || item.creatorId == currentUser.id
    val hasDateField = item.type == ItemType.EVENT || item.type == ItemType.TASK

    if (item.type == ItemType.NOTE) LaunchedEffect(item.id) {
        delay(5_000)
        if (item.readReceipts.firstOrNull { it.userId == currentUser.id }?.readAt == null) {
            onSave(item.copy(readReceipts = item.readReceipts.filterNot { it.userId == currentUser.id } + ReadReceipt(currentUser.id, Instant.now())))
        }
    }

    Scaffold(
        topBar = { TopAppBar(
            title = { Text(when(item.type) { ItemType.EVENT -> "פרטי אירוע"; ItemType.TASK -> "פרטי משימה"; ItemType.LIST -> "פרטי רשימה"; ItemType.NOTE -> "פרטי פתק" }) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                if (editable) IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Close else Icons.Default.Edit, null) }
                IconButton(onClick = { onSave(item.copy(pinned = !item.pinned)) }, enabled = online) { Icon(Icons.Default.PushPin, null, tint = if (item.pinned) itemColor(item.type) else LocalContentColor.current) }
                IconButton(onClick = { showMenu = true }, enabled = online) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("העברה לארכיון") }, onClick = { showMenu = false; onArchive() }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                    DropdownMenuItem(text = { Text("מחיקה") }, onClick = { showMenu = false; showDelete = true }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
        ) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = itemColor(item.type).copy(alpha = .12f))) {
                    Column(Modifier.padding(18.dp)) {
                        if (editing) {
                            OutlinedTextField(title, { title = it }, label = { Text("כותרת") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp)); OutlinedTextField(body, { body = it }, label = { Text("תוכן") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                            if (hasDateField) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp))
                                    Text(startAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) ?: "בחירת תאריך ושעה")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("שיוך", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(assignee == Assignee.NONE, { assignee = Assignee.NONE }, label = { Text("ללא") })
                                FilterChip(assignee == Assignee.USER_ONE, { assignee = Assignee.USER_ONE }, label = { Text(users["u1"]?.displayName ?: "ישי") })
                                FilterChip(assignee == Assignee.USER_TWO, { assignee = Assignee.USER_TWO }, label = { Text(users["u2"]?.displayName ?: "מעיין") })
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(pinned, { pinned = it }); Spacer(Modifier.width(8.dp)); Text("הצמדה") }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                onSave(item.copy(
                                    title = title, body = body, assignee = assignee, pinned = pinned,
                                    startAt = if (item.type == ItemType.EVENT) startAt else item.startAt,
                                    dueAt = if (item.type == ItemType.TASK) startAt else item.dueAt,
                                ))
                                editing = false
                            }, modifier = Modifier.fillMaxWidth()) { Text("שמירה") }
                        } else {
                            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            if (item.body.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(item.body) }
                            item.link?.let { LinkText(it, context) }
                        }
                    }
                }
            }
            item { Metadata(item, users, context) }
            if ((item.type == ItemType.EVENT || item.type == ItemType.TASK) && online) item {
                val current = item.reminders.firstOrNull { it.userId == currentUser.id }?.minutesBefore
                Card { Column(Modifier.padding(16.dp)) {
                    Text("תזכורת", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        detailReminderChoices.forEach { (label, minutes) ->
                            FilterChip((current ?: -1) == minutes, { onSetReminder(minutes.takeIf { it >= 0 }) }, label = { Text(label) })
                        }
                    }
                }}
            }
            if (item.type == ItemType.NOTE) item { ReadStatus(item, users) }
            if (item.type == ItemType.LIST || item.checklist.isNotEmpty()) {
                item { Text("פריטים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                lazyItems(item.checklist, key = { it.id }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(entry.completed, { onToggleChecklist(entry.id) })
                        Text(entry.title, Modifier.weight(1f), textDecoration = if (entry.completed) TextDecoration.LineThrough else null)
                        IconButton(onClick = { onRemoveChecklistEntry(entry.id) }) { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) }
                    }
                }
                if (item.type == ItemType.LIST) item {
                    OutlinedTextField(newEntry, { newEntry = it }, modifier = Modifier.fillMaxWidth(), label = { Text("פריט חדש") }, singleLine = true, trailingIcon = {
                        IconButton(enabled = newEntry.isNotBlank(), onClick = { onAddChecklistEntry(newEntry.trim()); newEntry = "" }) { Icon(Icons.Default.Add, null) }
                    })
                }
            }
            if (item.linkedItems.isNotEmpty()) {
                item { Text("פריטים מקושרים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                lazyItems(item.linkedItems) { linked -> ListItem(modifier = Modifier.clickable { onOpenItem(linked.itemId) }, headlineContent = { Text(linked.title) }, leadingContent = { Icon(Icons.Default.Link, null) }) }
            }
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("דיון", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (item.comments.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Badge { Text(item.comments.size.toString()) } }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (item.comments.isEmpty()) item { Text("אין תגובות עדיין", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            lazyItems(item.comments) { c ->
                val author = users[c.authorId]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    if (author != null) UserAvatar(author)
                    Spacer(Modifier.width(10.dp)); Column { Text(author?.displayName ?: c.authorId, fontWeight = FontWeight.Bold); Text(c.text); c.link?.let { LinkText(it, context) } }
                }}
            }
            item {
                OutlinedTextField(comment, { comment = it }, modifier = Modifier.fillMaxWidth(), label = { Text("כתיבת תגובה…") }, trailingIcon = {
                    IconButton(enabled = comment.isNotBlank(), onClick = { onSave(item.copy(comments = item.comments + Comment(authorId = currentUser.id, text = comment))); comment = "" }) { Icon(Icons.Default.Send, null) }
                })
            }
            if (item.type == ItemType.TASK || item.type == ItemType.LIST) item { Button(onClick = onComplete, enabled = online && item.status == ItemStatus.ACTIVE, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Done, null); Spacer(Modifier.width(8.dp)); Text("סימון כהושלם") } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("מחיקת פריט") },
            text = { Text("הפעולה תסיר את הפריט לצמיתות. להמשיך?") },
            confirmButton = { TextButton(onClick = { showDelete = false; onDelete() }) { Text("מחיקה", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("ביטול") } },
        )
    }
    if (showDatePicker) {
        DateTimePickerDialog(
            initial = startAt,
            onDismiss = { showDatePicker = false },
            onConfirm = { startAt = it; showDatePicker = false },
        )
    }
}

@Composable
private fun LinkText(url: String, context: android.content.Context) {
    Text(
        url,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.padding(top = 12.dp).clickable { openUrl(context, url) },
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    val uri = if (url.startsWith("http")) url else "https://$url"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimePickerDialog(initial: Instant?, onDismiss: () -> Unit, onConfirm: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val base = initial?.atZone(zone) ?: Instant.now().atZone(zone)
    val dateState = rememberDatePickerState(initialSelectedDateMillis = base.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli())
    var step by remember { mutableStateOf(0) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val timeState = rememberTimePickerState(initialHour = base.hour, initialMinute = base.minute, is24Hour = true)

    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = {
                dateState.selectedDateMillis?.let { pickedDate = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }
                step = 1
            }) { Text("הבא") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        ) { DatePicker(state = dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = {
                val date = pickedDate ?: base.toLocalDate()
                onConfirm(date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
            }) { Text("אישור") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
            text = { Column { TimePicker(state = timeState) } },
        )
    }
}

@Composable
private fun Metadata(item: HomeItem, users: Map<String, AppUser>, context: android.content.Context) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val owner = users[item.creatorId]
        Row(verticalAlignment = Alignment.CenterVertically) { if (owner != null) UserAvatar(owner); Spacer(Modifier.width(8.dp)); Text("נוצר על ידי ${owner?.displayName ?: item.creatorId}") }
        val time = item.startAt ?: item.dueAt
        time?.let { Text(it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))) }
        if (item.tags.isNotEmpty()) Text("תגיות: ${item.tags.joinToString()}")
        if (item.locationLabel != null) {
            val maps = item.mapsUrl ?: "geo:0,0?q=${Uri.encode(item.locationLabel)}"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { openUrl(context, maps) }) {
                Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp)); Text("מיקום: ${item.locationLabel}", color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
            }
        }
        Text(if (item.editPolicy == EditPolicy.SHARED_EDIT) "שניכם יכולים לערוך" else "רק היוצר יכול לערוך")
        Text("גרסה ${item.revision}", style = MaterialTheme.typography.bodySmall)
    }}
}

@Composable
private fun ReadStatus(item: HomeItem, users: Map<String, AppUser>) {
    Card { Column(Modifier.padding(16.dp)) {
        Text("סטטוס קריאה", fontWeight = FontWeight.Bold)
        item.readReceipts.forEach { receipt ->
            val user = users[receipt.userId]
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (user != null) UserAvatar(user)
                Spacer(Modifier.width(8.dp)); Text(user?.displayName ?: receipt.userId, modifier = Modifier.weight(1f))
                Text(receipt.readAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "טרם נקרא", style = MaterialTheme.typography.bodySmall)
            }
        }
    }}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    notifications: List<AppNotification>,
    onBack: () -> Unit,
    onMarkAll: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("התראות") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }, actions = { TextButton(onClick = onMarkAll, enabled = notifications.any { !it.read }) { Text("סמן הכול") } }) }) { padding ->
        if (notifications.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("אין התראות חדשות", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            lazyItems(notifications, key = { it.id }) { n ->
                val container = if (n.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer
                Card(Modifier.fillMaxWidth().clickable { n.itemId?.let(onOpenItem) }, colors = CardDefaults.cardColors(containerColor = container)) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = container),
                        headlineContent = { Text(n.title, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(n.detail) },
                        leadingContent = { Icon(if (n.read) Icons.Default.NotificationsNone else Icons.Default.Notifications, null) },
                        trailingContent = { Text(n.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm")), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentUser: AppUser,
    preferences: UserPreferences,
    hasPin: Boolean,
    onBack: () -> Unit,
    onSaveProfile: (String, String, Long) -> Unit,
    onUpdatePreferences: ((UserPreferences) -> UserPreferences) -> Unit,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
    onLogout: () -> Unit,
) {
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var showLogout by remember { mutableStateOf(false) }

    fun accentLabel(argb: Long) = accentOptions.firstOrNull { it.second == argb }?.first ?: "מותאם"

    Scaffold(topBar = { TopAppBar(title = { Text("הגדרות") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { SettingRow(Icons.Default.AccountCircle, "פרופיל", currentUser.displayName) { dialog = SettingsDialog.PROFILE } }
            item { HorizontalDivider() }
            item { SettingRow(Icons.Default.Home, "מסך פתיחה", landingLabel(preferences.startDestination)) { dialog = SettingsDialog.LANDING } }
            item { SettingRow(Icons.Default.Palette, "ערכת צבע אישית", accentLabel(preferences.accentArgb)) { dialog = SettingsDialog.ACCENT } }
            item { SettingRow(Icons.Default.Notifications, "תזכורות ברירת מחדל", "אירוע: ${preferences.defaultEventReminderMinutes.firstOrNull() ?: 0} דק' לפני") { dialog = SettingsDialog.REMINDER } }
            item { SettingRow(Icons.Default.Language, "שפה", if (preferences.localeTag == "he") "עברית" else "לפי המכשיר") { dialog = SettingsDialog.LANGUAGE } }
            item { HorizontalDivider() }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.VisibilityOff, null) },
                    headlineContent = { Text("תוכן במסך הנעילה") },
                    supportingContent = { Text(if (preferences.lockScreenContentVisible) "גלוי" else "מוסתר") },
                    trailingContent = { Switch(preferences.lockScreenContentVisible, { v -> onUpdatePreferences { it.copy(lockScreenContentVisible = v) } }) },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.CalendarMonth, null) },
                    headlineContent = { Text("שכבות לוח שנה") },
                    supportingContent = { Text("חגים, מועדים וחופשות") },
                    trailingContent = { Switch(preferences.calendarLayers.isNotEmpty(), { v -> onUpdatePreferences { it.copy(calendarLayers = if (v) setOf("israeli_holidays", "national_days", "hebrew_dates", "school_breaks") else emptySet()) } }) },
                )
                HorizontalDivider()
            }
            item { SettingRow(Icons.Default.Lock, "נעילה ב־PIN", if (hasPin) "פעיל" else "כבוי") { dialog = SettingsDialog.PIN } }
            item { HorizontalDivider() }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    headlineContent = { Text("גרסה ${com.yishaik.homeapp.BuildConfig.VERSION_NAME} (${com.yishaik.homeapp.BuildConfig.VERSION_CODE})") },
                    supportingContent = { Text("מחובר כ־${currentUser.displayName} · ${if (com.yishaik.homeapp.BuildConfig.SUPABASE_URL.isNotBlank()) "ענן" else "מקומי"}") },
                )
                HorizontalDivider(); Spacer(Modifier.height(12.dp))
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showLogout = true },
                    leadingContent = { Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("התנתקות", color = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }

    when (dialog) {
        SettingsDialog.PROFILE -> ProfileDialog(currentUser, onDismiss = { dialog = null }) { name, avatar, accent -> onSaveProfile(name, avatar, accent); dialog = null }
        SettingsDialog.LANDING -> ChoiceDialog("מסך פתיחה", landingOptions.map { it.first }, landingLabel(preferences.startDestination), onDismiss = { dialog = null }) { label ->
            landingOptions.firstOrNull { it.first == label }?.let { opt -> onUpdatePreferences { it.copy(startDestination = opt.second) } }; dialog = null
        }
        SettingsDialog.ACCENT -> ChoiceDialog("ערכת צבע", accentOptions.map { it.first }, accentLabel(preferences.accentArgb), onDismiss = { dialog = null }) { label ->
            accentOptions.firstOrNull { it.first == label }?.let { opt -> onUpdatePreferences { it.copy(accentArgb = opt.second) } }; dialog = null
        }
        SettingsDialog.REMINDER -> ChoiceDialog("תזכורת ברירת מחדל לאירוע", reminderOptions.map { it.first }, reminderOptions.firstOrNull { it.second == (preferences.defaultEventReminderMinutes.firstOrNull() ?: 30) }?.first ?: "30 דק' לפני", onDismiss = { dialog = null }) { label ->
            reminderOptions.firstOrNull { it.first == label }?.let { opt -> onUpdatePreferences { it.copy(defaultEventReminderMinutes = listOf(opt.second)) } }; dialog = null
        }
        SettingsDialog.LANGUAGE -> ChoiceDialog("שפה", listOf("לפי המכשיר", "עברית"), if (preferences.localeTag == "he") "עברית" else "לפי המכשיר", onDismiss = { dialog = null }) { label ->
            onUpdatePreferences { it.copy(localeTag = if (label == "עברית") "he" else null) }; dialog = null
        }
        SettingsDialog.PIN -> PinDialog(hasPin, onDismiss = { dialog = null }, onSet = { onSetPin(it); dialog = null }, onClear = { onClearPin(); dialog = null })
        null -> Unit
    }

    if (showLogout) AlertDialog(
        onDismissRequest = { showLogout = false },
        title = { Text("התנתקות") },
        text = { Text("החשבון ינותק מהמכשיר והנתונים המקומיים יימחקו. להמשיך?") },
        confirmButton = { TextButton(onClick = { showLogout = false; onLogout() }) { Text("התנתקות", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showLogout = false }) { Text("ביטול") } },
    )
}

private enum class SettingsDialog { PROFILE, LANDING, ACCENT, REMINDER, LANGUAGE, PIN }

private val landingOptions = listOf("היום" to "today", "לוח שנה" to "calendar", "משימות" to "tasks", "רשימות" to "lists", "פתקים" to "notes")
private fun landingLabel(dest: String) = landingOptions.firstOrNull { it.second == dest }?.first ?: "היום"
private val accentOptions = listOf("סגול" to 0xFF5A5BD7L, "כחול" to 0xFF3C6EEDL, "ורוד" to 0xFFE06A9BL, "ירוק" to 0xFF299764L, "כתום" to 0xFFE78A1AL)
private val reminderOptions = listOf("בזמן" to 0, "10 דק' לפני" to 10, "30 דק' לפני" to 30, "שעה לפני" to 60, "יום לפני" to 1440)
private val detailReminderChoices = listOf("ללא" to -1, "בזמן" to 0, "30 דק'" to 30, "שעה" to 60, "יום" to 1440)

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = { Icon(Icons.Default.ChevronLeft, null) },
    )
}

@Composable
private fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("שמירה") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun ProfileDialog(user: AppUser, onDismiss: () -> Unit, onConfirm: (String, String, Long) -> Unit) {
    var name by remember { mutableStateOf(user.displayName) }
    var avatar by remember { mutableStateOf(user.avatar) }
    var accent by remember { mutableStateOf(user.accentArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("פרופיל") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UserAvatar(user.copy(avatar = avatar.ifBlank { "?" }, accentArgb = accent), size = 48)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("שם תצוגה") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(avatar, { if (it.length <= 2) avatar = it }, singleLine = true, label = { Text("אות או אמוג׳י") }, modifier = Modifier.fillMaxWidth())
                Text("צבע", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    accentOptions.forEach { (_, argb) ->
                        val selected = argb == accent
                        Box(
                            Modifier.size(if (selected) 40.dp else 34.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color(argb))
                                .clickable { accent = argb },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Default.Check, null, tint = androidx.compose.ui.graphics.Color.White) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), avatar.trim().ifBlank { user.avatar }, accent) }, enabled = name.isNotBlank()) { Text("שמירה") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == option, { onSelect(option) }); Spacer(Modifier.width(8.dp)); Text(option)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגירה") } },
    )
}

@Composable
private fun PinDialog(hasPin: Boolean, onDismiss: () -> Unit, onSet: (String) -> Unit, onClear: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPin) "שינוי או כיבוי PIN" else "הגדרת PIN") },
        text = { Column {
            Text("PIN בן 4–8 ספרות", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onSet(pin) }, enabled = pin.length in 4..8) { Text("שמירה") } },
        dismissButton = { if (hasPin) TextButton(onClick = onClear) { Text("כיבוי PIN", color = MaterialTheme.colorScheme.error) } else TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
