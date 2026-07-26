package com.yishaik.homeapp.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yishaik.homeapp.HomeApplication
import com.yishaik.homeapp.MainActivity
import com.yishaik.homeapp.R
import com.yishaik.homeapp.data.LocalDatabase
import com.yishaik.homeapp.data.PreferencesStore

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra("item_id") ?: return
        val title = intent.getStringExtra("title") ?: context.getString(R.string.app_name)

        // Snooze: re-arm the same notification for 10 minutes from now instead of showing it again.
        if (intent.getBooleanExtra("snoozed", false)) {
            NotificationManagerCompat.from(context).cancel(itemId.hashCode())
            val fire = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("item_id", itemId); putExtra("title", title); putExtra("type", intent.getStringExtra("type"))
            }
            val pending = PendingIntent.getBroadcast(context, itemId.hashCode() + 2, fire, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = System.currentTimeMillis() + 10 * 60 * 1000L
            val am = context.getSystemService(AlarmManager::class.java)
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        // Privacy: "תוכן במסך נעילה" decides whether the item title may appear on the lock screen.
        // When hidden we post a PRIVATE notification plus a redacted public version carrying only
        // the app name, so the user still sees that *a* reminder fired.
        val contentVisible = runCatching {
            val store = (context.applicationContext as? HomeApplication)?.preferencesStore
                ?: PreferencesStore(LocalDatabase(context.applicationContext))
            store.read().lockScreenContentVisible
        }.getOrDefault(false)
        val open = PendingIntent.getActivity(context, itemId.hashCode(), Intent(context, MainActivity::class.java).putExtra("item_id", itemId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("item_id", itemId); putExtra("title", title); putExtra("type", intent.getStringExtra("type")); putExtra("snoozed", true)
        }
        val snooze = PendingIntent.getBroadcast(context, itemId.hashCode() + 1, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("HomeApp reminder")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .addAction(0, "Snooze 10 min", snooze)
            .setVisibility(if (contentVisible) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
        if (!contentVisible) builder.setPublicVersion(
            NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        )
        NotificationManagerCompat.from(context).notify(itemId.hashCode(), builder.build())
    }
}
