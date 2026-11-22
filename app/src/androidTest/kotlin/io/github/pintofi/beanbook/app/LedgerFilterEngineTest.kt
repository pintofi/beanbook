package io.github.pintofi.beanbook.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The filter box runs Fava's own filter in the engine and gets back positions in the journal the
 * engine summarised. These tests run that on the device, against the real engine.
 */
@RunWith(AndroidJUnit4::class)
class LedgerFilterEngineTest {

    private lateinit var main: File

    @Before
    fun writeLedger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        main = File(context.cacheDir, "filter-engine/main.beancount").apply {
            parentFile?.deleteRecursively()
            parentFile?.mkdirs()
            writeText(LEDGER)
        }
    }

    private fun ledger() = Python.getInstance().getModule("ledger")

    private fun answer(expression: String): JSONObject =
        JSONObject(ledger().callAttr("matching_transactions", main.absolutePath, expression).toString())

    private fun narrations(expression: String): List<String> {
        val summary = JSONObject(ledger().callAttr("inspect", main.absolutePath).toString())
        val journal = summary.getJSONArray("journal")
        val answer = answer(expression)
        assertTrue(answer.toString(), answer.getBoolean("ok"))
        assertEquals(journal.length(), answer.getInt("transaction_count"))
        assertEquals(summary.getString("input_hash"), answer.getString("input_hash"))
        val positions = answer.getJSONArray("positions")
        return (0 until positions.length()).map { journal.getJSONObject(positions.getInt(it)).getString("narration") }
    }

    @Test
    fun a_minus_leaves_entries_out_and_commas_and_parentheses_work_as_in_fava() {
        assertEquals(listOf("opening", "weekly shop"), narrations("-#trip"))
        assertEquals(listOf("hotel", "lunch"), narrations("#trip"))
        assertEquals(listOf("hotel", "lunch", "weekly shop"), narrations("#trip, ^inv-1"))
        assertEquals(listOf("lunch", "weekly shop"), narrations("(#trip, ^inv-1) -#work"))
        // A plain word is a text search, not a tag.
        assertEquals(listOf("lunch"), narrations("lunch"))
    }

    @Test
    fun a_filter_fava_cannot_read_is_refused_with_its_message_and_no_positions() {
        for (expression in listOf("#trip,", "(#trip", "#trip)", "-")) {
            val answer = answer(expression)
            assertFalse(expression, answer.getBoolean("ok"))
            assertFalse(expression, answer.has("positions"))
            assertTrue(expression, answer.getString("filter_error").isNotBlank())
        }
    }

    /**
     * Not a pass or fail on speed alone: it logs what a filter costs on a ledger of 25,000
     * transactions, cold and warm, so the figure is on record, and refuses only a result outside
     * the budget the cold load already has.
     */
    @Test
    fun a_filter_on_the_large_ledger_is_within_the_cold_load_budget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packaged = instrumentation.context.assets.list("corpus/large").orEmpty()
        assumeTrue("the large corpus is not packaged", packaged.isNotEmpty())
        val folder = File(instrumentation.targetContext.cacheDir, "filter-engine/large").apply { mkdirs() }
        copyAsset(instrumentation.context.assets, "corpus/large", folder)
        val large = File(folder, "main.beancount")
        assumeTrue("the large corpus has no main file", large.isFile)
        for (expression in listOf("-#recurring", "#recurring", "Grocer")) {
            repeat(2) { round ->
                val started = System.nanoTime()
                val answer = JSONObject(
                    ledger().callAttr("matching_transactions", large.absolutePath, expression).toString(),
                )
                val millis = (System.nanoTime() - started) / 1_000_000
                val engine = answer.optDouble("filter_ms")
                Log.i("FilterTiming", "$expression round=$round total_ms=$millis filter_ms=$engine")
                assertTrue(answer.getBoolean("ok"))
                assertTrue("filter took ${millis} ms", millis < 20_000)
            }
        }
    }

    private fun copyAsset(assets: android.content.res.AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset(assets, "$path/$it", File(target, it)) }
        }
    }

    private companion object {
        val LEDGER = """
            option "operating_currency" "USD"
            2026-01-01 open Assets:Cash
            2026-01-01 open Expenses:Food
            2026-01-01 open Expenses:Hotel
            2026-01-01 open Equity:Opening

            2026-01-02 * "opening"
              Assets:Cash        1000.00 USD
              Equity:Opening

            2026-01-05 * "Hotel Aurora" "hotel" #trip #work ^inv-1
              Expenses:Hotel      200.00 USD
              Assets:Cash

            2026-01-06 * "Cafe" "lunch" #trip
              Expenses:Food        30.00 USD
              Assets:Cash

            2026-02-01 * "Grocer" "weekly shop" ^inv-1
              Expenses:Food        12.00 USD
              Assets:Cash
        """.trimIndent()
    }
}
