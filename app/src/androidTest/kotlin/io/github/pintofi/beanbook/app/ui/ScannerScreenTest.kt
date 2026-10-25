package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.CapturedPages
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner on CameraX's Compose viewfinder, and pages reordered in its strip.
 *
 * Runs against whatever camera the device has: the emulator's virtual scene is enough to prove
 * the viewfinder binds and draws, not how a receipt photographs.
 */
@RunWith(AndroidJUnit4::class)
class ScannerScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "scanner-${System.nanoTime()}").apply { mkdirs() }

    @Before fun grantCamera() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
    }

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun page(name: String): String {
        val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return name
    }

    @Test fun theViewfinderBindsAndThePageStripMovesPages() {
        val pages = CapturedPages().add(page("a.jpg")).add(page("b.jpg")).add(page("c.jpg"))
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            MaterialTheme {
                ScannerScreen(
                    pages = pages.pages,
                    fileFor = { File(dir, it) },
                    captureFile = { File(dir, "shot-${System.nanoTime()}.jpg") },
                    onCaptured = { _, _, _, _ -> },
                    onRemovePage = {},
                    onDone = {},
                    onCancel = {},
                    onMovePage = { from, to -> moves += from to to },
                )
            }
        }

        compose.onNodeWithContentDescription("Leave the scanner").assertIsDisplayed()
        // The viewfinder is only composed once CameraX has handed it a surface.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Camera viewfinder. Tap to focus.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The strip offers the same moves the drag makes, to accessibility services.
        val node = compose.onNodeWithContentDescription("Page 2. Tap to see it, or hold and drag to move it.")
            .fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "Move page later" }.action() }
        assertEquals(listOf(1 to 2), moves)

        // And by hand: hold the first page, then drag it past the second.
        moves.clear()
        compose.onNodeWithContentDescription("Page 1. Tap to see it, or hold and drag to move it.")
            .performTouchInput {
                down(center)
                advanceEventTime(LONG_PRESS_MILLIS)
                repeat(DRAG_STEPS) { moveBy(androidx.compose.ui.geometry.Offset(width / 4f, 0f)) }
                up()
            }
        compose.waitForIdle()
        assertEquals("a long-press drag should move page 1 later", 0, moves.firstOrNull()?.first)
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 800L
        const val DRAG_STEPS = 8
    }

    private class Session(initial: CapturedPages) {
        var pages by androidx.compose.runtime.mutableStateOf(initial)
        val actions = io.github.pintofi.beanbook.app.documents.PageReviewActions(
            current = { pages },
            replace = { expected, next -> if (pages == expected) { pages = next; true } else false },
        )
    }

    private fun scanner(session: Session, onDone: () -> Unit = {}, onCancel: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ScannerScreen(
                    pages = session.pages.pages,
                    fileFor = { File(dir, it) },
                    captureFile = { File(dir, "shot-${System.nanoTime()}.jpg") },
                    onCaptured = { _, _, _, _ -> },
                    onRemovePage = {},
                    onDone = onDone,
                    onCancel = onCancel,
                    review = session.actions,
                    batch = session.pages,
                )
            }
        }
    }

    @Test fun theCheckOpensTheReviewAndBackReturnsToTheCamera() {
        val session = Session(CapturedPages().add(page("a.jpg")).add(page("b.jpg")))
        var left = false
        scanner(session, onCancel = { left = true })

        compose.onNodeWithContentDescription("Review the 2 pages").performClick()
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        // Auto-capture is paused behind the review, and back is the camera, not the end of the scan.
        compose.onNodeWithContentDescription("Back to the camera").performClick()
        compose.onNodeWithContentDescription("Leave the scanner").assertIsDisplayed()
        assertEquals(false, left)
    }

    @Test fun retakingAPageShowsItsNumberAndHidesTheBatch() {
        val session = Session(CapturedPages().add(page("a.jpg")).add(page("b.jpg")))
        scanner(session)

        compose.onNodeWithContentDescription("Review the 2 pages").performClick()
        compose.onNodeWithContentDescription("Retake page 2").performClick()
        compose.onNodeWithText("Retake page 2").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Review the 2 pages").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Page 1. Tap to see it, or hold and drag to move it.")
            .assertCountEquals(0)

        // Leaving a retake returns to the page with nothing changed.
        compose.onNodeWithContentDescription("Leave the scanner").performClick()
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        assertEquals(listOf("a.jpg", "b.jpg"), session.pages.pages.map { it.source })
    }

    @Test fun addingAPageFromTheReviewReturnsToTheCameraWithTheBatchKept() {
        val session = Session(CapturedPages().add(page("a.jpg")))
        scanner(session)

        compose.onNodeWithContentDescription("Review the 1 pages").performClick()
        compose.onNodeWithText("Add page").performClick()
        compose.onNodeWithContentDescription("Leave the scanner").assertIsDisplayed()
        assertEquals(1, session.pages.size)
    }

    @Test fun theCameraOffersAutomaticAndEveryStyleWithAutomaticChosen() {
        scanner(Session(CapturedPages()))
        compose.onNodeWithContentDescription("Capture pages in the style the scanner chooses").assertIsSelected()
        listOf("Colour", "Document", "Greyscale", "Black and white").forEach {
            compose.onNodeWithContentDescription("Capture pages in $it").assertExists()
        }
        compose.onNodeWithContentDescription("Capture pages in Greyscale").performClick()
        compose.onNodeWithContentDescription("Capture pages in Greyscale").assertIsSelected()
        compose.onNodeWithContentDescription("Capture pages in the style the scanner chooses").assertIsNotSelected()
    }

    @Test fun theLightGoesOutWhenAPageIsOpenedForReview() {
        val session = Session(CapturedPages().add(page("a.jpg")))
        scanner(session)
        compose.onNodeWithContentDescription("Turn the light on").performClick()
        compose.onNodeWithContentDescription("Turn the light off").assertIsDisplayed()

        compose.onNodeWithContentDescription("Review the 1 pages").performClick()
        compose.onNodeWithContentDescription("Back to the camera").performClick()
        // Back at the camera, the light is off and has to be asked for again.
        compose.onNodeWithContentDescription("Turn the light on").assertIsDisplayed()
    }

    @Test fun croppingToThePageCanBeTurnedOff() {
        scanner(Session(CapturedPages()))
        compose.onNodeWithContentDescription("Crop to the page automatically is on. Tap to keep the whole photo")
            .performClick()
        compose.onNodeWithContentDescription("Keep the whole photo is on. Tap to crop to the page automatically")
            .assertIsDisplayed()
    }

    @Test fun thePhotoSizeCanBeChosen() {
        scanner(Session(CapturedPages()))
        compose.onNodeWithContentDescription("Photo size: Large. Tap to change").performClick()
        compose.onNodeWithText("Medium", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Small", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Medium", substring = true).performClick()
        compose.onNodeWithContentDescription("Photo size: Medium. Tap to change").assertIsDisplayed()
    }
}
