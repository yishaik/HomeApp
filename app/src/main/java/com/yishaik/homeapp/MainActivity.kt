package com.yishaik.homeapp

import android.Manifest
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
        setContent {
            val preferences by app.preferencesStore.prefs.collectAsStateWithLifecycle()
            HomeAppTheme(accentArgb = preferences.accentArgb) { HomeAppRoot(app) }
        }
    }
}
