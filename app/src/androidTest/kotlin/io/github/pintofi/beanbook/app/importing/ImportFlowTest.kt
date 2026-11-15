package io.github.pintofi.beanbook.app.importing

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.storage.TreeLinkedSource
import io.github.pintofi.beanbook.core.data.ImportPreview
import io.github.pintofi.beanbook.core.data.ImportResult
import io.github.pintofi.beanbook.core.data.ProjectImporter
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.importLinked
import io.github.pintofi.beanbook.core.domain.LedgerLinks
import io.github.pintofi.beanbook.core.domain.LinkProblem
import io.github.pintofi.beanbook.core.domain.UnresolvedLink
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Importing from the file the person selects: the folder source, the project it makes, and the dialogs. */
@RunWith(AndroidJUnit4::class)
class ImportFlowTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "import-flow-${System.nanoTime()}")

    @After fun cleanUp() {
        base.deleteRecursively()
    }

    private fun folder(vararg files: Pair<String, String>): File {
        val root = File(base, "ledger").apply { mkdirs() }
        files.forEach { (path, text) -> File(root, path).apply { parentFile?.mkdirs() }.writeText(text) }
        return root
    }

    private fun source(root: File, mainDir: List<String>) =
        TreeLinkedSource(context.contentResolver, DocumentFile.fromFile(root), mainDir)

    @Test fun aFolderIsReadByPathAndOnlyWhatIsLinkedComesIn() {
        val root = folder(
            "books/hmw" to "include \"../shared.bean\"\ninclude \"2025/*.bean\"\n",
            "books/2025/jan.bean" to "", "books/2025/note.txt" to "", "shared.bean" to "",
            "private/passwords.txt" to "secret", "other.bean" to "",
        )
        val tree = source(root, listOf("books"))
        val ledger = LedgerLinks(tree, maxUp = 1).walk("hmw")

        val workspace = ProjectWorkspace(File(base, "app"))
        val result = ProjectImporter(workspace).importLinked(tree, ledger, listOf("books"), "p")

        assertTrue(result.toString(), result is ImportResult.Imported)
        val imported = workspace.projectDir("p").walkTopDown().filter { it.isFile }
            .map { it.relativeTo(workspace.projectDir("p")).path }.sorted().toList()
        assertEquals(listOf("books/2025/jan.bean", "books/hmw", "shared.bean"), imported)
        assertEquals("books/hmw", workspace.rootPath("p"))
        // The original is as it was.
        assertEquals("secret", File(root, "private/passwords.txt").readText())
        assertTrue(File(root, "books/hmw").readText().startsWith("include"))
    }

    @Test fun aLinkAboveTheAllowedFolderIsReportedNotFollowed() {
        val root = folder("books/hmw" to "include \"../shared.bean\"\n", "shared.bean" to "")

        val ledger = LedgerLinks(source(root, listOf("books")), maxUp = 0).walk("hmw")

        assertEquals(listOf(LinkProblem.OUTSIDE), ledger.unresolved.map { it.problem })
        assertEquals(1, ledger.neededUp)
    }

    private fun showPreview(count: Int = 3) {
        val ledger = LedgerLinks(
            source(folder("hmw" to "include \"a.bean\"\ninclude \"gone.bean\"\n", "a.bean" to ""), emptyList()),
        ).walk("hmw")
        compose.setContent {
            MaterialTheme {
                ImportDialogs(
                    ImportState.Preview(
                        "hmw", ImportPreview.lines(ledger, emptyList()), ledger.files.size, ledger.totalBytes,
                        ledger.unresolved,
                    ),
                    onChooseFolder = {}, onImportAlone = {}, onImport = { clicked += "import" },
                    onCancel = { clicked += "cancel" },
                )
            }
        }
        compose.waitForIdle()
    }

    private val clicked = mutableListOf<String>()

    @Test fun thePreviewNamesExactlyTheFilesAndNothingElseAndImportsOnlyWhenTold() {
        showPreview()

        compose.onNodeWithText("Import hmw?").assertIsDisplayed()
        compose.onNodeWithText("a.bean").assertIsDisplayed()
        compose.onNodeWithText("hmw  (your Beancount file)").assertIsDisplayed()
        compose.onNodeWithText("Not reachable").assertIsDisplayed()
        assertEquals(emptyList<String>(), clicked)

        compose.onNodeWithContentDescription("Import these files").performClick()
        assertEquals(listOf("import"), clicked)
    }

    @Test fun cancellingImportsNothing() {
        showPreview()

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("cancel"), clicked)
    }

    @Test fun theFolderQuestionSaysHowManyFilesAndOffersTheFileAlone() {
        val answers = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ImportDialogs(
                    ImportState.NeedsFolder("hmw", 14, up = 1),
                    onChooseFolder = { answers += "folder" }, onImportAlone = { answers += "alone" },
                    onImport = {}, onCancel = { answers += "cancel" },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Allow the folder that holds hmw").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import hmw on its own").performClick()
        compose.onNodeWithContentDescription("Choose the folder to allow").performClick()

        assertEquals(listOf("alone", "folder"), answers)
        assertFalse(answers.contains("cancel"))
    }

    @Test fun describeNamesTheFileTheLineAndTheTextAsWritten() {
        val text = describe(UnresolvedLink("hmw", 12, "include \"2025/*.bean\"", LinkProblem.NO_MATCH))

        assertEquals("hmw, line 12: include \"2025/*.bean\" matched no file", text)
    }
}
