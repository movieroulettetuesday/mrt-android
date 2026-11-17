package com.movieroulettetuesday.app.roulette

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.glance.appwidget.SizeMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import com.movieroulettetuesday.app.widget.SpinOfTheDayWidget
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element

/**
 * House Rules cannot drift from what the build declares: every static shortcut label and Assistant
 * synonym in res/xml/shortcuts.xml, every widget receiver and every widget size must have a row.
 */
@RunWith(AndroidJUnit4::class)
class HouseRulesTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val text: String by lazy {
        HouseRules.sections(HouseRules.labels(context)).joinToString("\n") { s -> (listOf(s.title) + s.rows + listOfNotNull(s.footer)).joinToString("\n") }
    }

    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.isFile }
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
    private fun elements(doc: org.w3c.dom.Document, tag: String) =
        doc.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    /** "@string/x" or "@array/x" → the resolved value(s). */
    private fun resolve(ref: String): List<String> {
        val (type, name) = ref.removePrefix("@").split("/", limit = 2)
        val id = context.resources.getIdentifier(name, type, context.packageName)
        assertTrue("missing resource $ref", id != 0)
        return when (type) {
            "string" -> listOf(context.getString(id))
            "array" -> context.resources.getStringArray(id).toList()
            else -> error("unexpected $ref")
        }
    }

    private fun assertMentioned(what: String) =
        assertTrue("House Rules does not mention “$what”:\n$text", text.contains(what, ignoreCase = true))

    @Test
    fun everyStaticShortcutLabelAndAssistantSynonymIsInHouseRules() {
        val shortcuts = elements(xml("res/xml/shortcuts.xml"), "shortcut")
        val ids = shortcuts.map { it.getAttribute("android:shortcutId") }
        assertEquals("HouseRules.labels lists the shortcuts.xml shortcuts, in order", ids, HouseRules.labels(context).shortcuts.map { it.id })
        assertEquals(ids.toSet(), HouseRules.shortcutActions.keys)
        for (s in shortcuts) {
            resolve(s.getAttribute("android:shortcutShortLabel")).forEach(::assertMentioned)
            resolve(s.getAttribute("android:shortcutLongLabel")).forEach(::assertMentioned)
            elements(s.ownerDocument, "parameter-binding").filter { it.parentNode.parentNode == s }.forEach { pb ->
                resolve(pb.getAttribute("android:value")).forEach(::assertMentioned)
            }
        }
        assertMentioned("Pick a movie for me in Movie Roulette")
        assertMentioned("What should I watch")
        assertMentioned("Long-press the app icon for Spin, Latest, Watchlist and Pick for me.")
    }

    @Test
    fun everyWidgetAndWidgetSizeIsInHouseRules() {
        val receivers = elements(xml("AndroidManifest.xml"), "receiver").filter { r ->
            elements(r.ownerDocument, "meta-data").any { it.parentNode == r && it.getAttribute("android:name") == "android.appwidget.provider" }
        }
        assertEquals("one widget today; a new one needs House Rules rows", 1, receivers.size)
        receivers.forEach { r -> resolve(r.getAttribute("android:label")).forEach(::assertMentioned) }
        val sizes = (SpinOfTheDayWidget().sizeMode as SizeMode.Responsive).sizes
        assertEquals(sizes, HouseRules.widgetSizes.keys)
        HouseRules.widgetSizes.values.forEach(::assertMentioned)
        assertMentioned("long-press an empty spot on your home screen, tap Widgets, then find Movie Roulette")
    }

    @Test
    fun onlyFeaturesThatExistAreDescribed() {
        val manifest = file("AndroidManifest.xml").readText()
        // No quick settings tile, share target or App Functions service in this build, so House Rules
        // must not promise one. Adding one should come with a House Rules row (and this check updated).
        assertFalse(manifest.contains("android.service.quicksettings"))
        assertFalse(manifest.contains("android.intent.action.SEND"))
        assertFalse(manifest.contains("appfunctions", ignoreCase = true))
        listOf("quick settings", "tile", "share sheet").forEach { assertFalse(it, text.contains(it, ignoreCase = true)) }
        // These do exist (deep links in the manifest, AppSearch indexer) and are described.
        assertTrue(manifest.contains("android:scheme=\"mrt\""))
        assertMentioned("mrt://")
        assertMentioned("on-device search")
    }

    @Test
    fun spinOrPickForMeSectionUsesTheApprovedCopy() {
        val sections = HouseRules.sections(HouseRules.labels(context))
        assertEquals(
            listOf("The Wheel", "Spin or Pick for Me", "Search", "Your Lists", "Assistant and Shortcuts", "Home-Screen Widget", "Good to Know"),
            sections.map { it.title },
        )
        val spin = sections[1].rows
        assertEquals("Pick for Me is on purpose: it picks one you haven’t watched, favouring your watchlist and your services, and tells you why.", spin[1])
        assertEquals("Runs on your device with Gemini Nano. Nothing leaves your device. If your device can’t run it, the button is hidden.", spin.last())
        assertFalse("no Apple in Android copy", text.contains("Apple"))
        assertFalse(text.contains("Siri"))
        assertFalse(text.contains("Spotlight"))
        assertFalse(text.contains("Suggest a Movie"))
        assertFalse(text.contains("What should we watch", ignoreCase = true))
        assertEquals(HouseRules.FOOTER, sections.last().footer)
    }

    @Test
    fun overflowMenuOpensTheHouseRulesSheetAndItScrolls() {
        compose.setContent {
            MrtTheme {
                var open by remember { mutableStateOf(false) }
                RouletteOverflowMenu(onHouseRules = { open = true })
                if (open) HouseRulesSheet(onDismiss = { open = false })
            }
        }
        compose.onNodeWithTag("roulette-overflow").performClick()
        compose.onNodeWithTag("open-house-rules").performClick()
        compose.onNodeWithTag("house-rules").assertIsDisplayed()
        compose.onNodeWithText("House Rules").assertIsDisplayed()
        assertEquals(7, compose.onAllNodesWithTag("house-rules-section").fetchSemanticsNodes().size)
        compose.onNodeWithText(HouseRules.FOOTER).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("house-rules-done").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("house-rules").assertDoesNotExist()
    }
}
