package io.github.pintofi.beanbook.core.domain

import kotlin.math.pow
import kotlin.math.roundToInt

/** Reading a 256-bin grey histogram. */
object Percentiles {

    /**
     * The grey level below which [fraction] of the pixels lie: the first bin whose running total
     * reaches that share. Fraction 0 gives the first non-empty bin and 1 the last. An empty
     * histogram gives 0.
     */
    fun at(hist: IntArray, fraction: Float): Int {
        val total = hist.sumOf { it.toLong() }
        if (total == 0L) return 0
        if (fraction <= 0f) return hist.indexOfFirst { it > 0 }
        if (fraction >= 1f) return hist.indexOfLast { it > 0 }
        val wanted = (total * fraction.toDouble()).toLong().coerceAtLeast(1)
        var running = 0L
        for (level in hist.indices) {
            running += hist[level]
            if (running >= wanted) return level
        }
        return hist.lastIndex
    }

    /** The fullest bin among [from]..[to], the paper's own peak when the page is mostly ink. */
    fun mode(hist: IntArray, from: Int, to: Int): Int {
        var best = from
        for (level in from..to) if (hist[level] > hist[best]) best = level
        return best
    }
}

/**
 * The tone curve that takes a flattened page to white paper and dark ink (#181), as a 256-entry
 * look-up table applied identically at every size: it is computed from a fixed-size copy, so the
 * preview and the PDF of one page get the same curve.
 */
object ToneCurve {

    /** Below this spread between ink and paper the page is blank or flat, and is not stretched. */
    const val MIN_SPREAD = 40

    /** The black point never sits above this, so a nearly blank receipt does not turn paper into ink. */
    const val MAX_BLACK = 128

    /** How far under the paper peak white is set. */
    const val WHITE_MARGIN = 4

    /** The paper peak is looked for between here and white. */
    const val PAPER_FROM = 128

    /** The curve for a page with histogram [hist], darkening mid-greys by [gamma] (above 1 darkens). */
    fun lut(hist: IntArray, gamma: Float): IntArray {
        require(hist.size == LEVELS) { "a histogram has $LEVELS bins" }
        val black = minOf(Percentiles.at(hist, 0.005f), MAX_BLACK)
        // A little under the paper's peak: paper grain on both sides of the peak then reaches white together
        // in every channel, and the noisier channel does not leave a tint on the paper.
        val peak = maxOf(Percentiles.mode(hist, PAPER_FROM, LEVELS - 1), Percentiles.at(hist, 0.60f))
        val white = (peak - WHITE_MARGIN).coerceAtLeast(black + 1)
        if (white - black < MIN_SPREAD) {
            // Blank or very low contrast: a linear lift of [black, white] to [black, 255], never a stretch of noise.
            return IntArray(LEVELS) { v ->
                when {
                    v <= black -> v
                    v >= white -> 255
                    else -> (black + (v - black) * (255 - black) / (white - black).coerceAtLeast(1))
                }
            }
        }
        return IntArray(LEVELS) { v ->
            when {
                v <= black -> 0
                v >= white -> 255
                else -> (255.0 * ((v - black).toDouble() / (white - black)).pow(gamma.toDouble())).roundToInt()
            }
        }
    }

    private const val LEVELS = 256
}

/** How big the filters are for a page whose shorter side (in the fixed working copy) is [short] pixels. */
object KernelSizes {

    /** The kernel that erases ink while keeping the paper: about 1/24 of the short side. */
    fun inkEraser(short: Int): Int = odd((short / 24).coerceIn(5, 31))

    /** The median that smooths the erased page into lighting: about 1/8 of the short side. */
    fun lightingMedian(short: Int): Int = odd((short / 8).coerceIn(15, 63))

    /** The window the black-and-white threshold looks around each pixel in, on a quarter-size copy. */
    fun sauvolaWindow(short: Int): Int = odd(maxOf(15, short / 6))

    /** The smallest odd number that is at least [value]. */
    fun odd(value: Int): Int = if (value % 2 == 0) value + 1 else value
}
