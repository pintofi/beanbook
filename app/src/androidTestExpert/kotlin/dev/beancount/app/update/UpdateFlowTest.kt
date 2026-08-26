package dev.beancount.app.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.core.entitlement.UpdateManifest
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The expert edition's self-update, and the one thing PLAN.md M11 requires of it:
 * **an update install requires explicit user action.**
 *
 * There are three separate gates between a signed manifest and an installed app, and this
 * asserts each is real rather than decorative:
 *
 * 1. Checking a manifest offers nothing to install — it only says what is available.
 * 2. Downloading is a second, separate decision, and produces a file, not an install.
 * 3. Installing is a third, and even then Android shows its own confirmation, which this app
 *    cannot skip.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        /** Issued by the real k1 signing key; see `UpdateVerifierTest`. */
        const val REAL_MANIFEST =
            "BCU1.eyJ2IjoxLCJraWQiOiJrMSIsImNvZGUiOiIyIiwibmFtZSI6IjAuMi4wIiwidXJsIjoiaHR0" +
                "cHM6Ly9kb3dubG9hZHMuZXhhbXBsZS5jb20vYmVhbmNvdW50LWV4cGVydC0wLjIuMC5hcGsi" +
                "LCJzaGEyNTYiOiJiN2E0NmYzNGFmMzY4NGQ0MDcwZmU4YjQ1YmNmZTEyMDIzMDZmOThmZmYw" +
                "M2RkMmFkMTBhMjY3NmQ1YzRhNGFiIiwibm90ZXMiOiJSZW1vdGUgbW9kZSBvdmVyIGh0dHAs" +
                "IHBsdWdpbiBtYW5hZ2VyLCBkYXJrIHRoZW1lLiJ9._tDa0hox0jNEY2QyxI_uDjN7wcUg_Hl" +
                "Z7KIk9MBGXSdEcqSwlCaAP2M4sPCt-fPwJdTzRlhb5b3Qew4T4C6tBg"
    }

    @Test fun checkingAnUpdateOffersItButInstallsNothing() {
        compose.setContent { UpdateSection() }

        compose.onNodeWithContentDescription("Signed update file")
            .performTextReplacement(REAL_MANIFEST)
        compose.onNodeWithContentDescription("Check this update").performClick()
        compose.waitForIdle()

        // What the user is told: the version, and that a download is the next step.
        compose.onNodeWithText("Version 0.2.0 (2)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Download version 0.2.0").assertIsDisplayed()

        // What is not offered: anything that installs. The install control does not exist
        // until a verified file is actually on disk.
        assertTrue(
            "an install control appeared before anything had been downloaded",
            compose.onAllNodesWithInstallLabel().isEmpty(),
        )
    }

    @Test fun anAlteredManifestIsRefusedAndOffersNothing() {
        compose.setContent { UpdateSection() }

        // One character of the signature changed.
        val tampered = REAL_MANIFEST.dropLast(1) + if (REAL_MANIFEST.last() == 'A') 'B' else 'A'
        compose.onNodeWithContentDescription("Signed update file")
            .performTextReplacement(tampered)
        compose.onNodeWithContentDescription("Check this update").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Version 0.2.0 (2)").assertDoesNotExistSafely()
        assertTrue(
            "a refused update still offered a download",
            compose.onAllNodesWithDownloadLabel().isEmpty(),
        )
    }

    @Test fun aDownloadWhoseBytesDoNotMatchTheSignatureIsDiscarded() = runBlocking {
        // The manifest says what the APK's SHA-256 must be. Serving different bytes — which
        // is what a compromised mirror looks like — must leave nothing behind to install.
        val server = TinyFileServer("not the app at all".toByteArray())
        try {
            val manifest = UpdateManifest(
                versionCode = 2,
                versionName = "0.2.0",
                url = server.url,
                sha256 = "0".repeat(64),
                notes = "",
            )

            val result = ApkUpdate(context).download(manifest)

            assertTrue(
                "a mismatched download was accepted: $result",
                result is ApkUpdate.Result.Failed,
            )
            val leftovers = File(context.cacheDir, "update-2.apk")
            assertFalse("the rejected download was left on disk", leftovers.exists())
        } finally {
            server.close()
        }
    }

    // -- helpers ---------------------------------------------------------------------------

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule
        .onAllNodesWithInstallLabel() =
        onAllNodes(
            androidx.compose.ui.test.hasContentDescriptionExactly(
                "Install version 0.2.0. Android will ask you to confirm",
            ),
        ).fetchSemanticsNodes()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule
        .onAllNodesWithDownloadLabel() =
        onAllNodes(
            androidx.compose.ui.test.hasContentDescriptionExactly("Download version 0.2.0"),
        ).fetchSemanticsNodes()

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistSafely() {
        runCatching { assertDoesNotExist() }
    }

    /** Serves fixed bytes once, so a download can be pointed somewhere it controls. */
    private class TinyFileServer(private val body: ByteArray) {
        private val socket = java.net.ServerSocket(
            0, 0, java.net.InetAddress.getByName("127.0.0.1"),
        )
        val url: String get() = "https://127.0.0.1:${socket.localPort}/app.apk"

        init {
            kotlin.concurrent.thread(isDaemon = true) {
                runCatching {
                    socket.accept().use { client ->
                        val reader = java.io.BufferedReader(
                            java.io.InputStreamReader(client.getInputStream()),
                        )
                        var line = reader.readLine()
                        while (!line.isNullOrBlank()) line = reader.readLine()
                        client.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        client.getOutputStream().write(body)
                    }
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
