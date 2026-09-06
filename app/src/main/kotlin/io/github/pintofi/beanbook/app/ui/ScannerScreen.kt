package io.github.pintofi.beanbook.app.ui

import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.pintofi.beanbook.core.domain.CapturedPage
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.scanner.ScanStyle
import io.github.pintofi.beanbook.scanner.Scanner
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The scanner, filling the screen.
 *
 * A viewfinder embedded in a form is a photograph tool; a scanner is a mode you enter, shoot
 * a stack of pages in, and leave. Everything here follows from that: the camera owns the
 * whole window, the app's own chrome is gone while it is open, and the controls sit over the
 * preview rather than beside it.
 *
 * Pages accumulate. The strip along the bottom is the batch, in order, with each page drawn
 * as itself — the previous screen listed captures by filename, which tells a person nothing
 * about whether the photograph came out.
 */
@Composable
fun ScannerScreen(
    pages: List<CapturedPage>,
    fileFor: (String) -> File,
    captureFile: () -> File,
    onCaptured: (File, CropQuad?, ScanStyle) -> Unit,
    onRemovePage: (Int) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var style by remember { mutableStateOf(ScanStyle.DOCUMENT) }
    var busy by remember { mutableStateOf(false) }
    var lastQuad by remember { mutableStateOf<CropQuad?>(null) }
    var shotSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scope = rememberCoroutineScope()

    // Back leaves the scanner, not the app (B55). The scanner is a mode drawn over
    // everything else, and without this the system's back gesture went to the activity --
    // which had nothing behind it, so a stray swipe closed Beanbook mid-batch.
    androidx.activity.compose.BackHandler(enabled = true) { onCancel() }

    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // 4:3, which is the sensor's own shape on almost every phone. Without it CameraX
            // picks a capture resolution independently of the preview's, so the photograph
            // was a tighter crop than the viewfinder had shown -- the "too zoomed in"
            // complaint, and worse than cosmetic: a receipt framed to the edges of the
            // preview came back with its edges cut off, and the page detector then had no
            // border to find.
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        androidx.camera.core.resolutionselector.AspectRatioStrategy
                            .RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                    )
                    .build(),
            )
            .build()
    }

    /** A page opened for a proper look, rather than removed by the tap that opened it. */
    var previewing by remember { mutableStateOf<Int?>(null) }

    // The three controls a scanner needs that a viewfinder does not, taken from what
    // OSS-DocumentScanner does (MIT; read, not copied — see B56): a torch for a receipt on a
    // dark table, zoom for a page you cannot get closer to, and a shutter that fires itself
    // once the page is squarely in frame.
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(0f) }
    var autoCapture by rememberSaveable { mutableStateOf(true) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var aimed by remember { mutableStateOf(false) }

    /**
     * Takes one page: shoot, detect, hand the crop up with the file.
     *
     * Hoisted out of the shutter because the automatic trigger fires the same thing. Two
     * copies of a camera capture is how one of them ends up not detecting the page.
     */
    fun shoot() {
        if (busy) return
        busy = true
        aimed = false
        val target = captureFile()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(target).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    // Detect here, once, and hand the crop up with the file. The overlay
                    // reads the same result, so a page is never analysed twice for one shot.
                    scope.launch {
                        val quad = withContext(Dispatchers.Default) {
                            io.github.pintofi.beanbook.app.documents.Photos
                                .decodeUpright(target)?.let { shot ->
                                    shotSize = androidx.compose.ui.unit.IntSize(shot.width, shot.height)
                                    Scanner.detect(shot)
                                }
                        }
                        lastQuad = quad
                        onCaptured(target, quad, style)
                        busy = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                }
            },
        )
    }

    // Fires the shutter when the page is squarely in frame, which is what a scanner does
    // that a camera does not (B56). Sampled from the preview a few times a second rather
    // than through a second analysis pipeline: the detector only needs a small bitmap, and a
    // full ImageAnalysis stream costs another camera surface and its battery for a hint.
    //
    // Two consecutive frames have to agree before it fires. One is a hand passing over the
    // table; two is a page held still.
    LaunchedEffect(autoCapture, previewView) {
        if (!autoCapture) return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        var steady = 0
        while (true) {
            kotlinx.coroutines.delay(AUTO_SAMPLE_MILLIS)
            if (busy) continue
            val frame = view.bitmap ?: continue
            val found = withContext(Dispatchers.Default) {
                val small = frame.scale()
                Scanner.detect(small) != null
            }
            aimed = found
            steady = if (found) steady + 1 else 0
            if (steady >= 2) {
                steady = 0
                shoot()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Pinch to zoom, for a page you cannot lean closer to.
            .pointerInput(camera) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    val control = camera?.cameraControl ?: return@detectTransformGestures
                    zoom = (zoom + (gestureZoom - 1f)).coerceIn(0f, 1f)
                    control.setLinearZoom(zoom)
                }
            }
            // The preview runs under the system bars; the controls must not.
            .systemBarsPadding(),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).also { preview ->
                    // COMPATIBLE for the same reason CameraPane uses it: a SurfaceView is
                    // composited outside the hierarchy and would draw over the controls.
                    preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    // FIT_CENTER, not the default FILL_CENTER: filling the window crops the
                    // frame to the screen's shape, so the viewfinder showed less than the
                    // camera saw and everything looked zoomed in (B55). Letterboxing shows
                    // the whole frame, which is the one the shutter actually captures.
                    preview.scaleType = PreviewView.ScaleType.FIT_CENTER
                    previewView = preview
                    val future = ProcessCameraProvider.getInstance(viewContext)
                    future.addListener({
                        val provider = future.get()
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            androidx.camera.core.Preview.Builder()
                                .setResolutionSelector(
                                    androidx.camera.core.resolutionselector.ResolutionSelector
                                        .Builder()
                                        .setAspectRatioStrategy(
                                            androidx.camera.core.resolutionselector
                                                .AspectRatioStrategy
                                                .RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .build(),
                                )
                                .build().also {
                                it.surfaceProvider = preview.surfaceProvider
                            },
                            capture,
                        )
                    }, ContextCompat.getMainExecutor(viewContext))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // The outline of the page the last shot found, over the preview. Drawn from the last
        // capture rather than from a live analysis stream: running detection on every frame
        // costs a second camera pipeline and battery for a hint, and the shot itself is what
        // the crop is taken from anyway.
        lastQuad?.let { quad ->
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / (shotSize.width.takeIf { it > 0 } ?: 1)
                val sy = size.height / (shotSize.height.takeIf { it > 0 } ?: 1)
                val path = Path().apply {
                    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                    val pts = corners.map { Offset(it.x * sx, it.y * sy) }
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Color(0xFF80D5D4), style = Stroke(width = 6f))
            }
        }

        // Scrims behind the controls. White icons over a live camera are legible until the
        // camera is pointed at something pale — the back arrow disappeared entirely against a
        // bright scene on the first device run. A gradient rather than a bar so the preview
        // is not boxed in, and it darkens only where controls actually sit.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))),
                ),
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                },
                modifier = Modifier.semantics {
                    contentDescription = if (torch) "Turn the light off" else "Turn the light on"
                },
            ) {
                Icon(
                    if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = { autoCapture = !autoCapture },
                modifier = Modifier.semantics {
                    contentDescription = if (autoCapture) {
                        "Stop shooting pages automatically"
                    } else {
                        "Shoot a page as soon as it is in frame"
                    }
                },
            ) {
                Icon(
                    if (autoCapture) Icons.Filled.MotionPhotosAuto else Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    tint = if (aimed) Color(0xFF80D5D4) else Color.White,
                )
            }
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .semantics { contentDescription = "Leave the scanner" },
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White) }

        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScanStyle.entries.forEach { option ->
                FilterChip(
                    selected = style == option,
                    onClick = { style = option },
                    label = { Text(option.label()) },
                    modifier = Modifier.semantics {
                        contentDescription = "Capture pages in ${option.label()}"
                    },
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pages.isNotEmpty()) {
                PageStrip(
                    pages = pages,
                    fileFor = fileFor,
                    onOpen = { previewing = it },
                    onRemovePage = onRemovePage,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (pages.isEmpty()) "" else "${pages.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = "${pages.size} pages captured"
                    },
                )
                ShutterButton(busy = busy) { shoot() }
                IconButton(
                    onClick = onDone,
                    enabled = pages.isNotEmpty(),
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = "Finish scanning with ${pages.size} pages"
                    },
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (pages.isEmpty()) Color.Gray else Color.White,
                    )
                }
            }
        }
    }

    previewing?.let { index ->
        pages.getOrNull(index)?.let { page ->
            PagePreview(
                file = fileFor(page.source),
                index = index,
                total = pages.size,
                onRemove = {
                    onRemovePage(index)
                    previewing = null
                },
                onDismiss = { previewing = null },
            )
        }
    }
}

/**
 * One captured page, big enough to judge (B55).
 *
 * Tapping a thumbnail used to delete it, with no preview anywhere: the one gesture a person
 * makes to check a photograph was the gesture that threw it away. Now a tap opens it, and
 * removing is a labelled button in front of the page it removes.
 */
@Composable
private fun PagePreview(
    file: File,
    index: Int,
    total: Int,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(file.path, file.length()) {
        runCatching {
            io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = 1600)
        }.getOrNull()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page ${index + 1} of $total") },
        text = {
            if (bitmap == null) {
                Text("This page could not be opened. Photograph it again.")
            } else {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${index + 1}, full size",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Keep it") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onRemove,
                modifier = Modifier.semantics {
                    contentDescription = "Remove page ${index + 1} from this document"
                },
            ) { Text("Remove page") }
        },
    )
}

/** A few times a second: often enough to feel immediate, rare enough to cost nothing. */
private const val AUTO_SAMPLE_MILLIS = 600L

/** The detector works on a small image; a preview frame at full size is wasted work. */
private fun android.graphics.Bitmap.scale(): android.graphics.Bitmap {
    val longest = maxOf(width, height)
    if (longest <= 720) return this
    val factor = 720f / longest
    return android.graphics.Bitmap.createScaledBitmap(
        this,
        (width * factor).toInt().coerceAtLeast(1),
        (height * factor).toInt().coerceAtLeast(1),
        true,
    )
}

private fun ScanStyle.label(): String = when (this) {
    ScanStyle.COLOUR -> "Colour"
    ScanStyle.DOCUMENT -> "Document"
    ScanStyle.GREYSCALE -> "Greyscale"
}

/** The batch so far, each page drawn as itself rather than named. */
@Composable
private fun PageStrip(
    pages: List<CapturedPage>,
    fileFor: (String) -> File,
    onOpen: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages.size) { index ->
            val page = pages[index]
            val bitmap = remember(page.source) {
                runCatching {
                    io.github.pintofi.beanbook.app.documents.Photos
                        .decodeUpright(fileFor(page.source), maxEdge = 256)
                }.getOrNull()
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.DarkGray)
                    .semantics { contentDescription = "Page ${index + 1}. Tap to see it." },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                IconButton(onClick = { onOpen(index) }, modifier = Modifier.fillMaxSize()) {}
                Text(
                    "${index + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
                // Removing stays one tap away, but it is its own target with its own label:
                // the thumbnail is for looking at the page, not for losing it (B55).
                IconButton(
                    onClick = { onRemovePage(index) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .semantics { contentDescription = "Remove page ${index + 1}" },
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White)
            .semantics { contentDescription = "Capture this page" },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.Black)
        } else {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {}
        }
    }
}
