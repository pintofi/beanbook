package io.github.pintofi.beanbook.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * The motion language: one gesture, used sparingly (B74).
 *
 * The rule this app follows is that motion explains a change and then stops. A ledger is read,
 * not watched, so there is no perpetual animation anywhere, nothing animates on a timer, and a
 * list does not stagger its rows -- twenty rows arriving one after another is twenty small
 * distractions on a surface whose whole job is to be scanned.
 *
 * What is animated: **the rule**. The column rule of a financial surface draws down when the
 * surface arrives, the figure above it settles a few pixels, and that is the signature. It
 * happens once per surface, it is under a quarter of a second, and it says "this page is ruled
 * paper and here is the page".
 *
 * Everything else uses [standard] to move between two states a person asked for.
 */
object Motion {

    /** A fast state change: pressed, selected, expanded. */
    const val QUICK_MS = 120

    /** The standard: a thing appearing, a value changing, a panel opening. */
    const val STANDARD_MS = 220

    /** The signature: the rule drawing down a surface as it arrives. */
    const val RULE_MS = 320

    /**
     * Material's emphasised-decelerate curve: quick to start, long to settle. It reads as
     * something arriving under its own weight rather than being switched on.
     */
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** The mirror of [enter], for something leaving. */
    val exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Symmetrical, for a value moving between two known states. */
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> standard(durationMs: Int = STANDARD_MS): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMs, easing = standardEasing)

    fun <T> arriving(durationMs: Int = RULE_MS, delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMs, delayMillis = delayMs, easing = enter)
}

/**
 * Whether animation should happen at all.
 *
 * A person who has turned animations off in Developer options or in Accessibility has asked
 * for the result, not the transition -- and on Android that is reported as an animator duration
 * scale of zero rather than as a "reduce motion" flag. Honouring it is not optional: for some
 * people motion is a vestibular problem, not a preference.
 *
 * Previews and screenshot tests read as motion-off, so a captured frame is the settled state
 * rather than whatever the animation happened to be doing.
 */
@Composable
@ReadOnlyComposable
fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) > 0f
}

/** [Motion.RULE_MS], or zero when the user has asked for no animation. */
@Composable
fun ruleDuration(): Int = if (animationsEnabled()) Motion.RULE_MS else 0

/** A remembered flag, for composables that only need to know once. */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val enabled = animationsEnabled()
    return remember(enabled) { enabled }
}
