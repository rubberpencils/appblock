package xyz.robinroy.appblock

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The launcher shortcuts in res/xml/shortcuts.xml name their target activity
 * by string, so nothing at compile time notices a rename or a missing
 * manifest entry: the shortcut just silently fails on the phone. These tests
 * check the wiring. (Unit tests run with the module dir, app/, as cwd.)
 */
class ShortcutsTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.all(tag: String) = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String) = getAttributeNS(androidNs, name)

    private val shortcuts = parse("src/main/res/xml/shortcuts.xml").all("shortcut")
    private val manifest = parse("src/main/AndroidManifest.xml")

    @Test
    fun `dont give up shortcut exists`() {
        assertTrue(shortcuts.any { it.attr("shortcutId") == "dont_give_up" })
    }

    @Test
    fun `contact support shortcut exists`() {
        assertTrue(shortcuts.any { it.attr("shortcutId") == "contact_support" })
    }

    @Test
    fun `every shortcut targets a real activity in this app`() {
        val declared = manifest.all("activity").map { "xyz.robinroy.appblock" + it.attr("name") }
        for (intent in shortcuts.flatMap { it.all("intent") }) {
            assertEquals("xyz.robinroy.appblock", intent.attr("targetPackage"))
            val target = intent.attr("targetClass")
            Class.forName(target, false, javaClass.classLoader)  // throws if renamed
            assertTrue("$target missing from the manifest", target in declared)
        }
    }

    @Test
    fun `launcher activity publishes the shortcuts`() {
        val main = manifest.all("activity").single { it.attr("name") == ".MainActivity" }
        assertTrue(main.all("meta-data").any {
            it.attr("name") == "android.app.shortcuts" && it.attr("resource") == "@xml/shortcuts"
        })
    }
}
