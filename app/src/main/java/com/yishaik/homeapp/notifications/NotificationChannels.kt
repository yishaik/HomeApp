package com.yishaik.homeapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yishaik.homeapp.R

object NotificationChannels {
    const val REMINDERS = "reminders"
    const val UPDATES = "updates"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(REMINDERS, context.getString(R.string.notification_channel_reminders), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(UPDATES, context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }
}
