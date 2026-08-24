package dev.beancount.core.entitlement

import java.time.LocalDate
import java.util.Base64

/**
 * A contribution key, as it is written down and pasted in (docs/distribution.md §4.5).
 *
 * ```
 * BCA1.<base64url(payload)>.<base64url(ed25519_sig)>
 *
 * payload = { "v":1, "kid":"k1", "tier":3,
 *             "email":"buyer@example.com", "order":"PP-8FJ2…", "iat":"2026-08-16" }
 * ```
 *
 * The signature covers **the encoded payload segment exactly as it appears in the key**,
 * not the JSON it decodes to. Signing the bytes on the wire removes the whole class of
 * canonicalisation bugs — re-serialising the JSON to check a signature is how verifiers
 * come to disagree with signers about whitespace and key order.
 */
data class ContributionKey(
    val keyId: String,
    val tier: Tier,
    val email: String,
    val orderId: String,
    val issuedOn: LocalDate,
    /** The whole key, so Settings can show it again and the user never loses it. */
    val raw: String,
) {
    fun stored(): StoredKey = StoredKey(tier = tier, email = email, orderId = orderId, raw = raw)
}

/**
 * Why a key was not accepted.
 *
 * Each case says what is wrong in the user's own terms: someone who has paid and is holding
 * a key that will not go in deserves better than "invalid key", and the difference between
 * "this is for a newer version" and "this has been altered" decides what they do next.
 */
sealed interface KeyProblem {
    data object NotAKey : KeyProblem
    data class UnsupportedVersion(val version: String) : KeyProblem
    data object Malformed : KeyProblem
    data class UnknownSigner(val keyId: String) : KeyProblem
    data object BadSignature : KeyProblem
    data class MissingField(val field: String) : KeyProblem
    data class UnknownTier(val tier: Int) : KeyProblem

    fun describe(): String = when (this) {
        NotAKey ->
            "That does not look like a contribution key. A key starts with \"BCA1.\" and has " +
                "three parts separated by dots."
        is UnsupportedVersion ->
            "This key is version \"$version\", which this app does not understand. A newer " +
                "version of the app will."
        Malformed -> "This key is damaged — some of it is missing or was altered in copying."
        is UnknownSigner ->
            "This key was signed with a key this app does not trust (\"$keyId\"). If it was " +
                "issued recently, a newer version of the app will accept it."
        BadSignature ->
            "This key's signature does not match its contents, so it has been altered since " +
                "it was issued."
        is MissingField -> "This key is missing its $field."
        is UnknownTier -> "This key is for tier $tier, which this app does not have."
    }
}

/** The outcome of checking a key. There is no third state: it verifies or it does not. */
sealed interface KeyCheck {
    data class Verified(val key: ContributionKey) : KeyCheck
    data class Rejected(val problem: KeyProblem) : KeyCheck
}

/**
 * Parsing only — no signature check. Split out so the parser can be tested against damaged
 * input without any crypto in the way, and so [KeyVerifier] is only ever handed a key whose
 * shape is already known.
 */
internal object KeyFormat {

    const val PREFIX = "BCA1"

    data class Parsed(
        val payload: KeyPayload,
        /** The bytes the signature covers: the encoded payload segment, ASCII. */
        val signedBytes: ByteArray,
        val signature: ByteArray,
        val raw: String,
    ) {
        // Data classes with arrays: equals/hashCode are identity-based, which would be a
        // trap in tests. Nothing compares these, so they are overridden to say so.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun parse(input: String): Result<Parsed> {
        val text = input.trim().replace("\n", "").replace(" ", "")
        val parts = text.split('.')
        if (parts.size != 3 || parts[0].isEmpty()) return failure(KeyProblem.NotAKey)
        if (parts[0] != PREFIX) {
            return failure(
                if (parts[0].startsWith("BCA")) KeyProblem.UnsupportedVersion(parts[0])
                else KeyProblem.NotAKey,
            )
        }
        if (parts[1].isEmpty() || parts[2].isEmpty()) return failure(KeyProblem.Malformed)

        val decoder = Base64.getUrlDecoder()
        val payloadBytes = runCatching { decoder.decode(parts[1]) }.getOrNull()
            ?: return failure(KeyProblem.Malformed)
        val signature = runCatching { decoder.decode(parts[2]) }.getOrNull()
            ?: return failure(KeyProblem.Malformed)
        if (signature.size != SIGNATURE_BYTES) return failure(KeyProblem.Malformed)

        val payload = KeyPayload.parse(String(payloadBytes, Charsets.UTF_8))
            .getOrElse { return Result.failure(it) }

        return Result.success(
            Parsed(
                payload = payload,
                signedBytes = parts[1].toByteArray(Charsets.US_ASCII),
                signature = signature,
                raw = text,
            ),
        )
    }

    private const val SIGNATURE_BYTES = 64

    private fun failure(problem: KeyProblem): Result<Nothing> =
        Result.failure(KeyRejected(problem))
}

/** Carries a [KeyProblem] out of parsing without inventing a second error vocabulary. */
internal class KeyRejected(val problem: KeyProblem) : Exception(problem.describe())

/** The decoded payload. Flat by design: nothing here needs structure. */
internal data class KeyPayload(
    val version: Int,
    val keyId: String,
    val tier: Int,
    val email: String,
    val orderId: String,
    val issuedOn: String,
) {
    companion object {
        fun parse(json: String): Result<KeyPayload> {
            val fields = MiniJson.parseFlatObject(json)
                ?: return Result.failure(KeyRejected(KeyProblem.Malformed))

            fun require(name: String): Result<String> =
                fields[name]?.let { Result.success(it) }
                    ?: Result.failure(KeyRejected(KeyProblem.MissingField(name)))

            val version = fields["v"]?.toIntOrNull()
                ?: return Result.failure(KeyRejected(KeyProblem.MissingField("version")))
            val tier = fields["tier"]?.toIntOrNull()
                ?: return Result.failure(KeyRejected(KeyProblem.MissingField("tier")))

            return Result.success(
                KeyPayload(
                    version = version,
                    keyId = require("kid").getOrElse { return Result.failure(it) },
                    tier = tier,
                    email = require("email").getOrElse { return Result.failure(it) },
                    orderId = require("order").getOrElse { return Result.failure(it) },
                    issuedOn = require("iat").getOrElse { return Result.failure(it) },
                ),
            )
        }
    }
}

/**
 * A deliberately small JSON reader for one shape: a flat object of string and number values.
 *
 * A key payload is six fields written by our own issuer. Pulling in a JSON library — and its
 * licence, its size and its reflection — to read that would be out of proportion; and a
 * parser that *cannot* express nesting cannot be talked into it by a hostile key.
 */
internal object MiniJson {

    fun parseFlatObject(text: String): Map<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val body = trimmed.substring(1, trimmed.length - 1)
        val fields = mutableMapOf<String, String>()

        var index = 0
        fun skipSpace() { while (index < body.length && body[index].isWhitespace()) index++ }

        skipSpace()
        if (index >= body.length) return fields

        while (true) {
            skipSpace()
            val name = readString(body, index) ?: return null
            index = name.second
            skipSpace()
            if (index >= body.length || body[index] != ':') return null
            index++
            skipSpace()
            if (index >= body.length) return null

            val value: String
            if (body[index] == '"') {
                val read = readString(body, index) ?: return null
                value = read.first
                index = read.second
            } else {
                val start = index
                while (index < body.length && body[index] != ',' && !body[index].isWhitespace()) {
                    index++
                }
                value = body.substring(start, index)
                // Only numbers and the two literals; an object or array here is a shape we
                // refuse rather than skip past.
                if (value.isEmpty() || value.any { it in "{}[]" }) return null
            }

            // A duplicated field would let a signer and a reader disagree about which value
            // counts, which is exactly the ambiguity a verifier must not have.
            if (fields.put(name.first, value) != null) return null

            skipSpace()
            if (index >= body.length) break
            if (body[index] != ',') return null
            index++
        }
        return fields
    }

    /** Reads a quoted string at [start]; returns the value and the index after it. */
    private fun readString(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length || text[start] != '"') return null
        val out = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            when (val char = text[index]) {
                '"' -> return out.toString() to index + 1
                '\\' -> {
                    index++
                    if (index >= text.length) return null
                    when (val escaped = text[index]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('')
                        'u' -> {
                            val hex = text.substring(index + 1, minOf(index + 5, text.length))
                            if (hex.length < 4) return null
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index++
                }
                else -> { out.append(char); index++ }
            }
        }
        return null
    }
}
