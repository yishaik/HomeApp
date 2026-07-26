package com.yishaik.homeapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishaik.homeapp.ui.HomeAppRoot
import com.yishaik.homeapp.ui.theme.HomeAppTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as HomeApplication
        handleIntent(intent)
        setContent {
            val preferences by app.preferencesStore.prefs.collectAsStateWithLifecycle()
            HomeAppTheme(accentArgb = preferences.accentArgb, rtl = preferences.localeTag != "en") { HomeAppRoot(app) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // Reminder notifications launch this activity with "item_id" (see ReminderReceiver); stash it
    // on the application so HomeAppRoot can open the item once it's loaded (N14).
    private fun handleIntent(intent: Intent) {
        intent.getStringExtra("item_id")?.let { (application as HomeApplication).pendingDeepLinkItemId.value = it }
    }
}
