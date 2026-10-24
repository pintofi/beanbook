package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.CapturedPage
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.PageLayout
import io.github.pintofi.beanbook.core.domain.PageSize
import io.github.pintofi.beanbook.core.domain.PageStyle
import java.io.File
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The pages one at a time, swiped between, each pinch-zoomable and drawn as it will be filed. */
@Composable
internal fun ReviewPager(
    state: PagerState,
    pages: CapturedPages,
    fileFor: (String) -> File,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = state,
        modifier = modifier.testTag(PAGER_TAG),
        key = { pages.pages.getOrNull(it)?.source ?: it },
    ) { index ->
        val page = pages.pages.getOrNull(index) ?: return@HorizontalPager
        val file = fileFor(page.source)
        // The page as it will be filed: straightened on its corners and rendered in its style.
        val render = LocalPageRenderer.current
        val shown by androidx.compose.runtime.produceState<PageRender>(PageRender.Working, page, file.length()) {
            value = render(page, file)?.let { PageRender.Ready(it) } ?: PageRender.Unreadable
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = (shown as? PageRender.Ready)?.bitmap
            if (shown == PageRender.Working) {
                LedgerLoadingIndicator()
            } else if (bitmap == null) {
                Text("This page could not be opened.")
            } else {
                // Pinch to check the small print before filing it. Telephoto hands a horizontal
                // drag to the pager at the page's edge, so swiping is how you turn pages.
                val image = androidx.compose.runtime.remember(bitmap, pages.pageSize) {
                    (if (pages.pageSize == PageSize.AS_SCANNED) bitmap else onSheet(bitmap, pages.pageSize))
                        .asImageBitmap()
                }
                ZoomablePage(bitmap = image, description = "Page ${index + 1}", modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/**
 * The page laid on a sheet of [size] paper exactly where the PDF will put it, so the margins and the
 * fit are seen before filing. Drawn at a fixed width, whatever the paper.
 */
private fun onSheet(page: android.graphics.Bitmap, size: PageSize): android.graphics.Bitmap {
    val placement = PageLayout.place(size, page.width, page.height)
    val scale = SHEET_WIDTH.toFloat() / placement.pageWidth
    val sheet = android.graphics.Bitmap.createBitmap(
        SHEET_WIDTH, (placement.pageHeight * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(sheet)
    canvas.drawColor(android.graphics.Color.WHITE)
    val top = (placement.pageHeight - placement.y - placement.height) * scale
    canvas.drawBitmap(
        page,
        null,
        android.graphics.RectF(
            placement.x * scale, top, (placement.x + placement.width) * scale, top + placement.height * scale,
        ),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    val edge = android.graphics.Paint().apply {
        color = SHEET_EDGE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRect(1f, 1f, sheet.width - 1f, sheet.height - 1f, edge)
    return sheet
}

private const val SHEET_WIDTH = 1200
private const val SHEET_EDGE = 0xFFBDBDBD.toInt()

/** How the shown page is rendered, one chip for every style, and a way to give every page the same. */
@Composable
internal fun StyleSection(
    shown: Int,
    pages: CapturedPages,
    onRestyle: (PageStyle) -> Unit,
    onRestyleAll: () -> Unit,
) {
    val style = pages.pages[shown].style
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageStyle.entries.forEach { option ->
            FilterChip(
                selected = style == option,
                onClick = { onRestyle(option) },
                label = { Text(option.label()) },
                modifier = Modifier.semantics {
                    contentDescription = "Render page ${shown + 1} in ${option.label()}"
                    role = Role.RadioButton
                },
            )
        }
    }
    if (pages.pages[shown].automaticStyle) {
        Text(
            "Chosen automatically",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    val differs = pages.pages.any { it.style != style }
    OutlinedButton(
        onClick = onRestyleAll,
        enabled = pages.size > 1 && differs,
        modifier = Modifier.padding(horizontal = 16.dp)
            .semantics { contentDescription = "Render every page in ${style.label()}" },
    ) { Text("Use for all pages") }
}

/** Turn and crop, on one floating toolbar. */
@Composable
internal fun PageTools(
    shown: Int,
    onTurn: (clockwise: Boolean) -> Unit,
    onCrop: () -> Unit,
    onRetake: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(expanded = true) {
            IconButton(
                onClick = { onTurn(false) },
                modifier = Modifier.semantics { contentDescription = "Turn page ${shown + 1} anticlockwise" },
            ) { Icon(Icons.Filled.RotateLeft, contentDescription = null) }
            IconButton(
                onClick = { onTurn(true) },
                modifier = Modifier.semantics { contentDescription = "Turn page ${shown + 1} clockwise" },
            ) { Icon(Icons.Filled.RotateRight, contentDescription = null) }
            IconButton(
                onClick = onCrop,
                modifier = Modifier.semantics { contentDescription = "Set the corners of page ${shown + 1}" },
            ) { Icon(Icons.Filled.Crop, contentDescription = null) }
            onRetake?.let {
                IconButton(
                    onClick = it,
                    modifier = Modifier.semantics { contentDescription = "Retake page ${shown + 1}" },
                ) { Icon(Icons.Filled.CameraAlt, contentDescription = null) }
            }
        }
    }
}

/**
 * Thumbnails of every page: tap to show one, hold and drag to reorder. The shown page is
 * outlined, and the strip keeps it in view.
 */
@Composable
internal fun ReviewStrip(
    pages: CapturedPages,
    fileFor: (String) -> File,
    shown: Int,
    onShow: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> onMove(from.index, to.index) }
    LaunchedEffect(shown) {
        if (shown in 0 until pages.size) listState.animateScrollToItem(shown)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(STRIP_HEIGHT.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(pages.size, key = { pages.pages[it].source }) { index ->
            val page: CapturedPage = pages.pages[index]
            ReorderableItem(reorder, key = page.source) {
                Box(
                    modifier = Modifier
                        .longPressDraggableHandle()
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(selected = index == shown, role = Role.Tab, onClick = { onShow(index) })
                        .then(
                            if (index == shown) {
                                Modifier.border(BorderStroke(3.dp, primary), RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            customActions = listOfNotNull(
                                androidx.compose.ui.semantics.CustomAccessibilityAction("Move page earlier") {
                                    onMove(index, index - 1); true
                                }.takeIf { index > 0 },
                                androidx.compose.ui.semantics.CustomAccessibilityAction("Move page later") {
                                    onMove(index, index + 1); true
                                }.takeIf { index < pages.size - 1 },
                            )
                        },
                ) {
                    PageThumbnail(
                        file = fileFor(page.source),
                        label = "Page ${index + 1}",
                        size = THUMBNAIL.dp,
                        page = page,
                    )
                }
            }
        }
    }
}

/** What a test finds the pager by, to swipe it. */
internal const val PAGER_TAG = "page-review-pager"

private const val STRIP_HEIGHT = 80
private const val THUMBNAIL = 64

internal sealed interface PageRender {
    data object Working : PageRender
    data object Unreadable : PageRender
    class Ready(val bitmap: android.graphics.Bitmap) : PageRender
}

/**
 * How the review draws a page. The app provides the assembler's own rendering, so the review shows
 * what will be filed; the default is the upright photograph, which is what a screen without the
 * app around it -- a test -- can show.
 */
val LocalPageRenderer = androidx.compose.runtime.staticCompositionLocalOf<
    suspend (io.github.pintofi.beanbook.core.domain.CapturedPage, File) -> android.graphics.Bitmap?,
    > {
    { page, file ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = 1600)
                ?.let { io.github.pintofi.beanbook.app.documents.Photos.upright(it, page.rotation) }
        }
    }
}
