package io.github.pintofi.beanbook.core.data

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The donation notice comes after a week of use, and only once (B81). */
class SupportPromptTest {

    private val dir = Files.createTempDirectory("support").toFile()
    private val start = Instant.parse("2026-09-01T10:00:00Z")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively().let { }

    @Test
    fun `not in the first week`() {
        val prompt = SupportPrompt(dir)
        prompt.recordLaunch(start)
        assertFalse(prompt.isDue(start))
        assertFalse(prompt.isDue(start.plus(Duration.ofDays(6)).plus(Duration.ofHours(23))))
    }

    @Test
    fun `due after a week, and the first launch is the one that counts`() {
        val prompt = SupportPrompt(dir)
        prompt.recordLaunch(start)
        prompt.recordLaunch(start.plus(Duration.ofDays(5)))
        assertTrue(SupportPrompt(dir).isDue(start.plus(Duration.ofDays(7))))
    }

    @Test
    fun `once answered, never again`() {
        val prompt = SupportPrompt(dir)
        prompt.recordLaunch(start)
        prompt.answer()
        assertFalse(SupportPrompt(dir).isDue(start.plus(Duration.ofDays(400))))
    }

    @Test
    fun `nothing recorded means nothing due`() {
        assertFalse(SupportPrompt(dir).isDue(start))
    }

    @Test
    fun `an unreadable file is a first launch, which only delays it`() {
        dir.resolve("support-prompt").writeText("garbage")
        val prompt = SupportPrompt(dir)
        prompt.recordLaunch(start)
        assertFalse(prompt.isDue(start.plus(Duration.ofDays(1))))
        assertTrue(prompt.isDue(start.plus(Duration.ofDays(8))))
    }
}
