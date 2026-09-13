package io.github.pintofi.beanbook.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

    /** A page arriving or leaving: longer, because it is a bigger change than a panel. */
    const val EMPHASIS_MS = 280

    /** The signature: the rule drawing down a surface as it arrives. */
    const val RULE_MS = 320

    /**
     * The save acknowledgement (B78): the band crosses the written lines, holds, and fades.
     * Long enough to be seen -- the first version was over before anyone looked -- and short
     * enough that the fifth entry of an evening is not waiting on it.
     */
    const val SAVE_SWEEP_MS = 480
    const val SAVE_HOLD_MS = 220
    const val SAVE_FADE_MS = 260

    /** How long the form stays after a committed save, so the acknowledgement is seen. */
    const val SAVE_ACKNOWLEDGE_MS = SAVE_SWEEP_MS + SAVE_HOLD_MS

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

/**
 * A remembered flag, for composables that only need to know once.
 *
 * Reads [LocalMotionEnabled], which the theme provides from the system setting, so a screen
 * asks the composition rather than the settings provider.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val enabled = LocalMotionEnabled.current
    return remember(enabled) { enabled }
}

/**
 * The motion language, by role (B76).
 *
 * The visual design says a financial surface is ruled paper. The motion says what a page of
 * paper does: it **arrives along one axis**, it **rises when it is on top of something**, and
 * it **stays still while it is being read**. Everything below is that idea divided into the
 * six jobs motion actually has in this app, so a screen picks a role rather than inventing a
 * duration.
 *
 * Two rules run through all of it:
 *
 * - **Distance is small, time is short.** A page moves 24dp, not half the screen, and settles
 *   in about a fifth of a second. A ledger is opened dozens of times a day and a transition a
 *   person has to wait through twice is a transition they resent.
 * - **Nothing bounces on money.** Springs are used for direct manipulation, where overshoot
 *   reads as physical, and never on an amount, a warning, or a destructive confirmation.
 */
object MotionRole {

    /** Press, selection, toggle: the app answering a finger. Fast, and never noticed. */
    fun <T> feedback(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.QUICK_MS, easing = Motion.standardEasing)

    /** A press scale, where a spring is right because a finger is physical. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Expand, collapse, show, hide: one surface changing inside a page that stays put. */
    fun <T> local(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.STANDARD_MS, easing = Motion.standardEasing)

    /** One set of content replacing another in the same frame: a section, a report, a state. */
    fun <T> replace(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.STANDARD_MS, easing = Motion.enter)

    /** Moving between peers -- one destination to the next -- along the horizontal axis. */
    fun <T> peer(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.STANDARD_MS, easing = Motion.standardEasing)

    /** Going deeper, or coming back: the vertical axis, and a touch longer than a peer move. */
    fun <T> hierarchy(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.EMPHASIS_MS, easing = Motion.enter)

    /** A row arriving, leaving, or moving inside a list. */
    fun <T> data(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.STANDARD_MS, easing = Motion.standardEasing)

    /** The signature: the rule drawing down a surface, and the entry filing itself. */
    fun <T> signature(): FiniteAnimationSpec<T> =
        tween(durationMillis = Motion.RULE_MS, easing = Motion.enter)
}

/** How far a surface travels. Small on purpose: this is a page settling, not a slide show. */
object MotionDistance {
    /** A peer destination, left or right. */
    val peer: Dp = 24.dp

    /** A page arriving from deeper in the hierarchy. */
    val hierarchy: Dp = 32.dp

    /** A notice or a row. */
    val local: Dp = 12.dp
}

/**
 * Whether this composition should animate at all.
 *
 * Provided once, at the theme, rather than read per composable: the animator duration scale
 * is a `Settings.Global` lookup, and a screen full of animated rows reading it individually
 * is a screen doing a content-provider call per row. Tests and previews can also override it,
 * which is what makes a screenshot deterministic.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** The spec for [role], or an instant one when the user has asked for no animation. */
@Composable
fun <T> motionSpec(role: () -> FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) role() else snap()
