package com.movieroulettetuesday.app.roulette

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.catalog.CatalogFixtures
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real wheel with the bundled catalog to build/outputs/screenshots for a visual check. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WheelScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun renderWheel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val catalog = CatalogJson.decode(CatalogFixtures.bundledBytes)
        val vm = RouletteViewModel(MutableStateFlow(CatalogRepository.State.Loaded(catalog)), SpinHistoryStore(null), scope)
        compose.setContent { MrtTheme { RouletteScreen(vm) } }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "roulette-wheel.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 0)
        scope.cancel()
    }
}
