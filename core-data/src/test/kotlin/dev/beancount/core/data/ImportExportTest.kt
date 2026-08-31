package dev.beancount.core.data

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Import validation against hostile input (docs/storage-and-backup.md §4), and the
 * round-trip guarantee that includes and document links survive export and re-import
 * (invariant 5).
 */
class ImportExportTest {

    private val root: File = Files.createTempDirectory("import-test").toFile()
    private val workspace = ProjectWorkspace(root.resolve("workspace"))
    private val importer = ProjectImporter(workspace)
    private val scratch = root.resolve("scratch").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun zipOf(vararg entries: Pair<String, String>): File =
        zipWith { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }

    private fun zipWith(build: (ZipOutputStream) -> Unit): File {
        val file = File(scratch, "archive-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use(build)
        return file
    }

    private fun problemsOf(archive: File): List<ImportProblem> =
        importer.plan(ZipImportSource(archive)).problems

    // --- hostile archives -------------------------------------------------------------

    @Test
    fun `path traversal is refused`() {
        val problems = problemsOf(
            zipOf(
                "main.beancount" to "option \"title\" \"x\"\n",
                "../../../../etc/passwd" to "root:x:0:0\n",
            )
        )
        assertTrue(problems.any { it is ImportProblem.PathTraversal }, "got $problems")
    }

    @Test
    fun `traversal hidden in the middle of a path is refused`() {
        // "files/../../escape" normalises to outside the root even though no segment
        // starts with ".."
        val problems = problemsOf(
            zipOf(
                "main.beancount" to "x",
                "files/../../escape.beancount" to "x",
            )
        )
        assertTrue(problems.any { it is ImportProblem.PathTraversal }, "got $problems")
    }

    @Test
    fun `an inner traversal that stays inside the project is allowed`() {
        // "files/../main.beancount" is just "main.beancount" — refusing it would be wrong.
        val problems = problemsOf(zipOf("files/../notes.beancount" to "x"))
        assertTrue(problems.isEmpty(), "got $problems")
    }

    @Test
    fun `absolute paths are refused, POSIX and Windows`() {
        assertTrue(problemsOf(zipOf("/etc/passwd" to "x")).any { it is ImportProblem.AbsolutePath })
        assertTrue(
            problemsOf(zipOf("C:\\Windows\\system32\\evil.dll" to "x"))
                .any { it is ImportProblem.AbsolutePath }
        )
    }

    @Test
    fun `a zip bomb is refused by its compression ratio`() {
        val bomb = zipWith { zip ->
            zip.putNextEntry(ZipEntry("main.beancount"))
            zip.write("option \"title\" \"x\"\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bomb.bin"))
            // 20 MB of zeros compresses to almost nothing.
            val chunk = ByteArray(1024 * 1024)
            repeat(20) { zip.write(chunk) }
            zip.closeEntry()
        }
        assertTrue(
            problemsOf(bomb).any { it is ImportProblem.SuspiciousCompression },
            "got ${problemsOf(bomb)}",
        )
    }

    @Test
    fun `an archive with too many entries is refused`() {
        val importer = ProjectImporter(workspace, ImportLimits(maxEntries = 10))
        val many = zipWith { zip ->
            repeat(50) { index ->
                zip.putNextEntry(ZipEntry("file$index.beancount"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        val problems = importer.plan(ZipImportSource(many)).problems
        assertTrue(problems.any { it is ImportProblem.TooManyEntries }, "got $problems")
    }

    @Test
    fun `a project larger than the limit is refused`() {
        val importer = ProjectImporter(workspace, ImportLimits(maxTotalBytes = 1024))
        // Incompressible content, so the *size* limit is what fires: a run of identical
        // bytes would trip the zip-bomb ratio check first.
        val random = java.util.Random(1)
        val big = zipWith { zip ->
            zip.putNextEntry(ZipEntry("main.beancount"))
            zip.write(ByteArray(8192).also { random.nextBytes(it) })
            zip.closeEntry()
        }
        assertTrue(
            importer.plan(ZipImportSource(big)).problems.any { it is ImportProblem.TooLarge },
        )
    }

    @Test
    fun `case-colliding names are refused rather than silently merged`() {
        val problems = problemsOf(
            zipOf(
                "main.beancount" to "x",
                "Files/Accounts.beancount" to "one",
                "files/accounts.beancount" to "two",
            )
        )
        assertTrue(problems.any { it is ImportProblem.CaseCollision }, "got $problems")
    }

    @Test
    fun `duplicate entries are refused`() {
        // The JDK refuses to *write* a zip with duplicate names, and ZipFile silently
        // keeps one when reading, so this rule is exercised through a source that can
        // report duplicates — a hand-built archive, or a SAF provider listing the same
        // name twice. The rule is defensive, and stays.
        val duplicating = object : ImportSource {
            override fun entries() = listOf(
                SourceEntry("main.beancount", 10),
                SourceEntry("main.beancount", 10),
            )
            override fun open(entry: SourceEntry) = "x".byteInputStream()
        }

        val problems = importer.plan(duplicating).problems
        assertTrue(problems.any { it is ImportProblem.DuplicateEntry }, "got $problems")
    }

    @Test
    fun `absurdly deep nesting is refused`() {
        val deep = (1..40).joinToString("/") { "d$it" } + "/main.beancount"
        assertTrue(problemsOf(zipOf(deep to "x")).any { it is ImportProblem.TooDeep })
    }

    @Test
    fun `windows reserved names are refused`() {
        val problems = problemsOf(zipOf("main.beancount" to "x", "files/CON.beancount" to "x"))
        assertTrue(problems.any { it is ImportProblem.ReservedName }, "got $problems")
    }

    @Test
    fun `a symlink in a directory source is refused`() {
        val source = File(scratch, "tree").apply { mkdirs() }
        File(source, "main.beancount").writeText("option \"title\" \"x\"\n")
        Files.createSymbolicLink(
            File(source, "escape.beancount").toPath(),
            File("/etc/passwd").toPath(),
        )

        val problems = importer.plan(DirectoryImportSource(source)).problems
        assertTrue(problems.any { it is ImportProblem.Symlink }, "got $problems")
    }

    @Test
    fun `nothing is written when an import is refused`() {
        val hostile = zipOf("main.beancount" to "x", "../escape" to "x")

        val result = importer.import(ZipImportSource(hostile), "hostile")

        assertIs<ImportResult.Rejected>(result)
        assertFalse(workspace.projectDir("hostile").exists(), "no project may be created")
        assertFalse(File(workspace.projectsDir, "hostile.importing").exists(), "no staging left behind")
    }

    @Test
    fun `an archive with no ledger is reported, not imported`() {
        val result = importer.import(ZipImportSource(zipOf("notes.txt" to "hello")), "empty")
        assertIs<ImportResult.NoLedgerFound>(result)
    }

    // --- the happy path ---------------------------------------------------------------

    @Test
    fun `a well-formed archive imports with its own layout preserved`() {
        val archive = zipOf(
            "main.beancount" to "option \"title\" \"Imported\"\ninclude \"files/accounts.beancount\"\n",
            "files/accounts.beancount" to "2024-01-01 open Assets:Cash USD\n",
            "documents/receipt.pdf" to "%PDF-1.4\n",
        )

        val result = importer.import(ZipImportSource(archive), "imported")

        val imported = assertIs<ImportResult.Imported>(result)
        assertEquals(3, imported.fileCount)
        assertTrue(imported.mainFile.isFile)
        assertTrue(workspace.projectDir("imported").resolve("files/accounts.beancount").isFile)
        assertTrue(workspace.projectDir("imported").resolve("documents/receipt.pdf").isFile)
    }

    @Test
    fun `a ledger that is not called main is adopted as the root`() {
        val archive = zipOf(
            "ledger.beancount" to "option \"title\" \"Odd name\"\n",
            "files/accounts.beancount" to "2024-01-01 open Assets:Cash USD\n",
        )

        val result = importer.import(ZipImportSource(archive), "odd")

        val imported = assertIs<ImportResult.Imported>(result)
        assertTrue(imported.mainFile.isFile, "a main.beancount must exist for the engine")
        assertTrue(
            workspace.projectDir("odd").resolve("ledger.beancount").isFile,
            "the user's own file must still be there under its own name",
        )
    }

    // --- invariant 5: includes and document links survive a round trip -----------------

    @Test
    fun `export then import keeps every byte, include and document link`() {
        val original = "roundtrip"
        workspace.createProject(original, title = "Round trip")
        val projectDir = workspace.projectDir(original)
        projectDir.resolve("files/transactions.beancount").writeText(
            """
            2026-09-01 * "Grocer" "Shop"
              document: "documents/receipt-2026-09-01.txt"
              Expenses:Food:Groceries    12.00 USD
              Assets:Cash
            """.trimIndent() + "\n"
        )
        projectDir.resolve("documents").mkdirs()
        projectDir.resolve("documents/receipt-2026-09-01.txt").writeText("receipt\n")

        val exportDir = File(scratch, "export").apply { mkdirs() }
        val exported = ProjectExporter(workspace).exportFolder(original, DirectoryExportTarget(exportDir))
        assertTrue(exported >= 5, "expected the whole tree, exported $exported files")

        val result = importer.import(DirectoryImportSource(exportDir), "reimported")
        val imported = assertIs<ImportResult.Imported>(result)

        // Every file identical, byte for byte.
        projectDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(projectDir).path
            val copy = workspace.projectDir("reimported").resolve(relative)
            assertTrue(copy.isFile, "missing after round trip: $relative")
            assertEquals(
                WriteJournal.sha256(file),
                WriteJournal.sha256(copy),
                "content changed for $relative",
            )
        }

        // Includes are relative and still resolve from the new location.
        val main = imported.mainFile.readText()
        main.lines().filter { it.startsWith("include") }.forEach { line ->
            val target = line.substringAfter('"').substringBefore('"')
            assertFalse(target.startsWith("/"), "include became absolute: $line")
            assertTrue(
                imported.mainFile.parentFile!!.resolve(target).isFile,
                "include no longer resolves: $target",
            )
        }

        // The document link still points at a file that exists.
        val documentLink = workspace.projectDir("reimported")
            .resolve("files/transactions.beancount").readText()
            .substringAfter("document: \"").substringBefore('"')
        assertTrue(
            workspace.projectDir("reimported").resolve(documentLink).isFile,
            "document link broken: $documentLink",
        )
    }

    @Test
    fun `an archive wrapped in one folder is unwrapped, not duplicated`() {
        // What `zip -r ledger.zip ledger` produces, and what the emulator import of
        // zipped-ledger.zip produced before this: main.beancount at the root *and* a second
        // copy under the wrapper, where editing the copy does nothing.
        val archive = zipOf(
            "zipped-ledger/main.beancount" to "include \"transactions.beancount\"\n",
            "zipped-ledger/transactions.beancount" to "; entries\n",
        )

        val result = importer.import(ZipImportSource(archive), "unwrapped")

        assertTrue(result is ImportResult.Imported, "import failed: $result")
        val dir = workspace.projectDir("unwrapped")
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()
        assertEquals(setOf("main.beancount", "transactions.beancount"), files)
        assertTrue(
            dir.resolve("main.beancount").readText().contains("include"),
            "the wrapper's ledger must be the project root, not a fresh file",
        )
    }

    @Test
    fun `two top-level folders are kept as they are`() {
        // Only a *shared* wrapper is stripped: here the layout is the author's own.
        val archive = zipOf(
            "personal/main.beancount" to "; personal\n",
            "business/ledger.beancount" to "; business\n",
        )

        val result = importer.import(ZipImportSource(archive), "two-roots")

        assertTrue(result is ImportResult.Imported, "import failed: $result")
        val dir = workspace.projectDir("two-roots")
        assertTrue(dir.resolve("personal/main.beancount").isFile)
        assertTrue(dir.resolve("business/ledger.beancount").isFile)
    }

    @Test
    fun `export contains no journal, snapshot or cache files`() {
        val projectId = "clean"
        workspace.createProject(projectId, title = "Clean")
        // Create runtime state of the kind that must never be exported.
        WriteJournal(workspace).begin(
            projectId,
            "noise",
            listOf(workspace.mainFile(projectId)),
        )
        workspace.cacheDir.resolve(projectId).apply { mkdirs() }
            .resolve("parse.cache").writeText("cache")

        val exportDir = File(scratch, "clean-export").apply { mkdirs() }
        ProjectExporter(workspace).exportFolder(projectId, DirectoryExportTarget(exportDir))

        val names = exportDir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertFalse(names.contains("state"), "journal state leaked into the export")
        assertFalse(names.contains("parse.cache"), "cache leaked into the export")
        assertTrue(names.contains(ProjectWorkspace.MAIN_FILE))
    }

    @Test
    fun `export writes through the target abstraction, so SAF failures surface`() {
        workspace.createProject("failing", title = "Failing")
        val failing = object : ExportTarget {
            override fun openOutput(relativePath: String): OutputStream =
                throw java.io.IOException("SAF provider said no")
        }

        val error = runCatching {
            ProjectExporter(workspace).exportFolder("failing", failing)
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException, "a provider failure must not be swallowed")
    }

    @Test
    fun `the root is the ledger no other ledger includes, not the first by name`() {
        // The case the old name heuristic got wrong. "accounts" sorts before "personal", and
        // both sit at the same depth, so the included file was chosen as the root.
        val archive = zipOf(
            "accounts.beancount" to "2026-01-01 open Assets:Bank\n",
            "personal.beancount" to "include \"accounts.beancount\"\n",
        )

        val result = importer.import(ZipImportSource(archive), "p1")

        assertTrue(result is ImportResult.Imported, "import failed: $result")
        assertEquals(
            "include \"accounts.beancount\"\n",
            workspace.mainFile("p1").readText(),
            "main.beancount is not the root ledger",
        )
    }

    @Test
    fun `an include in a subdirectory resolves against its own directory`() {
        val archive = zipOf(
            "books/ledger.beancount" to "include \"2026.beancount\"\n",
            "books/2026.beancount" to "2026-01-01 open Assets:Bank\n",
        )

        val result = importer.import(ZipImportSource(archive), "p2")

        assertTrue(result is ImportResult.Imported, "import failed: $result")
        assertEquals("include \"2026.beancount\"\n", workspace.mainFile("p2").readText())
    }

    @Test
    fun `every file is imported, not only what the root includes`() {
        // B22: taking only what the root reaches would lose a document a `document`
        // directive points at, and any ledger not yet included.
        val archive = zipOf(
            "main.beancount" to "include \"a.beancount\"\n",
            "a.beancount" to "2026-01-01 open Assets:Bank\n",
            "orphan.beancount" to "2026-01-01 open Expenses:Food\n",
            "documents/receipt.pdf" to "%PDF-1.4\n",
        )

        val result = importer.import(ZipImportSource(archive), "p3")

        assertTrue(result is ImportResult.Imported, "import failed: $result")
        val dir = workspace.projectDir("p3")
        assertTrue(dir.resolve("orphan.beancount").isFile, "an un-included ledger was dropped")
        assertTrue(dir.resolve("documents/receipt.pdf").isFile, "a document was dropped")
    }
}
