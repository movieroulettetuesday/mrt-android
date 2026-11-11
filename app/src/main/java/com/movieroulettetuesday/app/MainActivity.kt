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
import com.movieroulettetuesday.app.actions.DeepLink
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.widget.SpinOfTheDayWidget
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
        setIntent(intent)
        route(intent)
    }

    /**
     * Widget tap → today’s movie; new-episode notification → that episode; `mrt://` → [DeepLink].
     * Widget and notification paths stay silent; deep links go through [MrtApplication.dispatch].
     */
    private fun route(intent: Intent?) {
        intent ?: return
        intent.takeIf { it.action == SpinOfTheDayWidget.ACTION_OPEN_MOVIE }
            ?.getStringExtra(SpinOfTheDayWidget.EXTRA_MOVIE_ID)?.takeIf { it.isNotBlank() }
            ?.let { (application as MrtApplication).pendingMovie.value = it; return }
        val guid = intent.getStringExtra(NewEpisodeNotifier.EXTRA_GUID)?.takeIf { it.isNotBlank() }
        if (guid != null && intent.data?.scheme != DeepLink.SCHEME) {
            (application as MrtApplication).pendingEpisode.value = guid
            return
        }
        DeepLink.parse(intent)?.let { (application as MrtApplication).dispatch(it) }
    }
}
