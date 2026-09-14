package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * Drag the four corners of a page to straighten it (B31), over the whole screen (B73).
 *
 * A perspective crop rather than a rectangular one, because a receipt photographed on a table
 * is a general quadrilateral: its near edge is longer than its far edge and its sides lean. A
 * box cut out of that is still keystoned. Four corners mapped onto a rectangle is what makes
 * it read like a scan.
 *
 * **The screen is the page.** This was an `AlertDialog` with a paragraph of instructions above
 * a thumbnail: the picture a person had to place four corners on precisely was a few hundred
 * pixels tall, inside a card, on a screen that had just been showing the same page full size.
 * It is now the same black, full-bleed surface as the page editor it opens from, with the
 * same top bar, so pressing Crop changes what the controls do rather than where the page is.
 *
 * Corners arrive detected by the scanner (OpenCV, `:scanner`) where it found the page, and are
 * dragged by hand from there.
 */
@Composable
fun CropPageScreen(
    file: File,
    initial: CropQuad?,
    onCropped: (CropQuad?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back leaves the crop, not the editor behind it: the gesture that means "not this" has
    // to mean the nearest "this" first.
    androidx.activity.compose.BackHandler(enabled = true) { onDismiss() }

    // Upright: the corners a person drags have to sit on the page as they see it (B55).
    //
    // Sampled and off the main thread (B83). This decoded the photograph at full size inside
    // composition: a 12 MP camera page is a 48 MB bitmap, decoded on the UI thread, which is a
    // freeze on opening the crop and a real risk of running out of memory. The corners stay in
    // the full photograph's pixels, which is what the detector and the assembler use, so they
    // are scaled by [CropSource.scale] on the way in and out.
    val loaded by androidx.compose.runtime.produceState<CropSource?>(null, file.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cropSourceOf(file) }
    }
    val source = loaded
    if (source == null) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White)
        }
        return
    }
    val image: ImageBitmap? = source.image
    // Held here, where both the canvas that moves it and the tick that applies it can see it.
    // In the shown bitmap's pixels.
    var quad by remember(file.path, image) {
        mutableStateOf(
            initial?.scaled(1f / source.scale)
                ?: image?.let { CropQuad.whole(it.width.toFloat(), it.height.toFloat()) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Leave the crop unchanged" },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(
                "Crop this page",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (image != null) {
                IconButton(
                    onClick = { onCropped(quad?.scaled(source.scale)) },
                    modifier = Modifier.semantics { contentDescription = "Use this crop" },
                ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
            }
        }

        if (image == null) {
            // Said rather than shown as an empty frame: a page that cannot be decoded is a
            // capture that failed, and silently offering an empty canvas would hide that.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "The capture could not be read, so there is nothing to crop.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }

        CropCanvas(
            file = file,
            image = image,
            quad = quad ?: CropQuad.whole(image.width.toFloat(), image.height.toFloat()),
            onQuadChange = { quad = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // One action, not a column of three. Keeping the whole photo is a real choice and
        // stays; confirming is the tick in the bar, and leaving is the arrow beside it.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            TextButton(
                onClick = { onCropped(null) },
                modifier = Modifier.semantics { contentDescription = "Use the whole photo" },
            ) { Text("Use the whole photo", color = Color.White) }
        }
    }
}

/** The page, filling what is left of the screen, with a handle at each corner. */
@Composable
private fun CropCanvas(
    file: File,
    image: ImageBitmap,
    quad: CropQuad,
    onQuadChange: (CropQuad) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageWidth = image.width.toFloat()
    val imageHeight = image.height.toFloat()
    var dragging by remember(file.path) { mutableStateOf<CropCorner?>(null) }
    // The gesture below is started once per page and outlives many recompositions, so it reads
    // the corners through this rather than capturing them: a captured `quad` was the one from
    // when the page opened, and moving a second corner put the first one back.
    val current by rememberUpdatedState(quad)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // Room for a handle sitting on the edge of the page: at full width the two
                // outer rings were cut in half by the screen, and a handle you cannot see
                // the whole of is one you cannot tell you have hold of.
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .aspectRatio(imageWidth / imageHeight)
                .semantics {
                    contentDescription =
                        "The page, with a draggable handle at each of its four corners"
                }
                .pointerInput(file.path) {
                    // Canvas pixels are not image pixels: the page is scaled to the screen,
                    // so every position has to be mapped back or the corner lands somewhere
                    // else entirely.
                    fun toImage(position: Offset) = CropPoint(
                        position.x * (imageWidth / size.width),
                        position.y * (imageHeight / size.height),
                    )
                    detectDragGestures(
                        // The corner is chosen once, when the finger goes down, and held for
                        // the whole gesture. Choosing it on every move -- which is what this
                        // did first -- lets a quick drag pass closer to a different corner
                        // and silently start dragging that one instead.
                        onDragStart = { position ->
                            dragging = nearestCorner(current, toImage(position))
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        dragging?.let { corner ->
                            onQuadChange(
                                current.move(corner, toImage(change.position), imageWidth, imageHeight),
                            )
                        }
                        change.consume()
                    }
                },
        ) {
            val scaleX = size.width / imageWidth
            val scaleY = size.height / imageHeight
            fun on(point: CropPoint) = Offset(point.x * scaleX, point.y * scaleY)

            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))

            val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
            corners.forEachIndexed { index, corner ->
                val next = corners[(index + 1) % corners.size]
                drawLine(Color.White, on(corner), on(next), strokeWidth = 3f)
            }
            corners.forEach { corner ->
                // Two rings, light on dark, so a handle stays visible against both a white
                // receipt and a dark table. Larger here than in the old dialog, because the
                // page is now the size of the screen and so is the distance a finger covers.
                drawCircle(Color.Black, radius = 26f, center = on(corner), style = Stroke(width = 8f))
                drawCircle(Color.White, radius = 26f, center = on(corner), style = Stroke(width = 4f))
            }
        }
    }
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

/**
 * The photograph as the crop editor shows it: sampled to fit a screen, and how many pixels of
 * the full, upright photograph each shown pixel stands for. Null [image] when it cannot be read.
 */
internal class CropSource(val image: ImageBitmap?, val scale: Float)

private fun cropSourceOf(file: File): CropSource {
    val shown = runCatching {
        io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = CROP_EDGE)
    }.getOrNull() ?: return CropSource(null, 1f)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
    val full = maxOf(bounds.outWidth, bounds.outHeight).toFloat()
    val scale = if (full > 0) full / maxOf(shown.width, shown.height) else 1f
    return CropSource(shown.asImageBitmap(), scale)
}

private fun CropQuad.scaled(factor: Float): CropQuad {
    fun CropPoint.times() = CropPoint(x * factor, y * factor)
    return CropQuad(topLeft.times(), topRight.times(), bottomRight.times(), bottomLeft.times())
}

/** Enough to place corners precisely on a phone or tablet screen. */
private const val CROP_EDGE = 2048

