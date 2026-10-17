package io.github.pintofi.beanbook.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * A captured page, shown as itself.
 *
 * Every list of captures in this app named its pages and drew none of them, so the only way
 * to tell a good photograph from a blurred one was to file the stack and open the finished
 * PDF. A thumbnail is the whole answer to that.
 *
 * Decoded at a fraction of full size. A page is a camera-resolution JPEG — several megabytes
 * of pixels for a 72dp square — and decoding it whole to draw it small is how a list of eight
 * captures runs out of memory. `inSampleSize` asks the decoder for a smaller image rather
 * than shrinking a large one after the fact.
 *
 * A file that cannot be decoded draws its label instead of throwing: a capture that failed
 * should look like a capture that failed, not take the screen down with it.
 */
@Composable
fun PageThumbnail(
    file: File,
    label: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    /**
     * The page as captured, so the thumbnail shows it cropped, turned and styled the way it
     * will be filed. Without it the thumbnail is the raw photograph.
     */
    page: io.github.pintofi.beanbook.core.domain.CapturedPage? = null,
) {
    // Upright, and sampled down to something a 56dp box can use: a camera writes its
    // orientation into EXIF and leaves the pixels sideways. Off the main thread:
    // this decoded a camera JPEG inside composition, once per thumbnail, which is a stutter
    // every time a batch of pages appears.
    val bitmap = if (page != null) rememberRenderedPage(page, file) else rememberDecodedPage(file)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("?", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * [file] decoded upright at thumbnail size on the IO dispatcher; null until it is ready, and
 * null for a file that cannot be decoded, which the caller draws as a failed capture.
 */
@Composable
internal fun rememberDecodedPage(file: File, maxEdge: Int = THUMBNAIL_EDGE): android.graphics.Bitmap? {
    val decoded by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, file.path, file.length()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = maxEdge)
            }.getOrNull()
        }
    }
    return decoded
}

/**
 * [page] as it will be filed -- the scanner's crop, the rotation and the style applied -- sampled
 * down for a thumbnail.
 *
 * Thumbnails decoded the photograph, so a page the scanner had cropped showed uncropped until
 * it was opened full size, which read as the crop having been lost. The same renderer the page
 * editor uses draws it here.
 */
@Composable
internal fun rememberRenderedPage(
    page: io.github.pintofi.beanbook.core.domain.CapturedPage,
    file: File,
    maxEdge: Int = THUMBNAIL_EDGE,
): android.graphics.Bitmap? {
    val render = LocalPageRenderer.current
    val rendered by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        null, page, file.path, file.length(),
    ) {
        value = runCatching { render(page, file) }.getOrNull()?.let { full ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val scale = maxEdge.toFloat() / maxOf(full.width, full.height)
                if (scale >= 1f) {
                    full
                } else {
                    android.graphics.Bitmap.createScaledBitmap(
                        full,
                        (full.width * scale).toInt().coerceAtLeast(1),
                        (full.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                }
            }
        }
    }
    return rendered
}

private const val THUMBNAIL_EDGE = 256

