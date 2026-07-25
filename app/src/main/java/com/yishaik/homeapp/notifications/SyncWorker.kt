package com.yishaik.homeapp.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yishaik.homeapp.HomeApplication

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HomeApplication
        return app.repository.syncNow().fold(
            onSuccess = {
                app.repository.publishScheduledNotes()
                val meId = app.repository.currentUser.value.id
                app.repository.items.value.forEach { app.reminderScheduler.schedule(it, meId) }
                Result.success()
            },
            onFailure = { Result.retry() },
        )
    }
}
