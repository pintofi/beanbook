package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.BeancountText
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
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

    private fun zipOf(vararg entries: Pair<String, String>): ImportSource = filesOf(*entries)

    private fun filesOf(vararg entries: Pair<String, String>): ImportSource {
        val contents = entries.toMap()
        return object : ImportSource {
            override fun entries() =
                entries.map { (name, content) -> SourceEntry(name, content.toByteArray().size.toLong()) }

            override fun open(entry: SourceEntry) = contents.getValue(entry.sourcePath).byteInputStream()
        }
    }

    private fun problemsOf(source: ImportSource): List<ImportProblem> = importer.plan(source).problems

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
    fun `an archive with too many entries is refused`() {
        val importer = ProjectImporter(workspace, ImportLimits(maxEntries = 10))
        val many = filesOf(*Array(50) { "file$it.beancount" to "x" })
        val problems = importer.plan(many).problems
        assertTrue(problems.any { it is ImportProblem.TooManyEntries }, "got $problems")
    }

    @Test
    fun `a project larger than the limit is refused`() {
        val importer = ProjectImporter(workspace, ImportLimits(maxTotalBytes = 1024))
        val big = filesOf("main.beancount" to "x".repeat(8192))
        assertTrue(importer.plan(big).problems.any { it is ImportProblem.TooLarge })
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

        val result = importer.import(hostile, "hostile", "main.beancount")

        assertIs<ImportResult.Rejected>(result)
        assertFalse(workspace.projectDir("hostile").exists(), "no project may be created")
        assertFalse(File(workspace.projectsDir, "hostile.importing").exists(), "no staging left behind")
    }

    @Test
    fun `an archive with no ledger is reported, not imported`() {
        val result = importer.import(zipOf("notes.txt" to "hello"), "empty", "main.beancount")
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

        val result = importer.import(archive, "imported", "main.beancount")

        val imported = assertIs<ImportResult.Imported>(result)
        assertEquals(3, imported.fileCount)
        assertTrue(imported.mainFile.isFile)
        assertTrue(workspace.projectDir("imported").resolve("files/accounts.beancount").isFile)
        assertTrue(workspace.projectDir("imported").resolve("documents/receipt.pdf").isFile)
    }

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

        val result = importer.import(DirectoryImportSource(exportDir), "reimported", ProjectWorkspace.MAIN_FILE)
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
    fun `every file is imported, not only what the root includes`() {
        // taking only what the root reaches would lose a document a `document`
        // directive points at, and any ledger not yet included.
        val archive = zipOf(
            "main.beancount" to "include \"a.beancount\"\n",
            "a.beancount" to "2026-01-01 open Assets:Bank\n",
            "orphan.beancount" to "2026-01-01 open Expenses:Food\n",
            "documents/receipt.pdf" to "%PDF-1.4\n",
        )

        val result = importer.import(archive, "p3", "main.beancount")

        assertTrue(result is ImportResult.Imported, "import failed: $result")
        val dir = workspace.projectDir("p3")
        assertTrue(dir.resolve("orphan.beancount").isFile, "an un-included ledger was dropped")
        assertTrue(dir.resolve("documents/receipt.pdf").isFile, "a document was dropped")
    }
}
