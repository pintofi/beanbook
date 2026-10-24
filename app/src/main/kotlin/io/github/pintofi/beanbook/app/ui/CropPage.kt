package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import io.github.pintofi.beanbook.core.domain.CropEditor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import java.io.File

/**
 * Drag the corners, or the middles of the sides, of a page to straighten it, over the whole screen.
 *
 * A perspective crop rather than a rectangular one, because a receipt photographed on a table
 * is a general quadrilateral: its near edge is longer than its far edge and its sides lean. A
 * box cut out of that is still keystoned. Four corners mapped onto a rectangle is what makes
 * it read like a scan.
 *
 * **The screen is the page.** It is the same black, full-bleed surface as the page editor it
 * opens from, with the same top bar, so pressing Crop changes what the controls do rather than
 * where the page is.
 *
 * The corners can never be dragged into a shape that cannot be straightened
 * ([io.github.pintofi.beanbook.core.domain.CropEditor]), a loupe shows the held handle larger
 * above the finger, and the picture can be turned. Corners arrive detected by the scanner
 * (OpenCV, `:scanner`) where it found the page, and are dragged by hand from there.
 *
 * @param initialRotation how far the page is turned in the editor behind this, so the picture
 *   looks the same here; [onCropped] gives back where it ended up.
 */
@Composable
fun CropPageScreen(
    file: File,
    initial: CropQuad?,
    onCropped: (CropQuad?, rotation: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialRotation: Int = 0,
) {
    // Back leaves the crop, not the editor behind it: the gesture that means "not this" has
    // to mean the nearest "this" first.
    androidx.activity.compose.BackHandler(enabled = true) { onDismiss() }

    // Upright: the corners a person drags have to sit on the page as they see it.
    //
    // Sampled and off the main thread: a 12 MP camera page is a 48 MB bitmap, and decoding it
    // inside composition was a freeze on opening the crop and a real risk of running out of
    // memory. The corners stay in the full photograph's pixels, which is what the detector and
    // the assembler use, so they are scaled by [CropSource.scale] on the way in and out.
    val loaded by androidx.compose.runtime.produceState<CropSource?>(null, file.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cropSourceOf(file) }
    }
    val source = loaded
    if (source == null) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            LedgerLoadingIndicator(color = Color.White)
        }
        return
    }
    val image: ImageBitmap? = source.image

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        if (image == null) {
            CropBar(onDismiss = onDismiss, onDone = null)
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
        CropEditorBody(file, source, image, initial, initialRotation, onCropped, onDismiss)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CropEditorBody(
    file: File,
    source: CropSource,
    image: ImageBitmap,
    initial: CropQuad?,
    initialRotation: Int,
    onCropped: (CropQuad?, rotation: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val width = image.width.toFloat()
    val height = image.height.toFloat()
    // In the shown bitmap's pixels, and kept across a rotation of the device or a killed process.
    var editor by androidx.compose.runtime.saveable.rememberSaveable(
        file.path, width, height,
        stateSaver = remember(width, height) { cropEditorSaver(width, height) },
    ) {
        mutableStateOf(
            CropEditor(
                quad = initial?.scaled(1f / source.scale) ?: CropQuad.whole(width, height),
                width = width,
                height = height,
                rotation = initialRotation.mod(FULL_TURN),
            ),
        )
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    CropBar(
        onDismiss = onDismiss,
        onDone = if (editor.canConfirm) {
            { onCropped(editor.quad.scaled(source.scale), editor.rotation) }
        } else {
            { }
        },
        doneEnabled = editor.canConfirm,
        doneLabel = if (editor.canConfirm) "Use this crop" else "The corners cross. Move them apart to use this crop.",
    )

    CropCanvas(
        image = image,
        editor = editor,
        onEditor = { editor = it; message = null },
        modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp),
    )

    message?.let {
        Text(
            it,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CropAction("Turn", "Turn the page clockwise") { editor = editor.turn(clockwise = true) }
        CropAction("Undo", "Undo the last move", enabled = editor.canUndo) { editor = editor.undo() }
        CropAction("Find the page", "Find the page again", enabled = !finding) {
            finding = true
            scope.launch {
                val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    detectPageIn(file)
                }
                finding = false
                if (found == null) {
                    message = "No page found. Drag the corners instead."
                } else {
                    editor = editor.reset(found.scaled(1f / source.scale))
                    message = null
                }
            }
        }
        // Keeping the whole photo is a real choice and stays; confirming is the tick in the bar,
        // and leaving is the arrow beside it.
        CropAction("Use the whole photo", "Use the whole photo") { onCropped(null, editor.rotation) }
    }
}

@Composable
private fun CropBar(
    onDismiss: () -> Unit,
    onDone: (() -> Unit)?,
    doneEnabled: Boolean = true,
    doneLabel: String = "Use this crop",
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
            "Crop",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (onDone != null) {
            IconButton(
                onClick = onDone,
                enabled = doneEnabled,
                modifier = Modifier.semantics { contentDescription = doneLabel },
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (doneEnabled) Color.White else Color.White.copy(alpha = 0.38f),
                )
            }
        }
    }
}

@Composable
private fun CropAction(text: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 1f else 0.38f)),
    ) { Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.38f)) }
}

/** What a restart keeps of the editor: the corners, the turn and the steps to undo. A held finger is not kept. */
private fun cropEditorSaver(width: Float, height: Float) =
    androidx.compose.runtime.saveable.listSaver<CropEditor, Float>(
        save = { e ->
            buildList {
                add(e.rotation.toFloat())
                addAll(e.quad.flat())
                addAll(e.initial.flat())
                e.history.forEach { addAll(it.flat()) }
            }
        },
        restore = { saved ->
            fun quadAt(from: Int) = CropQuad(
                CropPoint(saved[from], saved[from + 1]), CropPoint(saved[from + 2], saved[from + 3]),
                CropPoint(saved[from + 4], saved[from + 5]), CropPoint(saved[from + 6], saved[from + 7]),
            )
            val history = (17 until saved.size step 8).map { quadAt(it) }
            CropEditor(
                quad = quadAt(1), width = width, height = height, rotation = saved[0].toInt(),
                initial = quadAt(9), history = history,
            )
        },
    )

private fun CropQuad.flat() = listOf(
    topLeft.x, topLeft.y, topRight.x, topRight.y, bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y,
)

private const val FULL_TURN = 360

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

