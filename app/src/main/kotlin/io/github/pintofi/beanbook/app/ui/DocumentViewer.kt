package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Size
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

    when (current) {
        Opened.Opening -> StatusLine("Opening…", modifier = Modifier.padding(24.dp))
        is Opened.Failed -> Text(
            "This file could not be shown here. ${current.reason} Share it to open it in another app.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
        is Opened.Picture -> ZoomablePage(
            bitmap = current.bitmap,
            description = "The document ${file.name}",
        )
        is Opened.Pdf -> {
            // A page at a time, each with its own zoom (B84). Pinch, pan, fling and double-tap
            // come from Telephoto, which also hands a drag to the pager once a page is at its
            // edge -- the hand-written two-finger gesture this replaced could not fling or stop
            // at the page's bounds, and could only zoom the whole list at once.
            val pager = rememberPagerState { current.source.pageCount }
            Box(Modifier.fillMaxSize()) {
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                    PdfPage(current.source, index)
                }
                Text(
                    "Page ${pager.currentPage + 1} of ${current.source.pageCount}",
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Page ${index + 1} of ${source.pageCount}" },
        contentAlignment = Alignment.Center,
    ) {
        if (shown == null) {
            Box(
                Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .aspectRatio(source.aspect(index))
                    .background(androidx.compose.ui.graphics.Color.White),
            )
        } else {
            ZoomablePage(bitmap = shown, description = null)
        }
    }
}

/** One page, fitted to the screen, with pinch, pan, fling and double-tap zoom (Telephoto). */
@Composable
internal fun ZoomablePage(bitmap: ImageBitmap, description: String?, modifier: Modifier = Modifier) {
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    LaunchedEffect(bitmap) {
        zoom.setContentLocation(
            ZoomableContentLocation.scaledInsideAndCenterAligned(
                Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
            ),
        )
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Inside,
        modifier = modifier.fillMaxSize().zoomable(zoom),
    )
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

/** A page shape outside this range is a malformed media box, not a very long receipt. */
private const val MIN_RATIO = 0.05f
private const val MAX_RATIO = 20f
