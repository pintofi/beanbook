package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewTest {

    private val sample = """
        # Changelog

        ## [Unreleased]

        ### Added
        - Not out yet. ([#9])

        ## [1.0.2] - 2026-10-25

        ### Added
        - A **bold** word and `code`, with a [link](https://example.org/x). ([#93], fixes [#69])
        - Expert edition: Only for Expert. ([#5])

        ### Fixed
        - A long entry that
          continues on a second line. ([#7])

        ## [1.0.1] - 2026-10-18

        ### Changed
        - Older. ([#1])

        [#1]: https://github.com/x/y/issues/1
    """.trimIndent()

    @Test fun `unreleased is skipped and the newest release comes first`() {
        val releases = WhatsNew.parse(sample, upTo = "1.0.2", expert = true)

        assertEquals(listOf("1.0.2", "1.0.1"), releases.map { it.version })
        assertEquals("2026-10-25", releases.first().date)
        assertFalse(releases.any { r -> r.groups.any { g -> g.entries.any { it.contains("Not out yet") } } })
    }

    @Test fun `references and link targets are removed and a wrapped entry is joined`() {
        val added = WhatsNew.parse(sample, "1.0.2", expert = true).first().groups.first { it.title == "Added" }
        val fixed = WhatsNew.parse(sample, "1.0.2", expert = true).first().groups.first { it.title == "Fixed" }

        assertEquals("A **bold** word and `code`, with a link.", added.entries.first())
        assertEquals("A long entry that continues on a second line.", fixed.entries.single())
    }

    @Test fun `a release newer than the installed version is left out`() {
        assertEquals(listOf("1.0.1"), WhatsNew.parse(sample, upTo = "1.0.1", expert = true).map { it.version })
    }

    @Test fun `expert only entries stay in for expert and go for play`() {
        val expert = WhatsNew.parse(sample, "1.0.2", expert = true).first().groups.first().entries
        val play = WhatsNew.parse(sample, "1.0.2", expert = false).first().groups.first().entries

        assertTrue(expert.any { it.startsWith(WhatsNew.EXPERT_PREFIX) })
        assertFalse(play.any { it.contains("Expert") })
    }

    @Test fun `versions compare by number not by text`() {
        assertTrue(WhatsNew.compare("1.0.10", "1.0.9") > 0)
        assertEquals(0, WhatsNew.compare("1.2.0", "1.2.0"))
        assertTrue(WhatsNew.compare("1.1.0", "1.0.99") > 0)
    }

    @Test fun `the real changelog parses to a release with entries for every version it lists`() {
        val text = File("../CHANGELOG.md").readText()
        val releases = WhatsNew.parse(text, upTo = "99.0.0", expert = true)

        assertTrue(releases.size >= 7, "found ${releases.size}")
        releases.forEach { release ->
            assertTrue(release.groups.isNotEmpty(), release.version)
            release.groups.flatMap { it.entries }.forEach {
                assertFalse(it.contains("[#"), "a reference is left in: $it")
                assertFalse(it.contains("](http"), "a link is left in: $it")
            }
        }
        val versions = releases.map { it.version }
        assertEquals(versions.sortedWith { a, b -> WhatsNew.compare(b, a) }, versions)
    }
}

class WhatsNewRecordTest {

    private val dir: File = Files.createTempDirectory("whatsnew").toFile()
    private val record = WhatsNewRecord(dir)

    @kotlin.test.AfterTest fun cleanUp() = dir.deleteRecursively().let { }

    @Test fun `a fresh install records the version and shows nothing`() {
        assertFalse(record.isDue("1.0.6", freshInstall = true))
        assertEquals("1.0.6", record.seen())
    }

    @Test fun `an update from a version that kept no record shows it once`() {
        assertTrue(record.isDue("1.0.6", freshInstall = false))
        assertNull(record.seen(), "nothing is recorded until the sheet is closed")

        record.markSeen("1.0.6")
        assertFalse(record.isDue("1.0.6", freshInstall = false))
    }

    @Test fun `a newer version is due and the next one after it is too`() {
        record.markSeen("1.0.6")
        assertTrue(record.isDue("1.0.7", freshInstall = false))
        record.markSeen("1.0.7")
        assertFalse(record.isDue("1.0.7", freshInstall = false))
        assertTrue(record.isDue("1.1.0", freshInstall = false))
    }

    @Test fun `a downgrade shows nothing and never lowers the record`() {
        record.markSeen("1.0.7")

        assertFalse(record.isDue("1.0.6", freshInstall = false))
        record.markSeen("1.0.6")
        assertEquals("1.0.7", record.seen())
    }

    @Test fun `a file that is not a version counts as silent and is replaced`() {
        File(dir, "whats-new").writeText("garbage")

        assertFalse(record.isDue("1.0.6", freshInstall = false))
        assertEquals("1.0.6", record.seen())
    }

    @Test fun `the record is a single small line`() {
        record.markSeen("1.0.6")
        assertNotNull(File(dir, "whats-new").takeIf { it.length() < 20 })
    }
}
