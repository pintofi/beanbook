package io.github.pintofi.beanbook.core.domain

/**
 * How a page is rendered.
 *
 * A domain enum rather than the scanner module's own: `:core-domain` is pure Kotlin and must
 * not learn about OpenCV, and the page model is what the PDF assembler reads. The app maps
 * this to the scanner's `ScanStyle` where the pixels are actually touched.
 */
enum class PageStyle {
    /** The photograph as taken. */
    COLOUR,

    /**
     * Flattened colour: the lighting is divided out, so the paper goes white and the ink dark while
     * stamps and highlights keep their colour. What a scanner means by a scan.
     */
    DOCUMENT,

    /** Flattened grey, for a page whose colour carries nothing (a thermal receipt), and faint print. */
    GREYSCALE,

    /** Pure black on white, from the grey scan. For clean print; faint or thin print can break up. */
    BLACK_AND_WHITE,
}

/**
 * One captured page, before it becomes part of a PDF.
 *
 * [source] identifies the original image and never changes: rotating or reordering a page
 * edits this description, not the file the camera wrote. The original is preserved until the
 * user explicitly discards it.
 */
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
     * How this page is rendered when the document is assembled.
     *
     * Per page, not per session: a batch is often one printed page and one handwritten
     * receipt, and forcing both through the same treatment makes one of them unreadable.
     * Recorded rather than baked in, for the reason [crop] is: the capture on disk stays the
     * photograph the camera produced.
     */
    val style: PageStyle = PageStyle.COLOUR,
    /**
     * Whether [style] was chosen by the scanner rather than by the person. A manual choice is final,
     * so [styledAs] clears this; a crop re-runs the choice only while it is still set.
     */
    val automaticStyle: Boolean = false,
) {
    init {
        require(rotation in ALLOWED_ROTATIONS) { "rotation must be a quarter turn, got $rotation" }
    }

    fun croppedTo(quad: CropQuad?): CapturedPage = copy(crop = quad)

    fun rotatedClockwise(): CapturedPage = copy(rotation = (rotation + 90) % 360)

    fun rotatedAnticlockwise(): CapturedPage = copy(rotation = (rotation + 270) % 360)

    fun styledAs(style: PageStyle): CapturedPage = copy(style = style, automaticStyle = false)

    /** The scanner's own choice of style, which a later crop may choose again. */
    fun styledAutomatically(style: PageStyle): CapturedPage = copy(style = style, automaticStyle = true)

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
data class CapturedPages(
    val pages: List<CapturedPage> = emptyList(),
    /** The paper the document's pages are laid out on (#184). One choice for the whole document. */
    val pageSize: PageSize = PageSize.AS_SCANNED,
) {

    val size: Int get() = pages.size

    val isEmpty: Boolean get() = pages.isEmpty()

    fun add(source: String, style: PageStyle = PageStyle.COLOUR, automatic: Boolean = false): CapturedPages =
        copy(pages = pages + CapturedPage(source, style = style, automaticStyle = automatic))

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
        return copy(pages = remaining)
    }

    /** Removes one page from the document being assembled. */
    fun remove(index: Int): CapturedPages =
        if (index in pages.indices) copy(pages = pages.filterIndexed { i, _ -> i != index })
        else this

    /** One page given the scanner's own choice of style, which stays open to being chosen again. */
    fun restyleAutomatically(index: Int, style: PageStyle): CapturedPages =
        replace(index) { it.styledAutomatically(style) }

    /** The pages with one page rendered differently. */
    fun restyle(index: Int, style: PageStyle): CapturedPages = replace(index) { it.styledAs(style) }

    /** The pages with one page's crop set, or cleared when [quad] is null. */
    fun crop(index: Int, quad: CropQuad?): CapturedPages = replace(index) { it.croppedTo(quad) }

    /** Every page rendered the same way: Apply to all. */
    fun restyleAll(style: PageStyle): CapturedPages = copy(pages = pages.map { it.styledAs(style) })

    /** The paper the pages are laid out on. */
    fun resized(size: PageSize): CapturedPages = copy(pageSize = size)

    /**
     * A new photograph for the page at [index]: its source and crop are replaced, its rotation
     * starts again from 0 (the new photo is upright as taken) and its style is kept.
     *
     * If the page there is no longer [expectedSource] -- it was moved or removed while the camera
     * was open -- the photograph is added as a new page instead, so it is never lost.
     */
    fun retake(index: Int, expectedSource: String, source: String, crop: CropQuad?): CapturedPages {
        val page = pages.getOrNull(index)
        if (page == null || page.source != expectedSource) {
            val added = add(source)
            return added.crop(added.pages.lastIndex, crop)
        }
        return replace(index) { it.copy(source = source, rotation = 0, crop = crop) }
    }

    private fun replace(index: Int, transform: (CapturedPage) -> CapturedPage): CapturedPages =
        if (index in pages.indices) {
            copy(pages = pages.mapIndexed { i, page -> if (i == index) transform(page) else page })
        } else {
            this
        }
}
