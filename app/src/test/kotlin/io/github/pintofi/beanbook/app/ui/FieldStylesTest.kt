package io.github.pintofi.beanbook.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A kind of input has one field, so a fix to how it behaves is made once and reaches every screen.
 *
 * Money and other figures use `AmountField` or `NumberField`, and a password or passphrase uses
 * `PasswordField`. The same drift this guards against happened with buttons (see
 * [ButtonStylesTest]): five password boxes asked only for the hiding and three asked the keyboard
 * not to remember the word, and a decimal-places box refused the backspace that cleared its only
 * digit, because each was written by hand where it stood.
 */
class FieldStylesTest {

    private val main = File("src/main/kotlin/io/github/pintofi/beanbook/app")

    /** The file that defines the standard fields; the one place the raw settings may appear. */
    private val definitions = "ui/Fields.kt"

    private fun sources() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(main).path to it.readText() }
        .filter { (path, _) -> path != definitions }
        .toList()

    @Test
    fun `a number box uses the number fields, not a text box with a number keyboard`() {
        val found = sources().filter { (_, source) ->
            "KeyboardType.Number" in source || "KeyboardType.Decimal" in source
        }.map { it.first }
        assertEquals(
            "Use AmountField for a figure with a point and a sign and NumberField for a whole number, " +
                "both in ui/Fields.kt, so every box that takes a number behaves alike.",
            emptyList<String>(),
            found,
        )
    }

    @Test
    fun `a password box uses PasswordField`() {
        val found = sources().filter { (_, source) ->
            "PasswordVisualTransformation" in source || "KeyboardType.Password" in source
        }.map { it.first }
        assertEquals(
            "Use PasswordField (ui/Fields.kt): it hides the text and asks the keyboard not to learn it.",
            emptyList<String>(),
            found,
        )
    }
}
