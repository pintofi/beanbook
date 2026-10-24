package io.github.pintofi.beanbook.app.ui

import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.pintofi.beanbook.core.domain.CapturedPage
import io.github.pintofi.beanbook.core.domain.PageEdit
import io.github.pintofi.beanbook.core.domain.apply
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
    /** Per-page editing, reached from the batch thumbnail. */
    review: io.github.pintofi.beanbook.app.documents.PageReviewActions =
        io.github.pintofi.beanbook.app.documents.PageReviewActions.None,
    /** The batch with its page size, for the review; the pages alone when the host does not say. */
    batch: io.github.pintofi.beanbook.core.domain.CapturedPages =
        io.github.pintofi.beanbook.core.domain.CapturedPages(pages),
    onMovePage: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Back leaves the scanner, not the app. The scanner is a mode drawn over
    // everything else, and without this the system's back gesture went to the activity --
    // which had nothing behind it, so a stray swipe closed Beanbook mid-batch.
    // Back from a retake returns to the page being retaken, with nothing changed; from adding it leaves.
    var chosenTarget by rememberSaveable(stateSaver = ScanTarget.Saver) {
        mutableStateOf<ScanTarget>(ScanTarget.Append)
    }
    var previewing by remember { mutableStateOf<Int?>(null) }
    val leaveOrReturn = {
        val retaking = chosenTarget as? ScanTarget.Replace
        if (retaking != null) {
            chosenTarget = ScanTarget.Append
            previewing = retaking.index
        } else {
            onCancel()
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) { leaveOrReturn() }

    // The form underneath stays composed while scanning, and a field in it may still
    // hold focus -- which would keep the keyboard up over the viewfinder.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(force = true) }

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

    // What the next photograph is for: another page, or a new photograph of one already taken. A
    // retake whose page has moved or gone since it was chosen falls back to adding.
    val latestPages by androidx.compose.runtime.rememberUpdatedState(pages)
    val target = chosenTarget.checkedAgainst(pages)
    var notice by remember { mutableStateOf<String?>(null) }

    /** Hands a new photograph on: as a page, or in place of the one being retaken. */
    fun deliver(file: File, inspected: PageInspection) {
        val quad = inspected.quad
        val style = inspected.style
        // Read now, not captured: the auto-shutter's closure outlives the composition that made it.
        val retaking = chosenTarget.checkedAgainst(latestPages) as? ScanTarget.Replace
        if (retaking == null) {
            onCaptured(file, quad, style)
            return
        }
        var done = false
        var tries = 0
        while (!done && tries < 2) {
            val before = review.current()
            val after = before.apply(PageEdit.Retake(retaking.index, retaking.source, file.name, quad))
            done = review.replace(before, after)
            tries += 1
        }
        chosenTarget = ScanTarget.Append
        notice = "Page ${retaking.index + 1} retaken."
        previewing = retaking.index
    }

    // The three controls a scanner needs that a viewfinder does not, taken from what
    // OSS-DocumentScanner does (MIT; read, not copied): a torch for a receipt on a
    // dark table, zoom for a page you cannot get closer to, and a shutter that fires itself
    // once the page is squarely in frame.
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var torch by remember { mutableStateOf(false) }
    // Off / on / auto, cycled by one button, as OSS-DocumentScanner does it. The torch
    // is a separate control because it is a different thing: a lamp for framing, not a flash
    // for the shot.
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoom by remember { mutableStateOf(0f) }
    var autoCapture by rememberSaveable { mutableStateOf(true) }
    var aimed by remember { mutableStateOf(false) }
    // When the shutter fires by itself, and how far through the hold the page is.
    val shutter = remember { AutoShutter() }
    var holdProgress by remember { mutableStateOf(0f) }
    // What the viewfinder draws into, handed over by CameraX each time it (re)binds.
    var surfaceRequest by remember { mutableStateOf<androidx.camera.core.SurfaceRequest?>(null) }
    // Where the last tap to focus landed, and a count so a second tap restarts the ring.
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTaps by remember { mutableIntStateOf(0) }
    val transformer = remember { androidx.camera.viewfinder.compose.MutableCoordinateTransformer() }
    // The page as the detector sees it right now, in the sampled frame's pixels. The
    // outline used to be the *last shot's*, stretched over the whole window without allowing
    // for the letterboxing, so it sat somewhere near the page rather than on it.
    var liveQuad by remember { mutableStateOf<CropQuad?>(null) }
    var liveFrame by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(flash) { capture.flashMode = flash }

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
        // Whoever pressed, the timer must not shoot this page again: it stays disarmed until the
        // page in view changes.
        shutter.shot(liveQuad)
        holdProgress = 0f
        val file = captureFile()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    // Detect here, once, and hand the crop up with the file. The overlay
                    // reads the same result, so a page is never analysed twice for one shot.
                    scope.launch {
                        val inspected = withContext(Dispatchers.Default) { inspectPage(file) }
                        deliver(file, inspected)
                        busy = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                }
            },
        )
    }

    // Watches the camera a few times a second: the outline drawn over the page, and the
    // shutter fired when the page is squarely in frame, which is what a scanner does that a
    // camera does not. Firing needs the page to be *still*, not merely found twice:
    // two detections of a page sliding across the table used to be enough, and the shot came
    // out blurred.
    //
    // Frames come from a small ImageAnalysis stream now. They used to be copied out of the
    // preview view's own bitmap, which the Compose viewfinder does not have -- and the analysis
    // frame is the camera's picture rather than a screenshot of the view, so the outline no
    // longer depends on how the preview happens to be letterboxed.
    fun onSample(quad: CropQuad?, frame: androidx.compose.ui.unit.IntSize) {
        // Not while a page is open for editing: the camera is still running behind the editor, and
        // a shutter that fires there would add a page nobody asked for to the batch being reviewed.
        if (busy || previewing != null) return
        liveQuad = quad
        liveFrame = frame
        aimed = quad != null
        val fire = shutter.sample(quad, kotlin.math.hypot(frame.width.toFloat(), frame.height.toFloat()))
        holdProgress = if (autoCapture) shutter.progress else 0f
        if (autoCapture && fire) shoot()
    }

    // Opening the editor forgets what the shutter was holding on, so coming back to the camera has to
    // see the page settle again before it fires.
    LaunchedEffect(previewing != null) {
        if (previewing != null) {
            shutter.shot(null)
            holdProgress = 0f
        }
    }

    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    val analysis = remember {
        androidx.camera.core.ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                    )
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            androidx.camera.core.resolutionselector.ResolutionStrategy
                                .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    LaunchedEffect(analysis) {
        var last = 0L
        analysis.setAnalyzer(analysisExecutor) { image ->
            image.use { frame ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - last < AUTO_SAMPLE_MILLIS) return@use
                last = now
                val upright = io.github.pintofi.beanbook.app.documents.Photos
                    .upright(frame.toBitmap(), frame.imageInfo.rotationDegrees)
                val small = upright.scale()
                val quad = Scanner.detect(small, thorough = false)
                val size = androidx.compose.ui.unit.IntSize(small.width, small.height)
                mainExecutor.execute { onSample(quad, size) }
            }
        }
    }

    // The lamp goes out with the screen. The camera is bound to the *activity's*
    // lifecycle, not to this composable, so leaving the scanner left the use cases bound and
    // the torch burning -- with no control anywhere in the app to turn it off again, because
    // the only switch had gone with the screen. Turning it off and unbinding on the way out
    // is what makes leaving the scanner mean leaving the camera.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { provider?.unbindAll() }
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }

    // Bound once, to the back camera: pages are photographed facing away from you, and
    // the front camera switch it once offered did nothing a scanner needs.
    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        provider = cameraProvider
        val preview = androidx.camera.core.Preview.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                    )
                    .build(),
            )
            .build()
            .also { it.setSurfaceProvider { request -> surfaceRequest = request } }
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
            analysis,
        )
    }

    // Photographs already on the phone become pages too: a receipt someone else sent, a
    // screenshot of a bill. The system photo picker needs no storage permission, and each
    // picture goes through the same detection as a shot from the camera.
    val importPictures = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            uris.forEach { uri ->
                val file = captureFile()
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        } != null
                    }.onFailure { android.util.Log.w("Scanner", "could not import a picture", it) }
                        .getOrDefault(false)
                }
                if (copied && file.length() > 0) {
                    val inspected = withContext(Dispatchers.Default) { inspectPage(file) }
                    deliver(file, inspected)
                }
            }
            busy = false
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
        // The Compose viewfinder, in place of a PreviewView inside AndroidView. Fitted, not
        // filled: filling the window crops the frame to the screen's shape, so the viewfinder
        // showed less than the camera saw. Embedded, so the controls draw over it.
        surfaceRequest?.let { request ->
            androidx.camera.compose.CameraXViewfinder(
                surfaceRequest = request,
                implementationMode = androidx.camera.viewfinder.core.ImplementationMode.EMBEDDED,
                coordinateTransformer = transformer,
                contentScale = ContentScale.Fit,
                // Tap to focus, the way the camera app does: a receipt close to the lens is
                // where continuous focus most often settles on the table instead. The tap is
                // mapped from the screen onto the camera's own buffer by the viewfinder.
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Camera viewfinder. Tap to focus." }
                    .pointerInput(camera, request) {
                    detectTapGestures { position ->
                        val control = camera?.cameraControl ?: return@detectTapGestures
                        focusPoint = position
                        focusTaps++
                        val onSurface = with(transformer) { position.transform() }
                        val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                            request.resolution.width.toFloat(),
                            request.resolution.height.toFloat(),
                        )
                        control.startFocusAndMetering(
                            androidx.camera.core.FocusMeteringAction
                                .Builder(factory.createPoint(onSurface.x, onSurface.y))
                                .build(),
                        )
                    }
                },
            )
        }

        // A ring where the tap landed, as camera apps draw one: it closes in on the point,
        // holds while the lens settles, and fades. Without it a tap looked like nothing happened.
        focusPoint?.let { point ->
            val ring = remember { Animatable(0f) }
            LaunchedEffect(focusTaps) {
                ring.snapTo(0f)
                ring.animateTo(1f, tween(FOCUS_RING_MILLIS, easing = LinearEasing))
                focusPoint = null
            }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val t = ring.value
                val closing = (t / FOCUS_RING_CLOSE).coerceAtMost(1f)
                val eased = 1f - (1f - closing) * (1f - closing)
                val radius = FOCUS_RING_RADIUS.dp.toPx() * (FOCUS_RING_START - (FOCUS_RING_START - 1f) * eased)
                val fade = if (t < FOCUS_RING_FADE) 1f else (1f - t) / (1f - FOCUS_RING_FADE)
                // A dark edge under the white, so the ring still shows against a white receipt.
                drawCircle(
                    color = Color.Black.copy(alpha = FOCUS_RING_SHADOW * fade),
                    radius = radius,
                    center = point,
                    style = Stroke(width = 4.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = fade),
                    radius = radius,
                    center = point,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // The page the detector sees now, on the preview. The analysis frame is upright
        // and the same 4:3 shape as the preview, which is fitted into this box, so a point maps
        // by one scale and the letterbox offset.
        // Eased between samples, which arrive every 0.4 s, so the ring sweeps and does not jump.
        val hold by androidx.compose.animation.core.animateFloatAsState(
            targetValue = holdProgress,
            animationSpec = androidx.compose.animation.core.tween(AUTO_SAMPLE_MILLIS.toInt()),
            label = "auto-capture hold",
        )
        liveQuad?.let { quad ->
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                if (liveFrame.width == 0 || liveFrame.height == 0) return@Canvas
                val fit = minOf(size.width / liveFrame.width, size.height / liveFrame.height)
                val left = (size.width - liveFrame.width * fit) / 2
                val top = (size.height - liveFrame.height * fit) / 2
                val path = Path().apply {
                    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                    val pts = corners.map { Offset(left + it.x * fit, top + it.y * fit) }
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, SCAN_INK.copy(alpha = 0.18f))
                drawPath(path, SCAN_INK.copy(alpha = 0.7f), style = Stroke(width = 2.dp.toPx()))
                // The page's corners, bracketed: the same mark the crop editor uses for
                // its handles, so the page found here is recognisably the page cropped there.
                val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                pageBrackets(
                    colour = SCAN_INK,
                    points = corners.map { Offset(left + it.x * fit, top + it.y * fit) },
                    arm = SCAN_BRACKET_ARM.dp.toPx(),
                    thickness = 4.dp.toPx(),
                )
                // The countdown: a ring filling on the page while it holds still, so a shot
                // is never a surprise.
                if (hold > 0.01f) {
                    val centre = Offset(
                        left + corners.map { it.x }.average().toFloat() * fit,
                        top + corners.map { it.y }.average().toFloat() * fit,
                    )
                    val radius = 30.dp.toPx()
                    drawCircle(Color.Black.copy(alpha = 0.35f), radius + 4.dp.toPx(), centre)
                    drawCircle(Color.White.copy(alpha = 0.35f), radius, centre, style = Stroke(width = 5.dp.toPx()))
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * hold.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(centre.x - radius, centre.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
        // No page found yet: four quiet brackets where one would sit, so the viewfinder says what
        // it is looking for.
        if (liveQuad == null && surfaceRequest != null) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 140.dp)) {
                pageBrackets(
                    colour = Color.White,
                    points = listOf(
                        Offset.Zero,
                        Offset(size.width, 0f),
                        Offset(size.width, size.height),
                        Offset(0f, size.height),
                    ),
                    arm = SCAN_BRACKET_ARM.dp.toPx(),
                    thickness = 4.dp.toPx(),
                )
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

        // Two rows, as OSS-DocumentScanner arranges them: the camera's own controls on the
        // top line, and what a page will look like on the line below. One row of both
        // put the style chips underneath the icon buttons, where they could not be read or
        // tapped.
        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { leaveOrReturn() },
                    modifier = Modifier.semantics { contentDescription = "Leave the scanner" },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                if (target is ScanTarget.Replace) {
                    Text(
                        "Retake page ${target.index + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
            IconButton(
                onClick = {
                    flash = when (flash) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = when (flash) {
                        ImageCapture.FLASH_MODE_OFF -> "Flash is off. Tap to turn it on"
                        ImageCapture.FLASH_MODE_ON -> "Flash is on. Tap for automatic"
                        else -> "Flash is automatic. Tap to turn it off"
                    }
                },
            ) {
                Icon(
                    when (flash) {
                        ImageCapture.FLASH_MODE_OFF -> Icons.Filled.FlashOff
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        else -> Icons.Filled.FlashAuto
                    },
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                },
                modifier = Modifier.semantics {
                    contentDescription = if (torch) "Turn the light off" else "Turn the light on"
                },
            ) {
                // A lamp, not a flash: two struck-through bolts side by side said nothing
                // about which was which.
                Icon(
                    if (torch) Icons.Filled.Highlight else Icons.Filled.HighlightOff,
                    contentDescription = null,
                    tint = if (torch) Color(0xFF80D5D4) else Color.White,
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

        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Shown only while it means something, as OSS-DocumentScanner shows its slider:
            // a zoom readout on a camera at 1x is a number nobody asked for.
            if (zoom > 0f) {
                Text(
                    text = "%.1fx".format(1f + zoom * MAX_ZOOM_LABEL),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (pages.isNotEmpty() && target is ScanTarget.Append) {
                PageStrip(
                    pages = pages,
                    fileFor = fileFor,
                    onOpen = { previewing = it },
                    onRemovePage = onRemovePage,
                    onMovePage = onMovePage,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // A spacer, not a thumbnail. The last page taken used to sit here as a
                // 56dp box beside the shutter, above a strip that already showed every page
                // including that one -- the same picture twice, one of them unable to say
                // which page it was or let the others be reached. The strip does both jobs,
                // so the box is gone and its width stays, because it is what keeps the
                // shutter in the middle of the screen rather than left of it.
                if (target is ScanTarget.Append) IconButton(
                    onClick = {
                        importPictures.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = "Add pages from photos on this phone"
                    },
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Color.White)
                }

                ShutterButton(
                    busy = busy,
                    count = pages.size,
                    auto = autoCapture,
                    aimed = aimed,
                    onLongPress = { autoCapture = !autoCapture },
                ) { shoot() }

                if (target is ScanTarget.Append) IconButton(
                    onClick = { previewing = pages.lastIndex },
                    enabled = pages.isNotEmpty(),
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = "Review the ${pages.size} pages"
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
        // The editor, not a dialog: a page you are straightening wants the whole screen, and
        // the batch is a thing to swipe through rather than a list of thumbnails with four
        // text buttons under each.
        PageReviewScreen(
            pages = batch,
            fileFor = fileFor,
            startAt = index,
            review = review,
            onDone = onDone,
            backLabel = "Back to the camera",
            onBack = { previewing = null; notice = null },
            onRetake = { retaking ->
                chosenTarget = ScanTarget.Replace(retaking, batch.pages[retaking].source)
                previewing = null
            },
            onAddPage = { previewing = null },
            notice = notice,
        )
    }
}

/** The batch so far, each page drawn as itself rather than named. */
@Composable
private fun PageStrip(
    pages: List<CapturedPage>,
    fileFor: (String) -> File,
    onOpen: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onMovePage: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    // Long-press a page and drag it along the strip to put the batch in order (Reorderable).
    // The page editor's earlier and later buttons are still there, and the same moves are
    // offered to accessibility services as actions on each page.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorder = sh.calvin.reorderable.rememberReorderableLazyListState(listState) { from, to ->
        onMovePage(from.index, to.index)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages.size, key = { pages[it].source }) { index ->
            val page = pages[index]
            // Rendered as it will be filed, crop included, off the main thread.
            val bitmap = rememberRenderedPage(page, fileFor(page.source))
            ReorderableItem(reorder, key = page.source) { dragging ->
            Box(
                modifier = Modifier
                    .longPressDraggableHandle()
                    .size(72.dp)
                    .graphicsLayer { if (dragging) { scaleX = DRAG_SCALE; scaleY = DRAG_SCALE } }
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.DarkGray)
                    .semantics {
                        contentDescription = "Page ${index + 1}. Tap to see it, or hold and drag to move it."
                        customActions = listOfNotNull(
                            androidx.compose.ui.semantics.CustomAccessibilityAction("Move page earlier") {
                                onMovePage(index, index - 1); true
                            }.takeIf { index > 0 },
                            androidx.compose.ui.semantics.CustomAccessibilityAction("Move page later") {
                                onMovePage(index, index + 1); true
                            }.takeIf { index < pages.size - 1 },
                        )
                    },
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
                // the thumbnail is for looking at the page, not for losing it.
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
}

/**
 * The shutter, with the batch count inside it and a ring that says whether it is watching.
 *
 * Taken from OSS-DocumentScanner's arrangement -- count in the button, auto-scan shown on the
 * ring around it, long-press to toggle it -- drawn with this app's colours rather than
 * theirs. The long-press is a shortcut, not the only way: the labelled toggle at the top is
 * what a screen reader finds.
 */
@Composable
private fun ShutterButton(
    busy: Boolean,
    count: Int,
    auto: Boolean,
    aimed: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    val ring = when {
        !auto -> Color.White
        aimed -> Color(0xFF80D5D4)
        else -> Color(0xFF80D5D4).copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White)
            .pointerInput(busy) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { if (!busy) onClick() },
                )
            }
            .semantics {
                contentDescription = if (auto) {
                    "Capture this page. Pages are also captured automatically"
                } else {
                    "Capture this page"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = ring,
                radius = size.minDimension / 2 - 4f,
                style = Stroke(width = 6f),
            )
        }
        if (busy) {
            LedgerLoadingIndicator(size = 32.dp, color = Color.Black)
        } else if (count > 0) {
            Text(
                text = "$count",
                color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** What a linear zoom of 1.0 is called on screen. Cameras vary; the label is a hint. */
private const val MAX_ZOOM_LABEL = 5f


/** How much a page grows while it is being dragged, so it reads as lifted. */
private const val DRAG_SCALE = 1.08f

/** The focus ring: how long it shows, how big it starts, and when it stops closing and fades. */
/** The scanner's ink: the dark theme's rule colour, legible over any camera picture. */
private val SCAN_INK = Color(0xFF80D5D4)
private const val SCAN_BRACKET_ARM = 28

private const val FOCUS_RING_MILLIS = 900
private const val FOCUS_RING_RADIUS = 36
private const val FOCUS_RING_START = 1.5f
private const val FOCUS_RING_CLOSE = 0.25f
private const val FOCUS_RING_FADE = 0.7f
private const val FOCUS_RING_SHADOW = 0.4f

/** The analysis stream only feeds a detector that works at 720 px; more is wasted battery. */
private const val ANALYSIS_WIDTH = 960
private const val ANALYSIS_HEIGHT = 720

/** A few times a second: often enough to feel immediate, rare enough to cost nothing. */
private const val AUTO_SAMPLE_MILLIS = 400L

/** What the photo picker offers at once; a batch larger than this is a second visit. */
private const val MAX_IMPORT = 20

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
