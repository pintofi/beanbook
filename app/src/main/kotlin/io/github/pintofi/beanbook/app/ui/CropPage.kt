package io.github.pintofi.beanbook.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.CropCorner
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import java.io.File

/**
 * Drag the four corners of a page to straighten it (B31).
 *
 * A perspective crop rather than a rectangular one, because a receipt photographed on a table
 * is a general quadrilateral: its near edge is longer than its far edge and its sides lean. A
 * box cut out of that is still keystoned. Four corners mapped onto a rectangle is what makes
 * it read like a scan.
 *
 * Corners are dragged by hand rather than detected. Automatic edge detection means ML Kit,
 * which is proprietary and needs this app's own GPL linking exception before it could be
 * used at all (CLAUDE.md), or OpenCV, which is a large native dependency for one feature.
 * Dragging four handles costs the user a few seconds and the project nothing.
 */
@Composable
fun CropPageDialog(
    file: File,
    initial: CropQuad?,
    onCropped: (CropQuad?) -> Unit,
    onDismiss: () -> Unit,
) {
    val image: ImageBitmap? = remember(file.path) {
        runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    }
    if (image == null) {
        // Said rather than shown as an empty frame: a page that cannot be decoded is a
        // capture that failed, and silently offering an empty canvas would hide that.
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Could not open that page") },
            text = { Text("The capture could not be read, so there is nothing to crop.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    val imageWidth = image.width.toFloat()
    val imageHeight = image.height.toFloat()
    var quad by remember(file.path) {
        mutableStateOf(initial ?: CropQuad.whole(imageWidth, imageHeight))
    }
    var dragging by remember(file.path) { mutableStateOf<CropCorner?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop this page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Drag each corner onto a corner of the page. The photo is kept as it " +
                        "was taken, so you can come back and change this.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(imageWidth / imageHeight)
                        .padding(4.dp)
                        .semantics {
                            contentDescription =
                                "The page, with a draggable handle at each of its four corners"
                        }
                        .pointerInput(file.path) {
                            // Canvas pixels are not image pixels: the preview is scaled to
                            // the dialog's width, so every position has to be mapped back or
                            // the corner lands somewhere else entirely.
                            fun toImage(position: Offset) = CropPoint(
                                position.x * (imageWidth / size.width),
                                position.y * (imageHeight / size.height),
                            )
                            detectDragGestures(
                                // The corner is chosen once, when the finger goes down, and
                                // held for the whole gesture. Choosing it on every move --
                                // which is what this did first -- lets a quick drag pass
                                // closer to a different corner and silently start dragging
                                // that one instead.
                                onDragStart = { position ->
                                    dragging = nearestCorner(quad, toImage(position))
                                },
                                onDragEnd = { dragging = null },
                                onDragCancel = { dragging = null },
                            ) { change, _ ->
                                dragging?.let { corner ->
                                    quad = quad.move(
                                        corner,
                                        toImage(change.position),
                                        imageWidth,
                                        imageHeight,
                                    )
                                }
                                change.consume()
                            }
                        },
                ) {
                    val scaleX = size.width / imageWidth
                    val scaleY = size.height / imageHeight
                    fun on(point: CropPoint) = Offset(point.x * scaleX, point.y * scaleY)

                    drawImage(
                        image,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )

                    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                    corners.forEachIndexed { index, corner ->
                        val next = corners[(index + 1) % corners.size]
                        drawLine(Color.White, on(corner), on(next), strokeWidth = 3f)
                    }
                    corners.forEach { corner ->
                        // Two rings, light on dark, so a handle stays visible against both a
                        // white receipt and a dark table.
                        drawCircle(Color.Black, radius = 15f, center = on(corner), style = Stroke(width = 5f))
                        drawCircle(Color.White, radius = 15f, center = on(corner), style = Stroke(width = 3f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCropped(quad) }) { Text("Crop") } },
        dismissButton = {
            Column {
                TextButton(onClick = { onCropped(null) }) { Text("Use the whole photo") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** The corner a drag is acting on: whichever is closest to the finger. */
internal fun nearestCorner(quad: CropQuad, to: CropPoint): CropCorner {
    val candidates = listOf(
        CropCorner.TOP_LEFT to quad.topLeft,
        CropCorner.TOP_RIGHT to quad.topRight,
        CropCorner.BOTTOM_RIGHT to quad.bottomRight,
        CropCorner.BOTTOM_LEFT to quad.bottomLeft,
    )
    return candidates.minBy { (_, point) ->
        val dx = point.x - to.x
        val dy = point.y - to.y
        dx * dx + dy * dy
    }.first
}
