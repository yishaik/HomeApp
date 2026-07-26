package com.yishaik.homeapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yishaik.homeapp.domain.HomeItem
import com.yishaik.homeapp.domain.ItemStatus
import java.time.Instant

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    // Remembers which alarm request codes are live per item so we can cancel them exactly, even
    // for reminders that were since removed (their ids are gone from the item by then).
    private val registry = context.getSharedPreferences("reminder_alarms", Context.MODE_PRIVATE)

    /** Whether this device currently allows exact alarms (always true below API 31, where the
     *  permission doesn't exist yet). Used to decide whether to surface the "reminders may be
     *  inaccurate" warning in the UI. */
    fun hasExactAlarmPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Schedules only the reminders that belong to [currentUserId] on this device, replacing any
     *  previously scheduled alarms for the item. A closed/archived item schedules nothing.
     *  Returns false if any reminder had to be scheduled inexactly because the exact-alarm
     *  permission is missing (API 31+), true otherwise. */
    fun schedule(item: HomeItem, currentUserId: String): Boolean {
        cancel(item.id)
        val target = item.startAt ?: item.dueAt ?: return true
        if (item.status != ItemStatus.ACTIVE) return true
        val codes = mutableListOf<Int>()
        var allExact = true
        item.reminders.filter { it.enabled && it.userId == currentUserId }.forEach { reminder ->
            val triggerAt = target.minusSeconds(reminder.minutesBefore * 60L)
            if (triggerAt.isBefore(Instant.now())) return@forEach
            val requestCode = (item.id + reminder.id).hashCode()
            if (!setAlarm(requestCode, item, triggerAt)) allExact = false
            codes += requestCode
        }
        if (codes.isEmpty()) registry.edit().remove(item.id).apply()
        else registry.edit().putString(item.id, codes.joinToString(",")).apply()
        return allExact
    }

    /** Cancels every alarm currently scheduled for the item (used on delete/complete/archive/change). */
    fun cancel(itemId: String) {
        registry.getString(itemId, null)?.split(",")?.filter { it.isNotBlank() }?.forEach { code ->
            val pending = PendingIntent.getBroadcast(
                context, code.toInt(), Intent(context, ReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pending)
        }
        registry.edit().remove(itemId).apply()
    }

    /** Cancels every alarm this device has registered and empties the registry (used on logout, so
     *  the departing member's reminders stop firing for whoever activates next). */
    fun cancelAll() {
        registry.all.keys.toList().forEach(::cancel)
        registry.edit().clear().apply()
    }

    /** Returns true if the alarm was scheduled exactly, false if it had to fall back to inexact. */
    private fun setAlarm(requestCode: Int, item: HomeItem, triggerAt: Instant): Boolean {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("item_id", item.id); putExtra("title", item.title); putExtra("type", item.type.name)
        }
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!hasExactAlarmPermission()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pending)
            return false
        }
        return runCatching { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pending) }
            .onFailure { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pending) }
            .isSuccess
    }
}
