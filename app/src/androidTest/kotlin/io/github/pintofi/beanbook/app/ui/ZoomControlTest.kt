package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.center
import androidx.compose.ui.test.centerLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The scanner's zoom control: stops to tap, a strip to slide along, and the value shown above. */
@RunWith(AndroidJUnit4::class)
class ZoomControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tappingAStopZoomsThereAndTheReadoutFollows() {
        var ratio by mutableStateOf(1f)
        compose.setContent {
            MaterialTheme { ZoomControl(ratio, 1f, 8f, { ratio = it }) }
        }
        compose.onNodeWithContentDescription("Zoom to 2×").performClick()
        compose.waitForIdle()
        assertEquals(2f, ratio, 1e-3f)
        compose.onNodeWithContentDescription("Zoom").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "2×",
            ),
        )
    }

    @Test fun slidingAlongTheStripZoomsInAndOutAndShowsTheValueBetweenStops() {
        var ratio by mutableStateOf(1f)
        compose.setContent {
            MaterialTheme { ZoomControl(ratio, 1f, 8f, { ratio = it }) }
        }
        // Pressed on the 1x stop and slid to the right.
        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(60f, 0f))
            moveTo(center)
            up()
        }
        compose.waitForIdle()
        assertTrue("the zoom should have gone up, was $ratio", ratio > 1.5f)
        compose.onNodeWithContentDescription("Zoom").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                ZoomScale.label(ratio),
            ),
        )
    }

    @Test fun aCameraWithNoZoomShowsNothing() {
        compose.setContent { MaterialTheme { ZoomControl(1f, 1f, 1f, {}) } }
        compose.onNodeWithContentDescription("Zoom").assertDoesNotExist()
    }
}
