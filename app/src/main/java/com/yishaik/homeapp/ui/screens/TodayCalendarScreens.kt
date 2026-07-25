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
import com.yishaik.homeapp.domain.hebrew.HebrewCalendarProvider
import com.yishaik.homeapp.domain.hebrew.HolidayOrVacation
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
fun TodayScreen(items: List<HomeItem>, users: Map<String, AppUser>, currentUser: AppUser, onOpenItem: (HomeItem) -> Unit, onQuickAction: (HomeItem, ItemQuickAction) -> Unit = { _, _ -> }) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    val now = Instant.now()
    val visible = items.filter { it.status != ItemStatus.ARCHIVED && it.isVisibleTo(currentUser.id, now) }
    val newNotes = visible.filter { it.type == ItemType.NOTE && it.scheduledPublishAt?.isAfter(now) != true && it.readReceipts.firstOrNull { r -> r.userId == currentUser.id }?.readAt == null }
    val allDay = visible.filter { it.type == ItemType.EVENT && it.allDay && it.dateIn(ZoneId.systemDefault()) == date }
    val unscheduled = visible.filter { (it.type == ItemType.TASK || it.type == ItemType.LIST) && (it.dueAt == null || it.isOverdue(now)) && it.status == ItemStatus.ACTIVE }
    val timeline = visible.filter { it.dateIn(ZoneId.systemDefault()) == date && !it.allDay && (it.startAt != null || it.dueAt != null) && it.status == ItemStatus.ACTIVE }
        .sortedBy { it.startAt ?: it.dueAt }
    val completed = visible.filter { it.status == ItemStatus.COMPLETED && it.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate() == date }
    val readNotes = visible.filter { it.type == ItemType.NOTE && it.readReceipts.firstOrNull { r -> r.userId == currentUser.id }?.readAt != null }
    val holidayEntries = remember(date) { HebrewCalendarProvider.entriesOn(date) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.Default.ChevronLeft, null) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (date == LocalDate.now()) "היום" else date.format(DateTimeFormatter.ofPattern("EEEE", Locale("he"))), fontWeight = FontWeight.Bold)
                    Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("he"))), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { date = date.plusDays(1) }) { Icon(Icons.Default.ChevronRight, null) }
            }
        }
        if (holidayEntries.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    holidayEntries.forEach { HolidayChip(it) }
                }
            }
        }
        if (newNotes.isNotEmpty()) {
            item { SectionHeader("פתקים חדשים ומוצמדים", newNotes.size) }
            lazyItems(newNotes.sortedByDescending { it.pinned }) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) }
        }
        if (allDay.isNotEmpty()) {
            item { SectionHeader("כל היום") }
            lazyItems(allDay) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) }
        }
        if (unscheduled.isNotEmpty()) {
            item { SectionHeader("ללא שעה ובאיחור", unscheduled.size) }
            lazyItems(unscheduled.sortedByDescending { it.pinned }) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) }
        }
        item { SectionHeader("ציר הזמן") }
        if (timeline.isEmpty()) item { EmptyState("אין פריטים מתוזמנים ביום הזה") }
        lazyItems(timeline) { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text((item.startAt ?: item.dueAt)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "", modifier = Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
                ItemCard(item, users, { onOpenItem(item) }, Modifier.weight(1f), onQuickAction = { action -> onQuickAction(item, action) })
            }
        }
        if (completed.isNotEmpty()) {
            item { SectionHeader("הושלמו היום", completed.size) }
            lazyItems(completed) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) }
        }
        if (readNotes.isNotEmpty()) {
            item { SectionHeader("פתקים שנקראו") }
            lazyItems(readNotes.take(4)) { ItemCard(it, users, { onOpenItem(it) }, onQuickAction = { action -> onQuickAction(it, action) }) }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
fun CalendarScreen(items: List<HomeItem>, users: Map<String, AppUser>, onOpenItem: (HomeItem) -> Unit) {
    var view by remember { mutableStateOf(CalendarView.MONTH) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
            CalendarView.entries.forEachIndexed { index, option ->
                SegmentedButton(selected = view == option, onClick = { view = option }, shape = SegmentedButtonDefaults.itemShape(index, CalendarView.entries.size)) {
                    Text(when(option) { CalendarView.DAY -> "יום"; CalendarView.WEEK -> "שבוע"; CalendarView.MONTH -> "חודש"; CalendarView.YEAR -> "שנה" })
                }
            }
        }
        when (view) {
            CalendarView.DAY -> DayCalendar(items, users, onOpenItem)
            CalendarView.WEEK -> WeekCalendar(items, users, onOpenItem)
            CalendarView.MONTH -> MonthCalendar(items, onOpenItem)
            CalendarView.YEAR -> YearCalendar(items)
        }
    }
}

@Composable
private fun DayCalendar(items: List<HomeItem>, users: Map<String, AppUser>, onOpenItem: (HomeItem) -> Unit) {
    val today = LocalDate.now()
    val dayItems = items.filter { it.dateIn(ZoneId.systemDefault()) == today && it.status != ItemStatus.ARCHIVED }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("he"))), style = MaterialTheme.typography.titleLarge) }
        lazyItems(dayItems) { ItemCard(it, users, { onOpenItem(it) }) }
    }
}

@Composable
private fun WeekCalendar(items: List<HomeItem>, users: Map<String, AppUser>, onOpenItem: (HomeItem) -> Unit) {
    val start = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    LazyRow(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(7) { offset ->
            val date = start.plusDays(offset.toLong())
            val dayItems = items.filter { it.dateIn(ZoneId.systemDefault()) == date && it.status != ItemStatus.ARCHIVED }
            Card(Modifier.width(250.dp).fillParentMaxHeight()) {
                LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(date.format(DateTimeFormatter.ofPattern("EEEE d/M", Locale("he"))), fontWeight = FontWeight.Bold) }
                    lazyItems(dayItems) { ItemCard(it, users, { onOpenItem(it) }) }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(items: List<HomeItem>, onOpenItem: (HomeItem) -> Unit) {
    val month = YearMonth.now()
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value % 7
    val total = leading + month.lengthOfMonth()
    Column(Modifier.padding(12.dp)) {
        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("he"))), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
        Row(Modifier.fillMaxWidth()) { listOf("א׳","ב׳","ג׳","ד׳","ה׳","ו׳","ש׳").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        repeat((total + 6) / 7) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val day = row * 7 + col - leading + 1
                    val date = if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
                    val dayItems = date?.let { d -> items.filter { it.dateIn(ZoneId.systemDefault()) == d && it.status != ItemStatus.ARCHIVED } }.orEmpty()
                    val holidays = date?.let { d -> HebrewCalendarProvider.entriesOn(d) }.orEmpty()
                    Card(Modifier.weight(1f).height(82.dp).padding(2.dp).clickable { dayItems.firstOrNull()?.let(onOpenItem) }) {
                        Column(Modifier.padding(5.dp)) {
                            Text(date?.dayOfMonth?.toString().orEmpty(), style = MaterialTheme.typography.labelMedium)
                            holidays.take(1).forEach { holiday ->
                                Text(
                                    holiday.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 3.dp),
                                )
                            }
                            dayItems.take(if (holidays.isEmpty()) 3 else 2).forEach { item -> Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(itemColor(item.type))) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearCalendar(items: List<HomeItem>) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(12) { index ->
            val month = YearMonth.of(LocalDate.now().year, index + 1)
            val count = items.count { it.dateIn(ZoneId.systemDefault())?.let(YearMonth::from) == month && it.status != ItemStatus.ARCHIVED }
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(month.month.getDisplayName(TextStyle.FULL, Locale("he")), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LinearProgressIndicator(progress = { (count / 15f).coerceIn(0f, 1f) }, modifier = Modifier.width(120.dp))
                Spacer(Modifier.width(12.dp)); Text("$count פריטים")
            }}
        }
    }
}


// Read-only overlay chip for Israeli holidays / school vacations. Never clickable, never persisted.
@Composable
private fun HolidayChip(entry: HolidayOrVacation, modifier: Modifier = Modifier) {
    val vacation = entry.type == "vacation"
    Surface(
        modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (vacation) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (vacation) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (vacation) Icons.Default.BeachAccess else Icons.Default.Celebration, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(entry.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState(text: String) = Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
