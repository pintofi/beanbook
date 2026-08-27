package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two wheels this project cross-builds itself, checked where it counts.
 *
 * `beancount` (C++) and `watchfiles` (Rust) have no Android wheels upstream, so they are
 * built by `tools/build-wheels.sh` and are the only parts of the Python closure whose
 * compilation this repository is responsible for. Everything else is either pure Python or
 * a prebuilt wheel from Chaquopy.
 *
 * That makes them the thing that quietly breaks when the build is changed — a new NDK, a
 * different backend version, a remapped path. Until M14 nothing on a device touched
 * `watchfiles`' native watcher at all: the spike that first proved it works
 * (docs/feasibility/s3-fava-closure.md) is disposable code that no longer runs.
 *
 * These tests import the extension modules and use them, rather than asserting that a file
 * exists. A wheel can be present, correctly named, and still fail at `import`.
 */
@RunWith(AndroidJUnit4::class)
class NativeWheelsTest {

    private val python: Python get() = Python.getInstance()

    @Test fun beancountsParserIsACompiledExtensionAndParsesADecimalAmount() {
        val parser = python.getModule("beancount.parser._parser")
        val origin = parser.get("__file__")?.toString().orEmpty()

        assertTrue(
            "beancount's parser is not a compiled extension here: $origin",
            origin.endsWith(".so"),
        )

        // The parser is only interesting if it parses. Invariant 1 is checked at the same
        // time: an amount that would round under binary floating point must stay exact.
        val loader = python.getModule("beancount.loader")
        val result = loader.callAttr(
            "load_string",
            """
            2026-01-01 open Assets:Cash
            2026-01-01 open Expenses:Food

            2026-01-02 * "Lunch"
              Expenses:Food   0.10 USD
              Expenses:Food   0.20 USD
              Assets:Cash
            """.trimIndent(),
        ).asList()

        val errors = result[1].asList()
        assertTrue("the ledger did not parse cleanly: $errors", errors.isEmpty())

        val entries = result[0].asList()
        val transaction = entries.last()
        val postings = transaction.get("postings")!!.asList()
        val total = postings[0].get("units")!!.get("number")!!
            .callAttr("__add__", postings[1].get("units")!!.get("number")!!)

        assertEquals("0.30", total.toString())
        assertEquals(
            "the amount is not a Decimal, so invariant 1 is not being kept by the parser",
            "decimal.Decimal",
            total.type().get("__module__").toString() + "." + total.type().get("__name__"),
        )
    }

    @Test fun watchfilesNativeWatcherLoadsRatherThanFallingBackToPolling() {
        // Fava falls back to a pure-Python poller when this import fails, so a broken wheel
        // does not crash anything — it just makes the app quietly slower and warmer, which
        // is exactly the kind of regression that goes unnoticed without a test.
        val notify = python.getModule("watchfiles._rust_notify")
        val origin = notify.get("__file__")?.toString().orEmpty()

        assertTrue(
            "watchfiles' native watcher is not a compiled extension here: $origin",
            origin.endsWith(".so"),
        )

        val directory = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir.resolve("watchfiles-probe")
            .apply { deleteRecursively(); mkdirs() }

        // Constructing the watcher is what actually enters the Rust code and asks the
        // kernel for an inotify handle; importing the module alone would not.
        // PyO3 checks for a real Python sequence. A Kotlin list arrives as a Java proxy
        // that Python will not even iterate, so the list is built on the Python side.
        val paths = python.builtins.callAttr("list")
        paths.callAttr("append", directory.absolutePath)
        val watcher = notify.callAttr("RustNotify", paths, false, false, 300, true, false)
        try {
            // An untouched directory must not produce an event, or "it works" would be
            // indistinguishable from a watcher that reports constantly.
            assertEquals(
                "the watcher reported a change in a directory nothing had touched",
                "timeout",
                watcher.callAttr("watch", 50, 50, 200, null).toString(),
            )

            // And then the property that actually matters: a real write is seen. If this
            // fails, Fava silently falls back to polling and the app gets slower and warmer
            // with no error anywhere.
            directory.resolve("ledger.beancount").writeText("2026-01-01 open Assets:Cash\n")

            val change = watcher.callAttr("watch", 50, 50, 5_000, null).toString()
            assertFalse("the native watcher never saw the file being written", change == "timeout")
            assertTrue("unexpected watcher result: $change", change.contains("ledger.beancount"))
        } finally {
            watcher.callAttr("close")
            directory.deleteRecursively()
        }
    }

    @Test fun beangulpIdentifiesStatementsWithoutLibmagic() {
        // V5. `python-magic` is a ctypes wrapper around libmagic, and libmagic is not in this
        // APK — nothing builds it for Android. So `import magic` fails on device, which is
        // the *expected* outcome rather than a defect: beangulp declares python-magic as an
        // install dependency but uses it optionally, behind
        // `except (ImportError, OSError): magic = None`, and falls back to identifying files
        // by extension. This asserts that contract end to end, so that a future beangulp
        // which hard-depends on magic fails here rather than in a user's import.
        val importFailed = try {
            python.getModule("magic")
            false
        } catch (expected: com.chaquo.python.PyException) {
            assertTrue(
                "python-magic failed for some reason other than the missing library: " +
                    "${expected.message}",
                expected.message.orEmpty().contains("libmagic"),
            )
            true
        }
        assertTrue(
            "libmagic is present after all — this test's premise no longer holds, and " +
                "content sniffing should be checked rather than the fallback",
            importFailed,
        )

        // The fallback, doing the job the importers actually need: a statement's type from
        // its name. `beangulp.mimetypes` is the current module; `file_type` is deprecated
        // upstream and is the one that reaches for magic.
        val mimetypes = python.getModule("beangulp.mimetypes")
        listOf(
            "statement.csv" to "text/csv",
            "statement.pdf" to "application/pdf",
            "statement.ofx" to "ofx",
        ).forEach { (name, expected) ->
            val guessed = mimetypes.callAttr("guess_type", name, false).asList()[0].toString()
            assertTrue(
                "beangulp could not identify $name without libmagic, it said: $guessed",
                guessed.contains(expected, ignoreCase = true),
            )
        }
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun startPython() {
            if (!Python.isStarted()) {
                Python.start(
                    AndroidPlatform(
                        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                    ),
                )
            }
        }
    }
}
