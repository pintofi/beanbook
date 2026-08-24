package dev.beancount.app.backup

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import dev.beancount.core.data.ArchiveEncryption
import dev.beancount.core.data.KdfCost
import dev.beancount.core.data.KeyDerivation
import java.nio.charset.StandardCharsets

/**
 * Argon2id, from `com.lambdapioneer.argon2kt` — the real key derivation for backup
 * encryption (docs/storage-and-backup.md §7).
 *
 * Argon2id rather than PBKDF2 because the threat is an attacker with the archive and a GPU:
 * memory hardness is what makes a weak passphrase expensive to guess rather than merely
 * slow. The platform has no Argon2 at any API level this app supports, so it comes from a
 * library — a small native one (~1 MB across both ABIs) rather than a general-purpose crypto
 * provider that would have added the best part of ten.
 *
 * The passphrase is converted to bytes and **wiped** as soon as the key exists. It is never
 * logged, never stored, and never leaves this function.
 */
class Argon2idKeyDerivation(private val argon2: Argon2Kt = Argon2Kt()) : KeyDerivation {

    override fun derive(passphrase: CharArray, salt: ByteArray, cost: KdfCost): ByteArray {
        val bytes = toBytes(passphrase)
        try {
            val result = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = bytes,
                salt = salt,
                tCostInIterations = cost.iterations,
                mCostInKibibyte = cost.memoryKib,
                parallelism = cost.parallelism,
                hashLengthInBytes = ArchiveEncryption.KEY_BYTES,
            )
            return result.rawHashAsByteArray()
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Encodes without letting the JVM keep an immutable copy.
     *
     * `String(passphrase)` would leave the passphrase in the string pool until the garbage
     * collector felt like it; a `CharBuffer` round trip keeps it in arrays we can zero.
     */
    private fun toBytes(passphrase: CharArray): ByteArray {
        val chars = java.nio.CharBuffer.wrap(passphrase)
        val encoded = StandardCharsets.UTF_8.encode(chars)
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }
}
