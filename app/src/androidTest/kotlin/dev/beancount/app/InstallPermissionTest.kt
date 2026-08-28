package dev.beancount.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The permission the Expert edition's self-update needs, and the Play edition must not have.
 *
 * `ApkUpdate` commits a `PackageInstaller` session in `MODE_FULL_INSTALL`. Android requires
 * `REQUEST_INSTALL_PACKAGES` for that from API 26 onwards, so without it a verified, downloaded
 * update fails at the final step — after the signature check, the checksum check and the
 * certificate check have all passed.
 *
 * `:verifyPlayFlavorBoundaries` already fails the build if this permission appears in the Play
 * edition. That rule guards nothing unless the Expert edition actually declares it, which is
 * the half no build gate was checking and no test reached: `UpdateFlowTest` verifies the
 * manifest, the checksum and the discard path, and stops deliberately short of installing.
 *
 * Asserted from the installed package rather than the source manifest, because what matters is
 * what the merged manifest granted the running app.
 */
@RunWith(AndroidJUnit4::class)
class InstallPermissionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun declaredPermissions(): List<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList().orEmpty()
    }

    @Test fun theEditionThatCanUpdateItselfMayInstallPackages() {
        val declared = declaredPermissions()
        val install = "android.permission.REQUEST_INSTALL_PACKAGES"

        if (currentPluginPolicy().allowsSelfUpdate) {
            assertTrue(
                "this edition ships a self-update that commits a PackageInstaller session, " +
                    "but does not declare $install — the install would fail after every " +
                    "verification passed. Declared: $declared",
                declared.contains(install),
            )
        } else {
            assertFalse(
                "the Play edition must not be able to install packages (docs/distribution.md " +
                    "2.1): $install is declared",
                declared.contains(install),
            )
        }
    }
}
