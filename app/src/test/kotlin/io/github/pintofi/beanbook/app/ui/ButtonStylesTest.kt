package io.github.pintofi.beanbook.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app has two kinds of button: a filled one for a page's main action and an outlined one
 * for everything else, both Material's own (`Button` and `OutlinedButton`).
 *
 * There used to be a third, a wrapper called `SecondaryButton` that forced a taller, tighter
 * outlined button than Material draws, so a row of buttons mixed two heights. A tonal or text
 * button turning up beside them is the same drift, so they are listed here, each with the one
 * reason it is allowed.
 */
class ButtonStylesTest {

    private val main = File("src/main/kotlin/io/github/pintofi/beanbook/app")

    /** Where a style other than filled or outlined is allowed, and why. */
    private val allowed = mapOf(
        "ui/BalancesScreen.kt" to "the valuation choice: the selected option is tonal, the others outlined",
        "UndoBar.kt" to "the action inside a snackbar, which Material draws as a text button",
    )

    private val others = listOf("FilledTonalButton(", "ElevatedButton(", "TextButton(", "SecondaryButton")

    @Test
    fun `buttons are filled or outlined, apart from the listed exceptions`() {
        val found = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { file ->
            val path = file.relativeTo(main).path
            val source = file.readText()
            val used = others.filter { it in source }
            if (used.isNotEmpty() && path !in allowed) "$path uses ${used.joinToString()}" else null
        }.toList()

        assertEquals(
            "Use Button for the main action and OutlinedButton for the others. If a page really needs " +
                "another style, name its file in ButtonStylesTest.allowed with the reason.",
            emptyList<String>(),
            found,
        )
    }

    @Test
    fun `every exception still uses the style it is excused for`() {
        val stale = allowed.keys.filter { path ->
            val source = File(main, path).takeIf { it.isFile }?.readText().orEmpty()
            others.none { it in source }
        }
        assertEquals("These no longer need an exception.", emptyList<String>(), stale)
    }
}
