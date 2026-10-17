package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.packages.ArchiveFetcher
import io.github.pintofi.beanbook.core.data.CatalogueInstaller
import io.github.pintofi.beanbook.core.data.PluginCatalogue
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every catalogue pin downloads on a device, matches its checksum, and yields code.
 *
 * **Uses the network**, like `WheelFetcherTest`: the pins point at PyPI and GitHub, and whether
 * they still resolve cannot be known any other way. Fails with the list of entries that did not,
 * rather than passing quietly offline. Dependencies are not fetched here -- that path is the
 * package fetcher's, tested by `WheelFetcherTest` -- so this proves the pins, not every closure.
 */
@RunWith(AndroidJUnit4::class)
class CatalogueDownloadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "catalogue-test-${System.nanoTime()}")

    @After fun clean() {
        root.deleteRecursively()
    }

    @Test fun everyPinnedPluginDownloadsVerifiesAndUnpacks() {
        val catalogue = context.assets.open("catalog/plugins.json").bufferedReader().use {
            PluginCatalogue.parse(it.readText())
        }
        val installer = CatalogueInstaller(root)
        val failures = mutableListOf<String>()
        catalogue.entries.filter { it.install != null }.forEach { entry ->
            val result = ArchiveFetcher().fetch(entry.install!!.archive)
                .mapCatching { installer.installCode(entry, it).getOrThrow() }
            result.onSuccess { site ->
                if (site.walkTopDown().none { it.isFile && it.extension == "py" }) {
                    failures += "${entry.id}: no Python files installed"
                }
            }.onFailure { failures += "${entry.id}: ${it.message}" }
        }
        assertTrue(
            "catalogue entries that did not install:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
        assertTrue("the catalogue shipped no pinned entries", catalogue.entries.count { it.install != null } >= 36)
    }
}
