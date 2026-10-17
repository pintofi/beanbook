package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.PageStyle
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page editor: one page at a time, with the things you do to a page.
 *
 * The arrangement is OSS-DocumentScanner's — swipe the batch, act on what is in front of you
 * — in this app's own controls. What is tested is that each control reports the page it is
 * actually on, because an editor that rotates the wrong page is worse than no editor.
 */
@RunWith(AndroidJUnit4::class)
class PageEditorTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "editor-${System.nanoTime()}").apply { mkdirs() }

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun page(name: String): String {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return name
    }

    private val pages = CapturedPages()
        .add(page("one.jpg"))
        .add(page("two.jpg"))

    @Test fun itSaysWhichPageIsInFrontOfYou() {
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = pages,
                    fileFor = { File(dir, it) },
                    startAt = 1,
                    onRotate = { _, _ -> },
                    onCrop = { _, _ -> },
                    onRestyle = { _, _ -> },
                    onRemove = {},
                    onDone = {},
                )
            }
        }
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
    }

    @Test fun turningAPageReportsThatPageAndThatDirection() {
        var turned: Pair<Int, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = pages,
                    fileFor = { File(dir, it) },
                    startAt = 0,
                    onRotate = { index, clockwise -> turned = index to clockwise },
                    onCrop = { _, _ -> },
                    onRestyle = { _, _ -> },
                    onRemove = {},
                    onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Turn page 1 anticlockwise").performClick()
        assertEquals(0 to false, turned)
        compose.onNodeWithContentDescription("Turn page 1 clockwise").performClick()
        assertEquals(0 to true, turned)
    }

    @Test fun theStyleIsChosenPerPage() {
        var styled: Pair<Int, PageStyle>? = null
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = pages,
                    fileFor = { File(dir, it) },
                    startAt = 0,
                    onRotate = { _, _ -> },
                    onCrop = { _, _ -> },
                    onRestyle = { index, style -> styled = index to style },
                    onRemove = {},
                    onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Render page 1 in Document").performClick()
        assertEquals(0 to PageStyle.DOCUMENT, styled)
    }

    @Test fun removingAPageNamesIt() {
        var removed: Int? = null
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = pages,
                    fileFor = { File(dir, it) },
                    startAt = 1,
                    onRotate = { _, _ -> },
                    onCrop = { _, _ -> },
                    onRestyle = { _, _ -> },
                    onRemove = { removed = it },
                    onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Remove page 2").performClick()
        assertEquals(1, removed)
    }

    @Test fun aPageCanBeMovedEitherWay() {
        var moved: Pair<Int, Int>? = null
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = pages,
                    fileFor = { File(dir, it) },
                    startAt = 1,
                    onRotate = { _, _ -> },
                    onCrop = { _, _ -> },
                    onRestyle = { _, _ -> },
                    onRemove = {},
                    onMove = { from, to -> moved = from to to },
                    onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Change the order of the pages").performClick()
        compose.onNodeWithText("Move page 2 later").assertIsNotEnabled()
        compose.onNodeWithText("Move page 2 earlier").performClick()
        assertEquals(1 to 0, moved)
        // The moved page stays in front, so the counter follows it.
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
    }

    /** Swiping turns pages, stops at either end, and the counter follows. */
    @Test fun swipingTurnsPagesAndStopsAtTheEnds() {
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = pages,
                    fileFor = { File(dir, it) },
                    startAt = 0,
                    onRotate = { _, _ -> },
                    onCrop = { _, _ -> },
                    onRestyle = { _, _ -> },
                    onRemove = {},
                    onDone = {},
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty()
        }
        fun swipe(towardsLater: Boolean) {
            compose.onRoot().performTouchInput { if (towardsLater) swipeLeft() else swipeRight() }
            compose.waitForIdle()
        }
        // No arrows: the swipe is the way between pages.
        compose.onAllNodesWithContentDescription("Next page").assertCountEquals(0)
        swipe(towardsLater = false)
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        swipe(towardsLater = true)
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        swipe(towardsLater = true)
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        swipe(towardsLater = false)
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
    }

    /** An empty batch has nothing to edit, and must not strand the user on a blank screen. */
    @Test fun anEmptyBatchLeavesImmediately() {
        var done = false
        compose.setContent {
            MaterialTheme {
                PageEditorScreen(
                    pages = CapturedPages(),
                    fileFor = { File(dir, it) },
                    startAt = 0,
                    onRotate = { _, _ -> },
                    onCrop = { _, _ -> },
                    onRestyle = { _, _ -> },
                    onRemove = {},
                    onDone = { done = true },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(true, done)
    }
}
