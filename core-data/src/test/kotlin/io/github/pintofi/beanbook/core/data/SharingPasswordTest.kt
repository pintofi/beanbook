package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharingPasswordTest {

    /**
     * the same vector `test_sharing.py` pins, computed by Python's hashlib. If the two
     * sides ever disagree, no password would ever sign in, and this is where it shows.
     */
    @Test
    fun `matches the engine's hash byte for byte`() {
        val salt = ByteArray(16) { it.toByte() }

        val stored = SharingPassword.hash("pw".toCharArray(), salt, iterations = 1000)

        assertEquals(
            "pbkdf2_sha256$1000$000102030405060708090a0b0c0d0e0f$" +
                "69ebe085b7b1cc2dc10399570936a6f9b825889902f62730b9759c08a6718f8d",
            stored,
        )
    }

    @Test
    fun `a fresh salt every time, so the same password is never stored the same way`() {
        val one = SharingPassword.hash("same password".toCharArray(), iterations = 1000)
        val two = SharingPassword.hash("same password".toCharArray(), iterations = 1000)

        assertNotEquals(one, two)
        assertTrue(one.startsWith("pbkdf2_sha256$1000$"))
    }

    @Test
    fun `agrees with the platform's PBKDF2 for any password and count`() {
        listOf("pw" to 1, "correct horse" to 1000, "pässwörd €" to 4096, "" to 2).forEach { (password, rounds) ->
            val salt = ByteArray(16) { (it * 7).toByte() }
            val reference = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded
                .joinToString("") { "%02x".format(it) }
            val stored = SharingPassword.hash(password.toCharArray(), salt, rounds)
            assertEquals(reference, stored.substringAfterLast('$'), "'$password' at $rounds rounds")
        }
    }

    @Test
    fun `new passwords use the current OWASP count`() {
        assertEquals(600_000, SharingPassword.ITERATIONS)
    }
}
