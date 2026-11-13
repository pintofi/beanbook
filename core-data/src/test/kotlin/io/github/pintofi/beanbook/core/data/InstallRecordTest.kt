package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallRecordTest {

    private val root: File = Files.createTempDirectory("records").toFile()
    private val installer = CatalogueInstaller(root)

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun install(source: String, archive: String, version: String) = PluginCatalogue.Install(
        source = source, archive = archive, sha256 = "a".repeat(64), version = version, strip = "",
        paths = listOf("pkg"), importName = null, requires = emptyList(), modules = listOf("pkg.mod"),
    )

    private fun entry(id: String, install: PluginCatalogue.Install?) = PluginCatalogue.Entry(
        id = id, name = id, kind = "beancount-plugin", description = "", repo = "", licence = "MIT",
        licenceNote = null, status = "supported", statusReason = null, install = install,
    )

    private val wheel = "https://files.pythonhosted.org/packages/26/ad/x/Auto_Bean-0.2.3-py3-none-any.whl"
    private val zip = "https://codeload.github.com/daniel-wells/beancount_balancechange/zip/cd5496872d9885"

    @Test
    fun `a PyPI wheel names its project and a GitHub archive its repository`() {
        assertEquals(UpstreamRef.PyPI("auto-bean"), UpstreamRef.of(install("pypi", wheel, "0.2.3")))
        assertEquals(
            UpstreamRef.GitHub("daniel-wells/beancount_balancechange"),
            UpstreamRef.of(install("github", zip, "cd5496872d98")),
        )
        assertNull(UpstreamRef.of(install("pypi", "https://example.invalid/file.zip", "1")))
        assertNull(UpstreamRef.of(install("other", wheel, "1")))
    }

    @Test
    fun `an install from before records is rebuilt from the version file and the catalogue`() {
        File(root, "lazy").mkdirs()
        File(root, "lazy/installed-version").writeText("0.2.3")
        val e = entry("lazy", install("pypi", wheel, "0.2.3"))

        val record = installer.record("lazy", e)!!

        assertEquals("auto-bean", record.upstream)
        assertEquals("catalogue-pin", record.verified)
        assertEquals("a".repeat(64), record.sha256)
    }

    @Test
    fun `an old version the catalogue no longer pins has a version and an upstream but no hash`() {
        File(root, "lazy").mkdirs()
        File(root, "lazy/installed-version").writeText("0.1.0")
        val e = entry("lazy", install("pypi", wheel, "0.2.3"))

        val record = installer.record("lazy", e)!!

        assertEquals("0.1.0", record.version)
        assertEquals("auto-bean", record.upstream)
        assertEquals("not-recorded", record.verified)
        assertEquals("", record.sha256)
    }

    @Test
    fun `a plugin with no entry and no record is not described`() {
        assertNull(installer.record("nothing", null))
    }

    @Test
    fun `marking installed writes the record and a damaged record falls back`() {
        File(root, "lazy").mkdirs()
        val e = entry("lazy", install("github", zip, "cd5496872d98"))

        installer.markInstalled(e, now = 42L)
        val written = installer.record("lazy", null)!!

        assertEquals("daniel-wells/beancount_balancechange", written.upstream)
        assertEquals(42L, written.installedAtEpochMs)
        assertFalse(File(root, "lazy/install.json.tmp").exists())
        File(root, "lazy/install.json").writeText("{ not json")
        assertEquals("cd5496872d98", installer.record("lazy", e)!!.version)
    }

    @Test
    fun `a newer pinned version is an update and an older one is not`() {
        val e = entry("lazy", install("pypi", wheel, "0.10.0"))
        assertTrue(e.updatesFrom("0.9.0"))
        assertFalse(e.updatesFrom("0.10.0"))
        assertFalse(e.updatesFrom("0.11.0"))
        assertFalse(e.updatesFrom("1.0.0"))
    }

    @Test
    fun `commits are different or not, since nothing orders them`() {
        val e = entry("lazy", install("github", zip, "cd5496872d98"))
        assertTrue(e.updatesFrom("0123456789ab"))
        assertFalse(e.updatesFrom("cd5496872d98"))
    }
}
