package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Installing a plugin is not the same as using one (B60).
 *
 * The files go where Beancount can find them; the *ledger* still has to name what it loads —
 * `plugin "module"` for a Beancount plugin, `custom "fava-extension" "module"` for a Fava
 * extension. The app wrote neither, so an installed and enabled plugin did nothing at all and
 * nothing on screen explained why.
 */
class PluginDirectiveTest {

    private val ledger = """
        option "title" "Books"
        option "operating_currency" "USD"

        2026-01-01 open Assets:Cash
    """.trimIndent()

    @Test fun `a plugin directive is added under the options`() {
        val out = BeancountText.withPluginDirective(ledger, "beancount_zakat", extension = false)!!
        val lines = out.lines()
        assertTrue(lines.any { it == """plugin "beancount_zakat"""" }, out)
        // Before the entries it acts on, after the options that configure the file.
        assertTrue(
            lines.indexOfFirst { it.startsWith("plugin ") } <
                lines.indexOfFirst { it.startsWith("2026-01-01") },
            out,
        )
    }

    @Test fun `a fava extension is added as its own custom directive`() {
        val out = BeancountText.withPluginDirective(
            ledger,
            "beancount_zakat.fava_extension",
            extension = true,
        )!!
        assertTrue(
            out.contains("""custom "fava-extension" "beancount_zakat.fava_extension""""),
            out,
        )
    }

    @Test fun `adding one twice does nothing the second time`() {
        val once = BeancountText.withPluginDirective(ledger, "x", extension = false)!!
        assertNull(BeancountText.withPluginDirective(once, "x", extension = false))
        assertTrue(BeancountText.hasPluginDirective(once, "x", extension = false))
    }

    @Test fun `removing takes the line and nothing else`() {
        val once = BeancountText.withPluginDirective(ledger, "x", extension = false)!!
        val out = BeancountText.withoutPluginDirective(once, "x", extension = false)!!
        assertEquals(ledger.lines().filter { it.isNotBlank() }, out.lines().filter { it.isNotBlank() })
    }

    @Test fun `a plugin with a configuration string is still recognised`() {
        val configured = """plugin "reds.zerosum" "{'tolerance': 0.01}"""" + "\n" + ledger
        assertTrue(BeancountText.hasPluginDirective(configured, "reds.zerosum", extension = false))
        val out = BeancountText.withoutPluginDirective(configured, "reds.zerosum", extension = false)
        assertTrue(out != null && !out.contains("zerosum"), "the configured directive survived: $out")
    }

    @Test fun `a module that is not there is reported rather than silently rewritten`() {
        assertNull(BeancountText.withoutPluginDirective(ledger, "absent", extension = false))
        assertTrue(!BeancountText.hasPluginDirective(ledger, "absent", extension = false))
    }
}
