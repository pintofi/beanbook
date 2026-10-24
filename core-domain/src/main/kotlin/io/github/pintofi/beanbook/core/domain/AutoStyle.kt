package io.github.pintofi.beanbook.core.domain

/**
 * How much of a page is clearly coloured, counted on a small copy after the paper has been made
 * white (so a lamp's cast and cream paper do not count): [coloured] of the [total] pixels have a
 * chroma well above noise and a lightness that is neither ink nor paper.
 */
data class ColourStats(val total: Int, val counted: Int, val coloured: Int)

/**
 * The style a page gets when the person has not chosen one. Integers only.
 *
 * Never black and white, whose threshold is the riskiest for faint thermal print, and never the
 * untouched photograph: Automatic chooses between a colour scan and a grey one.
 */
object AutoStyle {

    /** The share of the page that must be coloured, in hundredths of a percent: 0.3%. */
    const val COLOUR_PER_TEN_THOUSAND = 30

    /** And at least this many pixels, at the 256 px the statistics are taken at: about 6 x 6. */
    const val MIN_COLOURED_PIXELS = 40

    fun choose(stats: ColourStats): PageStyle =
        if (stats.coloured >= MIN_COLOURED_PIXELS &&
            stats.coloured.toLong() * TEN_THOUSAND >= stats.total.toLong() * COLOUR_PER_TEN_THOUSAND
        ) {
            PageStyle.DOCUMENT
        } else {
            PageStyle.GREYSCALE
        }

    private const val TEN_THOUSAND = 10_000L
}
