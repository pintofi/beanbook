package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.remote.KeystoreSecret
import dev.beancount.app.remote.RemoteState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A remote password, kept where the app cannot leak it.
 *
 * The Keystore is Android-only, so this is the only place the real thing can be exercised —
 * and the properties tested are the ones that matter if a device is lost or a bug report is
 * filed: the password is not readable on disk, not in a log, and not in the app's own files
 * in any recoverable form.
 */
@RunWith(AndroidJUnit4::class)
class RemoteCredentialsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "remote-credentials-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private val password = "correct horse battery staple".toCharArray()

    @After fun cleanUp() {
        KeystoreSecret(File(directory, "remote-password"), ALIAS).destroyKey()
        directory.deleteRecursively()
    }

    private fun secret() = KeystoreSecret(File(directory, "remote-password"), ALIAS)

    @Test fun aStoredPasswordComesBackExactly() {
        secret().store(password)

        assertArrayEquals(password, secret().read())
    }

    @Test fun theStoredFileDoesNotContainThePassword() {
        secret().store(password)

        val onDisk = File(directory, "remote-password").readBytes()
        val asText = String(onDisk, Charsets.ISO_8859_1)

        assertFalse("the password is readable on disk", asText.contains("correct horse"))
        assertFalse("a fragment of the password is on disk", asText.contains("battery"))
        assertTrue("nothing was written at all", onDisk.size > 16)
    }

    @Test fun twoStoresOfTheSameSecretProduceDifferentBytes() {
        secret().store(password)
        val first = File(directory, "remote-password").readBytes()
        secret().store(password)
        val second = File(directory, "remote-password").readBytes()

        // A fresh nonce each time: identical ciphertext would tell an observer that the
        // password had not changed.
        assertFalse("the nonce is being reused", first.contentEquals(second))
    }

    @Test fun losingTheKeyMakesTheStoredPasswordUnreadableRatherThanCrashing() {
        secret().store(password)

        // What happens when the user removes their screen lock, or clears app data: the
        // Keystore entry goes and the ciphertext is left behind.
        KeystoreSecret(File(directory, "remote-password"), ALIAS).destroyKey()
        File(directory, "remote-password").writeBytes(ByteArray(64) { 9 })

        assertNull("an unreadable secret must read as absent", secret().read())
    }

    @Test fun forgettingAServerRemovesEverythingAboutIt() = runBlocking {
        val state = RemoteState(directory, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        state.connect("https://fava.invalid", "user", password.copyOf())
        withTimeout(20_000) { state.message.first { it != null } }

        state.forget()
        withTimeout(20_000) { state.message.first { it?.contains("removed from this device") == true } }

        assertNull(state.savedAddress)
        assertNull(state.savedUsername)
        assertFalse(
            "the password file outlived the server it belonged to",
            File(directory, "remote-password").exists(),
        )
    }

    @Test fun connectingToAnUnreachableServerSaysSoWithoutLoggingTheAddress() = runBlocking {
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        val state = RemoteState(directory, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        state.connect("https://ledger.private.invalid", "someone", password.copyOf())
        val message = withTimeout(30_000) { state.message.first { it != null } }

        assertTrue("expected a reachability failure, got: $message", message!!.isNotBlank())

        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
            .bufferedReader().readText()
        assertFalse("the password reached the log", log.contains("correct horse"))
        assertFalse("the server address reached the log", log.contains("ledger.private.invalid"))
    }

    @Test fun anHttpAddressIsAcceptedAndSavedExactlyAsTyped() = runBlocking {
        // Cleartext is allowed for LAN and VPN hosting. What must not happen is the scheme
        // being quietly rewritten: if the user typed http, the app connects to http, and if
        // they typed https it never silently becomes http.
        val state = RemoteState(directory, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        state.connect("http://192.168.213.4:5000", null, null)
        withTimeout(30_000) { state.message.first { it != null } }

        assertEquals("http://192.168.213.4:5000", state.savedAddress)
    }

    @Test fun anAddressWithAnUnusableSchemeIsRefusedAndNeverSaved() = runBlocking {
        val state = RemoteState(directory, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        state.connect("ftp://fava.example.com", null, null)
        val message = withTimeout(20_000) { state.message.first { it != null } }

        assertTrue("expected a refusal, got: $message", message!!.contains("not an address"))
        assertNull("a refused address must not be saved", state.savedAddress)
    }

    private companion object {
        const val ALIAS = "dev.beancount.test.credentials"
    }
}
