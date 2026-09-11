package io.github.pintofi.beanbook.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import io.github.pintofi.beanbook.app.ui.theme.Motion
import io.github.pintofi.beanbook.app.ui.theme.MotionDistance
import io.github.pintofi.beanbook.app.ui.theme.MotionRole

/**
 * The parts motion is made of (B76).
 *
 * The visual system says the app is ruled paper; this says what paper does. A **peer** move
 * slides a short way along the horizontal axis, because two destinations sit side by side. A
 * **hierarchical** move rises from below and sinks back, because a section is on top of the
 * page you came from. A **replacement** dissolves in place, because nothing moved. Everything
 * here is short, small, and interruptible: `AnimatedContent` and `AnimatedVisibility` retarget
 * mid-flight rather than queueing, which is what makes a person mashing the navigation bar end
 * up on the destination they last tapped instead of the one they tapped first.
 *
 * When the user has turned animation off, every helper here collapses to an instant swap: the
 * specs come from `motionSpec`, which returns `snap()`, and the offsets are never read.
 */

/** Which way a surface travels when it is replaced. */
enum class MotionAxis {
    /** Left or right: two things at the same level. */
    PEER,

    /** Up from the page, or back down into it: one thing on top of another. */
    HIERARCHY,

    /**
     * Out of the control that opened it, and back into it.
     *
     * The entry form is opened by the button in the corner, so it grows from that corner and
     * shrinks back to it: the closest this app gets to a container transform without a shared
     * element, and reliable in a Scaffold whose action and content are different slots.
     */
    CONTAINER,

    /** Nowhere: the same place, different content. */
    NONE,

    /**
     * A word replacing a word in the same small space.
     *
     * The arriving text waits for the leaving text to go: two labels crossfading on top of
     * each other inside a top bar is not a transition, it is a smudge, and the space is too
     * small for either to be readable while both are there.
     */
    LABEL,
}

/**
 * One surface replacing another.
 *
 * [direction] is +1 forward, -1 back, and 0 when there is no direction to speak of. Forward
 * and back are mirror images by construction rather than by two hand-written pairs, so back
 * always undoes what forward did.
 */
@Composable
fun <T> LedgerContent(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "surface",
    contentKey: (T) -> Any? = { it },
    /**
     * How to get from one state to another, decided from the pair rather than remembered.
     *
     * `AnimatedContent` hands its transition spec both the state being left and the state
     * being entered, which is the only place the direction is reliably known: a flag updated
     * beside the navigation would be one recomposition behind on the first frame of every
     * transition, and wrong for the whole of an interrupted one.
     */
    move: (initial: T, target: T) -> Move,
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    val animate = LocalMotionEnabled.current
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        label = label,
        contentKey = contentKey,
        transitionSpec = {
            val movement = move(initialState, targetState)
            transformFor(movement, animate)
        },
        content = content,
    )
}

/** One step from one surface to another: which axis, which way along it, and from where. */
data class Move(
    val axis: MotionAxis,
    val direction: Int = 1,
    /** Where a [MotionAxis.CONTAINER] move grows from, in fractions of the surface. */
    val origin: TransformOrigin = TransformOrigin.Center,
) {
    companion object {
        val none = Move(MotionAxis.NONE, 0)
        fun peer(forward: Boolean) = Move(MotionAxis.PEER, if (forward) 1 else -1)
        fun deeper() = Move(MotionAxis.HIERARCHY, 1)
        fun back() = Move(MotionAxis.HIERARCHY, -1)

        /** The bottom-right corner, where the add button sits on a phone. */
        val action = TransformOrigin(0.88f, 0.92f)

        fun outOf(origin: TransformOrigin) = Move(MotionAxis.CONTAINER, 1, origin)
        fun backInto(origin: TransformOrigin) = Move(MotionAxis.CONTAINER, -1, origin)

        /** A label replacing a label: down for deeper, up for back. */
        fun label(deeper: Boolean) = Move(MotionAxis.LABEL, if (deeper) 1 else -1)
    }
}

/**
 * The enter/exit pair for one axis.
 *
 * Content leaves *faster than it arrives* -- 60% of the duration -- so the two never cross at
 * full opacity and the new page reads as the one being looked at. `SizeTransform(clip = false)`
 * because the surfaces here are full-height: clipping to the shrinking one cuts the arriving
 * page off at the old page's height for the length of the transition.
 */
private fun <T> AnimatedContentTransitionScope<T>.transformFor(
    move: Move,
    animate: Boolean,
): ContentTransform {
    val axis = move.axis
    val direction = move.direction
    if (!animate) {
        return (fadeIn(tween(0)) togetherWith fadeOut(tween(0)))
            .using(androidx.compose.animation.SizeTransform(clip = false))
    }
    val enter = MotionRole.peer<Float>()
    val exit = tween<Float>(
        durationMillis = (Motion.STANDARD_MS * 0.6f).toInt(),
        easing = Motion.exit,
    )
    val transform = when (axis) {
        MotionAxis.PEER -> {
            val travel = { full: Int -> (full * 0.06f).toInt().coerceAtLeast(1) * direction }
            slideInHorizontally(MotionRole.peer()) { travel(it) } + fadeIn(enter) togetherWith
                slideOutHorizontally(MotionRole.peer()) { -travel(it) } + fadeOut(exit)
        }

        MotionAxis.HIERARCHY -> {
            val travel = { full: Int -> (full * 0.04f).toInt().coerceAtLeast(1) * direction }
            slideInVertically(MotionRole.hierarchy()) { travel(it) } +
                fadeIn(enter) +
                scaleIn(MotionRole.hierarchy(), initialScale = 0.98f) togetherWith
                fadeOut(exit) + scaleOut(MotionRole.hierarchy(), targetScale = 1.01f)
        }

        MotionAxis.CONTAINER -> {
            // Growing out of the button, or shrinking back into it. The scale is small -- a
            // page that starts at a tenth of its size reads as a zoom, and a zoom on every
            // entry is exhausting -- and the origin is what carries the meaning.
            val forward = direction >= 0
            scaleIn(
                MotionRole.hierarchy(),
                initialScale = if (forward) 0.92f else 1.02f,
                transformOrigin = move.origin,
            ) + fadeIn(enter) togetherWith
                scaleOut(
                    MotionRole.hierarchy(),
                    targetScale = if (forward) 1.02f else 0.94f,
                    transformOrigin = move.origin,
                ) + fadeOut(exit)
        }

        MotionAxis.LABEL -> {
            val out = Motion.QUICK_MS
            fadeIn(tween(Motion.QUICK_MS, delayMillis = out, easing = Motion.enter)) +
                slideInVertically(
                    tween(Motion.STANDARD_MS, delayMillis = out, easing = Motion.enter),
                ) { (it * 0.4f).toInt() * direction } togetherWith
                fadeOut(tween(out, easing = Motion.exit))
        }

        MotionAxis.NONE -> fadeIn(enter) togetherWith fadeOut(exit)
    }
    return transform.using(androidx.compose.animation.SizeTransform(clip = false))
}

/**
 * A notice, a banner, or a block that comes and goes inside a page that stays put.
 *
 * Expands rather than slides: the thing around it is a column, so the honest movement is the
 * page making room. Fades on the way out at the same time as it collapses, because a notice
 * that shrinks to nothing while still opaque looks like a bug.
 */
@Composable
fun LedgerReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    label: String = "reveal",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val animate = LocalMotionEnabled.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        label = label,
        enter = if (animate) {
            expandVertically(MotionRole.local()) + fadeIn(MotionRole.local())
        } else {
            fadeIn(tween(0))
        },
        exit = if (animate) {
            shrinkVertically(MotionRole.local()) + fadeOut(MotionRole.local())
        } else {
            fadeOut(tween(0))
        },
        content = content,
    )
}

/**
 * A control answering a finger.
 *
 * A spring rather than a tween, because a press is direct manipulation and a person's thumb is
 * physical; damped so it settles without bouncing, because this is a ledger and not a toy. The
 * scale is applied in a graphics layer, so a pressed row costs a redraw rather than a
 * relayout of everything beside it.
 */
@Composable
fun Modifier.pressResponse(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.985f,
): Modifier {
    val animate = LocalMotionEnabled.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && animate) pressedScale else 1f,
        animationSpec = MotionRole.press(),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The signature: an entry filing itself.
 *
 * When a save lands, the row it produced is swept once by the app's own rule -- a band of the
 * rule colour travelling across the row from the gutter to the page edge, left behind for a
 * moment and then gone. It is the one moment in the app that celebrates anything, and it is
 * deliberately over in under half a second: the reward for writing an entry is seeing it in
 * the book, not watching an animation.
 *
 * Drawn, not laid out: the sweep is a rectangle in a `drawWithContent`, so the row it marks
 * does not move, resize, or re-measure while it happens, and a list of two thousand rows is
 * unaffected by one of them being highlighted.
 */
@Composable
fun Modifier.filedSweep(
    key: Any?,
    colour: Color,
): Modifier {
    val animate = LocalMotionEnabled.current
    var progress by remember { mutableFloatStateOf(if (key == null) 0f else 1f) }
    val swept = remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(key) {
        if (key == null || key == swept.value || !animate) {
            progress = 0f
            return@LaunchedEffect
        }
        swept.value = key
        androidx.compose.animation.core.Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = MotionRole.signature(),
        ) { progress = 1f - value }
        progress = 0f
    }
    if (progress <= 0f) return this
    return drawWithContent {
        drawContent()
        val width = size.width * progress
        val rule = 2.dp.toPx()
        drawRect(
            color = colour.copy(alpha = 0.18f * progress),
            topLeft = Offset.Zero,
            size = Size(width = width, height = size.height),
        )
        drawRect(
            color = colour.copy(alpha = 0.9f * progress),
            topLeft = Offset(x = (width - rule).coerceAtLeast(0f), y = 0f),
            size = Size(width = rule, height = size.height),
        )
    }
}

/**
 * A surface arriving from the action that opened it.
 *
 * Used for the entry form, which is opened by the button in the bottom-right corner: the page
 * grows from that corner rather than appearing whole, so the form reads as *that button
 * expanded* without needing a shared-element transition across the Scaffold's slots. A small
 * scale, because a page springing from 0 to full size is the kind of motion that looks
 * expensive and is tiring by the fifth entry of the evening.
 */
@Composable
fun Modifier.arrivesFrom(origin: TransformOrigin, visible: Boolean = true): Modifier {
    val animate = LocalMotionEnabled.current
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (animate) MotionRole.hierarchy() else androidx.compose.animation.core.snap(),
        label = "arrive",
    )
    if (progress >= 1f) return this
    return graphicsLayer {
        transformOrigin = origin
        val scale = 0.94f + 0.06f * progress
        scaleX = scale
        scaleY = scale
        alpha = progress
    }
}

/**
 * The primary action, arriving and leaving.
 *
 * Scales from the corner it occupies rather than fading in place, so the button reads as
 * coming out of the page. Kept small and quick: this happens on every destination change, and
 * anything longer would be the first thing a person notices about the app and then the first
 * thing they tire of.
 */
@Composable
fun LedgerAction(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val animate = LocalMotionEnabled.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        label = "action",
        enter = if (animate) {
            scaleIn(MotionRole.local(), initialScale = 0.8f, transformOrigin = Move.action) +
                fadeIn(MotionRole.local())
        } else {
            fadeIn(tween(0))
        },
        exit = if (animate) {
            scaleOut(MotionRole.local(), targetScale = 0.8f, transformOrigin = Move.action) +
                fadeOut(MotionRole.local())
        } else {
            fadeOut(tween(0))
        },
        content = content,
    )
}

/** How far a peer or hierarchical surface travels, for callers that place their own offsets. */
fun travelFor(axis: MotionAxis): Dp = when (axis) {
    MotionAxis.PEER -> MotionDistance.peer
    MotionAxis.HIERARCHY, MotionAxis.CONTAINER -> MotionDistance.hierarchy
    MotionAxis.NONE, MotionAxis.LABEL -> MotionDistance.local
}
