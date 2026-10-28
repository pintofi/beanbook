package io.github.pintofi.beanbook.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
    const val DP_PER_DOUBLING_PER_SECOND = 90f

    /** The fastest the zoom changes, in doublings a second, however far the finger goes. */
    const val MAX_DOUBLINGS_PER_SECOND = 2.5f

    /** Which step of the readout [ratio] is on. */
    fun step(ratio: Float): Int = floor(ratio / STEP + EPSILON).toInt()

    /** How many steps lie between two zooms, whichever is larger. */
    fun stepsBetween(from: Float, to: Float): Int = abs(step(to) - step(from))

    /**
     * How fast the zoom changes while the finger is [offsetDp] to the side of where it came down,
     * in doublings a second. Left (a negative offset) zooms in and right zooms out; the further it
     * is held, the faster; inside the dead zone nothing moves.
     */
    fun doublingsPerSecond(offsetDp: Float): Float {
        val beyond = abs(offsetDp) - DEAD_ZONE_DP
        if (beyond <= 0f) return 0f
        val rate = beyond / DP_PER_DOUBLING_PER_SECOND
        return (-sign(offsetDp) * rate).coerceIn(-MAX_DOUBLINGS_PER_SECOND, MAX_DOUBLINGS_PER_SECOND)
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
 * The scanner's zoom: the zoom said in the middle, and nothing else.
 *
 * Press it and hold the finger to the left of where it came down, and the zoom keeps going up,
 * faster the further over; hold it to the right and it keeps coming down; bring the finger back
 * to where it started, or lift it, and it stops. The number changes in place, and each step of it
 * gives a small tick under the fingertip.
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
    // How far to the side of where it came down the finger is, in dp, while it is down.
    var offsetDp by remember { mutableFloatStateOf(0f) }
    val grow by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "zoom-pressed")

    // While the finger is down the zoom moves every frame at the speed the offset asks for.
    LaunchedEffect(pressed, min, max) {
        if (!pressed) return@LaunchedEffect
        var last = 0L
        var lastTick = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
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

    Box(
        modifier = modifier
            .scale(grow)
            .widthIn(min = 96.dp)
            .height(44.dp)
            .background(Color(0xFF1C1C1A), RoundedCornerShape(22.dp))
            .border(
                BorderStroke(1.dp, if (pressed) accent else Color.White.copy(alpha = 0.6f)),
                RoundedCornerShape(22.dp),
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    down.consume()
                    pressed = true
                    offsetDp = 0f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val point = event.changes.firstOrNull { it.id == down.id } ?: break
                        offsetDp = (point.position.x - down.position.x) / density
                        point.consume()
                    } while (point.pressed)
                    pressed = false
                    offsetDp = 0f
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Zoom"
                stateDescription = ZoomScale.label(ratio)
                progressBarRangeInfo = ProgressBarRangeInfo(ratio, min..max)
                setProgress { target -> onRatio(target.coerceIn(min, max)); true }
            }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ZoomScale.label(ratio),
            color = accent,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** A camera whose largest zoom is not at least this much more than its smallest has no zoom to offer. */
private const val MIN_USEFUL_RANGE = 1.1f

/** How much the dial swells while a finger is on it. */
private const val PRESSED_SCALE = 1.08f

private const val NANOS_PER_SECOND = 1_000_000_000f

/** Ticks closer together than this blur into a buzz. */
private const val MIN_TICK_GAP_NANOS = 40_000_000L
