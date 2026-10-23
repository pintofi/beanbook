package io.github.pintofi.beanbook.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every page under More is either built from folios, or is on a short list of exceptions that
 * each say why.
 *
 * The Accounts page was added as plain rows and a bare form while every page beside it was cards,
 * and nothing noticed. Pages a person acts on are drawn with `FolioGroup` and `FolioRow` (see
 * Folio.kt); the ledger's own lists and editors stay ruled. A new section cannot be added to
 * `MoreSection` without being named here, which is the moment to decide which kind it is.
 */
class MorePagesTest {

    private val main = File("src/main/kotlin/io/github/pintofi/beanbook/app")

    /** The More sections and the file that draws each. */
    private val folioPages = mapOf(
        "Projects" to "ui/ProjectScreens.kt",
        "Accounts" to "ui/AccountsScreen.kt",
        "Backup" to "ui/BackupScreen.kt",
        "Mirror" to "ui/MirrorScreen.kt",
        "Remote" to "ui/RemoteScreen.kt",
        "Share on Wi-Fi" to "ui/SharingScreen.kt",
        "Plugins" to "ui/PluginsScreen.kt",
        "Appearance" to "ui/AppearanceScreen.kt",
        "Licences" to "ui/LicencesScreen.kt",
        "About" to "about/AboutSection.kt",
    )

    /** Sections that are not folios, and why. A section is added here only with a reason. */
    private val exceptions = mapOf(
        "Files" to "a plain list of the ledger's own files, opening a text editor",
        "Documents" to "the Add tab is a capture flow and Organize is a ruled list of the ledger's documents; " +
            "both are due to be reworked with the scanner (#180)",
        "Scripts" to "a notice and one primary action, the same shape as Plugins' notice and Backup's button; " +
            "its kept scripts are folios",
        "Diagnostics" to "a log read like ledger lines, with its actions above it",
    )

    private fun sections(): List<String> {
        val source = File(main, "LedgerApp.kt").readText()
        val body = source.substringAfter("enum class MoreSection(val label: String) {").substringBefore("}")
        return Regex("""[A-Z_]+\("([^"]+)"\)""").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every More section is a folio page or a named exception`() {
        val known = folioPages.keys + exceptions.keys
        val unclassified = sections().filterNot { it in known }
        assertEquals(
            "These More sections are not in MorePagesTest. Build the page from folios " +
                "(FolioGroup, FolioRow) and list it in folioPages, or add it to exceptions with the reason.",
            emptyList<String>(),
            unclassified,
        )
    }

    @Test
    fun `the sections this test names all still exist`() {
        val present = sections().toSet()
        val gone = (folioPages.keys + exceptions.keys).filterNot { it in present }
        assertEquals("These are named here but are no longer More sections.", emptyList<String>(), gone)
    }

    @Test
    fun `a folio page is drawn with folios, not with its own rows and cards`() {
        folioPages.forEach { (section, path) ->
            val source = File(main, path).readText()
            assertTrue("$section ($path) has no FolioGroup", "FolioGroup" in source)
            assertTrue("$section ($path) has no FolioRow or FolioBody", "FolioRow" in source || "FolioBody" in source)
            assertTrue(
                "$section ($path) draws a Material ListItem; use FolioRow",
                !Regex("""[^A-Za-z]ListItem\(""").containsMatchIn(source),
            )
        }
    }
}
