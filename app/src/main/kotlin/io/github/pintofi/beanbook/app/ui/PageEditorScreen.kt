package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.core.domain.PageStyle
import java.io.File
import kotlinx.coroutines.launch

/**
 * The captured pages, one screen each, with the things you do to a page (B58).
 *
 * The shape is OSS-DocumentScanner's editor — swipe through the batch, act on the page in
 * front of you — rebuilt in this app's own language: a Material top bar rather than their
 * action bar, segmented buttons for the rendering style rather than a filter strip, and the
 * app's own icons throughout. What was worth copying is the arrangement, not the paint: a
 * list of thumbnails with four text buttons under each is a form, and a page you are trying
 * to straighten wants the whole screen.
 *
 * Everything here edits the *description* of a page — its rotation, its corners, how it is
 * rendered. The photograph on disk is never rewritten, so any of it can be undone by doing
 * it again.
 */
@Composable
fun PageEditorScreen(
    pages: CapturedPages,
    fileFor: (String) -> File,
    startAt: Int,
    onRotate: (index: Int, clockwise: Boolean) -> Unit,
    onCrop: (index: Int, quad: CropQuad?) -> Unit,
    onRestyle: (index: Int, style: PageStyle) -> Unit,
    onRemove: (index: Int) -> Unit,
    /** Moves a page earlier or later in the document (B58). */
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val state = rememberPagerState(
        initialPage = startAt.coerceIn(0, pages.size - 1),
        pageCount = { pages.size },
    )
    var cropping by rememberSaveable { mutableStateOf<Int?>(null) }
    val current = state.currentPage.coerceIn(0, pages.size - 1)
    val scope = rememberCoroutineScope()
    var moveMenu by remember { mutableStateOf(false) }
    // A moved page stays in front of you, so the counter names where it went.
    val move = { from: Int, to: Int ->
        onMove(from, to)
        scope.launch { state.scrollToPage(to) }
    }

    // Cropping takes the screen in place of the editor rather than opening a card over it
    // (B73): it is the same page, at the same size, and only the controls change.
    val croppingIndex = cropping
    if (croppingIndex != null) {
        val page = pages.pages.getOrNull(croppingIndex)
        if (page != null) {
            CropPageScreen(
                file = fileFor(page.source),
                initial = page.crop,
                onCropped = { quad ->
                    onCrop(croppingIndex, quad)
                    cropping = null
                },
                onDismiss = { cropping = null },
                modifier = modifier,
            )
            return
        }
        cropping = null
    }

    androidx.activity.compose.BackHandler(enabled = true) { onDone() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDone,
                modifier = Modifier.semantics { contentDescription = "Finish editing the pages" },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(
                "Page ${current + 1} of ${pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onRemove(current) },
                modifier = Modifier.semantics {
                    contentDescription = "Remove page ${current + 1}"
                },
            ) { Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White) }
            // Moving a page is in words, in a menu, because it used to be the arrows under the
            // page -- which everyone read as "show me the next page". Pressing one swapped two
            // pages while the counter stayed put, and pressing it again swapped them back.
            Box {
                IconButton(
                    onClick = { moveMenu = true },
                    enabled = pages.size > 1,
                    modifier = Modifier.semantics { contentDescription = "Change the order of the pages" },
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = if (pages.size > 1) Color.White else Color.Gray,
                    )
                }
                DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Move page ${current + 1} earlier") },
                        enabled = current > 0,
                        onClick = { moveMenu = false; move(current, current - 1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Move page ${current + 1} later") },
                        enabled = current < pages.size - 1,
                        onClick = { moveMenu = false; move(current, current + 1) },
                    )
                }
            }
        }

        HorizontalPager(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = pages.pages[index]
            val file = fileFor(page.source)
            // The page as it will be filed (B79): straightened on its corners and rendered in
            // its style. The raw photograph said nothing about either until the PDF existed.
            val render = LocalPageRenderer.current
            val shown by androidx.compose.runtime.produceState<PageRender>(PageRender.Working, page, file.length()) {
                value = render(page, file)?.let { PageRender.Ready(it) } ?: PageRender.Unreadable
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bitmap = (shown as? PageRender.Ready)?.bitmap
                if (shown == PageRender.Working) {
                    LedgerLoadingIndicator(color = Color.White)
                } else if (bitmap == null) {
                    Text("This page could not be opened.", color = Color.White)
                } else {
                    // Pinch to check the small print before filing it (B84). Telephoto hands a
                    // horizontal drag to the pager at the page's edge, so swiping is how you
                    // turn pages: it stops at the first and last, and the counter follows. The
                    // arrows that used to sit under the page are gone (B86) -- as move buttons
                    // they read as next/previous, and as next/previous they repeated the swipe.
                    val shown = remember(bitmap) { bitmap.asImageBitmap() }
                    ZoomablePage(
                        bitmap = shown,
                        description = "Page ${index + 1}",
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        // How this page is rendered, per page rather than per session: a batch is often one
        // printed page and one handwritten receipt, and one treatment cannot suit both.
        ConnectedChoiceRow(
            options = PageStyle.entries,
            selected = pages.pages[current].style,
            onSelect = { onRestyle(current, it) },
            label = { it.label() },
            describe = { "Render page ${current + 1} in ${it.label()}" },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            // Legible on the editor's black ground (B79): the theme's colours for an
            // unchosen segment are dark text meant for a light surface.
            colors = androidx.compose.material3.ToggleButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
        )

        // The page's tools on an Expressive floating toolbar: a raised strip over the black
        // ground, so the three read as one set of tools for this page rather than as icons
        // loose on a photograph. Always expanded -- there are three tools and nothing to
        // scroll -- and the icons take the toolbar's own content colour.
        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            HorizontalFloatingToolbar(expanded = true) {
                IconButton(
                    onClick = { onRotate(current, false) },
                    modifier = Modifier.semantics {
                        contentDescription = "Turn page ${current + 1} anticlockwise"
                    },
                ) { Icon(Icons.Filled.RotateLeft, contentDescription = null) }
                IconButton(
                    onClick = { onRotate(current, true) },
                    modifier = Modifier.semantics {
                        contentDescription = "Turn page ${current + 1} clockwise"
                    },
                ) { Icon(Icons.Filled.RotateRight, contentDescription = null) }
                IconButton(
                    onClick = { cropping = current },
                    modifier = Modifier.semantics {
                        contentDescription = "Set the corners of page ${current + 1}"
                    },
                ) { Icon(Icons.Filled.Crop, contentDescription = null) }
            }
        }
    }

}

internal fun PageStyle.label(): String = when (this) {
    PageStyle.COLOUR -> "Colour"
    PageStyle.DOCUMENT -> "Document"
    PageStyle.GREYSCALE -> "Greyscale"
}

private sealed interface PageRender {
    data object Working : PageRender
    data object Unreadable : PageRender
    class Ready(val bitmap: android.graphics.Bitmap) : PageRender
}

/**
 * How the page editor draws a page (B79). The app provides the assembler's own rendering, so
 * the editor shows what will be filed; the default is the upright photograph, which is what a
 * screen without the app around it -- a test -- can show.
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
