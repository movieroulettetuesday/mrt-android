package com.movieroulettetuesday.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.theme.MrtTheme

/** Single activity; everything else is Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MrtTheme {
                MrtApp()
            }
        }
    }
}
