package io.github.pintofi.beanbook.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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

/**
 * The arithmetic of the scanner's zoom dial.
 *
 * A zoom is a ratio, and 1x to 2x is the same step as 2x to 4x, so a slide changes it by a factor
 * and not by an amount: the same distance doubles the zoom wherever it starts.
 */
internal object ZoomScale {
    /** The size of one step of the readout: the zoom is said to a tenth, and each tenth ticks. */
    private const val STEP = 0.1f

    /** How far a finger travels, in dp, to double the zoom. */
    const val DP_PER_DOUBLING = 160f

    /** Which step of the readout [ratio] is on. */
    fun step(ratio: Float): Int = floor(ratio / STEP + EPSILON).toInt()

    /** How many steps lie between two zooms, whichever is larger. */
    fun stepsBetween(from: Float, to: Float): Int = abs(step(to) - step(from))

    /**
     * The zoom after the finger has moved [dx] sideways from where the slide began at [start].
     * Sliding left (a negative [dx]) zooms in and sliding right zooms out.
     */
    fun slid(start: Float, dx: Float, dpPerDoubling: Float, min: Float, max: Float): Float =
        (start * 2f.pow(-dx / dpPerDoubling)).coerceIn(min, max)

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
 * Press it and slide left to zoom in, right to zoom out; the number follows the finger, and each
 * step of it gives a small tick under the fingertip. There are no fixed stops to tap: a dial that
 * can be set anywhere has no use for them.
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
    var pressed by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "zoom-pressed")

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
            .pointerInput(min, max) {
                var start = 1f
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = current
                        travelled = 0f
                        pressed = true
                    },
                    onDragEnd = { pressed = false },
                    onDragCancel = { pressed = false },
                ) { change, drag ->
                    change.consume()
                    travelled += drag
                    val next = ZoomScale.slid(start, travelled / density, ZoomScale.DP_PER_DOUBLING, min, max)
                    if (ZoomScale.stepsBetween(current, next) > 0) view.zoomTick()
                    onRatio(next)
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
