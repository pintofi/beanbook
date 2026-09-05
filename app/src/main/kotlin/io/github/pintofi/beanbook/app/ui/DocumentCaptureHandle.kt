package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.CapturedPages
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
    val onCapture: (File, (Boolean) -> Unit) -> Unit,
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
    val onFile: (account: String, onFiled: (String) -> Unit) -> Unit,
    /** Sets or clears one page's crop corners (B31). */
    val onCrop: (index: Int, quad: io.github.pintofi.beanbook.core.domain.CropQuad?) -> Unit = { _, _ -> },
    /** The capture on disk for a page, so it can be shown for cropping. */
    val fileFor: (String) -> File = { File(it) },
)
