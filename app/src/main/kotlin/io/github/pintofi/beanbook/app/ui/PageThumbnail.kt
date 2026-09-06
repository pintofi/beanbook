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
import androidx.compose.runtime.remember
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
) {
    val bitmap = remember(file.path, file.length()) {
        // Upright, and sampled down to something a 56dp box can use: a camera writes its
        // orientation into EXIF and leaves the pixels sideways (B55).
        runCatching {
            io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = 256)
        }.getOrNull()
    }

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
