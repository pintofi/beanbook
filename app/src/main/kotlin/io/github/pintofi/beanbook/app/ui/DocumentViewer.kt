package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A document, read inside the app (B57), over the whole screen (B79).
 *
 * Every route to a receipt used to leave Beanbook: "Share" handed the file to whatever the
 * device would open it with. A PDF is rendered here by the platform's own `PdfRenderer` -- no
 * library, no network, nothing that leaves the device -- and an image is simply drawn.
 *
 * **What changed in B79, and why.** It was a dialog the size of a postcard that rendered the
 * first eight pages up front, and any failure -- a file the app had looked for in the wrong
 * place, a PDF Android could not open -- became the same sentence, "could not be shown", with
 * the reason thrown away. A receipt is read to check a figure on it, which wants the whole
 * screen and a pinch to zoom. Pages are rendered as they scroll into view, so a forty-page
 * statement costs what is on screen, and a failure says what failed.
 */
@Composable
fun DocumentViewer(
    file: File,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { contentDescription = "Close ${file.name}" },
                    ) { Icon(Icons.Filled.Close, contentDescription = null) }
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.semantics { contentDescription = "Share ${file.name}" },
                    ) { Icon(Icons.Filled.Share, contentDescription = null) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DocumentPages(file)
                }
            }
        }
    }
}

/** What opening a file came to: its pages, or the reason there are none. */
private sealed interface Opened {
    data object Opening : Opened
    data class Pdf(val source: PdfSource) : Opened
    data class Picture(val bitmap: ImageBitmap) : Opened
    data class Failed(val reason: String) : Opened
}

@Composable
private fun DocumentPages(file: File) {
    val opened by produceState<Opened>(Opened.Opening, file.path, file.length()) {
        value = withContext(Dispatchers.IO) { open(file) }
    }
    val current = opened
    DisposableEffect(current) {
        onDispose { (current as? Opened.Pdf)?.source?.close() }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = Modifier
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                // Double-tap is the way back to the whole page after a pinch.
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = DOUBLE_TAP_ZOOM
            })
        }
        .pointerInput(Unit) {
            // Two fingers only. One finger belongs to the list, which is how a person scrolls
            // to the next page; a transform detector would take that drag for a pan.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    // The initial pass, so a pinch is taken before the list reads it as a scroll.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                        offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }

    when (current) {
        Opened.Opening -> Text("Opening…", modifier = Modifier.padding(24.dp))
        is Opened.Failed -> Text(
            "This file could not be shown here. ${current.reason} Share it to open it in another app.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
        is Opened.Picture -> Image(
            bitmap = current.bitmap,
            contentDescription = "The document ${file.name}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().then(zoom),
        )
        is Opened.Pdf -> {
            val list = rememberLazyListState()
            val page by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().then(zoom),
                ) {
                    items(current.source.pageCount) { index ->
                        PdfPage(current.source, index)
                    }
                }
                Text(
                    "Page $page of ${current.source.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfPage(source: PdfSource, index: Int) {
    val bitmap by produceState<ImageBitmap?>(null, source, index) {
        value = source.render(index)
    }
    val shown = bitmap
    val ratio = source.aspect(index)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .aspectRatio(ratio)
            .background(androidx.compose.ui.graphics.Color.White)
            .semantics { contentDescription = "Page ${index + 1} of ${source.pageCount}" },
    ) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * One open PDF, rendered a page at a time.
 *
 * `PdfRenderer` allows one open page at a time and is not thread-safe, so every use goes
 * through [lock]. It holds the file open while the viewer is on screen and is closed when the
 * viewer leaves.
 */
private class PdfSource(private val descriptor: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    private val ratios = (0 until renderer.pageCount).map { index ->
        renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }
    private var closed = false

    val pageCount: Int get() = ratios.size

    fun aspect(index: Int): Float = ratios[index].coerceIn(MIN_RATIO, MAX_RATIO)

    suspend fun render(index: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val height = (PAGE_WIDTH.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(PAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
                // White first: a PDF page is transparent where nothing is drawn, and black
                // text on a transparent bitmap is black text on a dark theme.
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    fun close() {
        // Taken without suspending: a render in progress finishes first, and the next one
        // sees `closed` and draws nothing.
        while (!lock.tryLock()) Thread.yield()
        try {
            if (!closed) {
                closed = true
                renderer.close()
                descriptor.close()
            }
        } finally {
            lock.unlock()
        }
    }
}

private fun open(file: File): Opened {
    if (!file.isFile) {
        return Opened.Failed("There is no file at ${file.name} in this project.")
    }
    if (!file.name.endsWith(".pdf", ignoreCase = true)) {
        val bitmap = io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = PAGE_WIDTH * 2)
            ?: return Opened.Failed("It is not a PDF, and Android could not read it as a picture.")
        return Opened.Picture(bitmap.asImageBitmap())
    }
    return try {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val source = PdfSource(descriptor)
            if (source.pageCount == 0) {
                source.close()
                Opened.Failed("The PDF has no pages.")
            } else {
                Opened.Pdf(source)
            }
        } catch (error: java.io.IOException) {
            descriptor.close()
            throw error
        } catch (error: SecurityException) {
            descriptor.close()
            throw error
        }
    } catch (error: SecurityException) {
        Log.w(TAG, "PDF is protected", error)
        Opened.Failed("The PDF is password-protected.")
    } catch (error: java.io.IOException) {
        Log.w(TAG, "PDF could not be opened", error)
        Opened.Failed("Android could not read it as a PDF (${error.message ?: "damaged file"}).")
    }
}

private const val TAG = "DocumentViewer"

/** Wide enough to read small print when zoomed, small enough to keep a few pages in memory. */
private const val PAGE_WIDTH = 1440

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** A page shape outside this range is a malformed media box, not a very long receipt. */
private const val MIN_RATIO = 0.05f
private const val MAX_RATIO = 20f
