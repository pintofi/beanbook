package io.github.pintofi.beanbook.core.data

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The stored form of the password for sharing on Wi-Fi.
 *
 * `pbkdf2_sha256$<iterations>$<salt hex>$<hash hex>` -- the format the engine's `sharing.py`
 * checks a sign-in against, so the password itself never has to leave the app process or be
 * written anywhere. Both sides are pinned to the same fixed vector by their tests.
 */
object SharingPassword {

    /**
     * Slow on purpose: it is what makes a copied hash expensive to guess. Below OWASP's current
     * figure for PBKDF2-HMAC-SHA256 (600,000), because this runs where the password is set and
     * has not been measured on a slow phone; a stored hash carries its own count, so raising it
     * later changes only passwords set after that.
     */
    const val ITERATIONS = 150_000

    fun hash(password: CharArray, salt: ByteArray = randomSalt(), iterations: Int = ITERATIONS): String {
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val digest = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return "pbkdf2_sha256$$iterations$${salt.hex()}$${digest.hex()}"
    }

    private fun randomSalt() = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
}
