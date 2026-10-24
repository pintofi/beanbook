package io.github.pintofi.beanbook.core.domain

/** The paper a document's pages are laid out on (#184). */
enum class PageSize(val portraitWidth: Int, val portraitHeight: Int) {
    /** Each page the shape it was photographed, at 200 dpi. */
    AS_SCANNED(0, 0),

    /** 210 x 297 mm. */
    A4(59528, 84189),

    /** 8.5 x 11 in. */
    LETTER(61200, 79200),
}

/**
 * Where a page's picture sits on its sheet, in hundredths of a PDF point from the bottom-left
 * corner. Integers all the way, so the same pages always make the same bytes.
 */
data class PagePlacement(
    val pageWidth: Int,
    val pageHeight: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

object PageLayout {
    /** Hundredths of a point per pixel at 200 dpi: 7200 / 200. */
    const val HUNDREDTHS_PER_PIXEL = 36

    /** 5 mm. */
    const val MARGIN = 1417

    /** A picture is never drawn larger than twice its natural size (100 dpi). */
    const val MAX_ENLARGEMENT = 2

    /**
     * Fits a [widthPx] x [heightPx] page on [size] without stretching it: landscape paper for a
     * wider-than-tall page, a 5 mm margin all round, never enlarged past twice its natural size,
     * and centred. As scanned is the natural size at the origin.
     */
    fun place(size: PageSize, widthPx: Int, heightPx: Int): PagePlacement {
        require(widthPx > 0 && heightPx > 0) { "a page needs a size, got ${widthPx}x$heightPx" }
        val naturalWidth = widthPx * HUNDREDTHS_PER_PIXEL
        val naturalHeight = heightPx * HUNDREDTHS_PER_PIXEL
        if (size == PageSize.AS_SCANNED) {
            return PagePlacement(naturalWidth, naturalHeight, 0, 0, naturalWidth, naturalHeight)
        }

        val landscape = widthPx > heightPx
        val paperWidth = if (landscape) size.portraitHeight else size.portraitWidth
        val paperHeight = if (landscape) size.portraitWidth else size.portraitHeight
        val availableWidth = paperWidth - 2 * MARGIN
        val availableHeight = paperHeight - 2 * MARGIN

        var width: Int
        var height: Int
        if (widthPx.toLong() * availableHeight <= heightPx.toLong() * availableWidth) {
            height = availableHeight
            width = scaled(availableHeight, widthPx, heightPx)
        } else {
            width = availableWidth
            height = scaled(availableWidth, heightPx, widthPx)
        }
        if (width > MAX_ENLARGEMENT * naturalWidth) {
            width = MAX_ENLARGEMENT * naturalWidth
            height = MAX_ENLARGEMENT * naturalHeight
        }
        val x = (paperWidth - width) / 2
        val y = (paperHeight - height) / 2
        return PagePlacement(paperWidth, paperHeight, x, y, width, height)
    }

    /** The order the choice is offered in: as scanned first, then the local paper, then the other. */
    fun choices(localPaper: PageSize): List<PageSize> {
        require(localPaper != PageSize.AS_SCANNED) { "the local paper is A4 or Letter" }
        val other = if (localPaper == PageSize.A4) PageSize.LETTER else PageSize.A4
        return listOf(PageSize.AS_SCANNED, localPaper, other)
    }

    /** round(a * b / c) in integers. */
    private fun scaled(a: Int, b: Int, c: Int): Int = ((a.toLong() * b + c / 2) / c).toInt()
}
