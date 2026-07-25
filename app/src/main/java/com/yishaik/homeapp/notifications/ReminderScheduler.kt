package com.yishaik.homeapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.yishaik.homeapp.domain.HomeItem
import java.time.Instant

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(item: HomeItem) {
        val target = item.startAt ?: item.dueAt ?: return
        item.reminders.filter { it.enabled }.forEach { reminder ->
            val triggerAt = target.minusSeconds(reminder.minutesBefore * 60L)
            if (triggerAt.isBefore(Instant.now())) return@forEach
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("item_id", item.id); putExtra("title", item.title); putExtra("type", item.type.name)
            }
            val requestCode = (item.id + reminder.id).hashCode()
            val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pending) }
                .onFailure { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pending) }
        }
    }
}
