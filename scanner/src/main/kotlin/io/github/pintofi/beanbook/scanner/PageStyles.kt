package io.github.pintofi.beanbook.scanner

import io.github.pintofi.beanbook.core.domain.AutoStyle
import io.github.pintofi.beanbook.core.domain.ColourStats
import io.github.pintofi.beanbook.core.domain.KernelSizes
import io.github.pintofi.beanbook.core.domain.PageStyle
import io.github.pintofi.beanbook.core.domain.Percentiles
import io.github.pintofi.beanbook.core.domain.ToneCurve
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * How a straightened page is rendered in each style (#181).
 *
 * A photograph of a page is never evenly lit. The fix is to estimate the lighting and divide it out,
 * channel by channel: paper goes to white, ink stays dark in proportion, and a stamp or a highlighter
 * keeps its colour because it is darker than the paper in some channel only. The lighting is
 * estimated on a fixed 512 px copy, so a 1600 px preview and a 2400 px PDF of one page get the same
 * lighting and the same tone curve and look the same.
 *
 * The estimate and the tone curve are written from the published ideas (morphological background
 * estimation, normalised convolution, Sauvola's local threshold), not copied from any scanner.
 */
internal object PageStyles {

    /** The page in [style]; [page] is RGBA. Returns [page] itself for the untouched photograph. */
    fun apply(page: Mat, style: ScanStyle): Mat = when (style) {
        ScanStyle.COLOUR -> page
        ScanStyle.DOCUMENT -> document(page)
        ScanStyle.GREYSCALE -> greyscale(page)
        ScanStyle.BLACK_AND_WHITE -> blackAndWhite(page)
    }

    // --- lighting -------------------------------------------------------------------------------

    /** The lighting of one channel, at the working size and at the channel's own size. */
    private class Lighting(val small: Mat, val smallLight: Mat, val full: Mat) {
        fun release() {
            small.release(); smallLight.release(); full.release()
        }
    }

    /**
     * The light falling on [channel] (8-bit, one channel), or null for a dark page, which cannot be
     * flattened: a navy statement divided by its own colour would come out white.
     */
    private fun lighting(channel: Mat, wantFull: Boolean = true): Lighting? {
        val small = Mat()
        val longEdge = maxOf(channel.cols(), channel.rows())
        if (longEdge > WORK_EDGE) {
            val s = WORK_EDGE.toDouble() / longEdge
            Imgproc.resize(channel, small, Size(channel.cols() * s, channel.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            channel.copyTo(small)
        }
        val short = minOf(small.cols(), small.rows())

        val light = Mat()
        // Sensor noise first. Dilation keeps the brightest pixel of each neighbourhood, so on noise it
        // reads the top of the noise as the light, and the noisiest channel (blue) comes out too bright:
        // dividing by it leaves a yellow cast. A small median takes the speckle out before it can.
        val calm = Mat()
        Imgproc.medianBlur(small, calm, NOISE_MEDIAN)
        // Erase the ink: a closing with a kernel wider than a stroke, so what is left is the paper and its
        // light. A closing (dilate, then erode) and not a dilation alone: along a gradient of light a
        // dilation reads the brighter side of every window, a bias that differs by channel when the light
        // is coloured and leaves a cast; the erosion puts the level back.
        val eraser = KernelSizes.inkEraser(short)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(eraser.toDouble(), eraser.toDouble()))
        Imgproc.morphologyEx(calm, light, Imgproc.MORPH_CLOSE, kernel)
        calm.release()
        kernel.release()
        Imgproc.medianBlur(light, light, KernelSizes.lightingMedian(short))
        Imgproc.GaussianBlur(light, light, Size(0.0, 0.0), short / SMOOTH_DIVISOR)

        val paper = Percentiles.at(histogram(light), PAPER_PERCENTILE)
        if (paper < DARK_PAGE) {
            small.release(); light.release()
            return null
        }
        fillDarkAreas(light, paper, short)

        val full = Mat()
        if (wantFull) Imgproc.resize(light, full, channel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        return Lighting(small, light, full)
    }

    /**
     * Large dark areas (a black header bar, a logo) survive the dilation as "lighting", and dividing
     * by them would erase them. They are refilled from the paper around them, by normalised
     * convolution: blur the lighting with the dark areas masked out, and divide by the blurred mask.
     */
    private fun fillDarkAreas(light: Mat, paper: Int, short: Int) {
        val dark = Mat()
        Core.compare(light, Scalar(DARK_FRACTION * paper), dark, Core.CMP_LT)
        try {
            if (Core.countNonZero(dark) == 0) return
            val keep = Mat()
            val keepF = Mat()
            val lightF = Mat()
            Core.bitwise_not(dark, keep)
            keep.convertTo(keepF, CvType.CV_32F, 1.0 / 255.0)
            light.convertTo(lightF, CvType.CV_32F)
            Core.multiply(lightF, keepF, lightF)
            val sigma = short / FILL_DIVISOR
            Imgproc.GaussianBlur(lightF, lightF, Size(0.0, 0.0), sigma)
            Imgproc.GaussianBlur(keepF, keepF, Size(0.0, 0.0), sigma)
            Core.max(keepF, Scalar(MIN_WEIGHT), keepF)
            Core.divide(lightF, keepF, lightF)
            val filled = Mat()
            lightF.convertTo(filled, CvType.CV_8U)
            filled.copyTo(light, dark)
            keep.release(); keepF.release(); lightF.release(); filled.release()
        } finally {
            dark.release()
        }
    }

    /** [channel] divided by its [light], so the light itself comes out at 255. */
    private fun flatten(channel: Mat, light: Mat): Mat {
        val safe = Mat()
        val out = Mat()
        Core.max(light, Scalar(1.0), safe)
        Core.divide(channel, safe, out, 255.0)
        safe.release()
        return out
    }

    private fun histogram(image: Mat): IntArray {
        val hist = Mat()
        Imgproc.calcHist(
            listOf(image), MatOfInt(0), Mat(), hist, MatOfInt(HIST_BINS), MatOfFloat(0f, HIST_BINS.toFloat()),
        )
        val counts = FloatArray(HIST_BINS)
        hist.get(0, 0, counts)
        hist.release()
        return IntArray(HIST_BINS) { counts[it].toInt() }
    }

    private fun applyCurve(image: Mat, hist: IntArray, gamma: Float): Mat {
        val lut = ToneCurve.lut(hist, gamma)
        val table = Mat(1, HIST_BINS, CvType.CV_8U)
        table.put(0, 0, ByteArray(HIST_BINS) { lut[it].toByte() })
        val out = Mat()
        Core.LUT(image, table, out)
        table.release()
        return out
    }

    // --- the styles -----------------------------------------------------------------------------

    private fun greyscale(page: Mat): Mat {
        val grey = Mat()
        Imgproc.cvtColor(page, grey, Imgproc.COLOR_RGBA2GRAY)
        val light = lighting(grey)
        val flat: Mat
        val hist: IntArray
        if (light == null) {
            flat = grey
            hist = histogram(grey)
        } else {
            flat = flatten(grey, light.full)
            val flatSmall = flatten(light.small, light.smallLight)
            hist = histogram(flatSmall)
            flatSmall.release(); light.release(); grey.release()
        }
        val out = applyCurve(flat, hist, GREY_GAMMA)
        flat.release()
        return out
    }

    private fun document(page: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(page, rgb, Imgproc.COLOR_RGBA2RGB)
        val flat = flattenedColour(rgb) ?: rgb.also { }
        val hist = histogram(greyOf(downscale(flat, WORK_EDGE)))
        val curved = applyCurve(flat, hist, COLOUR_GAMMA)
        val toned = smoothColour(curved)
        curved.release()
        val out = Mat()
        Imgproc.cvtColor(toned, out, Imgproc.COLOR_RGB2RGBA)
        toned.release()
        if (flat !== rgb) flat.release()
        rgb.release()
        return out
    }

    /**
     * The colour page flattened, or null when it is dark.
     *
     * The *brightness* of the light is estimated once, on the grey page, where the three channels'
     * sensor noise averages out. What differs between the channels is the colour of the light, and
     * that varies slowly across a page (a window on one side, a lamp on the other). It is measured as
     * each channel's share of the grey light over the paper pixels only (not the ink), averaged over a
     * wide window: an average, where the brightest-pixel estimate used for the brightness reads the
     * top of the noise, and the noisiest channel (blue) came out too bright, which left patches of
     * yellow on the paper.
     */
    private fun flattenedColour(rgb: Mat): Mat? {
        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        val grey = greyOf(rgb)
        val greyLight = lighting(grey, wantFull = false)
        try {
            if (greyLight == null) return null
            val small = greyLight.small
            val short = minOf(small.cols(), small.rows())
            val sigma = short / TINT_DIVISOR
            val lightSmall = greyLight.smallLight

            // The paper: pixels about as bright as the light there.
            val paper = Mat()
            val limit = Mat()
            lightSmall.convertTo(limit, CvType.CV_8U, PAPER_SHARE)
            Core.compare(small, limit, paper, Core.CMP_GE)
            val weight = Mat()
            paper.convertTo(weight, CvType.CV_32F, 1.0 / 255.0)
            val greyF = Mat()
            small.convertTo(greyF, CvType.CV_32F)
            Core.multiply(greyF, weight, greyF)
            Imgproc.GaussianBlur(greyF, greyF, Size(0.0, 0.0), sigma)
            Core.max(greyF, Scalar(1.0), greyF)
            val coverage = weight.clone()
            Imgproc.GaussianBlur(coverage, coverage, Size(0.0, 0.0), sigma)
            val thin = Mat()
            Core.compare(coverage, Scalar(MIN_COVERAGE), thin, Core.CMP_LT)

            val lightF = Mat()
            lightSmall.convertTo(lightF, CvType.CV_32F)
            val flats = channels.map { channel ->
                val smallChannel = Mat()
                Imgproc.resize(channel, smallChannel, small.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                val num = Mat()
                smallChannel.convertTo(num, CvType.CV_32F)
                Core.multiply(num, weight, num)
                Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sigma)
                Core.divide(num, greyF, num)
                // Where there is hardly any paper to measure, the channel is taken to share the grey light.
                num.setTo(Scalar(1.0), thin)
                Core.multiply(num, lightF, num)
                val small8 = Mat()
                num.convertTo(small8, CvType.CV_8U)
                val full = Mat()
                Imgproc.resize(small8, full, channel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val flat = flatten(channel, full)
                listOf(smallChannel, num, small8, full).forEach { it.release() }
                flat
            }
            listOf(paper, limit, weight, greyF, coverage, thin, lightF).forEach { it.release() }
            val merged = Mat()
            Core.merge(flats, merged)
            flats.forEach { it.release() }
            return merged
        } finally {
            channels.forEach { it.release() }
            grey.release()
            greyLight?.release()
        }
    }

    /**
     * The colour of every pixel averaged a little with its neighbours, the brightness untouched: the
     * speckle a phone sensor puts in the colour channels (worst in blue) would otherwise show as
     * yellow and blue dots on white paper. Stamps and highlights are areas, and keep their colour;
     * only single-pixel colour is smoothed.
     */
    private fun smoothColour(rgb: Mat): Mat {
        val ycc = Mat()
        Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val parts = mutableListOf<Mat>()
        Core.split(ycc, parts)
        val sigma = maxOf(1.0, maxOf(rgb.cols(), rgb.rows()) / CHROMA_DIVISOR)
        Imgproc.GaussianBlur(parts[1], parts[1], Size(0.0, 0.0), sigma)
        Imgproc.GaussianBlur(parts[2], parts[2], Size(0.0, 0.0), sigma)
        Core.merge(parts, ycc)
        val out = Mat()
        Imgproc.cvtColor(ycc, out, Imgproc.COLOR_YCrCb2RGB)
        parts.forEach { it.release() }
        ycc.release()
        return out
    }

    private fun greyOf(image: Mat): Mat = Mat().also { Imgproc.cvtColor(image, it, Imgproc.COLOR_RGB2GRAY) }

    private fun downscale(image: Mat, longEdge: Int): Mat {
        val out = Mat()
        val long = maxOf(image.cols(), image.rows())
        if (long > longEdge) {
            val s = longEdge.toDouble() / long
            Imgproc.resize(image, out, Size(image.cols() * s, image.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            image.copyTo(out)
        }
        return out
    }

    private fun blackAndWhite(page: Mat): Mat {
        val grey = greyscale(page)
        // Local statistics are smooth fields, so they are taken on a quarter-size copy: cheaper, and
        // no full-size float image is ever made.
        val quarter = Mat()
        val quarterSize = Size(maxOf(1, grey.cols() / 4).toDouble(), maxOf(1, grey.rows() / 4).toDouble())
        Imgproc.resize(grey, quarter, quarterSize, 0.0, 0.0, Imgproc.INTER_AREA)
        val q = Mat()
        quarter.convertTo(q, CvType.CV_32F)
        val window = KernelSizes.sauvolaWindow(minOf(q.cols(), q.rows()))
        val box = Size(window.toDouble(), window.toDouble())
        val mean = Mat()
        val squares = Mat()
        Imgproc.boxFilter(q, mean, CvType.CV_32F, box)
        Imgproc.sqrBoxFilter(q, squares, CvType.CV_32F, box)
        val meanSquared = Mat()
        Core.multiply(mean, mean, meanSquared)
        Core.subtract(squares, meanSquared, squares)
        Core.max(squares, Scalar(0.0), squares)
        val std = Mat()
        Core.sqrt(squares, std)
        // t = mean * (1 + k * (std / R - 1))
        val factor = Mat()
        std.convertTo(factor, CvType.CV_32F, SAUVOLA_K / SAUVOLA_R, 1.0 - SAUVOLA_K)
        val threshold = Mat()
        Core.multiply(mean, factor, threshold)
        val threshold8 = Mat()
        threshold.convertTo(threshold8, CvType.CV_8U)
        val thresholdFull = Mat()
        Imgproc.resize(threshold8, thresholdFull, grey.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

        // Not a hard cut: the distance from the threshold, stretched, so a pixel well off it is black or
        // white and one near it is grey. A hard cut made every edge of every letter a staircase of whole
        // pixels. The stretch is steep (full black and white within 16 grey levels), so the page is
        // still black ink on white paper and not a grey scan.
        val grey16 = Mat()
        val threshold16 = Mat()
        grey.convertTo(grey16, CvType.CV_16S)
        thresholdFull.convertTo(threshold16, CvType.CV_16S)
        Core.subtract(grey16, threshold16, grey16)
        val out = Mat()
        grey16.convertTo(out, CvType.CV_8U, EDGE_GAIN, MIDDLE_GREY)
        grey16.release()
        threshold16.release()
        val black = Mat()
        val white = Mat()
        Core.compare(grey, Scalar(BLACK_FLOOR.toDouble()), black, Core.CMP_LT)
        Core.compare(grey, Scalar(WHITE_CEILING.toDouble()), white, Core.CMP_GT)
        out.setTo(Scalar(0.0), black)
        out.setTo(Scalar(255.0), white)
        listOf(grey, quarter, q, mean, squares, meanSquared, std, factor, threshold, threshold8, thresholdFull)
            .forEach { it.release() }
        black.release()
        white.release()
        return out
    }

    // --- choosing a style -----------------------------------------------------------------------

    /**
     * How much of the straightened [page] is clearly coloured, counted on a small copy after the
     * paper has been made white, so a lamp's cast or cream paper does not count. See [AutoStyle].
     */
    fun colourStats(page: Mat): ColourStats {
        val rgba = downscale(page, STATS_EDGE)
        val margin = (minOf(rgba.cols(), rgba.rows()) * STATS_MARGIN).toInt()
        val inner = if (rgba.cols() > 2 * margin + 8 && rgba.rows() > 2 * margin + 8) {
            Mat(rgba, Rect(margin, margin, rgba.cols() - 2 * margin, rgba.rows() - 2 * margin))
        } else {
            rgba
        }
        val rgb = Mat()
        Imgproc.cvtColor(inner, rgb, Imgproc.COLOR_RGBA2RGB)
        val flat = flattenedColour(rgb) ?: rgb
        val smoothed = Mat()
        Imgproc.medianBlur(flat, smoothed, 3)
        val lab = Mat()
        Imgproc.cvtColor(smoothed, lab, Imgproc.COLOR_RGB2Lab)
        val parts = mutableListOf<Mat>()
        Core.split(lab, parts)
        val (l, a, b) = parts
        val band = Mat()
        Core.inRange(l, Scalar(BAND_LOW.toDouble()), Scalar(BAND_HIGH.toDouble()), band)
        val a32 = Mat(); val b32 = Mat()
        a.convertTo(a32, CvType.CV_32S, 1.0, -128.0)
        b.convertTo(b32, CvType.CV_32S, 1.0, -128.0)
        Core.multiply(a32, a32, a32)
        Core.multiply(b32, b32, b32)
        Core.add(a32, b32, a32)
        val strong = Mat()
        Core.compare(a32, Scalar((MIN_CHROMA * MIN_CHROMA).toDouble()), strong, Core.CMP_GE)
        val coloured = Mat()
        Core.bitwise_and(strong, band, coloured)
        val stats = ColourStats(
            total = inner.rows() * inner.cols(),
            counted = Core.countNonZero(band),
            coloured = Core.countNonZero(coloured),
        )
        listOf(rgba, rgb, smoothed, lab, l, a, b, band, a32, b32, strong, coloured).forEach { it.release() }
        if (inner !== rgba) inner.release()
        if (flat !== rgb) flat.release()
        return stats
    }

    /** What the scanner picks for a straightened page: a colour scan or a grey one. */
    fun chooseFor(page: Mat): PageStyle = AutoStyle.choose(colourStats(page))

    private const val WORK_EDGE = 512
    private const val STATS_EDGE = 256
    private const val STATS_MARGIN = 0.03
    private const val HIST_BINS = 256
    private const val NOISE_MEDIAN = 5
    private const val SMOOTH_DIVISOR = 48.0
    private const val FILL_DIVISOR = 6.0
    private const val TINT_DIVISOR = 8.0
    private const val CHROMA_DIVISOR = 900.0
    private const val PAPER_SHARE = 0.88
    private const val MIN_COVERAGE = 0.02
    private const val MIN_WEIGHT = 1e-3
    private const val PAPER_PERCENTILE = 0.90f
    private const val DARK_PAGE = 110
    private const val DARK_FRACTION = 0.4
    private const val GREY_GAMMA = 1.4f
    private const val COLOUR_GAMMA = 1.1f
    private const val EDGE_GAIN = 8.0
    private const val MIDDLE_GREY = 128.0
    private const val SAUVOLA_K = 0.15
    private const val SAUVOLA_R = 128.0
    private const val BLACK_FLOOR = 90
    private const val WHITE_CEILING = 235
    private const val BAND_LOW = 50
    private const val BAND_HIGH = 242
    private const val MIN_CHROMA = 24
}
