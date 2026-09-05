package dev.beancount.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Neither edition may install packages.
 *
 * This test used to assert the opposite for Expert: that edition shipped a self-updater which
 * committed a `PackageInstaller` session, and so had to declare `REQUEST_INSTALL_PACKAGES`.
 * Both are gone. Updating a sideloaded build is now what it is for any app installed from a
 * file — the user downloads the new one and opens it, and Android refuses anything not signed
 * with the key that signed the installed app.
 *
 * So the permission is now a thing to keep out rather than a thing to check for, in both
 * flavors, and the assertion is the same for both. `:verifyPlayFlavorBoundaries` covers the
 * Play half at build time (D3, docs/distribution.md 2.1); this covers both at run time, from
 * the merged manifest of the installed package rather than from a source file, because what
 * matters is what the running app was actually granted.
 */
@RunWith(AndroidJUnit4::class)
class InstallPermissionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noEditionMayInstallPackages() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val declared = info.requestedPermissions?.toList().orEmpty()
        val install = "android.permission.REQUEST_INSTALL_PACKAGES"

        assertFalse(
            "no edition installs packages any more: the self-updater was removed and " +
                "updating a sideloaded build is download-and-open. $install is declared, " +
                "so something reintroduced it. Declared: $declared",
            declared.contains(install),
        )
    }
}
