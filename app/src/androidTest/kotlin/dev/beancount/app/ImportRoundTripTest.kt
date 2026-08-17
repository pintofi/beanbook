package dev.beancount.app

import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.core.data.DirectoryExportTarget
import dev.beancount.core.data.DirectoryImportSource
import dev.beancount.core.data.ImportResult
import dev.beancount.core.data.ProjectExporter
import dev.beancount.core.data.ProjectImporter
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.data.WriteJournal
import dev.beancount.engine.local.LedgerReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Invariant 5, proven where it matters: after an import — and after an export and
 * re-import — **Beancount itself** still resolves every include and document link.
 *
 * The JVM tests assert bytes and paths. This one asks the engine, which is the only
 * opinion that counts.
 */
@RunWith(AndroidJUnit4::class)
class ImportRoundTripTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var workspace: ProjectWorkspace
    private lateinit var importer: ProjectImporter
    private lateinit var corpus: File

    @Before
    fun setUp() {
        val root = File(context.cacheDir, "roundtrip-${System.nanoTime()}")
        workspace = ProjectWorkspace(root)
        importer = ProjectImporter(workspace)

        corpus = File(context.cacheDir, "rt-corpus").apply { deleteRecursively() }
        copyAsset(instrumentation.context.assets, "corpus", corpus)
    }

    private fun copyAsset(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset(assets, "$path/$it", File(target, it)) }
        }
    }

    private fun readLedger(main: File) = runBlocking { LedgerReader(context).read(main.absolutePath) }

    @Test
    fun a_multi_file_project_imports_and_its_includes_still_resolve() {
        val source = File(corpus, "multi-file")
        val result = importer.import(DirectoryImportSource(source), "multi")
        val imported = result as ImportResult.Imported

        val snapshot = readLedger(imported.mainFile).getOrThrow()

        // The corpus case nests includes three deep; every one must have been followed.
        assertEquals("the ledger must parse cleanly after import", 0, snapshot.errors.size)
        assertTrue(
            "expected the nested includes to be loaded, got ${snapshot.includedFiles}",
            snapshot.includedFiles.any { it.endsWith("q1.beancount") },
        )
        assertTrue(snapshot.entryCount > 0)
        Log.i(TAG, "multi-file: entries=${snapshot.entryCount} files=${snapshot.includedFiles.size}")
    }

    @Test
    fun a_project_with_documents_keeps_its_links_after_export_and_reimport() {
        val source = File(corpus, "documents")
        val first = importer.import(DirectoryImportSource(source), "docs") as ImportResult.Imported
        val before = readLedger(first.mainFile).getOrThrow()

        // Export to a plain folder, then import that back as a new project.
        val exportDir = File(context.cacheDir, "rt-export-${System.nanoTime()}").apply { mkdirs() }
        val exported = ProjectExporter(workspace).exportFolder("docs", DirectoryExportTarget(exportDir))
        val second = importer.import(DirectoryImportSource(exportDir), "docs2") as ImportResult.Imported
        val after = readLedger(second.mainFile).getOrThrow()

        assertEquals("export lost files", exported, second.fileCount)
        assertEquals("the round trip changed the entry count", before.entryCount, after.entryCount)
        assertEquals("the round trip introduced errors", before.errors.size, after.errors.size)

        // The document the ledger points at must exist at the new location.
        val documentDir = workspace.projectDir("docs2").resolve("documents")
        assertTrue("documents folder missing", documentDir.isDirectory)
        assertTrue(
            "document files missing after round trip",
            documentDir.listFiles().orEmpty().isNotEmpty(),
        )

        // And every file is byte-identical to the original import.
        workspace.projectDir("docs").walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(workspace.projectDir("docs")).path
            val copy = workspace.projectDir("docs2").resolve(relative)
            assertTrue("missing after round trip: $relative", copy.isFile)
            assertEquals(
                "content changed for $relative",
                WriteJournal.sha256(file),
                WriteJournal.sha256(copy),
            )
        }
        Log.i(TAG, "documents: files=$exported entries=${after.entryCount}")
    }

    @Test
    fun a_custom_directive_project_survives_import_unchanged() {
        val source = File(corpus, "custom-directives")
        val original = File(source, "main.beancount").readText()

        val imported = importer.import(DirectoryImportSource(source), "custom") as ImportResult.Imported

        // Invariant 4: nothing is normalised on the way in.
        assertEquals(original, imported.mainFile.readText())
        val snapshot = readLedger(imported.mainFile).getOrThrow()
        assertEquals(0, snapshot.errors.size)
        assertTrue(
            "custom directives must survive",
            imported.mainFile.readText().contains("custom \"budget\""),
        )
    }

    private companion object {
        const val TAG = "RoundTrip"
    }
}
