package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Plugins screen says about a catalogue plugin after a load (B97 data, B98 screen). */
class PluginStatusTest {

    private fun status(
        modules: List<String>,
        health: Map<String, String?> = emptyMap(),
        plugins: List<String> = emptyList(),
        errors: List<String> = emptyList(),
        safeMode: Boolean = false,
    ) = pluginStatusFor(modules, health, plugins, errors, safeMode)

    @Test fun `an extension that imports is loaded`() {
        assertEquals(PluginStatus.Loaded, status(listOf("fava_investor"), health = mapOf("fava_investor" to null)))
    }

    @Test fun `an extension that does not import has failed, with the reason`() {
        assertEquals(
            PluginStatus.Failed("No module named 'scipy'"),
            status(
                listOf("fava_portfolio_returns"),
                health = mapOf("fava_portfolio_returns" to "No module named 'scipy'"),
            ),
        )
    }

    @Test fun `one failing module fails the plugin even when another loads`() {
        val result = status(
            listOf("a", "b"),
            health = mapOf("a" to null, "b" to "broken"),
        )
        assertEquals(PluginStatus.Failed("broken"), result)
    }

    @Test fun `a beancount plugin named by the ledger and not in its errors is loaded`() {
        assertEquals(
            PluginStatus.Loaded,
            status(listOf("beancount_zakat.plugin"), plugins = listOf("beancount_zakat.plugin")),
        )
    }

    @Test fun `a beancount plugin whose import Beancount reports has failed`() {
        val message = "Error importing \"beancount_interpolate.depreciate\": No module named 'beancount_interpolate'"
        assertEquals(
            PluginStatus.Failed(message),
            status(
                listOf("beancount_interpolate.depreciate"),
                plugins = listOf("beancount_interpolate.depreciate"),
                errors = listOf("Transaction does not balance", message),
            ),
        )
    }

    @Test fun `a plugin the ledger does not name says nothing`() {
        assertEquals(PluginStatus.Unknown, status(listOf("fava_investor")))
    }

    @Test fun `safe mode says nothing, because nothing was loaded`() {
        assertEquals(
            PluginStatus.Unknown,
            status(listOf("x"), plugins = listOf("x"), health = mapOf("x" to null), safeMode = true),
        )
    }

    @Test fun `a plugin with no modules says nothing`() {
        assertEquals(PluginStatus.Unknown, status(emptyList(), health = mapOf("x" to "broken")))
    }
}
