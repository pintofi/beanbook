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

    private fun freshState(directory: File): EntitlementState {
        directory.deleteRecursively()
        return EntitlementState(
            Entitlements(FileEntitlementStorage(directory)),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    @Test fun applyingAKeyWritesNothingAboutItToTheLog() = runBlocking {
        // A clean slate, so anything found afterwards was written by this test's key.
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

        val directory = File(context.filesDir, "entitlement-secrecy-test")
        val state = freshState(directory)
        state.applyKey(KEY)
        withTimeout(10_000) { state.entitlement.first { it.tier == Tier.POWER } }

        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
            .bufferedReader().readText()

        val signature = KEY.substringAfterLast('.')
        assertFalse("the whole key was logged", log.contains(KEY))
        assertFalse("the key's signature was logged", log.contains(signature))
        assertFalse("the payload segment was logged", log.contains(KEY.split('.')[1]))
        assertFalse("the buyer's email was logged", log.contains(EMAIL))
        assertFalse("the order reference was logged", log.contains("DEV-1"))
    }

    @Test fun aRejectedKeyIsNotQuotedBackIntoTheLogEither() = runBlocking {
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

        val directory = File(context.filesDir, "entitlement-secrecy-rejected")
        val state = freshState(directory)
        val tampered = KEY.dropLast(4) + "AAAA"
        state.applyKey(tampered)
        withTimeout(10_000) { state.message.first { it != null } }

        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
            .bufferedReader().readText()

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
     * The negative control. "The key was not in the log" is worth nothing unless a key that
     * *is* in the log gets caught — an emulator that dropped this app's logs entirely would
     * otherwise make the two tests above pass while proving nothing.
     */
    @Test fun theLogCheckWouldCatchALeak() = runBlocking {
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        android.util.Log.i("KeySecrecyTest", "deliberate leak for the control: $KEY")

        val log = withTimeout(10_000) {
            var text = ""
            while (!text.contains(KEY)) {
                text = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
                    .bufferedReader().readText()
            }
            text
        }

        assertTrue("the log check cannot see this app's logs at all", log.contains(KEY))
    }

    @Test fun theVerifierAcceptsThisKeyOnDevice() {
        // The same check the app runs, on the device's own JVM: Ed25519 comes from a library
        // rather than the platform precisely because API 28 has none (docs/distribution.md 4.5).
        val check = KeyVerifier().check(KEY)
        assertTrue("a real key must verify on device, got $check", check is KeyCheck.Verified)
    }
}
