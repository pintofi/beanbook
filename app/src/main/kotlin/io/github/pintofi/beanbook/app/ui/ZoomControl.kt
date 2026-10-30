package io.github.pintofi.beanbook.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The arithmetic of the scanner's zoom dial.
 *
 * A zoom is a ratio, and 1x to 2x is the same step as 2x to 4x, so the dial changes it by a
 * factor and not by an amount: the same push doubles the zoom wherever it starts.
 */
internal object ZoomScale {
    /** The size of one step of the readout: the zoom is said to a tenth, and each tenth ticks. */
    private const val STEP = 0.1f

    /** How far from where the finger came down it has to go before the zoom starts to move. */
    const val DEAD_ZONE_DP = 8f

    /** How far past the dead zone, in dp, makes the zoom double every second. */
    const val DP_PER_DOUBLING_PER_SECOND = 30f

    /** The fastest the zoom changes, in doublings a second, however far the finger goes. */
    const val MAX_DOUBLINGS_PER_SECOND = 2.5f

    /** Which step of the readout [ratio] is on. */
    fun step(ratio: Float): Int = floor(ratio / STEP + EPSILON).toInt()

    /** How many steps lie between two zooms, whichever is larger. */
    fun stepsBetween(from: Float, to: Float): Int = abs(step(to) - step(from))

    /**
     * How fast the zoom changes while the dial is pulled [offsetDp] to the side of the middle,
     * in doublings a second. Right (a positive offset) zooms in and left zooms out; the further it
     * is pulled, the faster; inside the dead zone nothing moves.
     */
    fun doublingsPerSecond(offsetDp: Float): Float {
        val beyond = abs(offsetDp) - DEAD_ZONE_DP
        if (beyond <= 0f) return 0f
        val rate = beyond / DP_PER_DOUBLING_PER_SECOND
        return (sign(offsetDp) * rate).coerceIn(-MAX_DOUBLINGS_PER_SECOND, MAX_DOUBLINGS_PER_SECOND)
    }

    /** The zoom after [seconds] at [doublingsPerSecond], held inside the camera's range. */
    fun advanced(ratio: Float, doublingsPerSecond: Float, seconds: Float, min: Float, max: Float): Float =
        (ratio * 2f.pow(doublingsPerSecond * seconds)).coerceIn(min, max)

    /** `2x` on a whole number, `2.4x` otherwise, as the readout shows it. */
    fun label(ratio: Float): String {
        val whole = ratio.roundToInt()
        return if (abs(ratio - whole) < 0.05f) "${whole}×" else "%.1f×".format(ratio)
    }

    private const val EPSILON = 1e-3f
}

/** A small, soft tick, as a dial would give: the subtlest the phone offers. */
internal fun View.zoomTick() {
    val tick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(tick)
}

/**
 * The scanner's zoom, as a slider that rests in the middle.
 *
 * The zoom is said in the middle, between two guides: small lines, a longer one and a minus on
 * the left, the same and a plus on the right. Press it and slide the number to the right and the
 * zoom keeps going up, faster the further over; slide it to the left and it keeps coming down. The
 * number follows the finger. Let go and it springs back to the middle, quickly from far away and
 * gently from near, and the zoom stops where it is. Each step of the readout gives a small tick.
 */
@Composable
internal fun ZoomControl(
    ratio: Float,
    min: Float,
    max: Float,
    onRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A camera that cannot zoom has nothing to offer here.
    if (max < min * MIN_USEFUL_RANGE) return
    val accent = Color(0xFF80D5D4)
    val view = LocalView.current
    val current by rememberUpdatedState(ratio)
    val report by rememberUpdatedState(onRatio)
    var pressed by remember { mutableStateOf(false) }
    // How far the number is from the middle, in dp: under the finger while it is down, and the
    // spring's position while it is coming back.
    var offsetDp by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var springBack by remember { mutableStateOf<Job?>(null) }
    val grow by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "zoom-pressed")

    // While the finger is down the zoom moves every frame at the speed the offset asks for.
    LaunchedEffect(pressed, min, max) {
        if (!pressed) return@LaunchedEffect
        var last = 0L
        var lastTick = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else (now - last) / NANOS_PER_SECOND
                last = now
                val rate = ZoomScale.doublingsPerSecond(offsetDp)
                if (rate != 0f && seconds > 0f) {
                    val next = ZoomScale.advanced(current, rate, seconds, min, max)
                    // A tick for each step, and no more often than the hand can tell them apart.
                    if (ZoomScale.stepsBetween(current, next) > 0 && now - lastTick >= MIN_TICK_GAP_NANOS) {
                        view.zoomTick()
                        lastTick = now
                    }
                    report(next)
                }
            }
        }
    }

    val guideColor = if (pressed) accent else Color.White.copy(alpha = 0.6f)
    Box(
        modifier = modifier
            .scale(grow)
            .width(TRACK_WIDTH_DP.dp)
            .height(44.dp)
            .background(Color(0xFF1C1C1A), RoundedCornerShape(22.dp))
            .border(BorderStroke(1.dp, guideColor), RoundedCornerShape(22.dp))
            .drawBehind { drawGuides(guideColor) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    down.consume()
                    springBack?.cancel()
                    pressed = true
                    val start = offsetDp
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val point = event.changes.firstOrNull { it.id == down.id } ?: break
                        val moved = start + (point.position.x - down.position.x) / density
                        offsetDp = moved.coerceIn(-MAX_TRAVEL_DP, MAX_TRAVEL_DP)
                        point.consume()
                    } while (point.pressed)
                    pressed = false
                    // Let go: the number springs back to the middle, and the zoom stays where it is.
                    springBack = scope.launch {
                        animate(offsetDp, 0f, animationSpec = spring(SPRING_DAMPING, SPRING_STIFFNESS)) { value, _ ->
                            offsetDp = value
                        }
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Zoom"
                stateDescription = ZoomScale.label(ratio)
                progressBarRangeInfo = ProgressBarRangeInfo(ratio, min..max)
                setProgress { target -> onRatio(target.coerceIn(min, max)); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ZoomScale.label(ratio),
            color = accent,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .offset { IntOffset((offsetDp * density).roundToInt(), 0) }
                // The guides pass behind the number rather than through it.
                .background(Color(0xFF1C1C1A))
                .padding(horizontal = 8.dp),
        )
    }
}

/**
 * The guides either side of the number: small lines, then a longer line, then the sign, with the
 * minus on the left and the plus on the right.
 */
private fun DrawScope.drawGuides(color: Color) {
    val mid = center.y
    val x0 = center.x
    val stroke = 1.5.dp.toPx()
    for (side in intArrayOf(-1, 1)) {
        for (i in 0 until SMALL_LINES) {
            val x = x0 + side * (FIRST_LINE_DP + i * LINE_GAP_DP).dp.toPx()
            val small = color.copy(alpha = color.alpha * 0.7f)
            drawLine(small, Offset(x, mid - 4.dp.toPx()), Offset(x, mid + 4.dp.toPx()), stroke)
        }
        val big = x0 + side * BIG_LINE_DP.dp.toPx()
        drawLine(color, Offset(big, mid - 9.dp.toPx()), Offset(big, mid + 9.dp.toPx()), stroke)
        val sign = x0 + side * SIGN_DP.dp.toPx()
        val arm = 5.dp.toPx()
        drawLine(color, Offset(sign - arm, mid), Offset(sign + arm, mid), stroke)
        if (side > 0) drawLine(color, Offset(sign, mid - arm), Offset(sign, mid + arm), stroke)
    }
}

/** A camera whose largest zoom is not at least this much more than its smallest has no zoom to offer. */
private const val MIN_USEFUL_RANGE = 1.1f

/** The width of the track the number slides along. */
private const val TRACK_WIDTH_DP = 248

/** How far the middle of the number can be pulled from the middle of the track, in dp. */
private const val MAX_TRAVEL_DP = 80f

/** The small lines either side: how many, where the first is and how far apart, in dp. */
private const val SMALL_LINES = 4
private const val FIRST_LINE_DP = 46
private const val LINE_GAP_DP = 11

/** Where the longer line and the sign sit, in dp from the middle. */
private const val BIG_LINE_DP = 94
private const val SIGN_DP = 111

/** The spring that brings the number back: a little give, and the further it was pulled, the quicker it goes. */
private const val SPRING_DAMPING = 0.55f
private const val SPRING_STIFFNESS = 300f

/** How much the dial swells while a finger is on it. */
private const val PRESSED_SCALE = 1.08f

private const val NANOS_PER_SECOND = 1_000_000_000f

/** Ticks closer together than this blur into a buzz. */
private const val MIN_TICK_GAP_NANOS = 40_000_000L
