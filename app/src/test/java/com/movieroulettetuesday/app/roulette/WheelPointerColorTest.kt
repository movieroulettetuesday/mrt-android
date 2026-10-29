package com.movieroulettetuesday.app.roulette

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.shared.SharedPalette
import com.movieroulettetuesday.app.ui.LocalWindowLayout
import com.movieroulettetuesday.app.ui.WindowLayout
import com.movieroulettetuesday.app.ui.theme.BrandColors
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pointer is iOS `CleanProColors.pointerLight` / `pointerDark` by theme (RouletteView colorScheme switch). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class WheelPointerColorTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    @Test fun brandPointerColorsMatchSharedPalette() {
        assertEquals(SharedPalette.CleanPro.pointerLight, hex(BrandColors.PointerLight))
        assertEquals(SharedPalette.CleanPro.pointerDark, hex(BrandColors.PointerDark))
        assertEquals(BrandColors.PointerLight, WheelPointer.color(dark = false))
        assertEquals(BrandColors.PointerDark, WheelPointer.color(dark = true))
    }

    private fun drawnPointerColor(dark: Boolean): Color {
        val vm = RouletteViewModel(MutableStateFlow(CatalogRepository.State.Loaded(RouletteFixtures.catalog)), SpinHistoryStore(null), scope)
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout(411f, 891f)) { MrtTheme(darkTheme = dark) { RouletteScreen(vm) } }
        }
        compose.waitForIdle()
        // Draw the window like RevealSheetTest (captureToImage times out under Robolectric).
        val root = host!!.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val b = compose.onNodeWithTag("wheel-pointer", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        // Inside the downward triangle: horizontally centred, a quarter of the way down.
        return Color(bmp.getPixel(b.center.x.toInt(), (b.top + b.height / 4).toInt()))
    }

    @Test fun lightThemeDrawsPointerLight() = assertEquals(hex(BrandColors.PointerLight), hex(drawnPointerColor(dark = false)))

    @Test fun darkThemeDrawsPointerDark() = assertEquals(hex(BrandColors.PointerDark), hex(drawnPointerColor(dark = true)))
}
