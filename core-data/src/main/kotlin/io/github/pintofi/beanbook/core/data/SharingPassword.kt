package io.github.pintofi.beanbook.core.data

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The stored form of the password for sharing on Wi-Fi.
 *
 * `pbkdf2_sha256$<iterations>$<salt hex>$<hash hex>` -- the format the engine's `sharing.py`
 * checks a sign-in against, so the password itself never has to leave the app process or be
 * written anywhere. Both sides are pinned to the same fixed vector by their tests.
 */
object SharingPassword {

    /**
     * Slow on purpose: it is what makes a copied hash expensive to guess. OWASP's current figure
     * for PBKDF2-HMAC-SHA256. A stored hash carries its own count, so hashes made with fewer
     * rounds (150,000, before #56) still sign in; only passwords set after this use the new count.
     *
     * It takes seconds on a phone, so it runs where the password is set, off the main thread.
     */
    const val ITERATIONS = 600_000

    fun hash(password: CharArray, salt: ByteArray = randomSalt(), iterations: Int = ITERATIONS): String {
        require(iterations > 0) { "iterations must be positive" }
        val key = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)).let {
            ByteArray(it.remaining()).also { bytes -> it.get(bytes) }
        }
        val digest = try {
            pbkdf2(key, salt, iterations)
        } finally {
            key.fill(0)
        }
        return "pbkdf2_sha256$$iterations$${salt.hex()}$${digest.hex()}"
    }

    /**
     * PBKDF2-HMAC-SHA256 for one 32-byte block, the length of the hash. The platform's own
     * `PBKDF2WithHmacSHA256` does the same sum about twice as slowly on Android, where it is
     * written in Java; this keeps one keyed `Mac` and asks it for each round. The test pins it
     * to the platform's answer.
     */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC pads its key with zeros, so one zero byte is the empty key, which `SecretKeySpec` refuses.
        mac.init(SecretKeySpec(password.takeIf { it.isNotEmpty() } ?: byteArrayOf(0), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var round = mac.doFinal()
        val result = round.copyOf()
        repeat(iterations - 1) {
            round = mac.doFinal(round)
            for (j in result.indices) result[j] = (result[j].toInt() xor round[j].toInt()).toByte()
        }
        return result
    }

    private fun randomSalt() = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private const val SALT_BYTES = 16
}
