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

/** The scanner's zoom dial: the zoom in the middle, slid left to zoom in and right to zoom out. */
@RunWith(AndroidJUnit4::class)
class ZoomControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun says(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test fun theZoomIsSaidInTheMiddle() {
        compose.setContent { MaterialTheme { ZoomControl(1f, 0.5f, 10f, {}) } }
        compose.onNodeWithContentDescription("Zoom").assert(says("1×"))
    }

    @Test fun slidingLeftZoomsInAndTheReadoutFollows() {
        var ratio by mutableStateOf(1f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 0.5f, 10f, { ratio = it }) } }

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(-200f, 0f))
            up()
        }
        compose.waitForIdle()

        assertTrue("the zoom should have gone up, was $ratio", ratio > 1.2f)
        compose.onNodeWithContentDescription("Zoom").assert(says(ZoomScale.label(ratio)))
    }

    @Test fun slidingRightZoomsOut() {
        var ratio by mutableStateOf(3f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 0.5f, 10f, { ratio = it }) } }

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(200f, 0f))
            up()
        }
        compose.waitForIdle()

        assertTrue("the zoom should have gone down, was $ratio", ratio < 2.8f)
    }

    @Test fun theZoomNeverLeavesTheCameraRange() {
        var ratio by mutableStateOf(1f)
        compose.setContent { MaterialTheme { ZoomControl(ratio, 1f, 4f, { ratio = it }) } }

        compose.onNodeWithContentDescription("Zoom").performTouchInput {
            down(center)
            moveBy(Offset(-5000f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(4f, ratio, 1e-3f)
    }

    @Test fun aCameraWithNoZoomShowsNothing() {
        compose.setContent { MaterialTheme { ZoomControl(1f, 1f, 1f, {}) } }
        compose.onNodeWithContentDescription("Zoom").assertDoesNotExist()
    }
}
