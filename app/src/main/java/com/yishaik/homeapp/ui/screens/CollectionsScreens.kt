package com.yishaik.homeapp.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yishaik.homeapp.domain.*
import com.yishaik.homeapp.ui.components.*
import com.yishaik.homeapp.ui.theme.*
import com.yishaik.homeapp.util.NaturalLanguageParser
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

@Composable
fun TasksScreen(items: List<HomeItem>, users: Map<String, AppUser>, currentUser: AppUser, onOpenItem: (HomeItem) -> Unit, onComplete: (HomeItem) -> Unit, onQuickAction: (HomeItem, ItemQuickAction) -> Unit = { _, _ -> }) {
    var filter by remember { mutableStateOf("הכול") }
    val now = Instant.now()
    val otherUser = users.values.firstOrNull { it.id != currentUser.id }
    val otherLabel = otherUser?.displayName ?: "השני"
    val myAssignee = assigneeSlot(currentUser.id, users.keys)
    val otherAssignee = if (myAssignee == Assignee.USER_ONE) Assignee.USER_TWO else Assignee.USER_ONE
    fun mine(t: HomeItem) = t.creatorId == currentUser.id || t.assignee == myAssignee || t.assignee == Assignee.BOTH
    fun theirs(t: HomeItem) = (otherUser != null && t.creatorId == otherUser.id) || t.assignee == otherAssignee || t.assignee == Assignee.BOTH
    val tasks = items
        .filter { it.type == ItemType.TASK && it.status != ItemStatus.ARCHIVED }
        .filter { when (filter) { "שלי" -> mine(it); "באיחור" -> it.isOverdue(now); else -> if (filter == otherLabel) theirs(it) else true } }
        .sortedByDescending { it.pinned }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FilterRow(listOf("הכול", "שלי", otherLabel, "באיחור"), filter) { filter = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionHeader("פתוחות", tasks.count { it.status == ItemStatus.ACTIVE }) }
            lazyItems(tasks.filter { it.status == ItemStatus.ACTIVE }) { task ->
                SwipeToComplete(task, users, onOpenItem, onComplete, onQuickAction)
            }
            item { SectionHeader("הושלמו", tasks.count { it.status == ItemStatus.COMPLETED }) }
            lazyItems(tasks.filter { it.status == ItemStatus.COMPLETED }) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToComplete(item: HomeItem, users: Map<String, AppUser>, onOpenItem: (HomeItem) -> Unit, onComplete: (HomeItem) -> Unit, onQuickAction: (HomeItem, ItemQuickAction) -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) onComplete(item); it != SwipeToDismissBoxValue.StartToEnd })
    SwipeToDismissBox(state = state, backgroundContent = { Box(Modifier.fillMaxSize().background(ListColor).padding(16.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Default.Done, null, tint = Color.White) } }, enableDismissFromStartToEnd = false) {
        ItemCard(item, users, { onOpenItem(item) }, onQuickAction = { action -> onQuickAction(item, action) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListsScreen(items: List<HomeItem>, users: Map<String, AppUser>, onOpenItem: (HomeItem) -> Unit, onToggle: (HomeItem, String) -> Unit, onQuickAction: (HomeItem, ItemQuickAction) -> Unit = { _, _ -> }) {
    val lists = items.filter { it.type == ItemType.LIST && it.status != ItemStatus.ARCHIVED }.sortedByDescending { it.pinned }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lazyItems(lists) { list ->
            var menuOpen by remember(list.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenItem(list) }, onLongClick = { menuOpen = true })) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FormatListBulleted, null, tint = ListColor); Spacer(Modifier.width(8.dp))
                        Text(list.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (list.pinned) Icon(Icons.Default.PushPin, null, tint = NoteColor, modifier = Modifier.size(18.dp))
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(if (list.pinned) "ביטול נעיצה" else "נעיצה") }, onClick = { menuOpen = false; onQuickAction(list, ItemQuickAction.TOGGLE_PIN) }, leadingIcon = { Icon(Icons.Default.PushPin, null) })
                            if (list.status == ItemStatus.ACTIVE) DropdownMenuItem(text = { Text("סמן כהושלם") }, onClick = { menuOpen = false; onQuickAction(list, ItemQuickAction.COMPLETE) }, leadingIcon = { Icon(Icons.Default.Done, null) })
                            DropdownMenuItem(text = { Text("ארכוב") }, onClick = { menuOpen = false; onQuickAction(list, ItemQuickAction.ARCHIVE) }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                            DropdownMenuItem(text = { Text("מחיקה") }, onClick = { menuOpen = false; onQuickAction(list, ItemQuickAction.DELETE) }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                        }
                    }
                    list.checklist.forEach { entry ->
                        Row(Modifier.fillMaxWidth().clickable { onToggle(list, entry.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.completed, { onToggle(list, entry.id) }); Text(entry.title)
                        }
                    }
                    Text("${list.checklist.count { it.completed }} מתוך ${list.checklist.size}", style = MaterialTheme.typography.labelMedium, color = ListColor)
                }
            }
        }
    }
}

@Composable
fun NotesScreen(items: List<HomeItem>, users: Map<String, AppUser>, currentUser: AppUser, onOpenItem: (HomeItem) -> Unit, onRead: (HomeItem) -> Unit, onQuickAction: (HomeItem, ItemQuickAction) -> Unit = { _, _ -> }) {
    var tab by remember { mutableIntStateOf(0) }
    val now = Instant.now()
    val notes = items.filter { it.type == ItemType.NOTE && it.status != ItemStatus.ARCHIVED && it.isVisibleTo(currentUser.id, now) }
    val unread = notes.filter { it.readReceipts.firstOrNull { r -> r.userId == currentUser.id }?.readAt == null && it.scheduledPublishAt?.isAfter(now) != true }
    val read = notes - unread.toSet()
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) { listOf("חדשים ${unread.size}", "נקראו ${read.size}", "מתוזמנים").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val shown = when(tab) { 0 -> unread; 1 -> read; else -> items.filter { it.type == ItemType.NOTE && it.creatorId == currentUser.id && it.scheduledPublishAt?.isAfter(now) == true } }
            lazyItems(shown.sortedByDescending { it.pinned }) { note -> ItemCard(note, users, { onOpenItem(note) }, onQuickAction = { action -> onQuickAction(note, action) }) }
        }
    }
}

@Composable
fun SearchScreen(items: List<HomeItem>, users: Map<String, AppUser>, onOpenItem: (HomeItem) -> Unit, onQuickAction: (HomeItem, ItemQuickAction) -> Unit = { _, _ -> }) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("הכול") }
    val results = items.filter { item ->
        val matchesQuery = query.isBlank() || item.title.contains(query, true) || item.body.contains(query, true) || item.tags.any { it.contains(query, true) }
        val matchesType = filter == "הכול" || item.type.name.equals(when(filter) { "אירוע" -> "EVENT"; "משימה" -> "TASK"; "רשימה" -> "LIST"; "פתק" -> "NOTE"; else -> filter }, true)
        matchesQuery && matchesType
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("חיפוש בכל התוכן") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp)); FilterRow(listOf("הכול","אירוע","משימה","רשימה","פתק"), filter) { filter = it }
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(results) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) } }
    }
}

