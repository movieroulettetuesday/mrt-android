package com.movieroulettetuesday.app

import android.os.Bundle
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
        setContent {
            val app = application as MrtApplication
            val appearance by app.preferences.appearance.collectAsStateWithLifecycle()
            MrtTheme(darkTheme = appearance.forcesDark ?: isSystemInDarkTheme()) {
                MrtApp()
            }
        }
    }
}
