package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen shows the log as it is now, not as it was at start-up (B62).
 *
 * The first build read the file where the screen's values were assembled, which is at app
 * start: the screen then reported a single "app started" line for the rest of the run, which
 * is the one moment a diagnostics screen must not be wrong about.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val lines = mutableListOf("first line")
    private var reads = 0

    private fun show() {
        compose.setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    read = {
                        reads++
                        DiagnosticsRecord(lines.toList(), lines.sumOf { it.length + 1 }.toLong())
                    },
                    onCopy = {},
                    onShare = {},
                    onClear = { lines.clear() },
                )
            }
        }
    }

    @Test fun readsTheLogWhenTheScreenOpens() {
        lines += "written after the screen was built"
        show()

        compose.onNodeWithText("written after the screen was built").assertIsDisplayed()
        assertEquals("read once for the opening", 1, reads)
    }

    @Test fun countsAndSizeAreReadable() {
        show()

        compose.onNodeWithText("1 line · 11 bytes kept on this device").assertIsDisplayed()
    }

    @Test fun clearingReadsAgain() {
        show()

        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Clear it").performClick()

        compose.onNodeWithText("Nothing recorded yet.").assertIsDisplayed()
        assertEquals("read again after clearing", 2, reads)
    }
}
