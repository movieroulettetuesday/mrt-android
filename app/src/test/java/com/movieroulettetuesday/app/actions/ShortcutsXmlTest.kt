package com.movieroulettetuesday.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ShortcutsXmlTest {
    private fun file(): File = listOf("src/main/res/xml/shortcuts.xml", "app/src/main/res/xml/shortcuts.xml")
        .map(::File).first { it.isFile }

    @Test
    fun staticShortcutsAndCapabilities() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file())
        val shortcuts = doc.getElementsByTagName("shortcut")
        val ids = (0 until shortcuts.length).map { (shortcuts.item(it) as Element).getAttribute("android:shortcutId") }
        assertEquals(listOf("spin", "latest", "watchlist", "suggest"), ids)

        fun data(id: String): String {
            val node = (0 until shortcuts.length).map { shortcuts.item(it) as Element }.first { it.getAttribute("android:shortcutId") == id }
            val intent = node.getElementsByTagName("intent").item(0) as Element
            return intent.getAttribute("android:data")
        }
        assertEquals("mrt://spin", data("spin"))
        assertEquals("mrt://latest", data("latest"))
        assertEquals("mrt://watchlist", data("watchlist"))
        assertEquals("mrt://suggest", data("suggest"))

        val caps = doc.getElementsByTagName("capability")
        val names = (0 until caps.length).map { (caps.item(it) as Element).getAttribute("android:name") }
        assertTrue(names.contains("actions.intent.OPEN_APP_FEATURE"))
        assertTrue(names.contains("actions.intent.GET_THING"))
    }

    @Test
    fun pickForMeKeepsWhatShouldIWatchAndSpinDropsWhatShouldWeWatch() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "values/strings.xml"))
        fun string(name: String) = (0 until strings.getElementsByTagName("string").length)
            .map { strings.getElementsByTagName("string").item(it) as Element }.first { it.getAttribute("name") == name }.textContent
        assertEquals("Pick for me", string("shortcut_suggest_short"))
        assertEquals("Pick a movie for me", string("shortcut_suggest_long"))
        assertEquals("Spin Movie Roulette", string("shortcut_spin_long"))
        val arrays = strings.getElementsByTagName("string-array")
        val suggest = (0 until arrays.length).map { arrays.item(it) as Element }.first { it.getAttribute("name") == "shortcut_suggest_features" }
        val items = suggest.getElementsByTagName("item").let { nl -> (0 until nl.length).map { nl.item(it).textContent } }
        assertEquals(listOf("Pick for me", "Pick a movie for me", "What should I watch"), items)
        val xml = file().readText()
        assertTrue(xml.contains("@array/shortcut_suggest_features"))
        listOf(xml, File(res, "values/strings.xml").readText()).forEach {
            assertFalse(it.contains("What should we watch", ignoreCase = true))
            assertFalse(it.contains("Suggest a Movie", ignoreCase = true))
        }
    }
}
