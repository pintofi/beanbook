package dev.beancount.core.data

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * An archive that lies about how big it is (R3).
 *
 * Every size limit in `plan` is read from the container's own metadata — a zip central
 * directory's `size` and `compressedSize`, which the archive itself supplies and an attacker
 * therefore chooses. Extraction then copied without a bound, so an archive declaring a
 * kilobyte per entry could inflate to whatever it liked and fill the device. The declared
 * figures decide whether the import is *allowed*; nothing made them true.
 */
class ImportOverrunTest {

    private val root: File = Files.createTempDirectory("import-overrun").toFile()
    private val workspace = ProjectWorkspace(root)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    /** Streams [actual] bytes while claiming to be [declared] long. */
    private class LyingSource(
        private val declared: Long,
        private val actual: Long,
    ) : ImportSource {
        override fun entries() = listOf(
            SourceEntry(path = "main.beancount", size = declared, compressedSize = declared),
        )

        override fun open(entry: SourceEntry): InputStream = object : InputStream() {
            private var produced = 0L
            override fun read(): Int = if (produced++ < actual) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= actual) return -1
                val n = minOf(len.toLong(), actual - produced).toInt()
                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                produced += n
                return n
            }
        }
    }

    @Test
    fun `an entry that streams more than it declared is refused`() = runBlocking {
        // Declares 1 KB, delivers 8 MB.
        val importer = ProjectImporter(workspace, ImportLimits(maxTotalBytes = 4L * 1024 * 1024))
        val result = importer.import(LyingSource(declared = 1024, actual = 8L * 1024 * 1024), "liar")

        assertTrue(
            result is ImportResult.Rejected,
            "an archive that streamed 8 MB while declaring 1 KB was accepted: $result",
        )
    }

    @Test
    fun `nothing is left behind when an entry overruns`() = runBlocking {
        val importer = ProjectImporter(workspace, ImportLimits(maxTotalBytes = 4L * 1024 * 1024))
        importer.import(LyingSource(declared = 1024, actual = 8L * 1024 * 1024), "liar")

        val staging = File(workspace.projectDir("liar").parentFile, "liar.importing")
        assertTrue(!staging.exists(), "the staging directory survived the refusal")
        assertTrue(!workspace.projectDir("liar").exists(), "a partial project was left behind")
    }

    @Test
    fun `an honest archive still imports`() = runBlocking {
        val importer = ProjectImporter(workspace, ImportLimits(maxTotalBytes = 4L * 1024 * 1024))
        val honest = LyingSource(declared = 2048, actual = 2048)

        val result = importer.import(honest, "honest")

        assertTrue(result is ImportResult.Imported, "an honest archive was refused: $result")
    }
}
