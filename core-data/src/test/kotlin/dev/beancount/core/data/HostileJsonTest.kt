package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Malformed JSON is rejected, not thrown out of (R9).
 *
 * `Json.parse` reads `plugins/installed.json`, which arrives inside an imported project, and
 * `PluginStore.list` calls it without a `runCatching`. A truncated `\u` escape ran
 * `substring(index + 1, index + 5)` past the end of the string, and a non-hex one ran
 * `toInt(16)` on letters — both exceptions rather than a parse failure the caller can report.
 */
class HostileJsonTest {

    private val root: File = Files.createTempDirectory("hostile-json").toFile()

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a truncated unicode escape is a parse failure, not a crash`() {
        assertNull(Json.parse("""{"a":"\u00"}"""), "a truncated escape parsed")
    }

    @Test
    fun `a non-hex unicode escape is a parse failure, not a crash`() {
        assertNull(Json.parse("""{"a":"\uZZZZ"}"""), "a non-hex escape parsed")
    }

    @Test
    fun `a well-formed escape still parses`() {
        val parsed = Json.parse("""{"a":"A"}""")
        assertTrue(parsed != null, "a valid escape was rejected")
    }

    @Test
    fun `a hostile installed json does not take the plugin list down with it`() {
        val project = File(root, "project").apply { mkdirs() }
        File(project, "plugins").apply { mkdirs() }
            .resolve("installed.json").writeText("""{"plugins":[{"id":"\u00"}]}""")

        val listed = PluginStore(project).list()

        assertEquals(emptyList(), listed, "a malformed store should read as empty, not throw")
    }
}
