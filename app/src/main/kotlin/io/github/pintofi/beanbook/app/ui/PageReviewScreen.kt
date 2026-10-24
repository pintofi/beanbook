package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.documents.PageReviewActions
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.PageEdit
import io.github.pintofi.beanbook.core.domain.UndoOffer
import io.github.pintofi.beanbook.core.domain.apply
import io.github.pintofi.beanbook.core.domain.offersUndo
import io.github.pintofi.beanbook.core.domain.shownAfter
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The captured pages for a last look before they are filed: swipe through them, turn, crop,
 * restyle, reorder, remove, with Undo for what loses something.
 *
 * Every change is one [PageEdit] applied to the session's pages through [PageReviewActions], so it
 * can be tested and undone as a value. The screen follows the app's theme; the camera and the crop
 * screen are the dark surfaces.
 *
 * Everything edits the *description* of a page -- its rotation, its corners, how it is rendered. The
 * photograph on disk is never rewritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageReviewScreen(
    pages: CapturedPages,
    fileFor: (String) -> File,
    startAt: Int,
    review: PageReviewActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Finish editing the pages",
) {
    val latest by rememberUpdatedState(pages)
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startAt.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    ) { latest.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var choosingSize by rememberSaveable { mutableStateOf(false) }
    var undo by remember { mutableStateOf<UndoOffer?>(null) }
    var cropping by rememberSaveable { mutableStateOf<Int?>(null) }
    var croppingSource by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = pager.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    fun show(index: Int) {
        scope.launch { pager.animateScrollToPage(index.coerceIn(0, (latest.size - 1).coerceAtLeast(0))) }
    }

    /** Applies one edit to the pages the session holds now; one retry if a capture landed meanwhile. */
    fun edit(edit: PageEdit, message: String? = null) {
        var before = review.current()
        var after = before.apply(edit)
        if (!review.replace(before, after)) {
            before = review.current()
            after = before.apply(edit)
            if (!review.replace(before, after)) return
        }
        val shownBefore = pager.currentPage
        val offer = if (edit.offersUndo) UndoOffer(edit, before, after, shownBefore) else null
        undo = offer
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            // Only once the screen has the new pages: the pager follows a page by its key, so a
            // scroll made against the old arrangement would be moved again when the new one arrives.
            kotlinx.coroutines.withTimeoutOrNull(SETTLE_MS) {
                androidx.compose.runtime.snapshotFlow { latest }.first { it == after }
            }
            pager.scrollToPage(edit.shownAfter(shownBefore, after.size))
            if (message != null) {
                val result = snackbar.showSnackbar(
                    message = message,
                    actionLabel = if (offer != null) "Undo" else null,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed && offer != null) {
                    val current = review.current()
                    val restored = offer.undo(current)
                    if (restored != null && review.replace(current, restored)) {
                        undo = null
                        kotlinx.coroutines.withTimeoutOrNull(SETTLE_MS) {
                            androidx.compose.runtime.snapshotFlow { latest }.first { it == restored }
                        }
                        pager.scrollToPage(offer.shownBefore.coerceIn(0, (restored.size - 1).coerceAtLeast(0)))
                    }
                }
            }
        }
    }

    val croppingPage = cropping?.let { pages.pages.getOrNull(it) }
    if (cropping != null && (croppingPage == null || croppingPage.source != croppingSource)) {
        cropping = null
    }
    if (cropping != null && croppingPage != null) {
        val index = cropping!!
        CropPageScreen(
            file = fileFor(croppingPage.source),
            initial = croppingPage.crop,
            initialRotation = croppingPage.rotation,
            onCropped = { quad, rotation ->
                edit(PageEdit.Crop(index, quad, rotation))
                cropping = null
            },
            onDismiss = { cropping = null },
            modifier = modifier,
        )
        return
    }

    androidx.activity.compose.BackHandler(enabled = true) { onDone() }

    if (choosingSize) {
        PageSizeDialog(
            current = pages.pageSize,
            localPaper = remember { io.github.pintofi.beanbook.app.documents.localPaper() },
            onUse = {
                choosingSize = false
                edit(PageEdit.Resize(it))
            },
            onDismiss = { choosingSize = false },
        )
    }

    val title = if (pages.isEmpty) "Review" else "Page ${shown + 1} of ${pages.size}"
    val titleUnderBar = LocalDensity.current.fontScale >= LARGE_FONT
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { if (!titleUnderBar) ReviewTitle(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onDone,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(
                        onClick = { edit(PageEdit.Remove(shown), "Page ${shown + 1} removed.") },
                        enabled = !pages.isEmpty,
                        modifier = Modifier.semantics { contentDescription = "Remove page ${shown + 1}" },
                    ) { Icon(Icons.Filled.Delete, contentDescription = null) }
                    // Moving a page is in words, in a menu, as well as by dragging on the strip: the
                    // arrows that used to sit under the page read as "next page".
                    androidx.compose.foundation.layout.Box {
                        IconButton(
                            onClick = { menu = true },
                            enabled = !pages.isEmpty,
                            modifier = Modifier.semantics { contentDescription = "More actions for these pages" },
                        ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Move page ${shown + 1} earlier") },
                                enabled = shown > 0,
                                onClick = { menu = false; edit(PageEdit.Move(shown, shown - 1)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Move page ${shown + 1} later") },
                                enabled = shown < pages.size - 1,
                                onClick = { menu = false; edit(PageEdit.Move(shown, shown + 1)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Page size: ${pages.pageSize.label()}") },
                                onClick = { menu = false; choosingSize = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (titleUnderBar) {
                // At a large font the title does not fit beside the bar's icons, and a title is
                // never cut short: it goes on its own line, where it may wrap.
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                )
            }
            if (pages.isEmpty) {
                EmptyReview(onDone)
            } else {
                ReviewBody(
                    pages = pages,
                    fileFor = fileFor,
                    pager = pager,
                    shown = shown,
                    onShow = ::show,
                    onEdit = { e, m -> edit(e, m) },
                    onCrop = {
                        croppingSource = pages.pages[shown].source
                        cropping = shown
                    },
                    onDone = onDone,
                )
            }
        }
    }
}

@Composable
private fun ReviewTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun EmptyReview(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No pages left.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onDone, enabled = false, modifier = Modifier.padding(top = 16.dp)) {
            Text("Finish with 0 pages")
        }
    }
}

/** The pager and the controls under it, laid out for how much room there is. */
@Composable
private fun ReviewBody(
    pages: CapturedPages,
    fileFor: (String) -> File,
    pager: androidx.compose.foundation.pager.PagerState,
    shown: Int,
    onShow: (Int) -> Unit,
    onEdit: (PageEdit, String?) -> Unit,
    onCrop: () -> Unit,
    onDone: () -> Unit,
) {
    val controls: @Composable () -> Unit = {
        StyleSection(
            shown = shown,
            pages = pages,
            onRestyle = { onEdit(PageEdit.Restyle(shown, it), null) },
            onRestyleAll = {
                val style = pages.pages[shown].style
                onEdit(PageEdit.RestyleAll(style), "Every page is now ${style.label()}.")
            },
        )
        PageTools(shown = shown, onTurn = { onEdit(PageEdit.Rotate(shown, it), null) }, onCrop = onCrop)
        ReviewStrip(
            pages = pages,
            fileFor = fileFor,
            shown = shown,
            onShow = onShow,
            onMove = { from, to -> onEdit(PageEdit.Move(from, to), null) },
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Button(onClick = onDone) {
                Text(if (pages.size == 1) "Finish with 1 page" else "Finish with ${pages.size} pages")
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE && maxWidth > maxHeight
        val short = !wide && maxHeight < SHORT
        val shortPagerHeight = maxOf(MIN_PAGER.dp, maxHeight * SHORT_SHARE)
        when {
            wide -> Row(Modifier.fillMaxSize()) {
                ReviewPager(pager, pages, fileFor, Modifier.weight(1f).fillMaxSize())
                Column(Modifier.width(CONTROLS_WIDTH.dp).verticalScroll(rememberScrollState())) { controls() }
            }

            short -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ReviewPager(pager, pages, fileFor, Modifier.fillMaxWidth().height(shortPagerHeight))
                controls()
            }

            else -> Column(Modifier.fillMaxSize()) {
                ReviewPager(pager, pages, fileFor, Modifier.weight(1f).fillMaxWidth())
                controls()
            }
        }
    }
}

/** At or above this font scale the title leaves the bar. */
private const val LARGE_FONT = 1.5f
private const val SETTLE_MS = 1_000L
private val WIDE = 600.dp
private val SHORT = 560.dp
private const val CONTROLS_WIDTH = 360
private const val MIN_PAGER = 240
private const val SHORT_SHARE = 0.6f
