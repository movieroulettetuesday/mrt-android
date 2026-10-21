package com.movieroulettetuesday.app

import android.content.Intent
import android.os.Bundle
import com.movieroulettetuesday.app.notifications.NewEpisodeNotifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.theme.MrtTheme

/** Single activity; everything else is Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        setContent {
            val app = application as MrtApplication
            val appearance by app.preferences.appearance.collectAsStateWithLifecycle()
            MrtTheme(darkTheme = appearance.forcesDark ?: isSystemInDarkTheme()) {
                MrtApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** New-episode notification tap → that episode's page. */
    private fun route(intent: Intent?) {
        val guid = intent?.getStringExtra(NewEpisodeNotifier.EXTRA_GUID)?.takeIf { it.isNotBlank() } ?: return
        (application as MrtApplication).pendingEpisode.value = guid
    }
}
