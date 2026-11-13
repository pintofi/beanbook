package io.github.pintofi.beanbook.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.packages.FetchResult
import io.github.pintofi.beanbook.app.packages.PackageUpdate
import io.github.pintofi.beanbook.app.packages.Wheel
import io.github.pintofi.beanbook.app.packages.WheelFetcher
import io.github.pintofi.beanbook.core.data.PackageRecords
import io.github.pintofi.beanbook.core.data.PackageSwap
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An update is built beside the packages in use, verified before it is unpacked, and switched to with
 * the earlier ones kept. The download is a spooled file, which is how the fetcher already reuses a wheel
 * it resolved, so no network is involved.
 */
class PackageUpdateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "pkgupdate-${System.nanoTime()}")
    private val root = File(base, "ondemand")
    private val spool = File(base, "ondemand-spool")

    @After
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun installedV1() {
        File(root, "pkgx").apply { mkdirs() }
        File(root, "pkgx/__init__.py").writeText("VERSION = 1\n")
        File(root, "pkgx-1.0.dist-info").apply { mkdirs() }
        File(root, "pkgx-1.0.dist-info/RECORD").writeText("pkgx/__init__.py,,\npkgx-1.0.dist-info/RECORD,,\n")
        File(root, "pkgx-1.0.dist-info/WHEEL").writeText("Tag: py3-none-any\n")
        File(root, "pkgx-1.0.dist-info/METADATA").writeText("Name: pkgx\nVersion: 1.0\n")
    }

    private fun wheelV2(): Pair<ByteArray, Wheel> {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                mapOf(
                    "pkgx/__init__.py" to "VERSION = 2\n",
                    "pkgx-2.0.dist-info/RECORD" to "pkgx/__init__.py,,\n",
                    "pkgx-2.0.dist-info/WHEEL" to "Tag: py3-none-any\n",
                    "pkgx-2.0.dist-info/METADATA" to "Name: pkgx\nVersion: 2.0\n",
                    "pkgx-2.0.dist-info/top_level.txt" to "pkgx\n",
                ).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val url = "https://files.pythonhosted.org/packages/x/pkgx-2.0-py3-none-any.whl"
        spool.mkdirs()
        File(spool, "pkgx-2.0-py3-none-any.whl").writeBytes(bytes)
        return bytes to Wheel("pkgx", "2.0", url, sha, native = false)
    }

    private fun fetcher() = WheelFetcher(root, allowed = true, allowNative = true, assets = null)

    @Test
    fun anUpdateReplacesTheOldFilesKeepsTheEarlierPackagesAndCanBeUndone() {
        installedV1()
        val (_, wheel) = wheelV2()

        val result = fetcher().applyUpdates(listOf(PackageUpdate("pkgx", "1.0", "2.0", wheel)))

        assertTrue(result.toString(), result is FetchResult.Installed)
        assertEquals("VERSION = 2\n", File(root, "pkgx/__init__.py").readText())
        assertFalse(File(root, "pkgx-1.0.dist-info").exists())
        assertTrue(File(root, "pkgx-2.0.dist-info").isDirectory)
        assertEquals("VERSION = 1\n", File(base, "ondemand-previous/pkgx/__init__.py").readText())
        assertEquals(listOf("pkgx"), fetcher().importNames(listOf(wheel)))
        assertEquals("2.0", PackageRecords(root, File(root, ".records.json")).current().single().version)

        val swap = PackageSwap(root)
        assertTrue(swap.isOnTrial())
        swap.rollback().getOrThrow()

        assertEquals("VERSION = 1\n", File(root, "pkgx/__init__.py").readText())
    }

    @Test
    fun aDownloadThatIsNotTheFileTheIndexPublishedChangesNothing() {
        installedV1()
        val (_, wheel) = wheelV2()

        val tampered = wheel.copy(sha256 = "0".repeat(64))

        val result = fetcher().applyUpdates(listOf(PackageUpdate("pkgx", "1.0", "2.0", tampered)))

        assertTrue(result.toString(), result is FetchResult.Failed)
        assertEquals("VERSION = 1\n", File(root, "pkgx/__init__.py").readText())
        assertFalse(File(base, "ondemand-next").exists())
        assertFalse(File(base, "ondemand-previous").exists())
    }

    @Test
    fun anUpdateKilledBeforeItWasProvedIsUndoneOnTheNextStart() {
        installedV1()
        val (_, wheel) = wheelV2()
        fetcher().applyUpdates(listOf(PackageUpdate("pkgx", "1.0", "2.0", wheel)))

        PackageSwap(root).recover()

        assertEquals("VERSION = 1\n", File(root, "pkgx/__init__.py").readText())
    }

    @Test
    fun theEditionThatMayNotDownloadRefuses() {
        installedV1()
        val (_, wheel) = wheelV2()

        val refused = WheelFetcher(root, allowed = false, allowNative = false, assets = null)
            .applyUpdates(listOf(PackageUpdate("pkgx", "1.0", "2.0", wheel)))

        assertTrue(refused is FetchResult.Refused)
    }
}
