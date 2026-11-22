package io.github.pintofi.beanbook.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nothing in a person's ledger changes without their say-so: not an entry, not a line, not a comment.
 * Every change to a ledger file is meant to go through `ProjectWriter` (snapshot, re-parse, Undo) and
 * to start from something the person did or approved.
 *
 * This test is the failsafe for that rule. It lists the source files that both name a project path and
 * write a file directly, with how many direct writes each has, and every one of them was read and has a
 * reason below. A new direct write in such a file, or in a new one, fails this test until somebody has
 * looked at it and either routed it through the writer or added it here with its reason. It is a count,
 * not a proof: it cannot see a write that names no project path, which is what the review is for.
 */
class ProjectFileWritesTest {

    private val call = Regex(
        """\.(writeText|appendText|writeBytes|outputStream|bufferedWriter|copyTo|copyRecursively|renameTo|""" +
            """deleteRecursively|delete)\(|FileOutputStream\(|Files\.(write|move|copy|delete)""",
    )
    private val projectPath = Regex("""projectDir\(|mainFile\(|\.beancount|ACCOUNTS_FILE|projectsDir|workspace\.root""")

    /** File, direct writes, and why each is not a change to a ledger the person did not ask for. */
    private val reviewed = mapOf(
        "app/BeancountApplication.kt" to 2, // app state under filesDir, not a project file
        "app/DocumentActions.kt" to 1, // the diagnostics report, in the cache folder
        "app/MainActivity.kt" to 3, // debug-only test hooks, behind BuildConfig.DEBUG
        "app/mirror/MirrorController.kt" to 11, // Mirror's own state; ledger files go through the writer
        "app/onboarding/NewLedgerSetup.kt" to 6, // the setup draft, outside the project tree
        "app/storage/SafBridge.kt" to 1, // copies out to a folder the person chose
        "core/data/BackupArchive.kt" to 9, // writes the archive; restore stages and swaps in
        "core/data/DocumentStore.kt" to 11, // documents and the document trash, not Beancount files
        "core/data/HeldWrites.kt" to 5, // writes held for the person's decision, outside the project
        "core/data/ImportExport.kt" to 9, // import (the person's choice) and export copies
        "core/data/MirrorPlan.kt" to 5, // planning; applied through the writer
        "core/data/ProjectRegistry.kt" to 23, // trash, restore and rename of whole projects, and their templates
        "core/data/ProjectWorkspace.kt" to 15, // creating a new ledger the person asked for
        "core/data/ProjectWriter.kt" to 4, // the write protocol itself
        "core/data/RestorePlan.kt" to 6, // restore, which the person confirms after a preview
        "core/data/TemplateStore.kt" to 4, // templates, outside the project tree
        "core/data/WriteJournal.kt" to 12, // snapshots and journal, outside the project tree
    )

    private fun sourceRoots(): List<File> {
        val base = if (File("../core-data").isDirectory) File("..") else File(".")
        return listOf("app", "core-data", "engine-local").map { File(base, "$it/src/main") }
    }

    @Test
    fun `every direct write that can reach a project folder has been reviewed`() {
        val found = sourceRoots().flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.mapNotNull { file ->
            val text = file.readText()
            val count = call.findAll(text).count()
            if (count > 0 && projectPath.containsMatchIn(text)) {
                // app files as app/<path>, core-data files as core/data/<name>.
                file.path.substringAfter("/pintofi/beanbook/") to count
            } else {
                null
            }
        }.toMap()

        assertEquals(
            "A direct file write now reaches a place that names a project path. Route it through " +
                "ProjectWriter, or review it and add it to `reviewed` with its reason.",
            reviewed.toSortedMap(),
            found.toSortedMap(),
        )
    }
}
