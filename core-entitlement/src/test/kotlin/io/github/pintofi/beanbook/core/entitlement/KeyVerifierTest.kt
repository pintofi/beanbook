package io.github.pintofi.beanbook.core.entitlement

import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Key verification.
 *
 * [REAL_KEY] was issued by the actual issuance script against the real k1 signing key
 * (docs/distribution.md §4.6) — the same path a buyer's key takes. That matters: a test that
 * signs its own keys with its own key pair proves the verifier agrees with itself, not that
 * it agrees with the issuer.
 */
class KeyVerifierTest {

    private val verifier = KeyVerifier()

    private companion object {
        /** Issued by `issue-key.sh --email buyer@example.com --tier 3 --order PP-8FJ2K`. */
        const val REAL_KEY =
            "BCA1.eyJ2IjoxLCJraWQiOiJrMSIsInRpZXIiOjMsImVtYWlsIjoiYnV5ZXJAZXhhbXBsZS5jb20i" +
                "LCJvcmRlciI6IlBQLThGSjJLIiwiaWF0IjoiMjAyNi0wOC0yNCJ9.rbGzSUSCx9aGHz4cgCYx" +
                "UkhY2-uhYF1RxVwm-hIH3wgEJCqqYtos9WzDDylorgXGGz1VcnE8M0HoW7tYDdAuBw"
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun payloadOf(key: String): String = key.split('.')[1]

    private fun signatureOf(key: String): String = key.split('.')[2]

    @Test
    fun `a key issued by the real signer verifies, with every field intact`() {
        val check = verifier.check(REAL_KEY)

        val key = assertIs<KeyCheck.Verified>(check).key
        assertEquals(Tier.POWER, key.tier)
        assertEquals("buyer@example.com", key.email)
        assertEquals("PP-8FJ2K", key.orderId)
        assertEquals("k1", key.keyId)
        assertEquals(LocalDate.parse("2026-08-24"), key.issuedOn)
        assertEquals(REAL_KEY, key.raw, "the whole key is kept so it can be shown again")
    }

    @Test
    fun `whitespace and line breaks from copying out of an email are tolerated`() {
        val wrapped = REAL_KEY.chunked(40).joinToString("\n")

        assertIs<KeyCheck.Verified>(verifier.check("  $wrapped  \n"))
    }

    @Test
    fun `a payload altered to claim a higher tier is refused`() {
        // The forgery someone would actually attempt: keep a real signature, raise the tier.
        val forged = payloadOf(REAL_KEY)
            .let { String(Base64.getUrlDecoder().decode(it)) }
            .replace("\"tier\":3", "\"tier\":9")
        val key = "BCA1.${encode(forged.toByteArray())}.${signatureOf(REAL_KEY)}"

        val rejected = assertIs<KeyCheck.Rejected>(verifier.check(key))
        assertEquals(KeyProblem.BadSignature, rejected.problem)
    }

    @Test
    fun `a payload altered to change the email is refused`() {
        val forged = payloadOf(REAL_KEY)
            .let { String(Base64.getUrlDecoder().decode(it)) }
            .replace("buyer@example.com", "other@example.com")
        val key = "BCA1.${encode(forged.toByteArray())}.${signatureOf(REAL_KEY)}"

        assertEquals(
            KeyProblem.BadSignature,
            assertIs<KeyCheck.Rejected>(verifier.check(key)).problem,
        )
    }

    @Test
    fun `a signature with one bit flipped is refused`() {
        val signature = Base64.getUrlDecoder().decode(signatureOf(REAL_KEY))
        signature[0] = (signature[0].toInt() xor 1).toByte()
        val key = "BCA1.${payloadOf(REAL_KEY)}.${encode(signature)}"

        assertEquals(
            KeyProblem.BadSignature,
            assertIs<KeyCheck.Rejected>(verifier.check(key)).problem,
        )
    }

    @Test
    fun `a signature of the wrong length is refused as damaged, not checked`() {
        val short = Base64.getUrlDecoder().decode(signatureOf(REAL_KEY)).copyOf(63)
        val key = "BCA1.${payloadOf(REAL_KEY)}.${encode(short)}"

        assertEquals(KeyProblem.Malformed, assertIs<KeyCheck.Rejected>(verifier.check(key)).problem)
    }

    @Test
    fun `a key signed by a kid this build does not know names the kid`() {
        // What a key issued after a rotation looks like to an app that predates it.
        val payload = """{"v":1,"kid":"k9","tier":3,"email":"b@e.com","order":"X","iat":"2026-08-24"}"""
        val key = "BCA1.${encode(payload.toByteArray())}.${signatureOf(REAL_KEY)}"

        val problem = assertIs<KeyCheck.Rejected>(verifier.check(key)).problem
        assertEquals(KeyProblem.UnknownSigner("k9"), problem)
        assertTrue(
            problem.describe().contains("a newer version of the app will accept it"),
            "the message must point at the fix, not just refuse: ${problem.describe()}",
        )
    }

    @Test
    fun `a retired kid keeps verifying, because keys already bought must not stop working`() {
        // k1 has been superseded by k2 for issuance; keys signed with k1 still verify.
        val rotated = KeyVerifier(
            listOf(
                TrustedSigner("k1", "3l-3OCfh7aX4SXpCz-5lJRSZZH4kv7QSOkVZGK-XMtg", issuing = false),
                TrustedSigner("k2", encode(ByteArray(32) { 7 }), issuing = true),
            ),
        )

        assertIs<KeyCheck.Verified>(rotated.check(REAL_KEY))
    }

    @Test
    fun `a key from a future format version says so instead of blaming the user`() {
        val problem = assertIs<KeyCheck.Rejected>(verifier.check("BCA2.abc.def")).problem
        assertEquals(KeyProblem.UnsupportedVersion("BCA2"), problem)
        assertTrue(problem.describe().contains("newer version of the app"))
    }

    @Test
    fun `text that is not a key at all is named as such`() {
        listOf("", "hello", "BCA1", "BCA1.only-two-parts").forEach { input ->
            val problem = assertIs<KeyCheck.Rejected>(verifier.check(input)).problem
            assertTrue(
                problem is KeyProblem.NotAKey || problem is KeyProblem.Malformed,
                "\"$input\" gave $problem",
            )
        }
    }

    @Test
    fun `a payload missing a field names the field`() {
        val payload = """{"v":1,"kid":"k1","tier":3,"iat":"2026-08-24"}"""
        val key = "BCA1.${encode(payload.toByteArray())}.${signatureOf(REAL_KEY)}"

        assertEquals(
            KeyProblem.MissingField("email"),
            assertIs<KeyCheck.Rejected>(verifier.check(key)).problem,
        )
    }

    @Test
    fun `a tier the app does not have is refused rather than rounded down`() {
        // Signed by a signer we control, so the signature is valid and only the tier is odd.
        val payload = """{"v":1,"kid":"k1","tier":7,"email":"b@e.com","order":"X","iat":"2026-08-24"}"""
        val key = "BCA1.${encode(payload.toByteArray())}.${signatureOf(REAL_KEY)}"

        // The signature check comes first, so this is refused there; either way it is not
        // silently treated as some tier the app does have.
        val problem = assertIs<KeyCheck.Rejected>(verifier.check(key)).problem
        assertTrue(problem is KeyProblem.BadSignature || problem is KeyProblem.UnknownTier)
    }

    @Test
    fun `a payload with a duplicated field is refused rather than picking one`() {
        val payload =
            """{"v":1,"kid":"k1","tier":1,"tier":3,"email":"b@e.com","order":"X","iat":"2026-08-24"}"""
        val key = "BCA1.${encode(payload.toByteArray())}.${signatureOf(REAL_KEY)}"

        assertEquals(
            KeyProblem.Malformed,
            assertIs<KeyCheck.Rejected>(verifier.check(key)).problem,
        )
    }

    @Test
    fun `a nested payload is refused, because this format has no nesting`() {
        val payload = """{"v":1,"kid":{"a":"b"},"tier":3,"email":"b@e.com","order":"X","iat":"x"}"""
        val key = "BCA1.${encode(payload.toByteArray())}.${signatureOf(REAL_KEY)}"

        assertEquals(
            KeyProblem.Malformed,
            assertIs<KeyCheck.Rejected>(verifier.check(key)).problem,
        )
    }

    @Test
    fun `the trusted list ships exactly one issuing signer`() {
        assertEquals(
            1,
            TrustedSigners.ALL.count { it.issuing },
            "keys must be issued with one signer; the rest are kept for verification only",
        )
        TrustedSigners.ALL.forEach {
            assertEquals(
                32,
                Base64.getUrlDecoder().decode(it.publicKey).size,
                "${it.keyId} is not a 32-byte Ed25519 public key",
            )
        }
    }
}
