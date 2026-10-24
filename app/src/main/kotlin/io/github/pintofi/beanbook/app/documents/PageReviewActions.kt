package io.github.pintofi.beanbook.app.documents

import io.github.pintofi.beanbook.core.domain.CapturedPages

/**
 * What the review screen needs from the capture session. The pages stay in [DocumentCapture]; the
 * screen reads them through [current] and writes an edit back with [replace], which refuses when the
 * pages changed in between (a capture arriving, say) so an edit is never applied to pages it was not
 * computed from.
 */
class PageReviewActions(
    val current: () -> CapturedPages,
    val replace: (expected: CapturedPages, next: CapturedPages) -> Boolean,
) {
    companion object {
        /** For a screen shown with no capture behind it: nothing can be changed. */
        val None = PageReviewActions({ CapturedPages() }, { _, _ -> false })
    }
}

/** The review screen's view of this session. */
fun DocumentCapture.reviewActions(): PageReviewActions = PageReviewActions(
    current = { pages.value },
    replace = ::replace,
)
