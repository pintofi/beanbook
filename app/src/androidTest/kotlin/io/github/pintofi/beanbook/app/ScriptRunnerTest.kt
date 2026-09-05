package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.plugins.ScriptRunnerState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The script runner, from the app's side (N10).
 *
 * The engine's own tests cover `run_script`; these cover the part that did not exist — that
 * the app can actually reach it, that output comes back, and that a script which will not
 * stop is reported honestly rather than silently abandoned.
 *
 * Everything goes through the `:engine` process, so this also exercises the AIDL path that
 * the R9 split makes the only route to Python.
 */
@RunWith(AndroidJUnit4::class)
class ScriptRunnerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun runner(): ScriptRunnerState =
        ScriptRunnerState(context, currentPluginPolicy(), scope).apply {
            onProjectOpened(ledger())
        }

    private fun ledger(): File =
        File(context.cacheDir, "script-runner/main.beancount").apply {
            parentFile?.mkdirs()
            writeText("2026-01-01 open Assets:Cash\n")
        }

    private fun script(name: String, body: String): android.net.Uri {
        val file = File(context.cacheDir, "script-runner/$name").apply {
            parentFile?.mkdirs()
            writeText(body)
        }
        return android.net.Uri.fromFile(file)
    }

    @Test fun aScriptRunsAndItsOutputComesBack() = runBlocking {
        val state = runner()
        state.runFrom(
            script("hello.py", "print('ledger is', ledger_path)\nprint(2 + 2)\n"),
            "hello.py",
        )

        val outcome = withTimeout(60_000) { state.outcome.first { it != null } }!!

        assertTrue("the script failed: ${outcome.summary()}", outcome.ok)
        assertTrue("the ledger path was not passed in: ${outcome.output}",
            outcome.output.contains("main.beancount"))
        assertTrue("stdout was not captured: ${outcome.output}", outcome.output.contains("4"))
        assertFalse(outcome.timedOut)
        assertFalse(outcome.cancelled)
    }

    @Test fun aScriptThatFailsReportsWhyRatherThanJustFailing() = runBlocking {
        val state = runner()
        state.runFrom(script("broken.py", "raise ValueError('no good')\n"), "broken.py")

        val outcome = withTimeout(60_000) { state.outcome.first { it != null } }!!

        assertFalse(outcome.ok)
        assertEquals("ValueError", outcome.errorType)
        assertTrue("the message was lost: ${outcome.error}", outcome.error.contains("no good"))
    }

    @Test fun aScriptThatWillNotEndIsStoppedAndSaidToBeStopped() = runBlocking {
        val state = runner()
        // A loop with no exit, which is the case the deadline exists for and the one the
        // trace-based stop can actually interrupt.
        state.runFrom(script("forever.py", "while True:\n    pass\n"), "forever.py", 3.0)

        val outcome = withTimeout(90_000) { state.outcome.first { it != null } }!!

        assertTrue("a script that never ends should not report success", !outcome.ok)
        assertTrue("it was not reported as out of time: ${outcome.summary()}", outcome.timedOut)
        assertTrue(
            "the summary does not tell the user what happened: ${outcome.summary()}",
            outcome.summary().contains("time", ignoreCase = true),
        )
    }

    @Test fun aSecondScriptIsRefusedWhileOneIsRunning() = runBlocking {
        val state = runner()
        state.runFrom(script("slow.py", "import time\ntime.sleep(4)\n"), "slow.py", 20.0)
        withTimeout(30_000) { state.running.first { it != null } }

        state.runFrom(script("hello.py", "print('hi')\n"), "hello.py")
        val message = withTimeout(10_000) { state.message.first { it != null } }

        assertTrue("expected a refusal, got: $message", message!!.contains("already running"))
        withTimeout(60_000) { state.outcome.first { it != null } }
        Unit
    }
}
