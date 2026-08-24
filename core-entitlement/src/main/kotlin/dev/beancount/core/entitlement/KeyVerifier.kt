package dev.beancount.core.entitlement

import java.security.Signature
import java.time.LocalDate
import java.util.Base64
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec

/**
 * One public key the app trusts, identified by the `kid` written into every key it signed.
 *
 * Rotation is the reason this is a list. If a signing key is ever compromised, a new `kid`
 * is added and issuance moves to it — and **the old `kid` keeps verifying forever**, because
 * keys already in people's hands were bought in good faith and must not stop working
 * (docs/distribution.md §4.5). [issuing] records which one new keys are signed with; it
 * changes nothing about verification.
 */
data class TrustedSigner(
    val keyId: String,
    /** Raw Ed25519 public key, 32 bytes, base64url. */
    val publicKey: String,
    val issuing: Boolean = false,
)

/**
 * Checks contribution keys offline (docs/distribution.md §4.5).
 *
 * There is no licence server, no network call, no device identifier and no activation
 * count: verification is a signature check against public keys compiled into the app. That
 * is the whole mechanism, and it is why the app works forever with no service behind it.
 *
 * Ed25519 comes from `net.i2p.crypto:eddsa` rather than the platform because
 * `java.security` only gained Ed25519 in API 33 and this app supports API 28.
 */
class KeyVerifier(private val trusted: List<TrustedSigner> = TrustedSigners.ALL) {

    fun check(input: String): KeyCheck {
        val parsed = KeyFormat.parse(input).getOrElse { error ->
            return KeyCheck.Rejected((error as? KeyRejected)?.problem ?: KeyProblem.Malformed)
        }

        if (parsed.payload.version != SUPPORTED_VERSION) {
            return KeyCheck.Rejected(KeyProblem.UnsupportedVersion("v${parsed.payload.version}"))
        }

        val signer = trusted.firstOrNull { it.keyId == parsed.payload.keyId }
            ?: return KeyCheck.Rejected(KeyProblem.UnknownSigner(parsed.payload.keyId))

        val valid = runCatching {
            verify(parsed.signedBytes, parsed.signature, signer.publicKey)
        }.getOrDefault(false)
        if (!valid) return KeyCheck.Rejected(KeyProblem.BadSignature)

        val tier = Tier.entries.firstOrNull { it.level == parsed.payload.tier }
            ?: return KeyCheck.Rejected(KeyProblem.UnknownTier(parsed.payload.tier))

        val issuedOn = runCatching { LocalDate.parse(parsed.payload.issuedOn) }.getOrNull()
            ?: return KeyCheck.Rejected(KeyProblem.MissingField("issue date"))

        return KeyCheck.Verified(
            ContributionKey(
                keyId = signer.keyId,
                tier = tier,
                email = parsed.payload.email,
                orderId = parsed.payload.orderId,
                issuedOn = issuedOn,
                raw = parsed.raw,
            ),
        )
    }

    private fun verify(message: ByteArray, signature: ByteArray, publicKey: String): Boolean {
        val raw = Base64.getUrlDecoder().decode(publicKey)
        require(raw.size == PUBLIC_KEY_BYTES) { "a trusted key must be $PUBLIC_KEY_BYTES bytes" }
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val key = EdDSAPublicKey(EdDSAPublicKeySpec(raw, spec))
        val engine: Signature = EdDSAEngine(java.security.MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initVerify(key)
        engine.update(message)
        return engine.verify(signature)
    }

    private companion object {
        const val SUPPORTED_VERSION = 1
        const val PUBLIC_KEY_BYTES = 32
    }
}

/**
 * The public keys this build trusts.
 *
 * **The private halves are not in this repository and never will be.** They live in the
 * separate private issuance repo (docs/distribution.md §4.6); this file is the public side
 * of that arrangement, and there is no bypass anywhere in the app — the developer unlocks
 * their own build by issuing themselves a key like any other contributor.
 */
object TrustedSigners {
    val ALL: List<TrustedSigner> = listOf(
        TrustedSigner(
            keyId = "k1",
            publicKey = "3l-3OCfh7aX4SXpCz-5lJRSZZH4kv7QSOkVZGK-XMtg",
            issuing = true,
        ),
    )
}
