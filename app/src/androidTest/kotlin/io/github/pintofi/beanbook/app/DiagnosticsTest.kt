package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The record a user can hand over.
 *
 * The app reported nothing anywhere and kept nothing, so a person who met a bug had nothing
 * to send. This checks the two halves of the answer: the app's own entries survive into the
 * report, and the report also carries what this process wrote to the system log — which is
 * where Chaquopy, Fava and every existing `Log` call in this app already write.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun install() {
        Diagnostics.install(context)
        Diagnostics.clear()
    }

    @Test fun whatTheAppRecordsReachesTheReport() {
        Diagnostics.i("DiagnosticsTest", "a thing the app did")
        val report = Diagnostics.report()

        assertTrue("the entry is missing: $report", report.contains("a thing the app did"))
        // The header answers the questions any report gets asked back.
        assertTrue("the app version is missing: $report", report.contains("Beanbook"))
        assertTrue("the platform version is missing", report.contains("Android "))
        assertTrue("the app log section is missing", report.contains("--- app log ---"))
    }

    @Test fun theSystemLogForThisProcessIsIncluded() {
        // Written the old way, by code that has never heard of Diagnostics -- which is the
        // case for Chaquopy, Fava, and every Log call already in this app.
        val marker = "marker-${System.nanoTime()}"
        android.util.Log.i("DiagnosticsTest", marker)
        Thread.sleep(500)

        val report = Diagnostics.report()
        assertTrue("this process's own log was not read: $report", report.contains(marker))
    }

    @Test fun aSecretWrittenByAnyoneIsRedactedOutOfTheReport() {
        // Through android.util.Log, so it arrives via the logcat path rather than the file:
        // the redaction has to cover what other code writes, not only our own calls.
        android.util.Log.i("DiagnosticsTest", "GET /x bca_token=SECRETVALUE123456")
        Diagnostics.i("DiagnosticsTest", "connecting to https://ann:hunter2@example.test/")
        Thread.sleep(500)

        val report = Diagnostics.report()
        assertFalse("the engine token reached the report", report.contains("SECRETVALUE123456"))
        assertFalse("a password reached the report", report.contains("hunter2"))
    }

    @Test fun clearingLeavesNothingBehind() {
        Diagnostics.i("DiagnosticsTest", "before clearing")
        Diagnostics.clear()
        assertFalse(Diagnostics.tail().any { it.contains("before clearing") })
    }
}
