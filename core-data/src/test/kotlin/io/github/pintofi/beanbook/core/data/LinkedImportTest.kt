package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.LedgerLinks
import io.github.pintofi.beanbook.core.domain.LinkedChild
import io.github.pintofi.beanbook.core.domain.LinkedSource
import io.github.pintofi.beanbook.core.domain.LinkedStat
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A ledger is imported from the file the person chose and what it links to, with names and folders kept. */
class LinkedImportTest {

    private val base: File = Files.createTempDirectory("linked-import").toFile()
    private val workspace = ProjectWorkspace(base)
    private val registry = ProjectRegistry(workspace, File(base, "state"))

    @AfterTest fun cleanUp() = base.deleteRecursively().let { }

    /** Files by real path; the main file's folder is [mainDir]. Records nothing it was not asked. */
    private class Fake(files: Map<String, String>, private val mainDir: String = "") : LinkedSource {
        val files = files.mapValues { it.value.toByteArray() }
        val touched = mutableListOf<String>()

        private fun real(path: String): String? {
            val parts = mutableListOf<String>()
            for (s in (if (mainDir.isEmpty()) path else "$mainDir/$path").split('/')) {
                when (s) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                    else -> parts += s
                }
            }
            return parts.joinToString("/")
        }

        override fun read(path: String): ByteArray? {
            touched += path
            return real(path)?.let { files[it] }
        }

        override fun stat(path: String): LinkedStat? {
            touched += path
            val r = real(path) ?: return null
            files[r]?.let { return LinkedStat(it.size.toLong(), false) }
            return if (files.keys.any { it.startsWith("$r/") }) LinkedStat(0, true) else null
        }

        override fun children(path: String): List<LinkedChild>? {
            val r = real(path) ?: return null
            val prefix = if (r.isEmpty()) "" else "$r/"
            val below = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            if (below.isEmpty()) return null
            return below.map { it.substringBefore('/') to it.contains('/') }.distinct()
                .map { LinkedChild(it.first, it.second) }
        }
    }

    private fun import(
        fake: Fake,
        main: String,
        mainFolders: List<String> = emptyList(),
        maxUp: Int = mainFolders.size,
        id: String = "p",
        limits: ImportLimits = ImportLimits(),
    ): ImportResult {
        val ledger = LedgerLinks(fake, maxUp).walk(main)
        return ProjectImporter(workspace, limits).importLinked(fake, ledger, mainFolders, id)
    }

    private fun projectFiles(id: String) = workspace.projectDir(id).walkTopDown().filter { it.isFile }
        .map { it.relativeTo(workspace.projectDir(id)).path }.sorted().toList()

    @Test
    fun `a file with no extension is the main file, and only what it links to comes with it`() {
        val fake = Fake(
            mapOf(
                "hmw" to "include \"accounts/open.bean\"\n", "accounts/open.bean" to "",
                "other.bean" to "not linked", "notes/secret.txt" to "not linked",
            ),
        )

        val result = assertIs<ImportResult.Imported>(import(fake, "hmw"))

        assertEquals(listOf("accounts/open.bean", "hmw"), projectFiles("p"))
        assertEquals("hmw", workspace.rootPath("p"))
        assertEquals(File(workspace.projectDir("p"), "hmw"), workspace.mainFile("p"))
        assertEquals(result.mainFile, workspace.mainFile("p"))
        assertTrue(workspace.exists("p"))
        assertFalse(fake.touched.any { it.contains("secret") || it == "other.bean" })
    }

    @Test
    fun `a file called main dot beancount needs no record`() {
        val fake = Fake(mapOf("main.beancount" to "option \"title\" \"T\"\n"))

        assertIs<ImportResult.Imported>(import(fake, "main.beancount"))

        assertFalse(workspace.rootRecord("p").exists())
        assertEquals("T", registry.summarise("p").title)
    }

    @Test
    fun `names and folders are kept, with files above the main file's folder placed where they are`() {
        val fake = Fake(
            mapOf(
                "ledgers/books/hmw" to "include \"../shared.bean\"\ninclude \"2025/jan.bean\"\n",
                "ledgers/books/2025/jan.bean" to "", "ledgers/shared.bean" to "include \"../top.bean\"\n",
                "top.bean" to "",
            ),
            mainDir = "ledgers/books",
        )

        assertIs<ImportResult.Imported>(import(fake, "hmw", mainFolders = listOf("ledgers", "books")))

        assertEquals(
            listOf("ledgers/books/2025/jan.bean", "ledgers/books/hmw", "ledgers/shared.bean", "top.bean"),
            projectFiles("p"),
        )
        assertEquals("ledgers/books/hmw", workspace.rootPath("p"))
    }

    @Test
    fun `files under the main file's folder keep their folders and nothing is flattened`() {
        val fake = Fake(mapOf("books/hmw" to "include \"x/a.bean\"\n", "books/x/a.bean" to ""), mainDir = "books")

        assertIs<ImportResult.Imported>(import(fake, "hmw", mainFolders = emptyList()))

        assertEquals(listOf("hmw", "x/a.bean"), projectFiles("p"))
    }

    @Test
    fun `an import never writes to the original`() {
        val fake = Fake(mapOf("hmw" to "include \"a.bean\"\n", "a.bean" to "x"))
        val before = fake.files.mapValues { it.value.toList() }

        import(fake, "hmw")

        assertEquals(before, fake.files.mapValues { it.value.toList() })
    }

    @Test
    fun `a project that is too large is refused and leaves nothing behind`() {
        val fake = Fake(mapOf("hmw" to "include \"big\"\n", "big" to "x".repeat(2_000)))

        val result = import(fake, "hmw", limits = ImportLimits(maxTotalBytes = 1_000))

        assertIs<ImportResult.Rejected>(result)
        assertFalse(workspace.projectDir("p").exists())
        assertFalse(File(workspace.projectsDir, "p.importing").exists())
    }

    @Test
    fun `a reserved name is refused as in any import`() {
        val fake = Fake(mapOf("hmw" to "include \"con.bean\"\n", "con.bean" to ""))

        assertIs<ImportResult.Rejected>(import(fake, "hmw"))
    }

    @Test
    fun `the project lists, exports byte for byte and its id is fresh`() {
        val fake = Fake(mapOf("books/hmw" to "include \"x/a.bean\"\n", "books/x/a.bean" to "1\n"), mainDir = "books")
        assertIs<ImportResult.Imported>(import(fake, "hmw"))

        assertEquals(listOf("p"), workspace.listProjects())
        assertFailsWith<IllegalArgumentException> { import(fake, "hmw") }
    }

    @Test
    fun `the number of folders named has to be the number the ledger reaches up`() {
        val fake = Fake(mapOf("a/hmw" to "include \"../s.bean\"\n", "s.bean" to ""), mainDir = "a")
        val ledger = LedgerLinks(fake, maxUp = 1).walk("hmw")

        assertFailsWith<IllegalArgumentException> {
            ProjectImporter(workspace).importLinked(fake, ledger, emptyList(), "p")
        }
    }
}
