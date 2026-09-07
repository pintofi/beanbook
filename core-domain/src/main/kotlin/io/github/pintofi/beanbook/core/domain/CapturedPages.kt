package io.github.pintofi.beanbook.core.domain

/**
 * One captured page, before it becomes part of a PDF.
 *
 * [source] identifies the original image and never changes: rotating or reordering a page
 * edits this description, not the file the camera wrote. The original is preserved until the
 * user explicitly discards it (SPEC.md, J3).
 */
/**
 * How a page is rendered (B58).
 *
 * A domain enum rather than the scanner module's own: `:core-domain` is pure Kotlin and must
 * not learn about OpenCV, and the page model is what the PDF assembler reads. The app maps
 * this to the scanner's `ScanStyle` where the pixels are actually touched.
 */
enum class PageStyle {
    /** The photograph as taken. */
    COLOUR,

    /** Thresholded to near-black text on near-white paper, which is what a scan looks like. */
    DOCUMENT,

    /** Grey, for a page whose colour carries nothing but takes space. */
    GREYSCALE,
}

data class CapturedPage(
    val source: String,
    /** Clockwise, in degrees; always one of 0, 90, 180, 270. */
    val rotation: Int = 0,
    /**
     * The page's four corners within the photograph, or `null` for the whole image.
     *
     * Kept as corners rather than as an already-cropped file so the capture on disk stays the
     * one the camera produced. A crop is then a decision the user can revisit or undo, and a
     * mistaken drag never destroys the original.
     */
    val crop: CropQuad? = null,
    /**
     * How this page is rendered when the document is assembled (B58).
     *
     * Per page, not per session: a batch is often one printed page and one handwritten
     * receipt, and forcing both through the same treatment makes one of them unreadable.
     * Recorded rather than baked in, for the reason [crop] is: the capture on disk stays the
     * photograph the camera produced.
     */
    val style: PageStyle = PageStyle.COLOUR,
) {
    init {
        require(rotation in ALLOWED_ROTATIONS) { "rotation must be a quarter turn, got $rotation" }
    }

    /** True when this page has been cropped to something other than the whole image. */
    val isCropped: Boolean get() = crop != null

    fun croppedTo(quad: CropQuad?): CapturedPage = copy(crop = quad)

    fun rotatedClockwise(): CapturedPage = copy(rotation = (rotation + 90) % 360)

    fun rotatedAnticlockwise(): CapturedPage = copy(rotation = (rotation + 270) % 360)

    fun styledAs(style: PageStyle): CapturedPage = copy(style = style)

    private companion object {
        val ALLOWED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

/**
 * The pages of a document being assembled, in the order they will appear.
 *
 * Kept in `:core-domain` and free of Android so the fiddly part — what happens to the order
 * when a page moves or is removed — can be tested without a camera. Every operation returns
 * a new list rather than mutating in place, so an undo is a matter of keeping the previous
 * value.
 */
data class CapturedPages(val pages: List<CapturedPage> = emptyList()) {

    val size: Int get() = pages.size

    val isEmpty: Boolean get() = pages.isEmpty()

    fun add(source: String, style: PageStyle = PageStyle.COLOUR): CapturedPages =
        CapturedPages(pages + CapturedPage(source, style = style))

    fun rotate(index: Int, clockwise: Boolean = true): CapturedPages =
        replace(index) { if (clockwise) it.rotatedClockwise() else it.rotatedAnticlockwise() }

    /**
     * Moves the page at [from] so that it lands at [to], sliding the rest along — what
     * dragging a page in a list does. Out-of-range positions are clamped rather than
     * throwing: a drag that overshoots the end of the list means "put it last".
     */
    fun move(from: Int, to: Int): CapturedPages {
        if (from !in pages.indices) return this
        val target = to.coerceIn(0, pages.size - 1)
        if (from == target) return this
        val remaining = pages.toMutableList()
        val moved = remaining.removeAt(from)
        remaining.add(target, moved)
        return CapturedPages(remaining)
    }

    /** Removes one page from the document being assembled. */
    fun remove(index: Int): CapturedPages =
        if (index in pages.indices) CapturedPages(pages.filterIndexed { i, _ -> i != index })
        else this

    /** The pages with one page rendered differently (B58). */
    fun restyle(index: Int, style: PageStyle): CapturedPages = replace(index) { it.styledAs(style) }

    /** The pages with one page's crop set, or cleared when [quad] is null. */
    fun crop(index: Int, quad: CropQuad?): CapturedPages = replace(index) { it.croppedTo(quad) }

    private fun replace(index: Int, transform: (CapturedPage) -> CapturedPage): CapturedPages =
        if (index in pages.indices) {
            CapturedPages(pages.mapIndexed { i, page -> if (i == index) transform(page) else page })
        } else {
            this
        }
}
