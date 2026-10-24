package io.github.pintofi.beanbook.core.domain

/** One change a person makes to the pages being reviewed. */
sealed interface PageEdit {
    data class Rotate(val index: Int, val clockwise: Boolean) : PageEdit

    data class Restyle(val index: Int, val style: PageStyle) : PageEdit

    /** Apply to all: every page takes [style]. */
    data class RestyleAll(val style: PageStyle) : PageEdit

    /** The corners and the turn the crop screen ended with. [rotation] is the page's turn afterwards. */
    data class Crop(val index: Int, val quad: CropQuad?, val rotation: Int) : PageEdit

    data class Move(val from: Int, val to: Int) : PageEdit

    data class Remove(val index: Int) : PageEdit

    /** A new photograph for the page at [index], if that page is still [expectedSource]. */
    data class Retake(val index: Int, val expectedSource: String, val source: String, val crop: CropQuad?) : PageEdit

    /** The paper the PDF is laid out on (#184). */
    data class Resize(val size: PageSize) : PageEdit
}

/** The pages after [edit]. An edit that names a page that is not there changes nothing. */
fun CapturedPages.apply(edit: PageEdit): CapturedPages = when (edit) {
    is PageEdit.Rotate -> rotate(edit.index, edit.clockwise)
    is PageEdit.Restyle -> restyle(edit.index, edit.style)
    is PageEdit.RestyleAll -> restyleAll(edit.style)
    is PageEdit.Crop -> crop(edit.index, edit.quad).turnedTo(edit.index, edit.rotation)
    is PageEdit.Move -> move(edit.from, edit.to)
    is PageEdit.Remove -> remove(edit.index)
    is PageEdit.Retake -> retake(edit.index, edit.expectedSource, edit.source, edit.crop)
    is PageEdit.Resize -> resized(edit.size)
}

private fun CapturedPages.turnedTo(index: Int, rotation: Int): CapturedPages {
    val page = pages.getOrNull(index) ?: return this
    if (page.rotation == rotation) return this
    return copy(pages = pages.mapIndexed { i, p -> if (i == index) p.copy(rotation = rotation) else p })
}

/** Which page the review should show after [this] edit, given the one shown before and how many pages are left. */
fun PageEdit.shownAfter(shown: Int, sizeAfter: Int): Int {
    val wanted = when (this) {
        is PageEdit.Move -> to
        is PageEdit.Remove -> if (index < shown) shown - 1 else shown
        is PageEdit.Retake -> if (index in 0 until sizeAfter) index else sizeAfter - 1
        else -> shown
    }
    return wanted.coerceIn(0, (sizeAfter - 1).coerceAtLeast(0))
}

/** Whether the screen offers Undo for this edit: the ones that lose something. */
val PageEdit.offersUndo: Boolean
    get() = this is PageEdit.Remove || this is PageEdit.RestyleAll || this is PageEdit.Retake

/**
 * An Undo that is still good. It restores [before] only while the pages are still exactly
 * [after]: any other change since (a capture arriving, another edit) voids it, so Undo can never
 * undo more than the one thing it was offered for.
 */
data class UndoOffer(
    val edit: PageEdit,
    val before: CapturedPages,
    val after: CapturedPages,
    val shownBefore: Int,
) {
    fun undo(current: CapturedPages): CapturedPages? = if (current == after) before else null
}
