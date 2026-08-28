package dev.beancount.core.entitlement

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A pasted key is untrusted text, and reaches the parser before any signature check (R9).
 *
 * `parseFlatObject` decodes `\u` escapes with `hex.toInt(16)`. The length is guarded; the
 * characters are not, so a non-hex escape reached `NumberFormatException`. The user pastes
 * whatever is on their clipboard, so this is the first thing the entitlement layer sees.
 */
class HostileKeyTest {

    private fun keyWith(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload = encoder.encodeToString(payloadJson.toByteArray())
        val signature = encoder.encodeToString(ByteArray(64))
        return "BCA1.$payload.$signature"
    }

    @Test fun `a non-hex unicode escape is rejected, not thrown`() {
        val check = KeyVerifier().check(keyWith("""{"v":1,"kid":"k1","tier":3,"email":"\uZZZZ"}"""))
        assertTrue(check is KeyCheck.Rejected, "got $check")
    }

    @Test fun `a truncated unicode escape is rejected, not thrown`() {
        val check = KeyVerifier().check(keyWith("""{"v":1,"kid":"k1","tier":3,"email":"\u00"}"""))
        assertTrue(check is KeyCheck.Rejected, "got $check")
    }

    @Test fun `arbitrary clipboard text is rejected, not thrown`() {
        val check = KeyVerifier().check("not a key at all")
        assertTrue(check is KeyCheck.Rejected, "got $check")
    }
}
