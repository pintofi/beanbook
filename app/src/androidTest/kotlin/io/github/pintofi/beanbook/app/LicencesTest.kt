package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.ui.loadLicences
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does every third-party component this app ships have a notice a user can read?
 *
 * Not "does the screen render" — that is the easy half. MIT, BSD, X11 and Apache-2.0 all
 * require the notice to travel with the software, and the GPL requires the terms be offered
 * to the user, so the property worth testing is **coverage**: the set of packages actually
 * inside the installed APK, compared against the set the licences screen knows about.
 *
 * It reads the installed APK rather than a build directory, so what is checked is what a user
 * would have on their device.
 */
@RunWith(AndroidJUnit4::class)
class LicencesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Every Python distribution packed into the APK we are running from. */
    private fun packagesInInstalledApk(): Set<String> {
        val found = mutableSetOf<String>()
        val pattern = Regex("""([A-Za-z0-9_.\-]+)-[0-9][^/]*\.dist-info/""")

        ZipFile(File(context.applicationInfo.sourceDir)).use { apk ->
            apk.entries().asSequence()
                .filter { it.name.startsWith("assets/chaquopy/requirements") }
                .forEach { entry ->
                    ZipInputStream(apk.getInputStream(entry)).use { inner ->
                        generateSequence { inner.nextEntry }.forEach { nested ->
                            pattern.find(nested.name)?.let {
                                found += it.groupValues[1].lowercase().replace("-", "_")
                            }
                        }
                    }
                }
        }
        return found
    }

    @Test fun everyPythonPackageInTheApkHasALicenceNotice() {
        val shipped = packagesInInstalledApk()
        assertTrue(
            "no Python packages found in the installed APK, so this test proves nothing",
            shipped.size > 20,
        )

        val (_, components) = loadLicences(context)
        val documented = components.map { it.name }.toSet()

        assertEquals(
            "packages ship in the APK with no licence notice the user can read",
            emptySet<String>(),
            shipped - documented,
        )
    }

    @Test fun everyNoticeHasRealLicenceTextRatherThanAName() {
        val (app, components) = loadLicences(context)

        (listOf(app) + components).forEach { entry ->
            // A licence file is never this short. The failure this catches is a notice that
            // says "MIT" and nothing else, which satisfies nobody.
            assertTrue(
                "${entry.name} has no usable licence text (${entry.text.length} chars)",
                entry.text.length > 400,
            )
        }
    }

    @Test fun theAppsOwnLicenceIsTheGplVersionTwoAndNotVersionThree() {
        val (app, _) = loadLicences(context)

        assertEquals("GPL-2.0-only", app.version)
        assertTrue(
            "the app's licence text is not the GPL version 2",
            app.text.contains("Version 2, June 1991"),
        )
        assertFalse(
            "the app ships GPLv3 text; Beancount is GPL-2.0-only, explicitly not or-later",
            app.text.contains("Version 3, 29 June 2007"),
        )
        assertTrue(
            "the full terms are not there",
            app.text.contains("END OF TERMS AND CONDITIONS"),
        )
    }

    @Test fun beancountsOwnLicenceIsPresentEvenThoughItsWheelCarriesNone() {
        // beancount's sdist has COPYING, but upstream declares no license-files, so neither
        // its PyPI wheel nor the one we cross-build contains it. The notice is supplied from
        // `licences/`, and the substitution is stated rather than hidden.
        val (_, components) = loadLicences(context)
        val beancount = components.single { it.name == "beancount" }

        assertTrue(
            "the substitution is not disclosed: ${beancount.source}",
            beancount.source.contains("supplied here"),
        )
        assertTrue(
            "beancount's notice is not the GPL",
            beancount.text.contains("GNU GENERAL PUBLIC LICENSE"),
        )
    }
}
