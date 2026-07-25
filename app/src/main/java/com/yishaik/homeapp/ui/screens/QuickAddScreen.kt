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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    currentUser: AppUser,
    online: Boolean,
    defaultEventReminderMinutes: Int,
    defaultTaskReminderMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (HomeItem) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ItemType.NOTE) }
    var notify by remember { mutableStateOf(false) }
    var reminderMinutes by remember { mutableStateOf<Int?>(null) }
    val parsed = remember(text) { NaturalLanguageParser.parse(text) }
    val effectiveType = selectedType.takeUnless { it == ItemType.NOTE && parsed.type != ItemType.NOTE } ?: parsed.type
    val showReminder = effectiveType == ItemType.EVENT || effectiveType == ItemType.TASK
    val defaultReminder = if (effectiveType == ItemType.EVENT) defaultEventReminderMinutes else defaultTaskReminderMinutes
    val activeReminder = reminderMinutes ?: defaultReminder

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("הוספה מהירה", style = MaterialTheme.typography.headlineSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(ItemType.entries) { type -> FilterChip(selectedType == type, { selectedType = type }, label = { Text(when(type) { ItemType.NOTE -> "פתק"; ItemType.EVENT -> "אירוע"; ItemType.TASK -> "משימה"; ItemType.LIST -> "רשימה" }) }, leadingIcon = { Icon(itemIcon(type), null) }) } }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text(if (effectiveType == ItemType.LIST) "כל שורה = פריט ברשימה" else "למשל: רופא שיניים מחר ב־16:00") })
            if (text.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(14.dp)) {
                Text("תצוגה מקדימה", fontWeight = FontWeight.Bold); Text(parsed.title)
                parsed.dateTime?.let { Text(it.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))) }
                if (parsed.needsAiFallback) Text("הפירוש אינו ודאי; שירות AI יכול להשלים את הניתוח.", style = MaterialTheme.typography.bodySmall)
            }}
            if (showReminder) {
                Text("תזכורת", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(reminderChoices) { (label, minutes) ->
                    FilterChip(activeReminder == minutes, { reminderMinutes = minutes }, label = { Text(label) })
                } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(notify, { notify = it }); Spacer(Modifier.width(8.dp)); Text("הודע למשתמש השני") }
            Button(
                onClick = {
                    val type = effectiveType
                    val instant = parsed.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()
                    val reminders = if (showReminder && activeReminder >= 0) listOf(Reminder(minutesBefore = activeReminder, userId = currentUser.id)) else emptyList()
                    val checklist = if (type == ItemType.LIST) text.lines().map(String::trim).filter { it.isNotBlank() }.drop(1)
                        .mapIndexed { i, line -> ChecklistEntry(title = line, orderIndex = i) } else emptyList()
                    onSave(HomeItem(
                        id = UUID.randomUUID().toString(), type = type, title = parsed.title.ifBlank { text }, body = if (type == ItemType.NOTE) text else "",
                        creatorId = currentUser.id, assignee = if (type == ItemType.EVENT) if (currentUser.id == "u1") Assignee.USER_ONE else Assignee.USER_TWO else Assignee.NONE,
                        startAt = if (type == ItemType.EVENT) instant else null, endAt = if (type == ItemType.EVENT) instant?.plusSeconds(1800) else null,
                        dueAt = if (type == ItemType.TASK || type == ItemType.LIST) instant else null,
                        reminders = reminders,
                        checklist = checklist,
                        notifyOtherUser = notify,
                        readReceipts = if (type == ItemType.NOTE) listOf(ReadReceipt("u1", null), ReadReceipt("u2", null)) else emptyList(),
                    ))
                },
                enabled = online && text.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text(if (online) "שמירה" else "לא ניתן לערוך במצב לא מקוון") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun EmptyState(text: String) = Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
