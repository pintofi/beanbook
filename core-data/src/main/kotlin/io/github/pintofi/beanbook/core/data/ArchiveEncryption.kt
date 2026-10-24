package io.github.pintofi.beanbook.core.data

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
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
     * [cipher] says whether that is one operation ([CIPHER], older archives) or chunks
     * ([CHUNKED_CIPHER], every archive written since #54).
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
        /** One GCM operation over the whole payload. Still read, no longer written (#54). */
        const val CIPHER = "AES-256-GCM"

        /**
         * AES-256-GCM over the payload in independent chunks of [CHUNK_BYTES], so a backup of any
         * size is sealed and opened with a few hundred KiB of memory (#54).
         */
        const val CHUNKED_CIPHER = "AES-256-GCM-CHUNKED"

        /** Plaintext bytes per chunk; each sealed chunk is [TAG_BITS]/8 bytes longer. */
        const val CHUNK_BYTES = 64 * 1024
        const val ARGON2ID = "argon2id"

        /** 128-bit tag: the GCM default, and what makes a wrong passphrase fail loudly. */
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12

        /** Ceilings on what an archive may ask the derivation to spend. The defaults are far below. */
        const val MAX_ITERATIONS = 64
        const val MAX_MEMORY_KIB = 1024 * 1024
        const val MIN_MEMORY_KIB = 8
        const val MAX_PARALLELISM = 16
        const val MIN_SALT_BYTES = 8
        const val MAX_SALT_BYTES = 64

        fun fromJson(value: Json?): ArchiveEncryption? = when (value) {
            null -> None
            is Json.Str -> if (value.value == NONE) None else null
            is Json.Obj -> parameters(value)
            else -> null
        }

        /**
         * The manifest is not secret and not authenticated, so what it says about how to derive a
         * key is a request from whoever made the archive. Read as one: an archive that asks for
         * a few gigabytes of memory, or billions of passes, to open itself is a way to make the
         * app run out of memory, and a different cipher or derivation than the ones this reads is
         * an archive this cannot open, whatever it claims.
         */
        private fun parameters(value: Json.Obj): Encrypted? {
            val encrypted = Encrypted(
                kdf = value.string("kdf") ?: return null,
                salt = decode(value.string("salt") ?: return null) ?: return null,
                nonce = decode(value.string("nonce") ?: return null) ?: return null,
                iterations = value.long("iterations")?.toInt() ?: return null,
                memoryKib = value.long("memory_kib")?.toInt() ?: return null,
                parallelism = value.long("parallelism")?.toInt() ?: return null,
                cipher = value.string("cipher") ?: CIPHER,
            )
            val acceptable = (encrypted.cipher == CIPHER || encrypted.cipher == CHUNKED_CIPHER) &&
                encrypted.kdf == ARGON2ID &&
                encrypted.nonce.size == NONCE_BYTES &&
                encrypted.salt.size in MIN_SALT_BYTES..MAX_SALT_BYTES &&
                encrypted.iterations in 1..MAX_ITERATIONS &&
                encrypted.memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
                encrypted.parallelism in 1..MAX_PARALLELISM
            return encrypted.takeIf { acceptable }
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

    /**
     * Seals a payload as it is written, in chunks (#54), so the project is never held in memory.
     *
     * Each chunk is its own GCM message. Its nonce is four random bytes followed by the chunk's
     * number, so no two chunks of an archive share one, and the key is new for every archive
     * because its salt is. One byte of associated data says whether the chunk is the last: a
     * payload cut off at a chunk boundary then ends on a chunk that was not sealed as the last,
     * fails the tag, and is refused like any other damage. Swapping or repeating chunks changes
     * their numbers and fails the same way.
     */
    class Sealer internal constructor(
        val encryption: ArchiveEncryption.Encrypted,
        private val key: ByteArray,
    ) {
        /**
         * An output stream that writes the sealed payload to [target]. Closing it writes the last
         * chunk and wipes the key; it does not close [target], which the caller owns.
         */
        fun wrap(target: OutputStream): OutputStream = SealingStream(target, key, encryption.nonce)
    }

    fun begin(
        passphrase: CharArray,
        derivation: KeyDerivation,
        kdf: String,
        cost: KdfCost = KdfCost(),
    ): Sealer {
        val salt = ArchiveEncryption.randomBytes(ArchiveEncryption.SALT_BYTES)
        val nonce = ArchiveEncryption.randomBytes(ArchiveEncryption.NONCE_BYTES)
        val key = derivation.derive(passphrase, salt, cost)
        require(key.size == ArchiveEncryption.KEY_BYTES) {
            "a derived key must be ${ArchiveEncryption.KEY_BYTES} bytes"
        }
        return Sealer(
            ArchiveEncryption.Encrypted(
                kdf = kdf,
                salt = salt,
                nonce = nonce,
                iterations = cost.iterations,
                memoryKib = cost.memoryKib,
                parallelism = cost.parallelism,
                cipher = ArchiveEncryption.CHUNKED_CIPHER,
            ),
            key,
        )
    }

    /**
     * Opens a payload sealed by [Sealer], writing the plaintext to [plaintext] a chunk at a time.
     *
     * @throws WrongPassphrase when any chunk fails its tag: a wrong passphrase, a damaged payload
     *   and a truncated one are not told apart, as with the one-shot cipher. Plaintext written
     *   before the failure is only fit to be thrown away, so write it to a temporary file.
     */
    fun decryptTo(
        ciphertext: InputStream,
        plaintext: OutputStream,
        passphrase: CharArray,
        derivation: KeyDerivation,
        encryption: ArchiveEncryption.Encrypted,
    ) {
        val key = derivation.derive(
            passphrase,
            encryption.salt,
            KdfCost(encryption.iterations, encryption.memoryKib, encryption.parallelism),
        )
        try {
            val spec = SecretKeySpec(key, "AES")
            var current = ByteArray(SEALED_CHUNK_BYTES)
            var following = ByteArray(SEALED_CHUNK_BYTES)
            var length = fill(ciphertext, current)
            var index = 0L
            while (true) {
                // A chunk is the last one when nothing follows it; reading ahead is how that is known.
                val ahead = if (length == SEALED_CHUNK_BYTES) fill(ciphertext, following) else 0
                val last = ahead == 0
                try {
                    plaintext.write(openChunk(spec, encryption.nonce, index, last, current, length))
                } catch (ignored: GeneralSecurityException) {
                    throw WrongPassphrase()
                }
                if (last) return
                index++
                current = following.also { following = current }
                length = ahead
            }
        } finally {
            key.fill(0)
        }
    }

    private const val SEALED_CHUNK_BYTES = ArchiveEncryption.CHUNK_BYTES + ArchiveEncryption.TAG_BITS / 8

    private fun chunkNonce(base: ByteArray, index: Long): ByteArray {
        val nonce = ByteArray(ArchiveEncryption.NONCE_BYTES)
        System.arraycopy(base, 0, nonce, 0, 4)
        for (i in 0 until 8) nonce[4 + i] = (index ushr (56 - 8 * i)).toByte()
        return nonce
    }

    private fun chunkCipher(mode: Int, key: SecretKeySpec, base: ByteArray, index: Long, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(ArchiveEncryption.TAG_BITS, chunkNonce(base, index)))
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }

    private fun openChunk(
        key: SecretKeySpec,
        base: ByteArray,
        index: Long,
        last: Boolean,
        chunk: ByteArray,
        length: Int,
    ): ByteArray = chunkCipher(Cipher.DECRYPT_MODE, key, base, index, last).doFinal(chunk, 0, length)

    /** Reads until [buffer] is full or the stream ends; the count is below the size only at the end. */
    private fun fill(input: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val n = input.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }

    private class SealingStream(
        private val target: OutputStream,
        private val key: ByteArray,
        private val base: ByteArray,
    ) : OutputStream() {
        private val spec = SecretKeySpec(key, "AES")
        private val pending = ByteArray(ArchiveEncryption.CHUNK_BYTES)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "the sealed payload is already finished" }
            var at = off
            var left = len
            while (left > 0) {
                // A full chunk waits until more data arrives, so the last chunk is never empty
                // unless the whole payload is, and is always the one sealed as last.
                if (filled == pending.size) emit(last = false)
                val n = minOf(left, pending.size - filled)
                System.arraycopy(b, at, pending, filled, n)
                filled += n
                at += n
                left -= n
            }
        }

        private fun emit(last: Boolean) {
            val cipher = chunkCipher(Cipher.ENCRYPT_MODE, spec, base, index, last)
            target.write(cipher.doFinal(pending, 0, filled))
            filled = 0
            index++
        }

        override fun close() {
            if (closed) return
            closed = true
            emit(last = true)
            key.fill(0)
        }
    }
}
