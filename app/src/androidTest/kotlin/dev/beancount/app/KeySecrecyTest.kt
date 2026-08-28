package dev.beancount.app

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.engine.EntitlementState
import dev.beancount.core.entitlement.Entitlements
import dev.beancount.core.entitlement.FileEntitlementStorage
import dev.beancount.core.entitlement.KeyCheck
import dev.beancount.core.entitlement.KeyVerifier
import dev.beancount.core.entitlement.Tier
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A key is the only secret this app holds. This is the evidence that it stays one
 * (CLAUDE.md: "No secrets in source, logs, backups, screenshots, or diagnostics").
 *
 * The key below was issued by the real issuance script against the real k1 signing key, to a
 * throwaway address. Applying it exercises the same path a buyer's key takes.
 */
@RunWith(AndroidJUnit4::class)
class KeySecrecyTest {

    private companion object {
        const val KEY =
            "BCA1.eyJ2IjoxLCJraWQiOiJrMSIsInRpZXIiOjMsImVtYWlsIjoiZGV2ZWxvcGVyQGV4YW1wbGUu" +
                "Y29tIiwib3JkZXIiOiJERVYtMSIsImlhdCI6IjIwMjYtMDgtMjQifQ.D5QUaSDP7N2zL2UXB0" +
                "lX00iJwHGCExlLx_BupvGq_Kx3h_bGxdClFiGFvt1k1nbmu8y4Wc1t-JXL4Oy_HQAiDw"
        const val EMAIL = "developer@example.com"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Writes a unique marker and returns it, so a later read can start from this moment.
     *
     * `logcat -c` is a request an app is not always allowed to make, and a test that assumes
     * it worked will read whatever an earlier test wrote and blame the app for it.
     */
    private fun mark(): String {
        val marker = "KeySecrecyTest-mark-" + java.util.UUID.randomUUID()
        android.util.Log.i("KeySecrecyTest", marker)
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        return marker
    }

    /** Everything logged after [marker], or the whole log if the marker is not in it. */
    private fun logSince(marker: String): String {
        val text = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
            .bufferedReader().readText()
        return text.substringAfter(marker, text)
    }

    private fun freshState(directory: File): EntitlementState {
        directory.deleteRecursively()
        return EntitlementState(
            Entitlements(FileEntitlementStorage(directory)),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    @Test fun applyingAKeyWritesNothingAboutItToTheLog() = runBlocking {
        // A clean slate, so anything found afterwards was written by this test's key. The
        // clear is attempted but not trusted: an app cannot always clear the system log, so
        // the read below is bounded by a marker written now (see [logSince]).
        val since = mark()

        val directory = File(context.filesDir, "entitlement-secrecy-test")
        val state = freshState(directory)
        state.applyKey(KEY)
        withTimeout(10_000) { state.entitlement.first { it.tier == Tier.POWER } }

        val log = logSince(since)

        val signature = KEY.substringAfterLast('.')
        assertFalse("the whole key was logged", log.contains(KEY))
        assertFalse("the key's signature was logged", log.contains(signature))
        assertFalse("the payload segment was logged", log.contains(KEY.split('.')[1]))
        assertFalse("the buyer's email was logged", log.contains(EMAIL))
        assertFalse("the order reference was logged", log.contains("DEV-1"))
    }

    @Test fun aRejectedKeyIsNotQuotedBackIntoTheLogEither() = runBlocking {
        val since = mark()

        val directory = File(context.filesDir, "entitlement-secrecy-rejected")
        val state = freshState(directory)
        val tampered = KEY.dropLast(4) + "AAAA"
        state.applyKey(tampered)
        withTimeout(10_000) { state.message.first { it != null } }

        val log = logSince(since)

        assertFalse("a rejected key was logged", log.contains(tampered))
        assertFalse("a rejected key's payload was logged", log.contains(KEY.split('.')[1]))
    }

    @Test fun theStoredKeyIsAppPrivateAndExcludedFromBackups() {
        val flags = context.applicationInfo.flags
        assertEquals(
            "the app must not be backed up, so a key cannot leave the device in one",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        // Where the key lives: app-private storage, and outside the portable project tree so
        // it can never be exported with a ledger.
        val entitlement = File(context.filesDir, "entitlement")
        assertTrue(
            "entitlement state must live outside projects/",
            !entitlement.absolutePath.contains("${File.separator}projects${File.separator}"),
        )
    }

    /**
     * The negative control. "The key was not in the log" is worth nothing unless something
     * that *is* in the log gets caught — an emulator that dropped this app's logs entirely
     * would otherwise make the two tests above pass while proving nothing.
     *
     * It logs a sentinel of the same shape rather than the key itself. Logging the real key
     * here made this class order-dependent and produced a false security failure on API 29:
     * `logcat -c` does not reliably clear the system log from an app, so whichever of these
     * tests ran after this one found the control's own leak and reported the app had written
     * the key (docs/milestones/m14-part17.md). A control that can fail its neighbours is not
     * a control.
     */
    @Test fun theLogCheckWouldCatchALeak() = runBlocking {
        val sentinel = "BCA1.control-" + java.util.UUID.randomUUID()
        android.util.Log.i("KeySecrecyTest", "deliberate leak for the control: $sentinel")

        val log = withTimeout(10_000) {
            var text = ""
            while (!text.contains(sentinel)) {
                text = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
                    .bufferedReader().readText()
            }
            text
        }

        assertTrue("the log check cannot see this app's logs at all", log.contains(sentinel))
    }

    @Test fun theVerifierAcceptsThisKeyOnDevice() {
        // The same check the app runs, on the device's own JVM: Ed25519 comes from a library
        // rather than the platform precisely because API 29 has none (docs/distribution.md 4.5).
        val check = KeyVerifier().check(KEY)
        assertTrue("a real key must verify on device, got $check", check is KeyCheck.Verified)
    }
}
