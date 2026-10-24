package io.github.pintofi.beanbook.scanner

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The fixed, ordered list of ways [PageDetector] looks for a page (#183).
 *
 * One pass is not enough in real use: a white receipt on a light table has almost the same
 * brightness as the table, so the grey edge map has nothing to find. The same page differs from the
 * table in saturation, or in one colour channel. So the detector tries several, and a pure rule
 * ([io.github.pintofi.beanbook.core.domain.PageChoice]) picks among what they find.
 *
 * Adapted from OSS-DocumentScanner's `cpp/src/DocumentDetector.cpp` (MIT, Copyright (c) 2022 Martin
 * Guillon): thresholding and edge detection run on each colour channel, a median blur first, and a
 * ladder of Canny thresholds from strict to permissive. Otsu replaces its fixed threshold of 160, and
 * saturation is added as a channel.
 *
 * The notice that comes with that adaptation (MIT):
 *
 *     Copyright (c) 2022 Martin Guillon
 *
 *     Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 *     and associated documentation files (the "Software"), to deal in the Software without
 *     restriction, including without limitation the rights to use, copy, modify, merge, publish,
 *     distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 *     Software is furnished to do so, subject to the following conditions: The above copyright notice
 *     and this permission notice shall be included in all copies or substantial portions of the
 *     Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 *     INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 *     AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 *     DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *     OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * The lists are fixed: nothing here depends on the clock, so the answer depends on the image only.
 */
internal object DetectionPasses {

    enum class Channel { GREY, SATURATION, RED, GREEN, BLUE }

    enum class Kind { LEGACY, OTSU, CANNY }

    class PassSpec(val index: Int, val channel: Channel, val kind: Kind, val low: Double = 0.0)

    const val LEGACY_PASS = 0

    private val CANNY_LADDER = listOf(90.0, 50.0, 25.0)

    val FULL: List<PassSpec> = buildList {
        add(PassSpec(0, Channel.GREY, Kind.LEGACY))
        add(PassSpec(1, Channel.GREY, Kind.OTSU))
        add(PassSpec(2, Channel.SATURATION, Kind.OTSU))
        CANNY_LADDER.forEach { add(PassSpec(size, Channel.SATURATION, Kind.CANNY, it)) }
        listOf(Channel.RED, Channel.GREEN, Channel.BLUE).forEach { channel ->
            add(PassSpec(size, channel, Kind.OTSU))
            CANNY_LADDER.forEach { add(PassSpec(size, channel, Kind.CANNY, it)) }
        }
    }

    /** The preview's five: the old pass, Otsu on grey and saturation, and two saturation Cannys. */
    val LIVE: List<PassSpec> = FULL.filter { it.index in setOf(0, 1, 2, 4, 5) }

    /** The images the passes read, made once from the working image. */
    class Channels(small: Mat, newMat: () -> Mat) {
        val rgba: Mat = small
        private val rgb = newMat()
        private val blurred = newMat()
        val grey = newMat()
        val saturation = newMat()
        val red = newMat()
        val green = newMat()
        val blue = newMat()

        init {
            Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
            // Erases text and paper grain at this size while keeping the page border as a step.
            Imgproc.medianBlur(rgb, blurred, MEDIAN)
            Imgproc.cvtColor(blurred, grey, Imgproc.COLOR_RGB2GRAY)
            val hsv = newMat()
            Imgproc.cvtColor(blurred, hsv, Imgproc.COLOR_RGB2HSV)
            Core.extractChannel(hsv, saturation, 1)
            Core.extractChannel(blurred, red, 0)
            Core.extractChannel(blurred, green, 1)
            Core.extractChannel(blurred, blue, 2)
        }

        fun of(channel: Channel): Mat = when (channel) {
            Channel.GREY -> grey
            Channel.SATURATION -> saturation
            Channel.RED -> red
            Channel.GREEN -> green
            Channel.BLUE -> blue
        }
    }

    /** The binary image a pass looks for page shapes in. */
    fun edges(pass: PassSpec, channels: Channels, newMat: () -> Mat): Mat {
        val out = newMat()
        when (pass.kind) {
            Kind.LEGACY -> legacy(channels.rgba, out)
            Kind.OTSU -> {
                val mode = Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                Imgproc.threshold(channels.of(pass.channel), out, 0.0, 255.0, mode)
                val close = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(4.0, 4.0))
                Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, close)
                close.release()
                thicken(out)
            }
            Kind.CANNY -> {
                Imgproc.Canny(channels.of(pass.channel), out, pass.low, pass.low * 2)
                thicken(out)
            }
        }
        return out
    }

    /** Exactly the single pass the detector had before: grey, blur, Canny, close the gaps. */
    private fun legacy(rgba: Mat, out: Mat) {
        Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(out, out, Size(5.0, 5.0), 0.0)
        // Canny before contours: a page edge is a brightness step, and thresholds relative to
        // the blurred image survive both a bright desk and a dim one better than a fixed pair.
        Imgproc.Canny(out, out, 50.0, 150.0)
        // Close first. A page photographed on a similar-coloured surface produces a broken
        // outline, and four almost-touching arcs are four contours, none of them a rectangle.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
    }

    private fun thicken(image: Mat) {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(image, image, kernel)
        kernel.release()
    }

    private const val MEDIAN = 9
}
