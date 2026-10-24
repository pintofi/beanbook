package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crop editor shows a sampled photograph, off the main thread, and keeps corners in the
 * full photograph's pixels.
 */
@RunWith(AndroidJUnit4::class)
class CropPageTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "crop-${System.nanoTime()}").apply { mkdirs() }

    @After fun clean() {
        dir.deleteRecursively()
    }

    @Test fun cornersOnALargePhotographComeBackInItsOwnPixels() {
        // Larger than the editor decodes, so the shown image is sampled and the scale is not 1.
        val file = File(dir, "big.jpg")
        val bitmap = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val initial = CropQuad(
            CropPoint(400f, 300f), CropPoint(3600f, 320f), CropPoint(3700f, 2800f), CropPoint(380f, 2700f),
        )
        var cropped: CropQuad? = null

        compose.setContent {
            MaterialTheme {
                CropPageScreen(file = file, initial = initial, onCropped = { quad, _ -> cropped = quad }, onDismiss = {})
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Use this crop").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Use this crop").performClick()

        assertNotNull("the crop was not reported", cropped)
        val result: CropQuad = cropped!!
        listOf(
            initial.topLeft to result.topLeft, initial.topRight to result.topRight,
            initial.bottomRight to result.bottomRight, initial.bottomLeft to result.bottomLeft,
        ).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 2f)
            assertEquals(expected.y, actual.y, 2f)
        }
    }
    private fun pageFile(): File {
        val file = File(dir, "page.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return file
    }

    private fun open(file: File, rotation: Int = 0, onCropped: (CropQuad?, Int) -> Unit) {
        compose.setContent {
            MaterialTheme {
                CropPageScreen(
                    file = file, initial = null, onCropped = onCropped, onDismiss = {}, initialRotation = rotation,
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Use this crop").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Pulls a handle from where it is by [byX], [byY] screen pixels, as a finger would. */
    private fun drag(handle: String, byX: Float, byY: Float) {
        compose.onNodeWithContentDescription(handle).performTouchInput {
            down(center)
            repeat(STEPS) { moveBy(Offset(byX / STEPS, byY / STEPS)) }
            up()
        }
        compose.waitForIdle()
    }

    /** Moving a second corner keeps the first where it was put. */
    @Test fun movingOneCornerThenAnotherKeepsBoth() {
        var cropped: CropQuad? = null
        open(pageFile()) { quad, _ -> cropped = quad }

        drag("Top left corner", 80f, 60f)
        drag("Top right corner", -80f, 60f)
        compose.onNodeWithContentDescription("Use this crop").performClick()

        val result: CropQuad = cropped ?: error("the crop was not reported")
        assertTrue("the first corner went back: ${result.topLeft}", result.topLeft.x > 20f && result.topLeft.y > 20f)
        assertTrue("the second corner did not move: ${result.topRight}", result.topRight.x < 780f && result.topRight.y > 20f)
        assertEquals("an untouched corner moved", 800f, result.bottomRight.x, 1f)
    }

    /** A press that lands on no handle moves nothing, unlike the old nearest-corner-from-anywhere. */
    @Test fun aPressInTheMiddleOfThePageMovesNothing() {
        var cropped: CropQuad? = null
        open(pageFile()) { quad, _ -> cropped = quad }

        compose.onNodeWithContentDescription("The page. Drag a corner, or the middle of a side, to move it.")
            .performTouchInput {
                down(center)
                repeat(STEPS) { moveBy(Offset(10f, 10f)) }
                up()
            }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Use this crop").performClick()

        val result: CropQuad = cropped ?: error("the crop was not reported")
        assertEquals(0f, result.topLeft.x, 1f)
        assertEquals(800f, result.bottomRight.x, 1f)
    }

    /** A corner dragged across the page stops where the page would fold. */
    @Test fun aCornerCannotBeDraggedIntoAShapeThatCannotBeStraightened() {
        var cropped: CropQuad? = null
        open(pageFile()) { quad, _ -> cropped = quad }

        drag("Top left corner", 2000f, 2000f)
        compose.onNodeWithContentDescription("Use this crop").performClick()

        val result: CropQuad = cropped ?: error("the crop was not reported")
        assertTrue(
            "the corner went past the page: ${result.topLeft}",
            result.topLeft.x < result.bottomRight.x - 50f && result.topLeft.y < result.bottomRight.y - 50f,
        )
    }

    /** The middle of a side slides the whole side. */
    @Test fun theMiddleOfASideMovesBothItsCorners() {
        var cropped: CropQuad? = null
        open(pageFile()) { quad, _ -> cropped = quad }

        drag("Top side", 0f, 100f)
        compose.onNodeWithContentDescription("Use this crop").performClick()

        val result: CropQuad = cropped ?: error("the crop was not reported")
        assertTrue("the top-left did not move down: ${result.topLeft}", result.topLeft.y > 20f)
        assertEquals(result.topLeft.y, result.topRight.y, 1f)
        assertEquals(0f, result.topLeft.x, 1f)
    }

    /** Turn changes the picture, not the crop, and the new turn is reported. */
    @Test fun turningThePictureIsReportedWithTheCrop() {
        var rotation = -1
        open(pageFile(), rotation = 90) { _, turned -> rotation = turned }

        compose.onNodeWithContentDescription("Turn the page clockwise").performClick()
        compose.onNodeWithContentDescription("Use this crop").performClick()

        assertEquals(180, rotation)
    }

    /** TalkBack and a keyboard have no drag: each handle has actions that move it a step. */
    @Test fun aHandleCanBeMovedWithoutDragging() {
        var cropped: CropQuad? = null
        open(pageFile()) { quad, _ -> cropped = quad }

        val moveRight = compose.onNodeWithContentDescription("Top left corner")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
            .first { it.label == "Move right" }
        compose.runOnUiThread { moveRight.action() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Use this crop").performClick()

        val result: CropQuad = cropped ?: error("the crop was not reported")
        assertEquals("one step is 1% of the shorter side", 6f, result.topLeft.x, 1f)
    }

    private companion object {
        const val STEPS = 10
    }
}
