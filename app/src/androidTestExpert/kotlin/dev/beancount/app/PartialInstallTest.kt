package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.packages.FetchResult
import dev.beancount.app.packages.Wheel
import dev.beancount.app.packages.WheelFetcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A batch of wheels that fails part-way leaves nothing behind (R7).
 *
 * `install` unpacked each wheel straight into the install root. A digest mismatch at wheel
 * *n* returned a failure with wheels 1..n-1 already unpacked and nothing deleting them, so
 * `installed()` then reported packages the app had decided not to trust — while the comment
 * beside the check said the opposite.
 *
 * Offline: the fetcher reads a wheel from its spool when one is there, so seeding the spool
 * exercises the whole install path without the network.
 */
@RunWith(AndroidJUnit4::class)
class PartialInstallTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "partial-install-${System.nanoTime()}")
    private val spool = File(root.parentFile, "${root.name}-spool")
    private val staging = File(root.parentFile, "${root.name}-staging")

    @After fun clean() {
        root.deleteRecursively(); spool.deleteRecursively(); staging.deleteRecursively()
    }

    private fun wheelBytes(module: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("$module/__init__.py"))
            zip.write("# $module\n".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Seeds the spool so `install` never reaches the network. */
    private fun seed(name: String): Pair<Wheel, ByteArray> {
        val bytes = wheelBytes(name)
        spool.mkdirs()
        File(spool, "$name.whl").writeBytes(bytes)
        return Wheel(name, "1.0", "https://example.invalid/$name.whl", sha256(bytes), false) to bytes
    }

    @Test fun aBatchThatFailsPartWayInstallsNothing() {
        val (good, _) = seed("goodpkg")
        val (bad, _) = seed("badpkg")
        // The second wheel's published digest does not match the bytes on the spool.
        val liar = bad.copy(sha256 = "0".repeat(64))

        val fetcher = WheelFetcher(root, allowed = true, allowNative = true, assets = context.assets)
        val result = fetcher.install(listOf(good, liar))

        assertTrue("expected a failure, got $result", result is FetchResult.Failed)
        assertFalse(
            "the first wheel was left installed after the batch failed",
            File(root, "goodpkg").exists(),
        )
        assertTrue(
            "installed() still reports a package from the failed batch: ${fetcher.installed()}",
            fetcher.installed().none { it.contains("goodpkg") },
        )
        assertFalse("the staging directory survived", staging.exists())
        assertFalse("the spool survived a failed install", spool.exists())
    }

    @Test fun aBatchThatSucceedsInstallsEverything() {
        val (one, _) = seed("alpha")
        val (two, _) = seed("beta")

        val fetcher = WheelFetcher(root, allowed = true, allowNative = true, assets = context.assets)
        val result = fetcher.install(listOf(one, two))

        assertTrue("expected success, got $result", result is FetchResult.Installed)
        assertTrue("alpha is missing", File(root, "alpha").exists())
        assertTrue("beta is missing", File(root, "beta").exists())
        assertFalse("the staging directory survived", staging.exists())
    }
}
