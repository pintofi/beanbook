package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.app.packages.UpdateChecker
import io.github.pintofi.beanbook.core.data.InstallRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the update check asks, and what it does with the answers. The network is a fake. */
class UpdateCheckerTest {

    private fun record(source: String, upstream: String?) = InstallRecord(
        name = "lazy", version = "x", source = source, upstream = upstream, archive = "", sha256 = "",
        verified = "catalogue-pin", installedAtEpochMs = 0,
    )

    private val digest = "a".repeat(64)
    private val pypiAnswer = """{"info":{"version":"2.0","requires_dist":["pyyaml"]},"releases":{
        "1.0":[{"filename":"p-1.0-py3-none-any.whl","url":"https://files.pythonhosted.org/p-1.0-py3-none-any.whl",
        "digests":{"sha256":"$digest"}}],
        "2.0":[{"filename":"p-2.0-py3-none-any.whl","url":"https://files.pythonhosted.org/p-2.0-py3-none-any.whl",
        "digests":{"sha256":"$digest"}}]}}"""

    @Test
    fun aNewerPyPIVersionIsFoundAndAnInstalledCurrentOneIsNot() {
        val asked = mutableListOf<String>()
        val checker = UpdateChecker { url -> asked += url; 200 to pypiAnswer }

        val newer = checker.check(record("pypi", "p"), "1.0").getOrThrow()
        val current = checker.check(record("pypi", "p"), "2.0").getOrThrow()

        assertEquals("2.0", newer?.version)
        assertNull(current)
        assertEquals("https://pypi.org/pypi/p/json", asked.first())
    }

    @Test
    fun aSourceThatCannotBeReachedIsAFailureAndNotUpToDate() {
        val down = UpdateChecker { 503 to "" }.check(record("pypi", "p"), "1.0")
        val rateLimited = UpdateChecker { 403 to "" }.check(record("github", "o/r"), "v1.0.0")

        assertTrue(down.isFailure)
        assertTrue(rateLimited.exceptionOrNull()?.message.orEmpty().contains("limiting"))
    }

    @Test
    fun aRecordWithNoUpstreamIsNotAskedAbout() {
        var asked = false
        val checker = UpdateChecker { asked = true; 200 to "{}" }

        assertTrue(checker.check(record("pypi", null), "1").isFailure)
        assertTrue(checker.check(record("zip", "x"), "1").isFailure)
        assertEquals(false, asked)
    }

    @Test
    fun aGitHubReleaseNewerThanTheInstalledTagIsOffered() {
        val checker = UpdateChecker { url ->
            when {
                url.endsWith("/releases/latest") -> 200 to """{"tag_name":"v1.10.0"}"""
                else -> error("unexpected $url")
            }
        }

        assertEquals("v1.10.0", checker.check(record("github", "o/r"), "v1.9.0").getOrThrow()?.version)
        assertNull(checker.check(record("github", "o/r"), "v1.10.0").getOrThrow())
    }

    @Test
    fun aCommitIsComparedByGitHubAndOnlyAheadCounts() {
        fun checker(status: String, compareCode: Int = 200) = UpdateChecker { url ->
            when {
                url.endsWith("/releases/latest") -> 404 to ""
                url.endsWith("/commits/HEAD") -> 200 to """{"sha":"${"1".repeat(40)}"}"""
                "/compare/" in url -> compareCode to """{"status":"$status"}"""
                else -> error("unexpected $url")
            }
        }

        val ahead = checker("ahead").check(record("github", "o/r"), "cd5496872d98").getOrThrow()
        assertEquals("111111111111", ahead?.version)
        assertNull(checker("identical").check(record("github", "o/r"), "cd5496872d98").getOrThrow())
        assertNull(checker("behind").check(record("github", "o/r"), "cd5496872d98").getOrThrow())
        assertNull(checker("", compareCode = 404).check(record("github", "o/r"), "cd5496872d98").getOrThrow())
    }
}
