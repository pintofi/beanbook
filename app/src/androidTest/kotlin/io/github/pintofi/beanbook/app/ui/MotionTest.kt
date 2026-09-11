package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.MoreSection
import io.github.pintofi.beanbook.app.Surface
import io.github.pintofi.beanbook.app.surfaceMove
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The motion contracts (B76).
 *
 * These assert what motion *means* and what it must never cost, rather than how many
 * milliseconds a fade takes: the axis a move travels along, that an interrupted move lands on
 * the destination last asked for, and that with animation turned off every transition is
 * already finished on the first frame. A test that pinned a duration would fail the next time
 * a spec was tuned and prove nothing about the app being usable.
 */
@RunWith(AndroidJUnit4::class)
class MotionTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun route(
        surface: Surface,
        adding: Boolean = false,
        section: MoreSection? = null,
    ) = Triple(surface, adding, section)

    @Test fun destinationsArePeersAndTravelInTheDirectionOfTheBar() {
        val forward = surfaceMove(route(Surface.JOURNAL), route(Surface.BALANCES))
        val back = surfaceMove(route(Surface.BALANCES), route(Surface.JOURNAL))

        assertSame(MotionAxis.PEER, forward.axis)
        assertSame(MotionAxis.PEER, back.axis)
        // Back is the mirror of forward, so the two undo each other on screen.
        assertEquals(forward.direction.toLong(), -back.direction.toLong())
    }

    /**
     * Reports is Fava's own page in a WebView, and it is a cut in both directions.
     *
     * Measured rather than assumed: sliding the browser window put the 95th-percentile frame
     * time up from 300ms to 500ms on the same scripted route, and the destination bar has
     * already said where the person went.
     */
    @Test fun theReportsTabIsACutBecauseAnimatingAWebViewCosts() {
        assertSame(
            MotionAxis.INSTANT,
            surfaceMove(route(Surface.HOME), route(Surface.REPORTS)).axis,
        )
        assertSame(
            MotionAxis.INSTANT,
            surfaceMove(route(Surface.REPORTS), route(Surface.MORE)).axis,
        )
    }

    /** Opening the form over Reports is still the form opening, not a cut. */
    @Test fun theFormStillComesOutOfTheActionEvenOverTheReportsTab() {
        assertSame(
            MotionAxis.CONTAINER,
            surfaceMove(route(Surface.REPORTS), route(Surface.REPORTS, adding = true)).axis,
        )
    }

    @Test fun aSectionIsDeeperThanTheHubItCameFrom() {
        val into = surfaceMove(route(Surface.MORE), route(Surface.MORE, section = MoreSection.BACKUP))
        val outOf = surfaceMove(route(Surface.MORE, section = MoreSection.BACKUP), route(Surface.MORE))

        assertSame(MotionAxis.HIERARCHY, into.axis)
        assertSame(MotionAxis.HIERARCHY, outOf.axis)
        assertEquals(into.direction.toLong(), -outOf.direction.toLong())
    }

    /** The form is opened by the button in the corner, so it comes out of that corner. */
    @Test fun theEntryFormComesOutOfTheActionThatOpenedIt() {
        val open = surfaceMove(route(Surface.HOME), route(Surface.HOME, adding = true))
        val close = surfaceMove(route(Surface.HOME, adding = true), route(Surface.HOME))

        assertSame(MotionAxis.CONTAINER, open.axis)
        assertSame(MotionAxis.CONTAINER, close.axis)
        assertEquals(Move.action, open.origin)
        assertEquals(open.direction.toLong(), -close.direction.toLong())
    }

    /**
     * Opening the form from inside a More section is still the form opening.
     *
     * The rule that decides an axis takes the cases in order, and this is the one that would
     * break first if that order were ever rearranged: a route change that alters both the
     * section and the form must read as the form, because that is what the person did.
     */
    @Test fun theFormWinsOverEverythingElseThatChangedWithIt() {
        val move = surfaceMove(
            route(Surface.MORE, section = MoreSection.BACKUP),
            route(Surface.JOURNAL, adding = true),
        )

        assertSame(MotionAxis.CONTAINER, move.axis)
    }

    /** One More section replacing another has no direction, and does not invent one. */
    @Test fun oneSectionReplacingAnotherDoesNotTravel() {
        val move = surfaceMove(
            route(Surface.MORE, section = MoreSection.BACKUP),
            route(Surface.MORE, section = MoreSection.MIRROR),
        )

        assertSame(MotionAxis.NONE, move.axis)
    }

    /**
     * Mashing the navigation bar lands on the last destination tapped.
     *
     * `AnimatedContent` retargets a transition that is still running rather than queueing the
     * next one, which is what makes this true -- but it is true only while the content is
     * driven by the state and not by an animation callback, so it is worth a test.
     */
    @Test fun aTransitionInterruptedMidFlightEndsOnTheLastStateAskedFor() {
        var target by mutableStateOf("one")
        compose.setContent {
            MaterialTheme {
                LedgerContent(
                    targetState = target,
                    move = { _, _ -> Move.peer(forward = true) },
                    modifier = Modifier.fillMaxSize(),
                ) { value -> Box(Modifier.fillMaxSize()) { Text(value) } }
            }
        }

        compose.mainClock.autoAdvance = false
        target = "two"
        compose.mainClock.advanceTimeBy(40)
        target = "three"
        compose.mainClock.advanceTimeBy(40)
        target = "four"
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithText("four").assertIsDisplayed()
        compose.onAllNodesWithText("one").assertCountEquals(0)
        compose.onAllNodesWithText("two").assertCountEquals(0)
        compose.onAllNodesWithText("three").assertCountEquals(0)
    }

    /**
     * With animation off the result is on screen immediately, not quickly.
     *
     * The distinction matters: a person who has turned animation off has asked for the end
     * state, and a 200ms transition that merely feels fast is still a screen they cannot use
     * for 200ms. The clock is held still here, so anything that arrives has arrived without
     * a single frame of animation.
     */
    @Test fun withMotionDisabledTheDestinationIsAlreadyThere() {
        var target by mutableStateOf("before")
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                MaterialTheme {
                    LedgerContent(
                        targetState = target,
                        move = { _, _ -> Move.deeper() },
                        modifier = Modifier.fillMaxSize(),
                    ) { value -> Box(Modifier.fillMaxSize()) { Text(value) } }
                }
            }
        }

        compose.mainClock.autoAdvance = false
        target = "after"
        // One frame, and no more: enough to recompose, not enough to animate.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("after").assertIsDisplayed()
        compose.onAllNodesWithText("before").assertCountEquals(0)
    }

    /** A block that comes and goes leaves nothing behind that could still be tapped. */
    @Test fun aRevealedBlockIsGoneWhenItHasGone() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                LedgerReveal(visible = visible) { Text("notice") }
            }
        }

        compose.onNodeWithText("notice").assertIsDisplayed()
        visible = false
        compose.waitForIdle()

        compose.onAllNodesWithText("notice").assertCountEquals(0)
    }
}
