package xyz.robinroy.appblock

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Auto Backup keeps the rules and usage, and leaves strict mode out (on a
 * new phone AppBlock isn't a device admin, so a restored "on" would be a
 * lie) and the clock's anchor (about this phone's clocks since it booted).
 * Android reads the file name with its ".xml", and Android 12+ reads cloud
 * backups and phone-to-phone transfers from separate sections, so each is
 * checked. (A real restore can't be tested: it kills the app's
 * process, which the e2e tests run in.)
 */
class BackupRulesTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.all(tag: String) = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun excludes(e: Element) = e.all("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }

    private val leftOut = listOf("sharedpref" to "${Storage.STRICT_PREFS}.xml", "sharedpref" to "${Storage.CLOCK_PREFS}.xml")

    @Test
    fun `android 11 and older leave out strict mode and the clock, and only them`() {
        assertEquals(leftOut, excludes(parse("src/main/res/xml/backup_rules.xml")))
    }

    @Test
    fun `android 12 and newer leave them out of cloud backups and transfers`() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertEquals(section, leftOut, excludes(rules.all(section).single()))
        }
    }

    @Test
    fun `the manifest uses both files`() {
        val app = parse("src/main/AndroidManifest.xml").all("application").single()
        assertEquals("true", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(androidNs, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
    }

    @Test
    fun `strict mode's device admin asks for no powers`() {
        val admin = parse("src/main/res/xml/strict_admin.xml")
        val policies = admin.all("uses-policies").single()
        assertTrue(policies.all("*").isEmpty())
    }
}
