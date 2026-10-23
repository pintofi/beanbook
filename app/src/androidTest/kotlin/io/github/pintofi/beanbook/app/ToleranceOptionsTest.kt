package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.DraftProblem
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import io.github.pintofi.beanbook.engine.local.LedgerReader
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two options that change Beancount's balance tolerance reach the form (#50): the engine reports
 * them, the reader keeps them, and the entry check then follows the ledger.
 */
@RunWith(AndroidJUnit4::class)
class ToleranceOptionsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = LedgerReader(context)
    private val dir = File(context.cacheDir, "tolopts-${System.nanoTime()}")

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun ledger(options: String): File {
        dir.mkdirs()
        return File(dir, "main.beancount").apply {
            writeText(
                options + "\n" +
                    "2026-01-01 open Assets:A\n2026-01-01 open Equity:O\n" +
                    "2026-01-02 * \"x\"\n  Assets:A  10.00 USD\n  Equity:O\n",
            )
        }
    }

    @Test fun aLedgerWithNeitherOptionHasBeancountsDefaults() {
        val format = runBlocking { reader.read(ledger("").absolutePath) }.getOrThrow().format

        assertEquals(0, BigDecimal("0.5").compareTo(format.toleranceMultiplier))
        assertFalse(format.inferToleranceFromCost)
    }

    @Test fun aLedgerThatSetsThemIsReadAsSet() {
        val options = "option \"tolerance_multiplier\" \"1.0\"\noption \"infer_tolerance_from_cost\" \"TRUE\""
        val format = runBlocking { reader.read(ledger(options).absolutePath) }.getOrThrow().format

        assertEquals(0, BigDecimal("1.0").compareTo(format.toleranceMultiplier))
        assertTrue(format.inferToleranceFromCost)
    }

    /** Beancount's own example: 2 x 18.572 VWELX {30.96 USD} against -1150.00 USD, which leaves 0.02176. */
    private fun vwelx() = TransactionDraft(
        date = "2026-01-02",
        postings = listOf(
            PostingDraft("Assets:A", Amount.parse("18.572", "VWELX"), cost = Cost(Amount.parse("30.96", "USD"))),
            PostingDraft("Assets:A", Amount.parse("18.572", "VWELX"), cost = Cost(Amount.parse("30.96", "USD"))),
            PostingDraft("Assets:B", Amount.parse("-1150.00", "USD")),
        ),
    )

    @Test fun theEntryCheckFollowsWhatTheEngineReported() {
        val off = runBlocking { reader.read(ledger("").absolutePath) }.getOrThrow().format
        val on = runBlocking {
            reader.read(ledger("option \"infer_tolerance_from_cost\" \"TRUE\"").absolutePath)
        }.getOrThrow().format

        assertTrue(
            "off: the 0.02176 residual is past 0.005",
            vwelx().validate(off).any { it is DraftProblem.DoesNotBalance },
        )
        assertFalse(
            "on: the same entry is within the widened tolerance, as Beancount says",
            vwelx().validate(on).any { it is DraftProblem.DoesNotBalance },
        )
    }
}
