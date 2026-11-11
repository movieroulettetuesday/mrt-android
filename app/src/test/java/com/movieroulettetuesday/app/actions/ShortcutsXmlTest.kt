package com.movieroulettetuesday.app.actions

import org.junit.Assert.assertEquals
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
}
