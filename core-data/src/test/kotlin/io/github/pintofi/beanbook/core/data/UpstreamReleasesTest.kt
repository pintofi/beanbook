package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpstreamReleasesTest {

    private fun file(
        name: String,
        requiresPython: String? = null,
        yanked: Boolean = false,
        host: String = "files.pythonhosted.org",
    ): String {
        val python = requiresPython?.let { "\"$it\"" } ?: "null"
        val digest = "a".repeat(64)
        return """{"filename":"$name","url":"https://$host/p/$name","yanked":$yanked,""" +
            """"requires_python":$python,"digests":{"sha256":"$digest"}}"""
    }

    private fun pypi(latest: String, vararg releases: Pair<String, List<String>>, requires: String = "[]"): String {
        val body = releases.joinToString(",") { (v, files) -> "\"$v\":[${files.joinToString(",")}]" }
        return """{"info":{"version":"$latest","requires_dist":$requires},"releases":{$body}}"""
    }

    private fun pick(json: String, installed: String?) = UpstreamReleases.pypi(json, installed).getOrThrow()

    @Test
    fun `the newest usable version is offered when it is newer than the installed one`() {
        val json = pypi(
            "2.0",
            "1.0" to listOf(file("p-1.0-py3-none-any.whl")),
            "1.5" to listOf(file("p-1.5-py3-none-any.whl")),
            "2.0" to listOf(file("p-2.0-py3-none-any.whl")),
        )

        val release = pick(json, "1.5")!!

        assertEquals("2.0", release.version)
        assertEquals("published-digest", release.verified)
        assertEquals("a".repeat(64), release.sha256)
    }

    @Test
    fun `an installed version that is current or ahead is up to date`() {
        val json = pypi("2.0", "2.0" to listOf(file("p-2.0-py3-none-any.whl")))

        assertNull(pick(json, "2.0"))
        assertNull(pick(json, "2.1"))
        assertNull(pick(json, "2.0.0"))
    }

    @Test
    fun `the newest release is skipped when it is yanked, a pre-release, or has no pure wheel`() {
        val json = pypi(
            "4.0",
            "1.0" to listOf(file("p-1.0-py3-none-any.whl")),
            "2.0" to listOf(file("p-2.0-py3-none-any.whl")),
            "3.0" to listOf(file("p-3.0-py3-none-any.whl", yanked = true)),
            "3.5rc1" to listOf(file("p-3.5rc1-py3-none-any.whl")),
            "4.0" to listOf(file("p-4.0-cp313-cp313-manylinux_2_17_x86_64.whl")),
        )

        assertEquals("2.0", pick(json, "1.0")!!.version)
    }

    @Test
    fun `a release that needs a newer Python than this one is not offered`() {
        val json = pypi(
            "2.0",
            "1.0" to listOf(file("p-1.0-py3-none-any.whl")),
            "2.0" to listOf(file("p-2.0-py3-none-any.whl", requiresPython = ">=3.14")),
        )

        assertNull(pick(json, "1.0"))
    }

    @Test
    fun `a pre-release is offered only to someone already on one`() {
        val json = pypi(
            "2.0rc1",
            "1.0" to listOf(file("p-1.0-py3-none-any.whl")),
            "2.0rc1" to listOf(file("p-2.0rc1-py3-none-any.whl")),
        )

        assertNull(pick(json, "1.0"))
        assertEquals("2.0rc1", pick(json, "2.0b1")!!.version)
    }

    @Test
    fun `the requirements are read only for the latest release and left alone otherwise`() {
        val files = listOf(file("p-2.0-py3-none-any.whl"))
        val latest = pypi(
            "2.0", "1.0" to listOf(file("p-1.0-py3-none-any.whl")), "2.0" to files,
            requires = """["PyYAML>=6; python_version >= '3.8'","requests","extra-only; extra == 'dev'"]""",
        )
        assertEquals(listOf("pyyaml", "requests"), pick(latest, "1.0")!!.requires)

        val older = pypi(
            "3.0", "2.0" to files, "3.0" to listOf(file("p-3.0-py3-none-any.whl", yanked = true)),
            requires = """["x"]""",
        )
        assertNull(pick(older, "2.0"))
        assertNull(pick(older.replace("\"3.0\":[", "\"1.0\":[").replace("\"2.0\":", "\"2.0\":"), "9"))
    }

    @Test
    fun `a file from somewhere other than PyPI's file host is refused`() {
        val json = pypi("2.0", "2.0" to listOf(file("p-2.0-py3-none-any.whl", host = "evil.example")))

        assertTrue(UpstreamReleases.pypi(json, "1.0").isFailure)
    }

    @Test
    fun `an answer that cannot be read is a failure and not an up to date`() {
        assertTrue(UpstreamReleases.pypi("<html>", "1.0").isFailure)
        assertTrue(UpstreamReleases.pypi("""{"info":{}}""", "1.0").isFailure)
    }

    @Test
    fun `a GitHub release becomes a download of its tag and a draft or pre-release is not one`() {
        val release = UpstreamReleases.githubLatest("""{"tag_name":"v1.2.0","prerelease":false}""", "o/r")!!
        assertEquals("v1.2.0", release.version)
        assertEquals("https://codeload.github.com/o/r/zip/refs/tags/v1.2.0", release.archive)
        assertNull(release.sha256)
        assertEquals("received-only", release.verified)
        assertNull(UpstreamReleases.githubLatest("""{"tag_name":"v2","prerelease":true}""", "o/r"))
        assertNull(UpstreamReleases.githubLatest("""{"tag_name":"v2","draft":true}""", "o/r"))
        assertNull(UpstreamReleases.githubLatest("""{"tag_name":"../x; rm"}""", "o/r"))
        assertNull(UpstreamReleases.githubLatest("""{"message":"Not Found"}""", "o/r"))
    }

    @Test
    fun `the newest commit becomes a download of that commit`() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val release = UpstreamReleases.githubHead("""{"sha":"$sha"}""", "o/r")!!
        assertEquals("0123456789ab", release.version)
        assertEquals("https://codeload.github.com/o/r/zip/$sha", release.archive)
        assertNull(UpstreamReleases.githubHead("""{"sha":"nope"}""", "o/r"))
    }

    @Test
    fun `compare says ahead only when the candidate is strictly ahead`() {
        assertTrue(UpstreamReleases.githubAhead("""{"status":"ahead"}"""))
        assertFalse(UpstreamReleases.githubAhead("""{"status":"identical"}"""))
        assertFalse(UpstreamReleases.githubAhead("""{"status":"diverged"}"""))
        assertFalse(UpstreamReleases.githubAhead("""{"status":"behind"}"""))
        assertFalse(UpstreamReleases.githubAhead("not json"))
    }

    @Test
    fun `two release tags are ordered and a commit is not orderable`() {
        assertEquals(true, UpstreamReleases.tagNewer("v1.9.0", "v1.10.0"))
        assertEquals(false, UpstreamReleases.tagNewer("v1.10.0", "v1.9.0"))
        assertNull(UpstreamReleases.tagNewer("cd5496872d98", "v1.0.0"))
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray =
        java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { z ->
                files.forEach { (n, t) ->
                    z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry()
                }
            }
        }.toByteArray()

    private fun entry(source: String, strip: String = "") = PluginCatalogue.Entry(
        id = "lazy", name = "lazy", kind = "beancount-plugin", description = "", repo = "", licence = "MIT",
        licenceNote = null, status = "supported", statusReason = null,
        install = PluginCatalogue.Install(
            source = source, archive = "https://example.invalid/old", sha256 = "b".repeat(64), version = "1",
            strip = strip, paths = listOf("pkg"), importName = null, requires = listOf("old"),
            modules = listOf("pkg.m"),
        ),
    )

    private fun sha(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `an entry at a release keeps what was tested and takes what arrived`() {
        val archive = zip("repo-1.2/pkg/__init__.py" to "")
        val release = UpstreamRelease(
            "v1.2", "https://codeload.github.com/o/r/zip/refs/tags/v1.2", null, "received-only", null, "github",
        )

        val updated = UpstreamReleases.entryFor(entry("github", "repo-abc/"), release, archive).install!!

        assertEquals("repo-1.2/", updated.strip)
        assertEquals(sha(archive), updated.sha256)
        assertEquals("v1.2", updated.version)
        assertEquals(listOf("pkg"), updated.paths)
        assertEquals(listOf("old"), updated.requires)
    }

    @Test
    fun `a published digest that is not the one that arrived refuses the update`() {
        val archive = zip("pkg/__init__.py" to "")
        val release = UpstreamRelease(
            "2.0", "https://files.pythonhosted.org/x.whl", "c".repeat(64), "published-digest", listOf("new"), "pypi",
        )

        val failure = runCatching { UpstreamReleases.entryFor(entry("pypi"), release, archive) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `a matching published digest is accepted and the new requirements are used`() {
        val archive = zip("pkg/__init__.py" to "")
        val release = UpstreamRelease(
            "2.0", "https://files.pythonhosted.org/x.whl", sha(archive), "published-digest", listOf("new"), "pypi",
        )

        val updated = UpstreamReleases.entryFor(entry("pypi"), release, archive).install!!

        assertEquals(listOf("new"), updated.requires)
        assertEquals("", updated.strip)
    }
}
