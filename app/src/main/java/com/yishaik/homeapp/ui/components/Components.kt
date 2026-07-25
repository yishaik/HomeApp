package com.yishaik.homeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yishaik.homeapp.domain.*
import com.yishaik.homeapp.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text("מצב לא מקוון — צפייה בלבד", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun UserAvatar(user: AppUser, size: Int = 30) {
    Box(
        Modifier.size(size.dp).background(Color(user.accentArgb), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(user.avatar, color = Color.White, fontWeight = FontWeight.Bold) }
}

fun itemColor(type: ItemType) = when (type) {
    ItemType.EVENT -> EventColor
    ItemType.TASK -> TaskColor
    ItemType.LIST -> ListColor
    ItemType.NOTE -> NoteColor
}

fun itemIcon(type: ItemType): ImageVector = when (type) {
    ItemType.EVENT -> Icons.Default.Event
    ItemType.TASK -> Icons.Default.CheckCircle
    ItemType.LIST -> Icons.Default.FormatListBulleted
    ItemType.NOTE -> Icons.Default.StickyNote2
}

@Composable
fun ItemCard(item: HomeItem, users: Map<String, AppUser>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = itemColor(item.type)
    val date = item.startAt ?: item.dueAt ?: item.scheduledPublishAt
    val meta = buildString {
        date?.let { append(it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM · HH:mm"))) }
        if (item.assignee != Assignee.NONE) {
            if (isNotEmpty()) append(" · ")
            append(when (item.assignee) { Assignee.USER_ONE -> "ישי"; Assignee.USER_TWO -> "מעיין"; Assignee.BOTH -> "שנינו"; else -> "" })
        }
    }
    ElevatedCard(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
            Box(Modifier.width(7.dp).fillMaxHeight().background(color))
            Row(Modifier.padding(12.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val user = users[item.creatorId]
                if (user != null) UserAvatar(user)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(itemIcon(item.type), null, tint = color, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.status != ItemStatus.ACTIVE) Text(item.status.name.lowercase().replaceFirstChar { it.titlecase() }, color = color, style = MaterialTheme.typography.labelSmall)
                }
                if (item.pinned) Icon(Icons.Default.PushPin, null, tint = NoteColor)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (count != null) Badge { Text(count.toString()) }
        if (action != null) action()
    }
}

@Composable
fun FilterRow(filters: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { label -> FilterChip(selected = selected == label, onClick = { onSelected(label) }, label = { Text(label) }) }
    }
}
