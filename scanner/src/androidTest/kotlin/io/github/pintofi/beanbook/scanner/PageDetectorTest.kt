package io.github.pintofi.beanbook.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detector finds a page, and the warp straightens it.
 *
 * On a synthetic frame rather than a photograph, because a photograph proves the detector
 * worked on that photograph. A drawn quadrilateral has corners this test *knows*, so it can
 * assert where they were found rather than that something was found — and it fails for a
 * detector that returns the whole frame, which is the failure mode a "did we get four
 * corners" test would pass.
 */
@RunWith(AndroidJUnit4::class)
class PageDetectorTest {

    private val frameWidth = 900
    private val frameHeight = 1200

    /** The corners the page is drawn at. Deliberately off-square, as a photographed page is. */
    private val drawn = listOf(
        floatArrayOf(180f, 240f),   // top-left
        floatArrayOf(700f, 300f),   // top-right
        floatArrayOf(760f, 980f),   // bottom-right
        floatArrayOf(140f, 900f),   // bottom-left
    )

    private fun frameWithPage(): Bitmap {
        val bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // A dark surface with a light page on it: the contrast the pipeline looks for.
        canvas.drawColor(Color.rgb(28, 28, 32))
        val path = Path().apply {
            moveTo(drawn[0][0], drawn[0][1])
            drawn.drop(1).forEach { lineTo(it[0], it[1]) }
            close()
        }
        canvas.drawPath(path, Paint().apply { color = Color.rgb(246, 246, 240); isAntiAlias = true })
        return bitmap
    }

    @Test fun opencvLoads() {
        assertTrue("OpenCV native library did not load; every other case here is vacuous", Scanner.available)
    }

    @Test fun theDrawnPageIsFoundWhereItWasDrawn() {
        val quad = Scanner.detect(frameWithPage())
        assertNotNull("no page found in a frame containing one", quad)

        val found = listOf(quad!!.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        // 25px of slack: the contour follows the anti-aliased edge, and the blur and the
        // downscale-then-rescale each move a corner by a pixel or two. Tight enough that
        // returning the frame's own corners -- the obvious wrong answer -- fails.
        found.forEachIndexed { index, point ->
            assertEquals("corner $index x", drawn[index][0], point.x, 25f)
            assertEquals("corner $index y", drawn[index][1], point.y, 25f)
        }
    }

    @Test fun straighteningSquaresThePage() {
        val photo = frameWithPage()
        val quad = requireNotNull(Scanner.detect(photo))
        val page = Scanner.straighten(photo, quad, ScanStyle.COLOUR)

        // The drawn page is about 540 wide and 670 tall along its longest edges. The output
        // must be that page, not the frame it sat in.
        assertTrue("output is as wide as the whole frame (${page.width})", page.width < frameWidth - 100)
        assertTrue("output is as tall as the whole frame (${page.height})", page.height < frameHeight - 200)

        // Corners are corners of the page now: all four should be page-coloured, which they
        // were not before straightening -- three of them were dark surface.
        listOf(
            page.getPixel(2, 2),
            page.getPixel(page.width - 3, 2),
            page.getPixel(2, page.height - 3),
            page.getPixel(page.width - 3, page.height - 3),
        ).forEachIndexed { index, pixel ->
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            assertTrue("corner $index is dark ($brightness); the page was not squared", brightness > 150)
        }
    }

    @Test fun documentStyleSeparatesInkFromPaper() {
        val photo = frameWithPage()
        val quad = requireNotNull(Scanner.detect(photo))
        val page = Scanner.straighten(photo, quad, ScanStyle.DOCUMENT)

        // A blank page thresholds to near-uniform white. The assertion is that it is not a
        // uniform *black*, which is what a global threshold does to an unevenly lit frame and
        // the reason this uses an adaptive one.
        var light = 0
        var counted = 0
        for (x in 2 until page.width step 7) {
            for (y in 2 until page.height step 7) {
                counted++
                if (Color.red(page.getPixel(x, y)) > 127) light++
            }
        }
        assertTrue("counted no pixels", counted > 0)
        assertTrue(
            "only $light of $counted pixels came out light; the page was thresholded to black",
            light > counted / 2,
        )
    }

    @Test fun aFrameWithNoPageFindsNothing() {
        val empty = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        Canvas(empty).drawColor(Color.rgb(28, 28, 32))
        // The negative control for every case above: a detector that always returns the frame
        // would satisfy "four corners found" and fail here.
        assertEquals("a page was found in an empty frame", null, Scanner.detect(empty))
    }
}
