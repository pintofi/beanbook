package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The scale of a camera's zoom, which is how the camera app draws it.
 *
 * A zoom is a ratio, and 1x to 2x is the same step as 2x to 4x, so the positions are on a log
 * scale: equal distances are equal changes in how much the picture grows.
 */
internal object ZoomScale {
    /** The stops offered when the camera reaches them. 0.5x is only there on a phone with a wide lens. */
    private val STOPS = listOf(0.5f, 1f, 2f, 5f, 10f)

    /** Within this share of a stop the zoom counts as being on it. */
    private const val ON_STOP = 0.03f

    fun stops(min: Float, max: Float): List<Float> =
        STOPS.filter { it >= min * (1f - ON_STOP) && it <= max * (1f + ON_STOP) }

    /** Where [ratio] sits along the strip, 0 at the camera's smallest zoom and 1 at its largest. */
    fun fractionOf(ratio: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        return (ln(ratio / min) / ln(max / min)).coerceIn(0f, 1f)
    }

    /** The zoom at [fraction] of the way along the strip. */
    fun ratioAt(fraction: Float, min: Float, max: Float): Float =
        (min * (max / min).pow(fraction.coerceIn(0f, 1f))).coerceIn(min, max)

    /** The stop [ratio] is on, or null when it is between two. */
    fun stopAt(ratio: Float, min: Float, max: Float): Float? =
        stops(min, max).firstOrNull { abs(ratio - it) <= it * ON_STOP }

    /** `2x` on a whole number, `2.4x` otherwise, as the readout shows it. */
    fun label(ratio: Float): String {
        val whole = ratio.roundToInt()
        return if (abs(ratio - whole) < 0.05f) "${whole}×" else "%.1f×".format(ratio)
    }
}

/**
 * The scanner's zoom: a few round stops to tap, a track to drag along, and the zoom itself above.
 *
 * Tapping a stop moves the zoom there. Pressing anywhere on the strip, a stop included, and sliding
 * sideways zooms in and out, and the readout above follows. The stop the zoom is on is filled
 * with the accent colour; between two stops a small thumb shows where it is.
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
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val stops = ZoomScale.stops(min, max)
    val on = ZoomScale.stopAt(ratio, min, max)
    val circle = 40.dp
    val circlePx = with(density) { circle.toPx() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = ZoomScale.label(ratio),
            color = accent,
            style = MaterialTheme.typography.titleMedium,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .height(circle)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(min, max, stops) {
                    detectTapGestures { tap ->
                        // The stop under the finger; a tap elsewhere on the strip does nothing.
                        val width = size.width.toFloat()
                        val centre = { stop: Float -> xOf(stop, min, max, width, circlePx) + circlePx / 2 }
                        stops.minByOrNull { abs(centre(it) - tap.x) }
                            ?.takeIf { abs(centre(it) - tap.x) <= circlePx }
                            ?.let(onRatio)
                    }
                }
                .pointerInput(min, max) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val usable = (size.width - circlePx).coerceAtLeast(1f)
                        onRatio(ZoomScale.ratioAt((change.position.x - circlePx / 2) / usable, min, max))
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "Zoom"
                    stateDescription = ZoomScale.label(ratio)
                    progressBarRangeInfo = ProgressBarRangeInfo(ratio, min..max)
                    setProgress { target -> onRatio(target.coerceIn(min, max)); true }
                },
        ) {
            // The track behind the stops.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = circle / 2)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.4f)),
            )
            stops.forEach { stop ->
                val selected = on == stop
                Box(
                    modifier = Modifier
                        .offset(x = with(density) { xOf(stop, min, max, widthPx.toFloat(), circlePx).toDp() })
                        .size(circle)
                        .clip(CircleShape)
                        .background(if (selected) accent else Color(0xFF1C1C1A))
                        .border(BorderStroke(1.dp, if (selected) accent else Color.White.copy(0.6f)), CircleShape)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Zoom to ${ZoomScale.label(stop)}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ZoomScale.label(stop),
                        color = if (selected) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (on == null) {
                // Between two stops: a small thumb where the zoom is.
                val thumb = 16.dp
                val thumbPx = with(density) { thumb.toPx() }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(
                            x = with(density) {
                                (xOf(ratio, min, max, widthPx.toFloat(), circlePx) + (circlePx - thumbPx) / 2).toDp()
                            },
                        )
                        .size(thumb)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}

/** The left edge, in pixels, of a circle for [ratio] on a strip [width] wide. */
private fun xOf(ratio: Float, min: Float, max: Float, width: Float, circle: Float): Float =
    ZoomScale.fractionOf(ratio, min, max) * (width - circle).coerceAtLeast(0f)

/** A camera whose largest zoom is not at least this much more than its smallest has no zoom to offer. */
private const val MIN_USEFUL_RANGE = 1.1f

