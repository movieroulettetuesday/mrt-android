package com.movieroulettetuesday.app.roulette

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.ui.LocalWindowLayout
import com.movieroulettetuesday.app.ui.WindowLayout
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Reveal sheet, iOS 1.0.1 parity: 99accd7 (the Infamous Jackpot banner is the sheet's title),
 * 6b88813 (the banner stays brand gold in light mode) and 78d72a7 (long titles keep Share and
 * Movie page on screen at phone size). Android goes further: the details scroll and the actions are
 * pinned, so even a huge font scale never pushes the buttons off-screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RevealSheetTest {
    @get:Rule val compose = createComposeRule()

    private val category = RouletteCategory(id = "cat-cult", name = "Dark Comedy", fullName = "Dark Comedy", colorHex = "#2A9D8F", movieIds = emptyList())

    private fun reveal(title: String = "The Room", jackpot: Boolean) = RouletteViewModel.Reveal(
        id = "r1",
        movie = Movie(id = "m", title = title, year = 2003, genre = "Drama", categories = listOf("cat-cult"),
            episodeIds = listOf("Buzzsprout-1"), episodeTitle = title),
        category = category.copy(movieIds = listOf("m")),
        spinCount = 1,
        isJackpot = jackpot,
    )

    private var current: MutableState<RouletteViewModel.Reveal>? = null

    /** Sets the content once; later calls swap the reveal in place (setContent is once per test). */
    private fun show(reveal: RouletteViewModel.Reveal, dark: Boolean = false, window: WindowLayout = WindowLayout.Phone) {
        current?.let { it.value = reveal; compose.waitForIdle(); return }
        val state = mutableStateOf(reveal).also { current = it }
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayout provides window) {
                MrtTheme(darkTheme = dark) {
                    RevealSheet(state.value, onDismiss = {}, onSpinAgain = {}, onShare = {}, onOpenMovie = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private var hostView: View? = null

    /** The title bar alone (the sheet's dialog window can't be captured under Robolectric). */
    private fun showTitleBar(dark: Boolean) {
        compose.setContent {
            hostView = LocalView.current
            MrtTheme(darkTheme = dark) { Surface { RevealTitleBar(isJackpot = true) } }
        }
        compose.waitForIdle()
    }

    private fun gone(tag: String) = assertTrue("$tag should be absent", compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    private fun titleLines(): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("reveal-title").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().lineCount
    }

    @Test fun jackpotBannerIsTheSheetTitle() {
        show(reveal(jackpot = true))
        compose.onNodeWithTag("reveal-jackpot-banner").assertIsDisplayed()
        val banner = compose.onNodeWithTag("reveal-jackpot-banner").fetchSemanticsNode().config
        assertTrue("banner is the sheet's heading", banner.getOrNull(SemanticsProperties.Heading) != null)
        assertEquals(listOf("Infamous jackpot"), banner[SemanticsProperties.ContentDescription])
        gone("reveal-sheet-title")
        // The title sits above the content: above the category chip and the movie title.
        val b = bounds("reveal-jackpot-banner")
        val bar = bounds("reveal-title-bar")
        assertTrue("banner inside the title bar", b.top >= bar.top && b.bottom <= bar.bottom)
        assertTrue("title bar above the category", bar.bottom <= bounds("reveal-category").top)
        assertTrue("title bar above the movie title", bar.bottom <= bounds("reveal-title").top)
    }

    @Test fun regularRevealTitleIsTonightsMovie() {
        show(reveal(jackpot = false))
        compose.onNodeWithTag("reveal-sheet-title").assertIsDisplayed()
        gone("reveal-jackpot-banner")
        val node = compose.onNodeWithTag("reveal-sheet-title").fetchSemanticsNode().config
        assertTrue(node.getOrNull(SemanticsProperties.Heading) != null)
        assertTrue(bounds("reveal-title-bar").bottom <= bounds("reveal-title").top)
    }

    /** Share of the banner's pixels that are brand gold #F2C14E (the rest is ink text / antialiasing). */
    private fun goldFraction(): Double {
        // Draw the window like WheelScreenshotTest (captureToImage times out under Robolectric).
        val root = hostView!!.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val b = bounds("reveal-jackpot-banner")
        val x0 = b.left.toInt(); val y0 = b.top.toInt(); val x1 = b.right.toInt(); val y1 = b.bottom.toInt()
        var gold = 0
        for (x in x0 until x1) for (y in y0 until y1) {
            val c = bmp.getPixel(x, y)
            if (abs(AColor.red(c) - 0xF2) <= 6 && abs(AColor.green(c) - 0xC1) <= 6 && abs(AColor.blue(c) - 0x4E) <= 6) gold++
        }
        val total = (x1 - x0) * (y1 - y0)
        assertTrue("banner has area", total > 0)
        return gold.toDouble() / total
    }

    @Test fun jackpotBannerIsBrandGoldInLightTheme() {
        showTitleBar(dark = false)
        val f = goldFraction()
        assertTrue("light theme: banner is brand gold (gold fraction $f)", f > 0.5)
    }

    @Test fun jackpotBannerIsBrandGoldInDarkTheme() {
        showTitleBar(dark = true)
        val f = goldFraction()
        assertTrue("dark theme: banner is brand gold (gold fraction $f)", f > 0.5)
    }

    private fun assertActionsFullyOnScreen(tag: String) {
        val screenH = Resources.getSystem().displayMetrics.heightPixels
        for (t in listOf("reveal-share", "reveal-spin-again", "reveal-movie-page")) {
            compose.onNodeWithTag(t).assertIsDisplayed()
            val b = bounds(t)
            assertTrue("$t fully on screen ($tag): bottom ${b.bottom} vs screen $screenH", b.bottom <= screenH && b.top >= 0f)
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun longTitlesKeepShareAndMoviePageOnScreenOnACompactPhone() {
        for (jackpot in listOf(false, true)) for (title in LONG_TITLES) {
            show(reveal(title, jackpot))
            assertActionsFullyOnScreen("$title, jackpot $jackpot")
            assertTrue("phone: title on at most 2 lines", titleLines() <= 2)
        }
    }

    /** Beyond iOS: at 2x font scale the details scroll and the actions stay pinned on screen. */
    @Test @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun hugeFontScaleStillLeavesActionsOnScreen() {
        show(reveal(LONG_TITLES.last(), jackpot = true))
        assertActionsFullyOnScreen("font scale 2")
        compose.onNodeWithTag("reveal-jackpot-banner").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w800dp-h1280dp")
    fun tabletAllowsThreeTitleLinesAndKeepsActionsOnScreen() {
        for (title in LONG_TITLES) {
            show(reveal(title, jackpot = true), window = WindowLayout(800f, 1280f))
            assertActionsFullyOnScreen("tablet $title")
            assertTrue("tablet: title on at most 3 lines", titleLines() <= 3)
        }
        assertEquals(2, revealTitleMaxLines(isRegularWidth = false))
        assertEquals(3, revealTitleMaxLines(isRegularWidth = true))
    }

    companion object {
        /** The catalog's longest titles (iOS RevealSheetLayoutTests). */
        val LONG_TITLES = listOf(
            "The Unbearable Weight of Massive Talent",
            "Master and Commander: The Far Side of the World",
            "The Adventures of Buckaroo Banzai Across the 8th Dimension",
        )
    }
}
