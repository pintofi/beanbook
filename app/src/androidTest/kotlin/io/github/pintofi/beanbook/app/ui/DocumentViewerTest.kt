package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

/**
 * Reading a receipt without leaving the app (B57).
 *
 * Every route to a document used to be "Share", which hands the file to another app and
 * takes the user with it. The check that matters is that a real PDF is rendered here — a
 * dialog that says "could not be shown" for a valid file would look like a viewer and be
 * none.
 */
@RunWith(AndroidJUnit4::class)
class DocumentViewerTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "viewer-${System.nanoTime()}").apply { mkdirs() }

    @After fun clean() {
        dir.deleteRecursively()
    }

    /** A two-page PDF, made the way the app makes one. */
    private fun pdf(): File {
        val document = PdfDocument()
        repeat(2) { index ->
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(200, 300, index + 1).create(),
            )
            page.canvas.drawColor(android.graphics.Color.WHITE)
            document.finishPage(page)
        }
        val file = File(dir, "statement.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    @Test fun aPdfIsRenderedInTheApp() {
        val file = pdf()
        compose.setContent {
            MaterialTheme { DocumentViewer(file = file, onShare = {}, onDismiss = {}) }
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTextSafely("This file could not be shown here.")
                .fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTextSafely("Opening…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("statement.pdf").assertIsDisplayed()
        // One page at a time, each zoomable (B84), with the count on screen.
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close statement.pdf").assertIsDisplayed()
    }

    @Test fun sharingIsStillOfferedFromInside() {
        var shared = false
        val file = pdf()
        compose.setContent {
            MaterialTheme { DocumentViewer(file = file, onShare = { shared = true }, onDismiss = {}) }
        }
        compose.onNodeWithContentDescription("Share statement.pdf").performClick()
        assertTrue("sharing from the viewer did nothing", shared)
    }

    @Test fun aFileThatIsNotThereSaysSoRatherThanShowingNothing() {
        compose.setContent {
            MaterialTheme {
                DocumentViewer(file = File(dir, "gone.pdf"), onShare = {}, onDismiss = {})
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTextSafely("This file could not be shown here.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafely(
    text: String,
    substring: Boolean = false,
) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring))
