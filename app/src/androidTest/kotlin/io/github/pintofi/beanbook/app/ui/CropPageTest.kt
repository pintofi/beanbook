package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crop editor shows a sampled photograph, off the main thread, and keeps corners in the
 * full photograph's pixels (B83).
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
                CropPageScreen(file = file, initial = initial, onCropped = { cropped = it }, onDismiss = {})
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
}
