package com.yishaik.homeapp

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yishaik.homeapp.data.ConnectivityObserver
import com.yishaik.homeapp.data.HomeRepository
import com.yishaik.homeapp.data.LocalDatabase
import com.yishaik.homeapp.data.PreferencesStore
import com.yishaik.homeapp.notifications.NotificationChannels
import com.yishaik.homeapp.notifications.ReminderScheduler
import com.yishaik.homeapp.notifications.SyncWorker
import com.yishaik.homeapp.security.ActivationManager
import com.yishaik.homeapp.security.PinVault
import com.yishaik.homeapp.security.SessionStore
import com.yishaik.homeapp.sync.SupabaseApi
import java.util.concurrent.TimeUnit

class HomeApplication : Application() {
    lateinit var repository: HomeRepository
    lateinit var preferencesStore: PreferencesStore
    lateinit var pinVault: PinVault
    lateinit var sessionStore: SessionStore
    lateinit var activationManager: ActivationManager
    lateinit var reminderScheduler: ReminderScheduler

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        val api = SupabaseApi()
        val database = LocalDatabase(this)
        sessionStore = SessionStore(this)
        preferencesStore = PreferencesStore(database)
        repository = HomeRepository(database, ConnectivityObserver(this), api, sessionStore, preferencesStore)
        pinVault = PinVault(this)
        activationManager = ActivationManager(this, api, sessionStore)
        reminderScheduler = ReminderScheduler(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "periodic-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
