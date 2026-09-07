package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A document, read inside the app (B57).
 *
 * Every route to a receipt used to leave Beanbook: "Share" handed the file to whatever the
 * device would open it with, so checking what a transaction was for meant a trip through a
 * chooser and back. A PDF is rendered here by the platform's own `PdfRenderer` — no library,
 * no network, nothing that leaves the device — and an image is simply drawn.
 *
 * Rendering is capped at [MAX_PAGES]. A long statement is a thing to open in a real reader;
 * this exists to answer "is this the right receipt", and rendering forty pages into memory to
 * answer it would be the wrong trade.
 */
@Composable
fun DocumentViewer(
    file: File,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pages by produceState<List<ImageBitmap>?>(initialValue = null, file.path, file.length()) {
        value = withContext(Dispatchers.Default) { render(file) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, style = MaterialTheme.typography.titleSmall) },
        text = {
            val rendered = pages
            when {
                rendered == null -> Text("Opening…")
                rendered.isEmpty() -> Text(
                    "This file could not be shown here. Share it to open it in another app.",
                )
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rendered) { page ->
                        Image(
                            bitmap = page,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Close ${file.name}" },
            ) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                onClick = onShare,
                modifier = Modifier.semantics { contentDescription = "Share ${file.name}" },
            ) { Text("Share") }
        },
    )
}

/** As many pages as are worth holding in memory to answer "is this the right document". */
private const val MAX_PAGES = 8

/** Wide enough to read on a phone, small enough that eight of them are not a problem. */
private const val PAGE_WIDTH = 1000

private fun render(file: File): List<ImageBitmap> {
    if (!file.isFile) return emptyList()
    if (!file.name.endsWith(".pdf", ignoreCase = true)) {
        val bitmap = io.github.pintofi.beanbook.app.documents.Photos
            .decodeUpright(file, maxEdge = PAGE_WIDTH)
        return listOfNotNull(bitmap?.asImageBitmap())
    }
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until minOf(renderer.pageCount, MAX_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val height = (PAGE_WIDTH.toFloat() / page.width * page.height).toInt()
                            .coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
                        // White first: a PDF page is transparent where nothing is drawn, and
                        // black text on a transparent bitmap is black text on a dark theme.
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }
            }
        }
    }.getOrDefault(emptyList())
}
