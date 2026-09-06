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

    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // The preview runs under the system bars; the controls must not.
            .systemBarsPadding(),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).also { preview ->
                    // COMPATIBLE for the same reason CameraPane uses it: a SurfaceView is
                    // composited outside the hierarchy and would draw over the controls.
                    preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    val future = ProcessCameraProvider.getInstance(viewContext)
                    future.addListener({
                        val provider = future.get()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            androidx.camera.core.Preview.Builder().build().also {
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
                PageStrip(pages, fileFor, onRemovePage)
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
                ShutterButton(busy = busy) {
                    if (busy) return@ShutterButton
                    busy = true
                    val target = captureFile()
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(target).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                // Detect here, once, and hand the crop up with the file. The
                                // overlay reads the same result, so a page is never analysed
                                // twice for one shot.
                                scope.launch {
                                    val quad = withContext(Dispatchers.Default) {
                                        BitmapFactory.decodeFile(target.absolutePath)?.let { shot ->
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
    onRemovePage: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages.size) { index ->
            val page = pages[index]
            val bitmap = remember(page.source) {
                runCatching { BitmapFactory.decodeFile(fileFor(page.source).absolutePath) }.getOrNull()
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.DarkGray)
                    .semantics { contentDescription = "Page ${index + 1}. Tap to remove." },
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
                IconButton(onClick = { onRemovePage(index) }, modifier = Modifier.fillMaxSize()) {}
                Text(
                    "${index + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
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
