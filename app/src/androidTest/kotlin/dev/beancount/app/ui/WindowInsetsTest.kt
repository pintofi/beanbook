package dev.beancount.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowInsetsCompat
import dev.beancount.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's content stays out of the status and navigation bars.
 *
 * This needs a real window, which is why it is not in [AccessibilityTest] or [PixelGateTest]:
 * both render a composable in isolation, where `WindowInsets` are zero and every screen looks
 * correctly inset whether or not it is.
 *
 * The defect this catches shipped. From API 35 the platform draws a targetSdk-35+ app behind
 * the system bars whether or not it asks, and this app said nothing about insets at all — so
 * "Beancount" sat directly beneath the clock on the first screen anybody sees. The onboarding
 * and project screens render outside the Scaffold, so the top bar's own inset handling never
 * applied to them.
 */
private fun hasAnyText() = SemanticsMatcher("has text") {
    it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
}

@RunWith(AndroidJUnit4::class)
class WindowInsetsTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun contentDoesNotDrawUnderTheStatusBar() {
        val activity = compose.activity
        val insets = compose.runOnUiThread {
            activity.window.decorView.rootWindowInsets
        }
        assumeTrue("no window insets reported on this device", insets != null)
        val top = WindowInsetsCompat.toWindowInsetsCompat(insets!!)
            .getInsets(WindowInsetsCompat.Type.systemBars()).top
        assumeTrue("this device reports no status bar inset", top > 0)

        compose.waitForIdle()

        // Not the root node: `safeDrawingPadding` shrinks what a composable *draws*, and the
        // root still measures the full window, so its bounds say nothing either way. What
        // matters is where the topmost thing a person can actually see begins.
        val drawn = compose.onAllNodes(hasAnyText()).fetchSemanticsNodes()
        assumeTrue("nothing was rendered to measure", drawn.isNotEmpty())
        val highest = drawn.minOf { it.boundsInWindow.top }
        val label = drawn.minByOrNull { it.boundsInWindow.top }
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "?"

        // Before the fix this was 0 against a 63px bar on the API 36 emulator, and the word
        // measured was "Beancount" — the title, inside the status bar.
        android.util.Log.i("WindowInsets", "MEASURE top_inset=$top highest_content=$highest label=$label")
        assertTrue(
            "the topmost content (\"$label\") starts at ${highest}px with a ${top}px system " +
                "bar inset — it is drawing underneath the status bar",
            highest > top,
        )
    }
}
