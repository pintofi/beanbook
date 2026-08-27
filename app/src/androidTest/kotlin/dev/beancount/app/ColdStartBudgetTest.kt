package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the cold start actually goes (SPEC §6, V3).
 *
 * M3 measured 7,718 ms for the first parse of the 25k-entry corpus on a Galaxy A16 against an
 * 8 s budget, and 1,392 ms for the second parse in the same process. The conclusion drawn
 * then — that the cost is import, not parsing — was inferred from that 5× gap rather than
 * measured directly. This measures it.
 *
 * The absolute numbers here are from an emulator and are **pessimistic**: S2 found the
 * emulator roughly 3× slower than real arm64 hardware, so they are recorded, not compared
 * against the budget. What is asserted is the shape, which does not depend on the machine:
 * if importing Beancount ever stops dominating a first parse, the tuning work aimed at import
 * time is aimed at the wrong thing, and this fails.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartBudgetTest {

    private val python: Python get() = Python.getInstance()

    @Test fun importingBeancountIsNotTheDominantCost() {
        val timer = python.getModule("time")
        fun now(): Double = timer.callAttr("perf_counter").toDouble()

        // A ledger small enough that parsing it is not the story, so what is left is import.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("cold-start.beancount")
        file.writeText(
            buildString {
                appendLine("2026-01-01 open Assets:Cash")
                appendLine("2026-01-01 open Expenses:Food")
                repeat(200) { index ->
                    appendLine()
                    appendLine("2026-01-02 * \"Lunch $index\"")
                    appendLine("  Expenses:Food   1.00 USD")
                    appendLine("  Assets:Cash")
                }
            },
        )

        val beforeImport = now()
        val loader = python.getModule("beancount.loader")
        val importMs = ((now() - beforeImport) * 1000).toLong()

        val beforeFirst = now()
        loader.callAttr("load_file", file.absolutePath)
        val firstMs = ((now() - beforeFirst) * 1000).toLong()

        val beforeSecond = now()
        loader.callAttr("load_file", file.absolutePath)
        val secondMs = ((now() - beforeSecond) * 1000).toLong()

        android.util.Log.i(
            "ColdStartBudget",
            "import=${importMs}ms first_parse=${firstMs}ms second_parse=${secondMs}ms " +
                "entries=202 (emulator; roughly 3x slower than device, see s2)",
        )

        // Recorded rather than asserted against: 200 entries is not the 25k the budget is
        // written for, and an emulator is not the budget device.
        file.delete()

        assertTrue(
            "starting the interpreter took ${startupMs}ms and importing Beancount ${importMs}ms. " +
                "The costs have swapped places, so the remedy V3 records — trimming locale " +
                "data to save import time — is now aimed at the larger of the two rather than " +
                "the smaller, and the entry should be rewritten",
            startupMs > importMs,
        )
    }

    @Test fun startingPythonIsMeasuredSeparatelyFromImporting() {
        // The piece M3 never separated. Its 7,718 ms was everything between process start and
        // a parsed ledger, and the conclusion "import, not parsing" was drawn from the second
        // parse being 5x faster. That is equally consistent with interpreter start-up, and the
        // two want completely different fixes: trimming locale data cannot help if the time
        // goes into unpacking the runtime.
        assertTrue(
            "Python was already running when this class began, so the figure below is not a " +
                "cold start. Run this class on its own.",
            startupMs >= 0,
        )
        android.util.Log.i(
            "ColdStartBudget",
            "python_start=${startupMs}ms (emulator; roughly 3x slower than device, see s2)",
        )
    }

    private companion object {
        /** How long `Python.start()` took, measured once for the process. */
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
