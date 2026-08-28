package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.packages.FetchResult
import dev.beancount.app.packages.WheelFetcher
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fetching a Python package on demand, on a device.
 *
 * The app bundles what Beancount and Fava need. beangrow's returns reports need numpy,
 * pandas, matplotlib and protobuf on top of that — a 37 MB download that takes about 143 MB
 * once unpacked, which does not belong in every install for a feature most people will not
 * use. So the expert edition fetches them when asked.
 *
 * These drive `WheelFetcher` directly. What drives it the way the app does is
 * [PackagesStateTest], and the difference mattered: the fetcher passed every test here while
 * no app code called it at all.
 *
 * These tests use the network deliberately and are the only ones here that do. They are
 * written to fail *informatively* when it is unavailable rather than to pass quietly.
 */
@RunWith(AndroidJUnit4::class)
class WheelFetcherTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "wheel-fetcher-test")

    private fun fetcher(allowed: Boolean = true, native: Boolean = true) =
        WheelFetcher(root, allowed = allowed, allowNative = native)

    @After fun clean() {
        root.deleteRecursively()
    }

    @Test fun aPurePythonPackageResolvesToAWheelWithAPublishedDigest() {
        val resolved = fetcher().resolve(listOf("packaging")).getOrElse {
            throw AssertionError("could not resolve packaging — is the network up? $it")
        }
        val packaging = resolved.wheels.single { it.name == "packaging" }

        assertTrue("resolved to a wheel with no digest", packaging.sha256.length == 64)
        assertFalse("packaging should be pure Python", packaging.native)
        assertTrue(
            "expected a pure-Python wheel, got ${packaging.filename}",
            packaging.filename.endsWith("-py3-none-any.whl"),
        )
    }

    @Test fun installingPutsAnImportableModuleOnDisk() {
        val resolved = fetcher().resolve(listOf("packaging")).getOrThrow()
        val result = fetcher().install(resolved.wheels)

        assertTrue("install did not succeed: $result", result is FetchResult.Installed)
        assertTrue(
            "nothing importable was written; got ${root.list()?.toList()}",
            File(root, "packaging").isDirectory,
        )
        assertTrue("the package has no modules", File(root, "packaging/version.py").isFile)
    }

    @Test fun aPackageAlreadyInTheApkIsNeverFetched() {
        // Fetching `beancount` would put a second copy ahead of the tested one on sys.path,
        // which is a good way to make a working app stop working.
        val resolved = fetcher().resolve(listOf("beancount", "packaging")).getOrThrow()

        assertTrue(
            "the bundled beancount would have been downloaded over itself",
            resolved.wheels.none { it.name == "beancount" },
        )
        assertTrue("packaging should still resolve", resolved.wheels.any { it.name == "packaging" })
    }

    @Test fun theSpoolIsNotLeftBehindAfterASuccessfulInstall() {
        // The spool exists so a wheel is downloaded once rather than twice. Left behind, it
        // would double the disk cost of every install instead — and for a closure containing
        // OpenBLAS that is tens of megabytes of nothing.
        val resolved = fetcher().resolve(listOf("packaging")).getOrThrow()
        fetcher().install(resolved.wheels)

        assertFalse(
            "the download spool was left on disk",
            File(root.parentFile, "${root.name}-spool").exists(),
        )
    }

    @Test fun preloadingIsHarmlessWhenThereAreNoCompanionLibraries() {
        // Pure-Python packages bring no `chaquopy/lib`. Preloading has to be a no-op there
        // rather than an error, because the caller cannot know which kind it just installed.
        val resolved = fetcher().resolve(listOf("packaging")).getOrThrow()
        fetcher().install(resolved.wheels)

        assertTrue("preloading invented a library", fetcher().preloadNativeLibraries().isEmpty())
    }

    @Test fun installedDoesNotCountThingsThatAreNotPackages() {
        val resolved = fetcher().resolve(listOf("packaging")).getOrThrow()
        fetcher().install(resolved.wheels)

        // `chaquopy` is where native wheels put their shared libraries, and a spool is a
        // download in progress. Reporting either as installed makes "is numpy here?"
        // answerable with the wrong yes.
        File(root, "chaquopy/lib").mkdirs()
        val installed = fetcher().installed()

        assertTrue("packaging should be reported", "packaging" in installed)
        assertFalse("the shared library directory is not a package", "chaquopy" in installed)
    }

    @Test fun theEditionThatMayNotDownloadRefusesRatherThanFailing() {
        val refused = fetcher(allowed = false)
            .install(fetcher().resolve(listOf("packaging")).getOrThrow().wheels)

        // Refused, not Failed: this edition is working correctly by not doing it.
        assertTrue("expected a refusal, got $refused", refused is FetchResult.Refused)
        assertFalse("something was installed anyway", File(root, "packaging").isDirectory)
    }

    @Test fun anEditionThatMayNotInstallNativeCodeSaysSoBeforeDownloadingAnything() {
        // numpy is native. The Play edition may not install it, and the refusal has to come
        // at resolve time — before the bytes cross the network, not after.
        val outcome = fetcher(native = false).resolve(listOf("numpy"))

        assertTrue("resolving numpy without native code should fail", outcome.isFailure)
        assertTrue(
            "the reason should name native code: ${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull()?.message.orEmpty().contains("native code"),
        )
    }
}
