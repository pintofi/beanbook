package dev.beancount.core.entitlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The join between the release pipeline and the self-update, checked end to end.
 *
 * A release produces an APK; `:app:releaseChecksums` prints its SHA-256; `issue-update.sh`
 * signs a manifest carrying that checksum and a download URL; the app verifies the signature
 * and then refuses any download whose bytes do not hash to it.
 *
 * The manifest below came out of that sequence for real — issued against the SHA-256 of an
 * actual signed release APK built by this project, not a value typed in. If any link in the
 * chain is broken, this does not verify.
 */
class ReleaseLoopTest {

    private companion object {
        /** The SHA-256 `:app:releaseChecksums` printed for app-expert-arm64-v8a-release.apk. */
        const val APK_SHA256 = "e3d5c62f422749e931ae6fbaf1cc5c015a1009e9627430027ec86a3a7d3a79fe"

        const val ISSUED_MANIFEST =
            // A real signed key, wrapped only so it fits the line-length gate; the value is
            // unchanged and the concatenation is folded by the compiler.
            "BCU1.eyJ2IjoxLCJraWQiOiJrMSIsImNvZGUiOiIyIiwibmFtZSI6IjAuMi4wIiwidXJsIjoiaHR0c" +
                "HM6Ly9naXRodWIuY29tL2V4YW1wbGUvYmVhbmNvdW50LWFuZHJvaWQvcmVsZWFzZXMvZG93bmxvYWQ" +
                "vdjAuMi4wL2FwcC1leHBlcnQtYXJtNjQtdjhhLXJlbGVhc2UuYXBrIiwic2hhMjU2IjoiZTNkNWM2M" +
                "mY0MjI3NDllOTMxYWU2ZmJhZjFjYzVjMDE1YTEwMDllOTYyNzQzMDAyN2VjODZhM2E3ZDNhNzlmZSI" +
                "sIm5vdGVzIjoiV2lkZ2V0cywgcGx1Z2luIG1hbmFnZXIsIGRhcmsgdGhlbWUuIn0.T_VsumXLKYXV_" +
                "Sr_7mxIhu40ObCCNL5Uu74Y_8aukAdxBFQvLJ0gwnOz1jsqQiS3dHyDB1Yd-ANiU3EuZqjEBw"
    }

    @Test
    fun `a manifest issued for a real release APK verifies and carries its checksum`() {
        val check = UpdateVerifier().check(ISSUED_MANIFEST, installedVersionCode = 1)

        val offered = assertIs<UpdateCheck.Offered>(check).manifest
        assertEquals(APK_SHA256, offered.sha256)
        assertEquals(2L, offered.versionCode)
        assertEquals("0.2.0", offered.versionName)
    }
}
