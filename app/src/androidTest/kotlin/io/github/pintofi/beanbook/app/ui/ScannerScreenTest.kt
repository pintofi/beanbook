package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
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
 * The scanner on CameraX's Compose viewfinder, and pages reordered in its strip (B84).
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
                    onCaptured = { _, _, _ -> },
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
}
