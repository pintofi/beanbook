package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner's zoom dial: the zoom in the middle, and held to the left it keeps zooming in, held
 * to the right it keeps zooming out, until the finger comes back or lifts.
 */
@RunWith(AndroidJUnit4::class)
class ZoomControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The dial changes the zoom every frame while a finger is down, so Compose is never idle then:
     * the test steps the clock itself instead of waiting for it.
     */
    private fun held() {
        compose.mainClock.autoAdvance = false
    }

    private fun says(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test fun theZoomIsSaidInTheMiddle() {
        compose.setContent { MaterialTheme { ZoomControl(1f, 0.5f, 10f, {}) } }
        compose.onNodeWithContentDescription("Zoom").assert(says("1×"))
    }

    @Test fun heldToTheLeftTheZoomKeepsIncreasing() {
        var ratio by mutableStateOf(1f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 0.5f, 10f, { ratio = it }) } }
        held()

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(-200f, 0f))
        }
        compose.mainClock.advanceTimeBy(500)
        val early = ratio
        compose.mainClock.advanceTimeBy(500)
        val later = ratio
        compose.mainClock.advanceTimeBy(500)
        val latest = ratio
        compose.onNodeWithContentDescription("Zoom").performTouchInput { up() }

        assertTrue("it should have started to climb, was $early", early > 1.05f)
        assertTrue("it should keep climbing while held: $early, $later, $latest", later > early && latest > later)
        compose.onNodeWithContentDescription("Zoom").assert(says(ZoomScale.label(ratio)))
    }

    @Test fun heldToTheRightTheZoomKeepsDecreasing() {
        var ratio by mutableStateOf(4f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 0.5f, 10f, { ratio = it }) } }
        held()

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(200f, 0f))
        }
        compose.mainClock.advanceTimeBy(500)
        val early = ratio
        compose.mainClock.advanceTimeBy(500)
        val later = ratio
        compose.onNodeWithContentDescription("Zoom").performTouchInput { up() }

        assertTrue("it should have started to fall, was $early", early < 3.9f)
        assertTrue("it should keep falling while held: $early, $later", later < early)
    }

    @Test fun liftingTheFingerStopsIt() {
        var ratio by mutableStateOf(1f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 0.5f, 10f, { ratio = it }) } }
        held()

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(-200f, 0f))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Zoom").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        val stopped = ratio
        compose.mainClock.advanceTimeBy(1000)

        assertEquals(stopped, ratio, 1e-6f)
    }

    @Test fun bringingTheFingerBackToWhereItStartedStopsIt() {
        var ratio by mutableStateOf(1f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 0.5f, 10f, { ratio = it }) } }
        held()

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(-200f, 0f))
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Zoom").performTouchInput { moveBy(Offset(200f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        val held = ratio
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("Zoom").performTouchInput { up() }

        assertEquals(held, ratio, 1e-6f)
    }

    @Test fun theZoomNeverLeavesTheCameraRange() {
        var ratio by mutableStateOf(1f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 1f, 4f, { ratio = it }) } }
        held()

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(-2000f, 0f))
        }
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithContentDescription("Zoom").performTouchInput { up() }

        assertEquals(4f, ratio, 1e-3f)
    }

    @Test fun aCameraWithNoZoomShowsNothing() {
        compose.setContent { MaterialTheme { ZoomControl(1f, 1f, 1f, {}) } }
        compose.onNodeWithContentDescription("Zoom").assertDoesNotExist()
    }
}
