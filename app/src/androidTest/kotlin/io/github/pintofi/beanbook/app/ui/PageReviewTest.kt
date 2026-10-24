package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.documents.PageReviewActions
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.PageSize
import io.github.pintofi.beanbook.core.domain.PageStyle
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page review: one page at a time, with the things you do to a page.
 *
 * What is tested is that each control acts on the page actually in front of you -- a review that
 * rotates the wrong page is worse than none -- and that what loses something can be undone.
 */
@RunWith(AndroidJUnit4::class)
class PageReviewTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "review-${System.nanoTime()}").apply { mkdirs() }

    @After fun clean() {
        dir.deleteRecursively()
    }

    private fun page(name: String): String {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return name
    }

    /** A capture session in memory: the pages, and the review's way to read and change them. */
    private class Session(initial: CapturedPages) {
        var pages by mutableStateOf(initial)
        val actions = PageReviewActions(current = { pages }, replace = { expected, next ->
            if (pages == expected) {
                pages = next
                true
            } else {
                false
            }
        })
    }

    private val two = CapturedPages().add(page("one.jpg")).add(page("two.jpg"))
    private val three = two.add(page("three.jpg"))

    private fun open(session: Session, startAt: Int = 0, onDone: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                PageReviewScreen(
                    pages = session.pages,
                    fileFor = { File(dir, it) },
                    startAt = startAt,
                    review = session.actions,
                    onDone = onDone,
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun itSaysWhichPageIsInFrontOfYou() {
        open(Session(two), startAt = 1)
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
    }

    @Test fun turningAPageTurnsThatPage() {
        val session = Session(two)
        open(session)
        compose.onNodeWithContentDescription("Turn page 1 clockwise").performClick()
        assertEquals(90, session.pages.pages[0].rotation)
        compose.onNodeWithContentDescription("Turn page 1 anticlockwise").performClick()
        assertEquals(0, session.pages.pages[0].rotation)
        assertEquals(0, session.pages.pages[1].rotation)
    }

    @Test fun theStyleIsChosenPerPageAndCanBeGivenToAll() {
        val session = Session(two)
        open(session)
        compose.onNodeWithContentDescription("Render page 1 in Document").performClick()
        assertEquals(listOf(PageStyle.DOCUMENT, PageStyle.COLOUR), session.pages.pages.map { it.style })

        compose.onNodeWithText("Use for all pages").performClick()
        assertEquals(listOf(PageStyle.DOCUMENT, PageStyle.DOCUMENT), session.pages.pages.map { it.style })
        compose.onNodeWithText("Every page is now Document.").assertIsDisplayed()

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf(PageStyle.DOCUMENT, PageStyle.COLOUR), session.pages.pages.map { it.style })
    }

    @Test fun removingAPageNamesItAndUndoBringsItBack() {
        val session = Session(three)
        open(session, startAt = 1)
        compose.onNodeWithContentDescription("Remove page 2").performClick()
        assertEquals(listOf("one.jpg", "three.jpg"), session.pages.pages.map { it.source })
        compose.onNodeWithText("Page 2 removed.").assertIsDisplayed()

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf("one.jpg", "two.jpg", "three.jpg"), session.pages.pages.map { it.source })
        compose.onNodeWithText("Page 2 of 3").assertIsDisplayed()
    }

    @Test fun undoDoesNothingOnceAnythingElseHasChanged() {
        val session = Session(three)
        open(session, startAt = 1)
        compose.onNodeWithContentDescription("Remove page 2").performClick()
        // Something else changes the pages while the Undo is still on screen.
        compose.runOnIdle { session.pages = session.pages.add(page("four.jpg")) }
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf("one.jpg", "three.jpg", "four.jpg"), session.pages.pages.map { it.source })
    }

    @Test fun removingTheOnlyPageLeavesAnEmptyStateWithUndo() {
        val session = Session(CapturedPages().add(page("one.jpg")))
        open(session)
        compose.onNodeWithContentDescription("Remove page 1").performClick()
        compose.onNodeWithText("No pages left.").assertIsDisplayed()
        compose.onNodeWithText("Finish with 0 pages").assertIsNotEnabled()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(1, session.pages.size)
    }

    @Test fun aPageCanBeMovedEitherWayAndTheScreenFollowsIt() {
        val session = Session(two)
        open(session, startAt = 1)
        compose.onNodeWithContentDescription("More actions for these pages").performClick()
        compose.onNodeWithText("Move page 2 later").assertIsNotEnabled()
        compose.onNodeWithText("Move page 2 earlier").performClick()
        assertEquals(listOf("two.jpg", "one.jpg"), session.pages.pages.map { it.source })
        // The moved page stays in front, so the counter follows it.
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
    }

    @Test fun theStripMarksTheShownPageAndTappingAThumbnailShowsIt() {
        open(Session(three), startAt = 0)
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tab).assertCountEquals(3)
        compose.onAllNodes(tab)[0].assertIsSelected()
        compose.onAllNodes(tab)[2].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Page 3 of 3").assertIsDisplayed()
        compose.onAllNodes(tab)[2].assertIsSelected()
    }

    /** Swiping turns pages, stops at either end, and the counter follows. */
    @Test fun swipingTurnsPagesAndStopsAtTheEnds() {
        open(Session(two))
        fun swipe(towardsLater: Boolean) {
            compose.onNodeWithTag(PAGER_TAG).performTouchInput { if (towardsLater) swipeLeft() else swipeRight() }
            compose.waitForIdle()
        }
        compose.onAllNodesWithContentDescription("Next page").assertCountEquals(0)
        swipe(towardsLater = false)
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        swipe(towardsLater = true)
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        swipe(towardsLater = true)
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
    }

    @Test fun finishingNamesHowManyPages() {
        var done = 0
        open(Session(three), onDone = { done++ })
        compose.onNodeWithText("Finish with 3 pages").performClick()
        assertEquals(1, done)
    }

    @Test fun thePageSizeIsChosenForTheWholeDocument() {
        val session = Session(two)
        open(session)
        compose.onNodeWithContentDescription("More actions for these pages").performClick()
        compose.onNodeWithText("Page size: As scanned").performClick()

        compose.onNodeWithText("Each page keeps the shape it was photographed.").assertIsDisplayed()
        compose.onNodeWithText("210 × 297 mm. Pages are fitted and centred.").assertIsDisplayed()
        compose.onNodeWithText("8.5 × 11 in. Pages are fitted and centred.").assertIsDisplayed()
        compose.onNodeWithText("A4").performClick()
        compose.onNodeWithText("Use").performClick()
        assertEquals(PageSize.A4, session.pages.pageSize)

        compose.onNodeWithContentDescription("More actions for these pages").performClick()
        compose.onNodeWithText("Page size: A4").assertIsDisplayed()
    }

    @Test fun cancellingThePageSizeDialogChangesNothing() {
        val session = Session(two)
        open(session)
        compose.onNodeWithContentDescription("More actions for these pages").performClick()
        compose.onNodeWithText("Page size: As scanned").performClick()
        compose.onNodeWithText("Letter").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(PageSize.AS_SCANNED, session.pages.pageSize)
    }

    @Test fun retakeAndAddPageAreOfferedOnlyWhereThereIsACameraToOpen() {
        val session = Session(two)
        open(session)
        compose.onAllNodesWithContentDescription("Retake page 1").assertCountEquals(0)
        compose.onAllNodesWithText("Add page").assertCountEquals(0)
    }

    @Test fun everyStyleIsOfferedAndAnAutomaticChoiceSaysSo() {
        val session = Session(CapturedPages().add(page("one.jpg"), PageStyle.GREYSCALE, automatic = true))
        open(session)
        PageStyle.entries.forEach { style ->
            compose.onNodeWithContentDescription("Render page 1 in ${style.label()}").assertExists()
        }
        compose.onNodeWithText("Black and white").assertExists()
        compose.onNodeWithText("Chosen automatically").assertIsDisplayed()

        compose.onNodeWithContentDescription("Render page 1 in Black and white").performClick()
        assertEquals(PageStyle.BLACK_AND_WHITE, session.pages.pages[0].style)
        // A choice of your own is final, and the note goes.
        compose.onAllNodesWithText("Chosen automatically").assertCountEquals(0)
    }
}
