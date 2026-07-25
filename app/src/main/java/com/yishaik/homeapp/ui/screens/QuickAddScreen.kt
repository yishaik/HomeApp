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

private val reminderChoices = listOf("ללא" to -1, "בזמן" to 0, "30 דק'" to 30, "שעה" to 60, "יום" to 1440)
private val assigneeChoices = listOf(Assignee.USER_ONE to "ישי", Assignee.USER_TWO to "מעיין", Assignee.BOTH to "שנינו")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    currentUser: AppUser,
    memberIds: Collection<String>,
    online: Boolean,
    defaultEventReminderMinutes: Int,
    defaultTaskReminderMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (HomeItem) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var listTitle by remember { mutableStateOf("") }
    var listItems by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ItemType.NOTE) }
    var notify by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var reminderMinutes by remember { mutableStateOf<Int?>(null) }
    var pickedDateTime by remember { mutableStateOf<Instant?>(null) }
    var location by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf(Assignee.NONE) }
    var showPicker by remember { mutableStateOf(false) }
    val parsed = remember(text) { NaturalLanguageParser.parse(text) }
    val effectiveType = selectedType.takeUnless { it == ItemType.NOTE && parsed.type != ItemType.NOTE } ?: parsed.type
    val isEvent = effectiveType == ItemType.EVENT
    val isTask = effectiveType == ItemType.TASK
    val isList = effectiveType == ItemType.LIST
    val isNote = effectiveType == ItemType.NOTE
    val showReminder = isEvent || isTask
    val defaultReminder = if (isEvent) defaultEventReminderMinutes else defaultTaskReminderMinutes
    val activeReminder = reminderMinutes ?: defaultReminder
    val nlInstant = parsed.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()
    val effectiveInstant = pickedDateTime ?: nlInstant
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("הוספה מהירה", style = MaterialTheme.typography.headlineSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(ItemType.entries) { type -> FilterChip(selectedType == type, { selectedType = type }, label = { Text(when(type) { ItemType.NOTE -> "פתק"; ItemType.EVENT -> "אירוע"; ItemType.TASK -> "משימה"; ItemType.LIST -> "רשימה" }) }, leadingIcon = { Icon(itemIcon(type), null) }) } }
            if (isList) {
                OutlinedTextField(
                    listTitle, { listTitle = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("כותרת הרשימה") }, placeholder = { Text("למשל: קניות לשבת") },
                )
                OutlinedTextField(
                    listItems, { listItems = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    label = { Text("פריטים") }, placeholder = { Text("פריט בכל שורה") },
                )
            } else {
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = if (isNote) 3 else 2,
                    label = { Text(if (isNote) "תוכן הפתק" else "כותרת") },
                    placeholder = { Text(if (isNote) "מה תרצה לזכור?" else "למשל: רופא שיניים מחר ב־16:00") },
                )
            }
            if (text.isNotBlank() && !isList && !isNote && (nlInstant != null || parsed.needsAiFallback)) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(14.dp)) {
                Text("תצוגה מקדימה", fontWeight = FontWeight.Bold); Text(parsed.title)
                parsed.dateTime?.let { Text(it.format(fmt)) }
                if (parsed.needsAiFallback) Text("הפירוש אינו ודאי; שירות AI יכול להשלים את הניתוח.", style = MaterialTheme.typography.bodySmall)
            }}

            if (isEvent || isTask || isNote) {
                val label = when { isNote -> "מועד פרסום (אופציונלי)"; isTask -> "תאריך יעד"; else -> "מתי" }
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp))
                    Text(effectiveInstant?.atZone(ZoneId.systemDefault())?.format(fmt) ?: label, modifier = Modifier.weight(1f))
                    if (pickedDateTime != null) Icon(Icons.Default.Close, null, Modifier.clickable { pickedDateTime = null })
                }
            }
            if (isEvent) OutlinedTextField(location, { location = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("מיקום") }, leadingIcon = { Icon(Icons.Default.Place, null) })
            if (isEvent || isTask) {
                Text("שיוך", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(assigneeChoices) { (value, label) ->
                    FilterChip(assignee == value, { assignee = if (assignee == value) Assignee.NONE else value }, label = { Text(label) })
                } }
            }
            if (showReminder) {
                Text("תזכורת", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(reminderChoices) { (label, minutes) ->
                    FilterChip(activeReminder == minutes, { reminderMinutes = minutes }, label = { Text(label) })
                } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(notify, { notify = it }); Spacer(Modifier.width(8.dp)); Text("הודע למשתמש השני") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(pinned, { pinned = it }) { Icon(Icons.Default.PushPin, null, tint = if (pinned) NoteColor else LocalContentColor.current) }
                Text("הצמדה")
            }
            val canSave = if (isList) listTitle.isNotBlank() else text.isNotBlank()
            Button(
                onClick = {
                    val type = effectiveType
                    val instant = effectiveInstant
                    val reminders = if (showReminder && activeReminder >= 0) listOf(Reminder(minutesBefore = activeReminder, userId = currentUser.id)) else emptyList()
                    val checklist = if (type == ItemType.LIST) listItems.lines().map(String::trim).filter { it.isNotBlank() }.mapIndexed { i, line -> ChecklistEntry(title = line, orderIndex = i) } else emptyList()
                    val resolvedAssignee = when {
                        type == ItemType.EVENT || type == ItemType.TASK ->
                            assignee.takeIf { it != Assignee.NONE } ?: assigneeSlot(currentUser.id, memberIds)
                        else -> Assignee.NONE
                    }
                    val noteReaders = (memberIds + currentUser.id).filter { it.isNotBlank() }.distinct()
                    val title = when { type == ItemType.LIST -> listTitle.trim().ifBlank { "רשימה" }; else -> parsed.title.ifBlank { text.trim() } }
                    onSave(HomeItem(
                        id = UUID.randomUUID().toString(), type = type, title = title, body = if (type == ItemType.NOTE) text.trim() else "",
                        creatorId = currentUser.id, assignee = resolvedAssignee,
                        startAt = if (type == ItemType.EVENT) instant else null, endAt = if (type == ItemType.EVENT) instant?.plusSeconds(1800) else null,
                        dueAt = if (type == ItemType.TASK || type == ItemType.LIST) instant else null,
                        scheduledPublishAt = if (type == ItemType.NOTE) instant else null,
                        locationLabel = if (type == ItemType.EVENT) location.trim().ifBlank { null } else null,
                        reminders = reminders,
                        checklist = checklist,
                        pinned = pinned,
                        notifyOtherUser = notify,
                        readReceipts = if (type == ItemType.NOTE) noteReaders.map { ReadReceipt(it, null) } else emptyList(),
                    ))
                },
                enabled = online && canSave, modifier = Modifier.fillMaxWidth()
            ) { Text(if (online) "שמירה" else "לא ניתן לערוך במצב לא מקוון") }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showPicker) DateTimePickerDialog(initial = effectiveInstant, onDismiss = { showPicker = false }, onConfirm = { pickedDateTime = it; showPicker = false })
}

@Composable
private fun EmptyState(text: String) = Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
