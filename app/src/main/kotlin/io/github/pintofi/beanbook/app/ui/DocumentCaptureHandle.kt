package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.scanner.ScanStyle
import java.io.File

/**
 * Everything a screen needs to photograph pages and file them as a document.
 *
 * One parameter rather than seven. Both the documents screen and transaction entry need the
 * same set, and threading them separately through MainActivity is what makes detekt's
 * LargeClass bite on it — the rule is doing its job there, not being pedantic.
 *
 * Null where a screen is rendered without a camera, which is how the accessibility tests
 * exercise these surfaces on a device that has none.
 */
data class DocumentCaptureHandle(
    val pages: CapturedPages,
    val captureFile: () -> File,
    /**
     * Called with the file a page was written to.
     *
     * It took a `(Boolean) -> Unit` completion callback until 2026-09-05, which nothing
     * ever called: the implementation decides success from the file itself, and both
     * call sites passed a lambda that ignored the argument.
     */
    val onCapture: (File) -> Unit,
    /** True while the fullscreen scanner owns the window. */
    val scanning: Boolean = false,
    val onStartScanning: () -> Unit = {},
    val onStopScanning: () -> Unit = {},
    /**
     * A page the scanner captured, with the outline it found and the style chosen.
     *
     * Separate from [onCapture] because the scanner already knows the crop — it detected it
     * to draw the outline — and re-detecting on the other side of the callback would analyse
     * the same photograph twice.
     */
    val onScanned: (File, CropQuad?, ScanStyle) -> Unit = { _, _, _ -> },
    val onRotate: (Int) -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onRemovePage: (Int) -> Unit,
    /**
     * Assembles the captured pages, files them under [account], and calls back with the
     * project-relative path.
     *
     * The file is durably in place before the callback runs, so a caller writing a link to
     * it cannot name a file that does not exist yet (invariant 5).
     */
    val onFile: (account: String, label: String?, onFiled: (List<String>) -> Unit) -> Unit,
    /** Turns one page either way. The single-argument [onRotate] is the clockwise case. */
    val onRotateBy: (index: Int, clockwise: Boolean) -> Unit = { _, _ -> },
    /** Renders one page in a different style. */
    val onRestyle: (index: Int, style: io.github.pintofi.beanbook.core.domain.PageStyle) -> Unit =
        { _, _ -> },
    /** How the review screen reads and changes the pages, one edit at a time with Undo. */
    val review: io.github.pintofi.beanbook.app.documents.PageReviewActions =
        io.github.pintofi.beanbook.app.documents.PageReviewActions.None,
    /** Sets or clears one page's crop corners. */
    val onCrop: (index: Int, quad: io.github.pintofi.beanbook.core.domain.CropQuad?) -> Unit = { _, _ -> },
    /**
     * What the pages and files will be filed as under an account, relative to the project, before they
     * are: what the entry form's preview names in its `document:` lines. Empty when there
     * is nothing to file.
     */
    val plannedPaths: suspend (account: String, label: String?) -> List<String> = { _, _ -> emptyList() },
    /** Files attached as they are, by the name they had. */
    val attachedFiles: kotlinx.coroutines.flow.StateFlow<List<String>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
    /** Attaches a file the person picked. */
    val onAttachFile: (android.net.Uri) -> Unit = {},
    val onRemoveFile: (Int) -> Unit = {},
    /** The capture on disk for a page, so it can be shown for cropping. */
    val fileFor: (String) -> File = { File(it) },
)
