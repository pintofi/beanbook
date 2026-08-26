package dev.beancount.core.entitlement

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Update manifests, verified offline against the same trusted keys as contribution keys.
 *
 * [REAL_MANIFEST] was produced by the actual issuance script against the real k1 signing
 * key, exactly as a released update would be. A test that signs its own manifests with its
 * own key pair proves the verifier agrees with itself, which is not the question.
 */
class UpdateVerifierTest {

    private val verifier = UpdateVerifier()

    private companion object {
        /**
         * `issue-update.sh --code 2 --name 0.2.0 --url … --sha256 … --notes …`
         */
        const val REAL_MANIFEST =
            "BCU1.eyJ2IjoxLCJraWQiOiJrMSIsImNvZGUiOiIyIiwibmFtZSI6IjAuMi4wIiwidXJsIjoiaHR0" +
                "cHM6Ly9kb3dubG9hZHMuZXhhbXBsZS5jb20vYmVhbmNvdW50LWV4cGVydC0wLjIuMC5hcGsi" +
                "LCJzaGEyNTYiOiJiN2E0NmYzNGFmMzY4NGQ0MDcwZmU4YjQ1YmNmZTEyMDIzMDZmOThmZmYw" +
                "M2RkMmFkMTBhMjY3NmQ1YzRhNGFiIiwibm90ZXMiOiJSZW1vdGUgbW9kZSBvdmVyIGh0dHAs" +
                "IHBsdWdpbiBtYW5hZ2VyLCBkYXJrIHRoZW1lLiJ9._tDa0hox0jNEY2QyxI_uDjN7wcUg_Hl" +
                "Z7KIk9MBGXSdEcqSwlCaAP2M4sPCt-fPwJdTzRlhb5b3Qew4T4C6tBg"

        /** A real contribution key, from `KeyVerifierTest`. Same signer, different purpose. */
        const val REAL_CONTRIBUTION_KEY =
            "BCA1.eyJ2IjoxLCJraWQiOiJrMSIsInRpZXIiOjMsImVtYWlsIjoiYnV5ZXJAZXhhbXBsZS5jb20i" +
                "LCJvcmRlciI6IlBQLThGSjJLIiwiaWF0IjoiMjAyNi0wOC0yNCJ9.rbGzSUSCx9aGHz4cgCYx" +
                "UkhY2-uhYF1RxVwm-hIH3wgEJCqqYtos9WzDDylorgXGGz1VcnE8M0HoW7tYDdAuBw"
    }

    private fun segments(manifest: String) = manifest.split(".")

    @Test
    fun `a manifest issued by the real signer is offered, with every field intact`() {
        val check = verifier.check(REAL_MANIFEST, installedVersionCode = 1)

        val offered = assertIs<UpdateCheck.Offered>(check).manifest
        assertEquals(2L, offered.versionCode)
        assertEquals("0.2.0", offered.versionName)
        assertEquals(
            "https://downloads.example.com/beancount-expert-0.2.0.apk",
            offered.url,
        )
        assertEquals(64, offered.sha256.length)
        assertTrue(offered.notes.contains("plugin manager"), offered.notes)
    }

    // ---- domain separation ---------------------------------------------------------------

    @Test
    fun `a contribution key cannot be presented as an update`() {
        // Both are signed by the same key pair. Without a separate prefix — and a signed
        // message that includes it — a signature obtained for one could be offered as the
        // other, and this app would install whatever a key-holder pointed it at.
        val check = verifier.check(REAL_CONTRIBUTION_KEY, installedVersionCode = 1)

        assertIs<UpdateProblem.NotAManifest>(assertIs<UpdateCheck.Rejected>(check).problem)
    }

    @Test
    fun `a manifest cannot be turned into one by relabelling a contribution key`() {
        // Swap the prefix and nothing else: the payload and signature are genuine, and the
        // signature still does not cover this message.
        val relabelled = "BCU1." + segments(REAL_CONTRIBUTION_KEY).drop(1).joinToString(".")

        val check = verifier.check(relabelled, installedVersionCode = 1)

        assertIs<UpdateProblem.BadSignature>(assertIs<UpdateCheck.Rejected>(check).problem)
    }

    // ---- tampering -------------------------------------------------------------------------

    @Test
    fun `changing the download URL invalidates the signature`() {
        // The attack this exists to stop: keep a genuine signature, point the download
        // somewhere else.
        val fields = String(Base64.getUrlDecoder().decode(segments(REAL_MANIFEST)[1]))
        val hostile = fields.replace(
            "https://downloads.example.com/beancount-expert-0.2.0.apk",
            "https://evil.example.com/not-really-the-app.apk",
        )
        val repacked = "BCU1." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(hostile.toByteArray()) +
            "." + segments(REAL_MANIFEST)[2]

        val check = verifier.check(repacked, installedVersionCode = 1)

        assertIs<UpdateProblem.BadSignature>(assertIs<UpdateCheck.Rejected>(check).problem)
    }

    @Test
    fun `a manifest signed by a key this app does not trust is refused`() {
        val fields = String(Base64.getUrlDecoder().decode(segments(REAL_MANIFEST)[1]))
        val other = fields.replace("\"kid\":\"k1\"", "\"kid\":\"someone-else\"")
        val repacked = "BCU1." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(other.toByteArray()) +
            "." + segments(REAL_MANIFEST)[2]

        val check = verifier.check(repacked, installedVersionCode = 1)

        assertIs<UpdateProblem.UnknownSigner>(assertIs<UpdateCheck.Rejected>(check).problem)
    }

    @Test
    fun `a damaged manifest is refused rather than throwing`() {
        listOf(
            "",
            "not a manifest",
            "BCU1.",
            "BCU1.@@@@.@@@@",
            "BCU1.aGk.aGk",
            REAL_MANIFEST.dropLast(4),
        ).forEach { input ->
            assertIs<UpdateCheck.Rejected>(
                verifier.check(input, installedVersionCode = 1),
                "accepted: $input",
            )
        }
    }

    // ---- what it may offer ------------------------------------------------------------------

    @Test
    fun `the same version is not offered again`() {
        val check = verifier.check(REAL_MANIFEST, installedVersionCode = 2)

        val problem = assertIs<UpdateProblem.NotNewer>(
            assertIs<UpdateCheck.Rejected>(check).problem,
        )
        assertEquals(2L, problem.offered)
    }

    @Test
    fun `a downgrade is refused, however well signed it is`() {
        // Reintroducing a fixed bug on purpose is a plausible thing to want to do to
        // somebody, and a valid signature is no argument against it.
        val check = verifier.check(REAL_MANIFEST, installedVersionCode = 7)

        assertIs<UpdateProblem.NotNewer>(assertIs<UpdateCheck.Rejected>(check).problem)
        assertTrue(
            assertIs<UpdateCheck.Rejected>(check).problem.describe().contains("newer"),
        )
    }

    @Test
    fun `every refusal explains itself in terms a person can act on`() {
        listOf(
            UpdateProblem.UnknownSigner,
            UpdateProblem.BadSignature,
            UpdateProblem.NotHttps,
            UpdateProblem.NotNewer(1, 2),
        ).forEach { problem ->
            val text = problem.describe()
            assertTrue(text.length > 20, "too terse to act on: $text")
            assertTrue(text.first().isUpperCase(), "not a sentence: $text")
        }
    }
}
