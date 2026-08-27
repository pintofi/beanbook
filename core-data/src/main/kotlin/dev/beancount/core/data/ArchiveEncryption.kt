package dev.beancount.core.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How a backup is encrypted, as recorded in the manifest
 * (docs/storage-and-backup.md §7).
 *
 * **The manifest itself is never encrypted.** Someone holding an archive they cannot open
 * must still be able to see what it is, whose project it was and when it was taken —
 * otherwise a folder of backups becomes a folder of indistinguishable blobs, and the one you
 * need cannot be found.
 *
 * Encryption is **off by default**: a plain archive is a zip anyone can open with any tool,
 * on any machine, forever, which is the right default for a plain-text-accounting product.
 * Turning it on is a deliberate act with a warning attached, because a forgotten passphrase
 * is unrecoverable — by us as much as by anyone else.
 */
sealed interface ArchiveEncryption {

    data object None : ArchiveEncryption

    /**
     * AES-256-GCM over the whole project payload, with the key derived from a passphrase.
     *
     * Every parameter needed to repeat the derivation is stored in the clear. That is not a
     * weakness — salt and cost parameters are not secrets, and an archive that cannot say
     * how its key was derived is an archive that stops opening the day the defaults change.
     */
    data class Encrypted(
        val kdf: String,
        val salt: ByteArray,
        val nonce: ByteArray,
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val cipher: String = CIPHER,
    ) : ArchiveEncryption {
        override fun equals(other: Any?): Boolean =
            other is Encrypted &&
                kdf == other.kdf &&
                salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                iterations == other.iterations &&
                memoryKib == other.memoryKib &&
                parallelism == other.parallelism &&
                cipher == other.cipher

        override fun hashCode(): Int = salt.contentHashCode() * 31 + nonce.contentHashCode()
    }

    fun toJson(): Json = when (this) {
        None -> Json.of(NONE)
        is Encrypted -> Json.obj(
            "cipher" to Json.of(cipher),
            "kdf" to Json.of(kdf),
            "salt" to Json.of(encode(salt)),
            "nonce" to Json.of(encode(nonce)),
            "iterations" to Json.of(iterations),
            "memory_kib" to Json.of(memoryKib),
            "parallelism" to Json.of(parallelism),
        )
    }

    companion object {
        const val NONE = "none"
        const val CIPHER = "AES-256-GCM"
        const val ARGON2ID = "argon2id"

        /** 128-bit tag: the GCM default, and what makes a wrong passphrase fail loudly. */
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12

        fun fromJson(value: Json?): ArchiveEncryption? = when (value) {
            null -> None
            is Json.Str -> if (value.value == NONE) None else null
            is Json.Obj -> Encrypted(
                kdf = value.string("kdf") ?: return null,
                salt = decode(value.string("salt") ?: return null) ?: return null,
                nonce = decode(value.string("nonce") ?: return null) ?: return null,
                iterations = value.long("iterations")?.toInt() ?: return null,
                memoryKib = value.long("memory_kib")?.toInt() ?: return null,
                parallelism = value.long("parallelism")?.toInt() ?: return null,
                cipher = value.string("cipher") ?: CIPHER,
            )
            else -> null
        }

        fun encode(bytes: ByteArray): String =
            Base64.getEncoder().withoutPadding().encodeToString(bytes)

        fun decode(text: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(padded(text)) }.getOrNull()

        private fun padded(text: String): String =
            text + "=".repeat((4 - text.length % 4) % 4)

        fun randomBytes(count: Int): ByteArray =
            ByteArray(count).also { SecureRandom().nextBytes(it) }
    }
}

/**
 * How much work deriving a key from a passphrase costs.
 *
 * The defaults follow the OWASP guidance for Argon2id — 64 MiB, three passes — chosen so a
 * mid-range phone takes a noticeable moment rather than an unnoticeable one. The cost is
 * paid once per backup and once per restore, and it is the only thing standing between a
 * weak passphrase and someone with the archive.
 */
data class KdfCost(
    val iterations: Int = 3,
    val memoryKib: Int = 64 * 1024,
    val parallelism: Int = 2,
)

/**
 * Turns a passphrase into a key.
 *
 * An interface because Argon2id is native code that only exists on Android, while the
 * envelope around it — the cipher, the manifest, the failure modes — is ordinary Kotlin that
 * must be testable on the JVM. The real derivation is exercised on a device
 * (`app/src/androidTest`), and the envelope is exercised here.
 */
fun interface KeyDerivation {
    /** @return exactly 32 bytes. */
    fun derive(passphrase: CharArray, salt: ByteArray, cost: KdfCost): ByteArray
}

/**
 * AES-256-GCM over a payload, with the key from a [KeyDerivation].
 *
 * GCM is authenticated: a wrong passphrase, a flipped bit or a truncated payload all fail
 * the tag check, and the result is a refusal rather than plausible-looking rubbish.
 */
object PayloadCipher {

    class WrongPassphrase : Exception("that passphrase does not open this archive")

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        derivation: KeyDerivation,
        kdf: String,
        cost: KdfCost = KdfCost(),
    ): Pair<ByteArray, ArchiveEncryption.Encrypted> {
        val salt = ArchiveEncryption.randomBytes(ArchiveEncryption.SALT_BYTES)
        val nonce = ArchiveEncryption.randomBytes(ArchiveEncryption.NONCE_BYTES)
        val key = derivation.derive(passphrase, salt, cost)
        require(key.size == ArchiveEncryption.KEY_BYTES) {
            "a derived key must be ${ArchiveEncryption.KEY_BYTES} bytes"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(ArchiveEncryption.TAG_BITS, nonce),
        )
        val ciphertext = cipher.doFinal(plaintext)
        key.fill(0)

        return ciphertext to ArchiveEncryption.Encrypted(
            kdf = kdf,
            salt = salt,
            nonce = nonce,
            iterations = cost.iterations,
            memoryKib = cost.memoryKib,
            parallelism = cost.parallelism,
        )
    }

    fun decrypt(
        ciphertext: ByteArray,
        passphrase: CharArray,
        derivation: KeyDerivation,
        encryption: ArchiveEncryption.Encrypted,
    ): ByteArray {
        val key = derivation.derive(
            passphrase,
            encryption.salt,
            KdfCost(encryption.iterations, encryption.memoryKib, encryption.parallelism),
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(ArchiveEncryption.TAG_BITS, encryption.nonce),
        )
        return try {
            cipher.doFinal(ciphertext)
        } catch (ignored: javax.crypto.AEADBadTagException) {
            // Indistinguishable by design: a wrong passphrase and a damaged payload both
            // fail the tag, and neither yields anything usable.
            throw WrongPassphrase()
        } finally {
            key.fill(0)
        }
    }
}
