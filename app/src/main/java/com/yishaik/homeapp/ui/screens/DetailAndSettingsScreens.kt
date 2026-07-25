package com.yishaik.homeapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yishaik.homeapp.domain.*
import com.yishaik.homeapp.ui.components.UserAvatar
import com.yishaik.homeapp.ui.components.itemColor
import kotlinx.coroutines.delay
import java.time.Instant
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
) {
    var editing by remember { mutableStateOf(false) }
    var title by remember(item.id) { mutableStateOf(item.title) }
    var body by remember(item.id) { mutableStateOf(item.body) }
    var comment by remember { mutableStateOf("") }
    val editable = item.editPolicy == EditPolicy.SHARED_EDIT || item.creatorId == currentUser.id

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
                if (editable && online) IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Close else Icons.Default.Edit, null) }
                IconButton(onClick = onArchive, enabled = online) { Icon(Icons.Default.Archive, null) }
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
                            Spacer(Modifier.height(10.dp)); Button(onClick = { onSave(item.copy(title = title, body = body)); editing = false }, modifier = Modifier.fillMaxWidth()) { Text("שמירה") }
                        } else {
                            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            if (item.body.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(item.body) }
                            item.link?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                        }
                    }
                }
            }
            item { Metadata(item, users) }
            if (item.type == ItemType.NOTE) item { ReadStatus(item, users) }
            if (item.checklist.isNotEmpty()) {
                item { Text("פריטים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                lazyItems(item.checklist) { entry -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(entry.completed, null); Text(entry.title) } }
            }
            if (item.linkedItems.isNotEmpty()) {
                item { Text("פריטים מקושרים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                lazyItems(item.linkedItems) { linked -> ListItem(headlineContent = { Text(linked.title) }, leadingContent = { Icon(Icons.Default.Link, null) }) }
            }
            item { Text("תגובות", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            lazyItems(item.comments) { c ->
                val author = users[c.authorId]
                Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    if (author != null) UserAvatar(author)
                    Spacer(Modifier.width(10.dp)); Column { Text(author?.displayName ?: c.authorId, fontWeight = FontWeight.Bold); Text(c.text); c.link?.let { Text(it, color = MaterialTheme.colorScheme.primary) } }
                }}
            }
            item {
                OutlinedTextField(comment, { comment = it }, modifier = Modifier.fillMaxWidth(), label = { Text("תגובה חדשה") }, trailingIcon = {
                    IconButton(enabled = online && comment.isNotBlank(), onClick = { onSave(item.copy(comments = item.comments + Comment(authorId = currentUser.id, text = comment))); comment = "" }) { Icon(Icons.Default.Send, null) }
                })
            }
            if (item.type == ItemType.TASK || item.type == ItemType.LIST) item { Button(onClick = onComplete, enabled = online && item.status == ItemStatus.ACTIVE, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Done, null); Spacer(Modifier.width(8.dp)); Text("סימון כהושלם") } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun Metadata(item: HomeItem, users: Map<String, AppUser>) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val owner = users[item.creatorId]
        Row(verticalAlignment = Alignment.CenterVertically) { if (owner != null) UserAvatar(owner); Spacer(Modifier.width(8.dp)); Text("נוצר על ידי ${owner?.displayName ?: item.creatorId}") }
        val time = item.startAt ?: item.dueAt
        time?.let { Text(it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))) }
        if (item.tags.isNotEmpty()) Text("תגיות: ${item.tags.joinToString()}")
        if (item.locationLabel != null) Text("מיקום: ${item.locationLabel}")
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
fun NotificationsScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val notifications = remember { listOf(
        Triple("שיעור הפסנתר עודכן", "השעה שונתה מ־14:30 ל־14:00 · לפני 2 דקות", "event"),
        Triple("נוספה תגובה לפתק", "אני אשים אותו בתיק בערב · לפני 8 דקות", "note"),
        Triple("המשימה הושלמה", "מעיין השלימה: לסדר את החדר", "task"),
    ) }
    Scaffold(topBar = { TopAppBar(title = { Text("התראות") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }, actions = { TextButton(onClick = {}) { Text("סמן הכול") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(notifications.size) { index -> val n = notifications[index]; Card(Modifier.fillMaxWidth().clickable { onOpenItem(n.third) }) { ListItem(headlineContent = { Text(n.first, fontWeight = FontWeight.Bold) }, supportingContent = { Text(n.second) }, leadingContent = { Icon(Icons.Default.Notifications, null) }) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("הגדרות") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { ListItem(headlineContent = { Text("פרופיל") }, supportingContent = { Text("שם, תמונה או אווטאר") }, leadingContent = { Icon(Icons.Default.AccountCircle, null) }) }
            item { HorizontalDivider() }
            val rows = listOf(
                "מסך פתיחה" to "היום", "נעילה ב־PIN" to "לאחר 5 דקות", "שפה" to "לפי המכשיר",
                "ערכת צבע אישית" to "סגול", "סדר אזורים במסך היום" to "מותאם אישית",
                "תזכורות ברירת מחדל" to "אירוע: 30 דקות לפני", "תוכן במסך הנעילה" to "מוסתר",
                "שכבות לוח שנה" to "חגים, עברי וחופשות", "גיבוי" to "יומי · 30 ימים",
                "ייצוא" to "JSON, CSV, ICS ו־PDF"
            )
            items(rows.size) { i -> val row = rows[i]; ListItem(headlineContent = { Text(row.first) }, supportingContent = { Text(row.second) }, trailingContent = { Icon(Icons.Default.ChevronLeft, null) }); HorizontalDivider() }
        }
    }
}
