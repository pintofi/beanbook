package io.github.pintofi.beanbook.app.mirror

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The mirror looks up the files a project has and follows new links; it never lists the rest of the folder. */
@RunWith(AndroidJUnit4::class)
class ExternalFilesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "external-files-${System.nanoTime()}")
    private val external = ExternalFiles(context.contentResolver)

    @After fun cleanUp() {
        base.deleteRecursively()
    }

    private fun folder(vararg files: Pair<String, String>): DocumentFile {
        files.forEach { (path, text) -> File(base, path).apply { parentFile?.mkdirs() }.writeText(text) }
        return DocumentFile.fromFile(base)
    }

    private fun names(found: Map<String, DocumentFile>) = found.keys.sorted()

    @Test fun onlyTheFilesTheProjectHasAreFoundAndTheRestOfTheFolderIsNot() {
        val root = folder(
            "hmw" to "include \"a.bean\"\n", "a.bean" to "", "scans/x.pdf" to "pdf",
            "other.bean" to "not ours", "repo/.git/config" to "secret", "notes/todo.txt" to "x",
        )

        val found = external.find(root, listOf("hmw", "a.bean", "scans/x.pdf"), "hmw")

        assertEquals(listOf("a.bean", "hmw", "scans/x.pdf"), names(found))
    }

    @Test fun aFileThatIsNotThereIsAbsent() {
        val root = folder("hmw" to "")

        val found = external.find(root, listOf("hmw", "gone.bean", "deep/gone.bean"), "hmw")

        assertEquals(listOf("hmw"), names(found))
    }

    @Test fun aLinkAddedOnTheOtherSideIsFollowedToTheFileItNames() {
        val root = folder(
            "hmw" to "include \"a.bean\"\ninclude \"new/b.bean\"\n2025-01-01 document Assets:A \"scans/r.pdf\"\n",
            "a.bean" to "", "new/b.bean" to "", "scans/r.pdf" to "x", "unlinked.bean" to "",
        )

        val found = external.find(root, listOf("hmw", "a.bean"), "hmw")

        assertEquals(listOf("a.bean", "hmw", "new/b.bean", "scans/r.pdf"), names(found))
        assertFalse("unlinked.bean" in found)
    }

    @Test fun aMainFileInASubfolderFollowsLinksAboveItAndKeepsProjectPaths() {
        val root = folder(
            "books/hmw" to "include \"../shared.bean\"\ninclude \"2025/jan.bean\"\n",
            "books/2025/jan.bean" to "", "shared.bean" to "", "books/skip.bean" to "",
        )

        val found = external.find(root, listOf("books/hmw"), "books/hmw")

        assertEquals(listOf("books/2025/jan.bean", "books/hmw", "shared.bean"), names(found))
    }

    @Test fun conflictCopiesAndPathsThatEscapeAreNeverOffered() {
        val root = folder("hmw" to "", "hmw.conflict-20260101" to "")

        val found = external.find(root, listOf("hmw", "hmw.conflict-20260101", "../outside", "a/../../x"), "hmw")

        assertEquals(listOf("hmw"), names(found))
    }

    @Test fun oneFileIsLookedUpByItsPath() {
        val root = folder("a/b/c.bean" to "x", "a/b/d.bean" to "y")

        assertTrue(external.findOne(root, "a/b/c.bean") != null)
        assertEquals(null, external.findOne(root, "a/b/zzz.bean"))
        assertEquals(null, external.findOne(root, "../c.bean"))
    }

    @Test fun aFolderWithManyProjectFilesIsFoundInOnePass() {
        val files = (1..40).map { "docs/f$it.pdf" to "x" }.toTypedArray()
        val root = folder("hmw" to "", *files)

        val found = external.find(root, listOf("hmw") + files.map { it.first }, "hmw")

        assertEquals(41, found.size)
    }
}
