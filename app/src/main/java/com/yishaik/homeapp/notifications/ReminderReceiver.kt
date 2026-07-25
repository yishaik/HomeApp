package com.yishaik.homeapp.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yishaik.homeapp.MainActivity
import com.yishaik.homeapp.R

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val itemId = intent.getStringExtra("item_id") ?: return
        val title = intent.getStringExtra("title") ?: context.getString(R.string.app_name)
        val open = PendingIntent.getActivity(context, itemId.hashCode(), Intent(context, MainActivity::class.java).putExtra("item_id", itemId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("item_id", itemId); putExtra("title", title); putExtra("snoozed", true)
        }
        val snooze = PendingIntent.getBroadcast(context, itemId.hashCode() + 1, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("HomeApp reminder")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .addAction(0, "Snooze 10 min", snooze)
            .build()
        NotificationManagerCompat.from(context).notify(itemId.hashCode(), notification)
    }
}
