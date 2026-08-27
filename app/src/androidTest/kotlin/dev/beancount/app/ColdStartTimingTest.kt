package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parses a known ledger, and records how long each phase of getting there took.
 *
 * The **assertion** is a correctness one and holds whatever else has run first: 200 lunches
 * against one cash account produce 202 entries and a balance of −200.00 USD. The **timings**
 * are a by-product, logged for `tools/measure-cold-start.sh` to read.
 *
 * They are deliberately not asserted against SPEC §6's 8 s budget. In a suite the interpreter
 * is usually already running, so the same code would measure a warm start and check it against
 * a cold-start threshold — which is how `KeySecrecyTest` came to report a key leak that was its
 * own control's (docs/milestones/m14-part17.md). A number that means something only in a fresh
 * process is a measurement, and the script is where a measurement belongs.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartTimingTest {

    @Test fun aKnownLedgerParsesToKnownNumbers() {
        val python = Python.getInstance()
        val timer = python.getModule("time")
        fun now() = timer.callAttr("perf_counter").toDouble()

        val file = InstrumentationRegistry.getInstrumentation().targetContext
            .cacheDir.resolve("cold-start.beancount")
        file.writeText(
            buildString {
                appendLine("2026-01-01 open Assets:Cash")
                appendLine("2026-01-01 open Expenses:Food")
                repeat(200) {
                    appendLine()
                    appendLine("2026-01-02 * \"Lunch $it\"")
                    appendLine("  Expenses:Food   1.00 USD")
                    appendLine("  Assets:Cash")
                }
            },
        )

        val beforeImport = now()
        val loader = python.getModule("beancount.loader")
        val importMs = ((now() - beforeImport) * 1000).toLong()

        val beforeParse = now()
        val result = loader.callAttr("load_file", file.absolutePath).asList()
        val parseMs = ((now() - beforeParse) * 1000).toLong()

        android.util.Log.i(
            TAG,
            "python_start=${startupMs}ms beancount_import=${importMs}ms parse_202=${parseMs}ms " +
                "(negative python_start means the interpreter was already running, so this " +
                "run is warm)",
        )

        assertEquals("the ledger did not parse cleanly", 0, result[1].asList().size)
        assertEquals("202 directives expected", 202, result[0].asList().size)
        file.delete()
    }

    private companion object {
        const val TAG = "ColdStart"

        /** How long `Python.start()` took, or −1 if something had already started it. */
        var startupMs: Long = -1

        @JvmStatic
        @BeforeClass
        fun startPython() {
            if (!Python.isStarted()) {
                val began = System.nanoTime()
                Python.start(
                    AndroidPlatform(
                        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                    ),
                )
                startupMs = (System.nanoTime() - began) / 1_000_000
            }
        }
    }
}
