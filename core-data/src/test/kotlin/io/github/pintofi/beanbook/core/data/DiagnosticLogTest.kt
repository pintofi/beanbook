package io.github.pintofi.beanbook.core.data

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's own record of what it did (B62).
 *
 * Two properties carry the whole feature. **Secrets never reach the file** — redaction on the
 * way in, because a secret that lands in it is a secret in every copy: the backup, the share
 * sheet, the screenshot of the screen that shows it. And **the file cannot grow without
 * bound**, because a log that fills a phone is a bug of its own.
 */
class DiagnosticLogTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "diag-${System.nanoTime()}")
    private val log = DiagnosticLog(dir, maxBytes = 2_000)

    @AfterTest fun clean() {
        dir.deleteRecursively()
    }

    @Test fun `an entry is written with its level, tag and time`() {
        log.append(LogLevel.WARN, "EngineManager", "engine failed to start", atEpochMs = 0L)
        val line = log.tail().single()
        assertTrue(line.contains("W/EngineManager: engine failed to start"), line)
    }

    @Test fun `the engine token never reaches the file`() {
        log.append(LogLevel.INFO, "Web", "GET /x with bca_token=abc123DEF456 and more")
        val written = log.text()
        assertFalse(written.contains("abc123DEF456"), "the token was written: $written")
        assertTrue(written.contains("bca_token=<redacted>"), written)
    }

    @Test fun `a passphrase is taken out however it was labelled`() {
        log.append(LogLevel.INFO, "Backup", "restore passphrase=hunter2 for archive")
        assertFalse(log.text().contains("hunter2"))
    }

    @Test fun `credentials inside a URL are taken out`() {
        log.append(LogLevel.INFO, "Remote", "connecting to https://ann:s3cret@fava.example/x")
        val written = log.text()
        assertFalse(written.contains("s3cret"), "the password survived: $written")
        assertTrue(written.contains("https://<redacted>@fava.example/x"), written)
    }

    @Test fun `a home directory does not name the person who owns it`() {
        log.append(LogLevel.WARN, "Engine", "no such file: /home/ann/ledgers/main.beancount")
        val written = log.text()
        assertFalse(written.contains("/home/ann"))
        assertTrue(written.contains("<home>/ledgers/main.beancount"), written)
    }

    @Test fun `the record rotates rather than growing without bound`() {
        repeat(400) { log.append(LogLevel.INFO, "Load", "a reasonably long line about entry $it") }
        assertTrue(log.sizeBytes() <= 2_000 * 2 + 500, "the log grew past two files: ${log.sizeBytes()}")
        // The newest entry survives rotation, which is the one a person is looking for.
        assertTrue(log.text().contains("entry 399"))
    }

    @Test fun `clearing empties it`() {
        log.append(LogLevel.INFO, "X", "something")
        log.clear()
        assertEquals("", log.text())
        assertEquals(0L, log.sizeBytes())
    }

    /** The check has to bite: an ordinary message must survive intact. */
    @Test fun `an ordinary message is not mangled`() {
        log.append(LogLevel.INFO, "Ledger", "loaded 25061 entries in 3421 ms")
        assertTrue(log.text().contains("loaded 25061 entries in 3421 ms"))
    }
}
