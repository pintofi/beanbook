package io.github.pintofi.beanbook.core.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Catalogue plugins install and remove one at a time, and only from the pinned archive (B82). */
class CatalogueInstallerTest {

    private val root: File = Files.createTempDirectory("catalogue").toFile()
    private val installer = CatalogueInstaller(root)

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun zip(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                files.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(
        id: String,
        archive: ByteArray,
        paths: List<String>,
        strip: String = "",
        importName: String? = null,
    ) =
        PluginCatalogue.Entry(
            id = id, name = id, kind = "beancount-plugin", description = "", repo = "", licence = "MIT",
            licenceNote = null, status = "supported", statusReason = null,
            install = PluginCatalogue.Install(
                source = "github", archive = "https://example.invalid/a.zip", sha256 = sha(archive),
                version = "abc123", strip = strip, paths = paths, importName = importName,
                requires = emptyList(), modules = listOf("$id.plugin"),
            ),
        )

    @Test
    fun `the named package is taken out of a GitHub archive and nothing else`() {
        val archive = zip(
            "repo-abc/src/lazy/__init__.py" to "", "repo-abc/src/lazy/valuation.py" to "x = 1",
            "repo-abc/tests/test_x.py" to "", "repo-abc/README.md" to "",
        )
        val lazy = entry("lazy.valuation", archive, listOf("src/lazy"), strip = "repo-abc/")

        val site = installer.installCode(lazy, archive).getOrThrow()
        installer.markInstalled(lazy)

        assertTrue(File(site, "lazy/valuation.py").isFile)
        assertFalse(File(site, "tests").exists())
        assertFalse(File(site, "README.md").exists())
        assertEquals(mapOf("lazy.valuation" to "abc123"), installer.installed())
    }

    @Test
    fun `a single-file plugin and a root-level extension`() {
        val single = zip("m-1/mortgage.py" to "def f(): pass")
        val mortgage = entry("beancount-mortgage", single, listOf("mortgage.py"), strip = "m-1/")
        installer.installCode(mortgage, single).getOrThrow()
        assertTrue(File(installer.siteFor("beancount-mortgage"), "mortgage.py").isFile)

        val rootLevel = zip("s-1/__init__.py" to "", "s-1/templates/Summary.html" to "")
        val summary = entry(
            "fava-portfolio-summary", rootLevel, listOf(""), strip = "s-1/", importName = "fava_portfolio_summary",
        )
        installer.installCode(summary, rootLevel).getOrThrow()
        val template = File(
            installer.siteFor("fava-portfolio-summary"),
            "fava_portfolio_summary/templates/Summary.html",
        )
        assertTrue(template.isFile)
    }

    @Test
    fun `an archive that is not the pinned one is refused and leaves nothing`() {
        val pinned = zip("p/__init__.py" to "")
        val other = zip("p/__init__.py" to "import os; os.remove('/')")
        val result = installer.installCode(entry("p", pinned, listOf("p")), other)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("does not match"))
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }

    @Test
    fun `a member escaping the folder is refused`() {
        val evil = zip("p/../../outside.py" to "")
        val result = installer.installCode(entry("p", evil, listOf("p")), evil)
        assertTrue(result.isFailure)
        assertFalse(File(root.parentFile, "outside.py").exists())
    }

    @Test
    fun `removing one plugin leaves the other, and an unfinished install is not reported`() {
        val a = zip("a/__init__.py" to ""); val b = zip("b/__init__.py" to "")
        val first = entry("a", a, listOf("a")); val second = entry("b", b, listOf("b"))
        installer.installCode(first, a).getOrThrow(); installer.markInstalled(first)
        installer.installCode(second, b).getOrThrow()

        assertEquals(setOf("a"), installer.installed().keys, "b's packages never finished, so b is not installed")
        installer.remove("a")
        assertFalse(File(root, "a").exists())
        assertTrue(File(root, "b").exists())
    }

    @Test
    fun `the shipped catalogue parses, and every entry it pins is installable in principle`() {
        val catalogue = PluginCatalogue.parse(File("../catalog/plugins.json").readText())
        assertTrue(catalogue.entries.size >= 36, "expected every awesome-beancount plugin plus beancount-interpolate")
        catalogue.entries.forEach { item ->
            val install = item.install
            assertTrue(install != null, "${item.id} has no install pin")
            assertTrue(install!!.paths.isNotEmpty(), "${item.id} names no code")
            // An importer is run by an import workflow, not named in a ledger.
            if (item.kind != "beancount-importer") {
                assertTrue(install.modules.isNotEmpty(), "${item.id} names nothing a ledger could use")
            }
        }
    }
}
