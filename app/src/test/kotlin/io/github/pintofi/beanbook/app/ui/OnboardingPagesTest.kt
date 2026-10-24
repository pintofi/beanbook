package io.github.pintofi.beanbook.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pages of first-run setup where a person chooses something are built from folios, like every
 * page under More (see [MorePagesTest]).
 *
 * "Add a ledger" and the three setup steps were plain headings, fields and buttons laid straight on
 * the page, beside a project list and a Backup page made of cards. The welcome page and the page
 * that says the ledger is ready are the app introducing itself and are not control pages; they
 * are the two things left out here, on purpose.
 */
class OnboardingPagesTest {

    private val dir = File("src/main/kotlin/io/github/pintofi/beanbook/app/ui/onboarding")

    /** The source of one composable, from its declaration to the next one. */
    private fun body(file: String, name: String): String {
        val source = File(dir, file).readText()
        val start = source.indexOf(" fun $name(")
        check(start >= 0) { "$name is not in $file" }
        val next = source.indexOf("\n@Composable", start)
        return source.substring(start, if (next < 0) source.length else next)
    }

    private val controlPages = listOf(
        "OnboardingFlow.kt" to "WaysInPage",
        "RemoteCard.kt" to "RemoteCard",
        "Steps.kt" to "BasicsStep",
        "Steps.kt" to "AccountsStep",
        "Steps.kt" to "GetSetStep",
    )

    @Test
    fun `every setup page where something is chosen is made of folios`() {
        controlPages.forEach { (file, name) ->
            val source = body(file, name)
            assertTrue("$name ($file) has no FolioHeading", "FolioHeading(" in source)
            assertTrue("$name ($file) has no FolioGroup", "FolioGroup" in source)
        }
    }

    @Test
    fun `sections are folios, not a heading and a rule laid on the page`() {
        controlPages.forEach { (file, name) ->
            assertTrue(
                "$name ($file) separates its sections with a HorizontalDivider; use a FolioHeading and FolioGroup",
                "HorizontalDivider(" !in body(file, name),
            )
        }
    }

    @Test
    fun `the steps are laid out with the folio page's own spacing`() {
        val frame = File(dir, "SetupScaffold.kt").readText()
        assertTrue(
            "SetupPage should space its content as FolioPage does (Measure.snug)",
            "Arrangement.spacedBy(Measure.snug)" in frame,
        )
    }
}
