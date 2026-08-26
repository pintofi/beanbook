package dev.beancount.core.entitlement

import java.util.Base64

/**
 * A signed statement that a newer build exists (expert edition only).
 *
 * `BCU1.<base64url(payload)>.<base64url(ed25519_sig)>` — the same shape as a contribution
 * key, and deliberately **a different prefix**. A contribution key must never verify as an
 * update manifest and an update manifest must never verify as a key: they are signed by the
 * same key pair, so without domain separation a signature obtained for one could be
 * presented as the other. The prefix is part of the signed message for exactly that reason.
 *
 * Only the *verification* lives here, in a module both editions compile. Nothing in this file
 * downloads anything, touches an APK, or knows what a `PackageInstaller` is — all of that is
 * in the expert source set, where `:verifyPlayFlavorBoundaries` requires it to stay.
 */
data class UpdateManifest(
    /** Android `versionCode` of the offered build. */
    val versionCode: Long,
    /** Human-readable `versionName`, for the release note. */
    val versionName: String,
    /** Where the APK is. Must be https. */
    val url: String,
    /** Lowercase hex SHA-256 of the APK. */
    val sha256: String,
    /** What changed, shown before anything is downloaded. */
    val notes: String,
)

/** Why an update offer was refused. Each says what it means for the user. */
sealed interface UpdateProblem {
    data object NotAManifest : UpdateProblem
    data class UnsupportedVersion(val found: String) : UpdateProblem
    data object Malformed : UpdateProblem
    data object UnknownSigner : UpdateProblem
    data object BadSignature : UpdateProblem
    data class MissingField(val name: String) : UpdateProblem
    data object NotHttps : UpdateProblem
    data object BadChecksum : UpdateProblem
    data class NotNewer(val offered: Long, val installed: Long) : UpdateProblem

    fun describe(): String = when (this) {
        NotAManifest -> "That is not an update file."
        is UnsupportedVersion ->
            "This update file was made for a newer version of the app ($found). Update " +
                "through the usual route first."
        Malformed -> "That update file is damaged."
        UnknownSigner ->
            "That update was not signed by this app's developer. It has been ignored."
        BadSignature ->
            "That update's signature does not check out — it has been altered since it was " +
                "signed. It has been ignored."
        is MissingField -> "That update file is missing its \"$name\"."
        NotHttps -> "That update would be downloaded over an unencrypted connection."
        BadChecksum -> "That update file does not say what the download should contain."
        is NotNewer ->
            "You already have this version or a newer one (offered $offered, installed " +
                "$installed)."
    }
}

class UpdateRejected(val problem: UpdateProblem) : Exception(problem.describe())

/** The outcome of checking an update offer. */
sealed interface UpdateCheck {
    data class Offered(val manifest: UpdateManifest) : UpdateCheck
    data class Rejected(val problem: UpdateProblem) : UpdateCheck
}

/**
 * Verifies an update manifest offline, against the same trusted keys as contribution keys.
 *
 * @param installedVersionCode the running build, so a manifest can never talk the app into
 *   installing something older than it already has. A downgrade is how a fixed bug gets
 *   reintroduced on purpose.
 */
class UpdateVerifier(
    private val trusted: List<TrustedSigner> = TrustedSigners.ALL,
) {

    fun check(input: String, installedVersionCode: Long): UpdateCheck {
        val text = input.trim()
        val parts = text.split(".")
        if (parts.size != 3 || parts[0].isEmpty()) {
            return UpdateCheck.Rejected(UpdateProblem.NotAManifest)
        }
        if (parts[0] != PREFIX) {
            // Includes a contribution key offered as an update, which is the case the
            // separate prefix exists to refuse.
            return UpdateCheck.Rejected(
                if (parts[0].startsWith("BCU")) UpdateProblem.UnsupportedVersion(parts[0])
                else UpdateProblem.NotAManifest,
            )
        }

        val decoder = Base64.getUrlDecoder()
        val payloadBytes = runCatching { decoder.decode(parts[1]) }.getOrNull()
            ?: return UpdateCheck.Rejected(UpdateProblem.Malformed)
        val signature = runCatching { decoder.decode(parts[2]) }.getOrNull()
            ?: return UpdateCheck.Rejected(UpdateProblem.Malformed)

        val fields = MiniJson.parseFlatObject(String(payloadBytes))
            ?: return UpdateCheck.Rejected(UpdateProblem.Malformed)

        val keyId = fields["kid"] ?: return UpdateCheck.Rejected(UpdateProblem.MissingField("kid"))
        val signer = trusted.firstOrNull { it.keyId == keyId }
            ?: return UpdateCheck.Rejected(UpdateProblem.UnknownSigner)

        // The signature covers the prefix and the encoded payload segment, not the decoded
        // bytes: signing what was actually transmitted leaves no room for two encodings of
        // the same payload to disagree.
        val signed = "${parts[0]}.${parts[1]}".toByteArray()
        if (!Ed25519.verify(signed, signature, signer.publicKey)) {
            return UpdateCheck.Rejected(UpdateProblem.BadSignature)
        }

        val versionCode = fields["code"]?.toLongOrNull()
            ?: return UpdateCheck.Rejected(UpdateProblem.MissingField("code"))
        val versionName = fields["name"]
            ?: return UpdateCheck.Rejected(UpdateProblem.MissingField("name"))
        val url = fields["url"] ?: return UpdateCheck.Rejected(UpdateProblem.MissingField("url"))
        val sha256 = fields["sha256"]
            ?: return UpdateCheck.Rejected(UpdateProblem.MissingField("sha256"))

        if (!url.startsWith("https://")) return UpdateCheck.Rejected(UpdateProblem.NotHttps)
        if (!sha256.matches(SHA256)) return UpdateCheck.Rejected(UpdateProblem.BadChecksum)
        if (versionCode <= installedVersionCode) {
            return UpdateCheck.Rejected(UpdateProblem.NotNewer(versionCode, installedVersionCode))
        }

        return UpdateCheck.Offered(
            UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                url = url,
                sha256 = sha256.lowercase(),
                notes = fields["notes"].orEmpty(),
            ),
        )
    }

    private companion object {
        const val PREFIX = "BCU1"
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
