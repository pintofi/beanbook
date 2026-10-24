package io.github.pintofi.beanbook.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page styles on straightened, drawn pages (#181), so that a detection change cannot fail them
 * and the inks and papers are known: dividing out the light, keeping stamps and highlights, keeping
 * the decimal point on a receipt, and choosing grey for a page whose only colour is a lamp.
 */
@RunWith(AndroidJUnit4::class)
class PageStylesTest {

    private val width = 1200
    private val height = 1700

    @Before fun loadOpenCv() {
        assertTrue("OpenCV did not load", Scanner.available)
    }

    private fun page(paper: Int = Color.rgb(250, 250, 248), build: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paper)
        build(canvas)
        return bitmap
    }

    private val ink = Paint().apply { color = Color.rgb(25, 25, 28); isAntiAlias = true; textSize = 44f }

    private fun textRows(canvas: Canvas, paint: Paint = ink, rows: Int = 20, top: Float = 120f) {
        for (row in 0 until rows) canvas.drawText("Line $row  total 12.50  Invoice 4471", 80f, top + row * 70f, paint)
    }

    private fun shade(bitmap: Bitmap, from: Float, to: Float): Bitmap {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f, Color.argb((255 * (1 - from)).toInt(), 0, 0, 0),
                Color.argb((255 * (1 - to)).toInt(), 0, 0, 0), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun grey(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Double {
        var sum = 0.0
        for (j in y until y + h) for (i in x until x + w) {
            val p = bitmap.getPixel(i, j)
            sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3.0
        }
        return sum / (w * h)
    }

    private fun inkMean(bitmap: Bitmap, x0: Int, x1: Int): Double {
        // The darkest 2% of pixels in the band: what the strokes came out as.
        val values = ArrayList<Int>()
        for (y in 100 until height - 100 step 2) for (x in x0 until x1 step 2) {
            val p = bitmap.getPixel(x, y)
            values += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
        }
        values.sort()
        return values.take(values.size / 50).average()
    }

    private fun styled(bitmap: Bitmap, style: ScanStyle) = Scanner.restyle(bitmap, style)

    // --- S1: the shadow comes out of the paper and the ink stays dark -----------------------------

    @Test fun aShadowedPageHasEvenPaperAndDarkInkInBothHalves() {
        val source = shade(page { textRows(it) }, 1.0f, 0.45f)
        listOf(ScanStyle.GREYSCALE, ScanStyle.DOCUMENT).forEach { style ->
            val out = styled(source, style)
            val lit = grey(out, 20, 20, 80, 30)
            val shadowed = grey(out, 1100, 20, 80, 30)
            assertTrue("$style paper in the light is $lit", lit >= 235)
            assertTrue("$style paper in the shadow is $shadowed", shadowed >= 235)
            assertTrue("$style paper differs by ${abs(lit - shadowed)}", abs(lit - shadowed) <= 8)
            // The text runs to about x = 740; the shadow deepens to the right.
            assertTrue("$style ink in the shadow is ${inkMean(out, 520, 740)}", inkMean(out, 520, 740) <= 80)
            assertTrue("$style ink in the light is ${inkMean(out, 80, 300)}", inkMean(out, 80, 300) <= 80)
        }
    }

    // --- S2: a faded receipt keeps its decimal point ----------------------------------------------

    @Test fun aThermalReceiptKeepsFaintPrintAndItsDecimalPoint() {
        val faint = Paint(ink).apply { color = Color.rgb(170, 170, 170); textSize = 40f }
        val source = page(Color.rgb(238, 236, 230)) { canvas ->
            textRows(canvas, faint, rows = 18)
            canvas.drawText("TOTAL 12.50", 80f, 1500f, faint)
        }
        val grey = styled(source, ScanStyle.GREYSCALE)
        assertTrue("grey ink ${inkMean(grey, 60, 700)}", inkMean(grey, 60, 700) <= 140)
        assertTrue("grey paper ${grey(grey, 1000, 20, 100, 40)}", grey(grey, 1000, 20, 100, 40) >= 240)

        val bw = styled(source, ScanStyle.BLACK_AND_WHITE)
        // Every pixel is black or white, the paper is clean, and print survives.
        var black = 0
        var white = 0
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            val v = Color.red(bw.getPixel(x, y))
            if (v < 128) black++ else white++
        }
        assertTrue("paper is ${white * 100 / (black + white)}% white", white * 100 / (black + white) >= 90)
        assertTrue("no print survived", black > 200)
        // The decimal point of "12.50": find ink in a small box where the full stop is drawn.
        val before = faint.measureText("TOTAL 12")
        var dotInk = 0
        for (y in 1480 until 1505) for (x in (80 + before).toInt() until (80 + before + faint.measureText(".")).toInt() + 2) {
            if (Color.red(bw.getPixel(x, y)) < 128) dotInk++
        }
        assertTrue("the decimal point was lost", dotInk >= 1)
    }

    // --- S3 and S4: colour that is a stamp, and colour that is only a lamp -------------------------

    @Test fun aStampAndAHighlighterKeepTheirColourAndAutoChoosesColour() {
        val source = page { canvas ->
            canvas.drawRect(RectF(80f, 330f, 1100f, 400f), Paint().apply { color = Color.rgb(250, 240, 90) })
            textRows(canvas)
            canvas.drawCircle(900f, 1300f, 120f, Paint().apply {
                color = Color.rgb(200, 30, 30); style = Paint.Style.STROKE; strokeWidth = 24f; isAntiAlias = true
            })
        }
        val out = styled(source, ScanStyle.DOCUMENT)
        val stamp = out.getPixel(900 + 120, 1300)
        val highlight = out.getPixel(1050, 365)
        assertTrue("stamp lost its red: ${Color.red(stamp) - Color.green(stamp)}", Color.red(stamp) - Color.green(stamp) >= 80)
        assertTrue(
            "highlight lost its yellow: ${minOf(Color.red(highlight), Color.green(highlight)) - Color.blue(highlight)}",
            minOf(Color.red(highlight), Color.green(highlight)) - Color.blue(highlight) >= 60,
        )
        val paper = out.getPixel(1150, 50)
        assertTrue("paper is tinted", abs(Color.red(paper) - Color.green(paper)) <= 6 && abs(Color.green(paper) - Color.blue(paper)) <= 6)
        assertEquals(ScanStyle.DOCUMENT, Scanner.chooseStyle(source, null))
    }

    @Test fun aWarmLampIsNotColourAndAutoChoosesGrey() {
        val source = page { textRows(it) }
        // The whole page lit by a warm lamp: red 1.0, green 0.93, blue 0.80.
        val cast = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            val p = source.getPixel(x, y)
            cast.setPixel(x, y, Color.rgb(Color.red(p), (Color.green(p) * 0.93).toInt(), (Color.blue(p) * 0.80).toInt()))
        }
        assertEquals(ScanStyle.GREYSCALE, Scanner.chooseStyle(cast, null))
        val out = styled(cast, ScanStyle.DOCUMENT)
        val paper = out.getPixel(1150, 50)
        assertTrue("paper kept the cast: ${Color.red(paper)} vs ${Color.blue(paper)}", abs(Color.red(paper) - Color.blue(paper)) <= 6)
    }

    // --- S5: a black header bar stays black -------------------------------------------------------

    @Test fun aBlackHeaderBarIsNotWashedOut() {
        val source = page { canvas ->
            canvas.drawRect(RectF(0f, 0f, width.toFloat(), 255f), Paint().apply { color = Color.rgb(15, 15, 15) })
            textRows(canvas, top = 400f, rows = 17)
        }
        listOf(ScanStyle.GREYSCALE, ScanStyle.DOCUMENT, ScanStyle.BLACK_AND_WHITE).forEach { style ->
            val out = styled(source, style)
            val bar = grey(out, 100, 40, 1000, 160)
            assertTrue("$style header bar came out $bar", bar <= 60)
        }
    }

    // --- S6: a blank page does not grow noise ------------------------------------------------------

    @Test fun aBlankNoisyPageStaysBlank() {
        val random = Random(6)
        val source = page(Color.rgb(236, 236, 232)) { }
        for (y in 0 until height) for (x in 0 until width) {
            val n = (random.nextGaussian() * 3).toInt()
            val g = (236 + n - y * 12 / height).coerceIn(0, 255)
            source.setPixel(x, y, Color.rgb(g, g, g - 4))
        }
        val grey = styled(source, ScanStyle.GREYSCALE)
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 0 until height step 4) for (x in 0 until width step 4) {
            val v = Color.red(grey.getPixel(x, y)).toDouble(); sum += v; sumSq += v * v; n++
        }
        val std = kotlin.math.sqrt(sumSq / n - (sum / n) * (sum / n))
        assertTrue("noise grew to a standard deviation of $std", std <= 6)
        val bw = styled(source, ScanStyle.BLACK_AND_WHITE)
        var white = 0
        for (y in 0 until height step 4) for (x in 0 until width step 4) if (Color.red(bw.getPixel(x, y)) > 128) white++
        assertTrue("only ${white * 1000 / n / 10.0}% white", white * 1000 / n >= 995)
    }

    // --- S7: a dark page is not washed to white ----------------------------------------------------

    @Test fun aDarkPageStaysDarkWithReadableText() {
        val white = Paint(ink).apply { color = Color.WHITE }
        val source = page(Color.rgb(20, 30, 70)) { textRows(it, white) }
        listOf(ScanStyle.GREYSCALE, ScanStyle.DOCUMENT).forEach { style ->
            val out = styled(source, style)
            val background = grey(out, 1100, 20, 60, 40)
            assertTrue("$style background came out $background", background <= 140)
            val brightest = (100 until height - 100 step 2).maxOf { y -> (60 until 700 step 2).maxOf { x -> Color.green(out.getPixel(x, y)) } }
            assertTrue("$style contrast ${brightest - background}", brightest - background >= 100)
        }
        // A navy page has chroma, so it is a colour scan.
        assertEquals(ScanStyle.DOCUMENT, Scanner.chooseStyle(source, null))
    }

    // --- S8: the preview looks like the PDF --------------------------------------------------------

    @Test fun aPreviewAndAPdfOfOnePageLookTheSame() {
        val big = shade(page { textRows(it) }, 1.0f, 0.5f)
        val small = Bitmap.createScaledBitmap(big, 1131 * 2 / 3, 1600 * 2 / 3, true)
        listOf(ScanStyle.GREYSCALE, ScanStyle.DOCUMENT).forEach { style ->
            val a = Bitmap.createScaledBitmap(styled(big, style), 600, 850, true)
            val b = Bitmap.createScaledBitmap(styled(small, style), 600, 850, true)
            var diff = 0L
            for (y in 0 until 850 step 5) for (x in 0 until 600 step 5) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                diff += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            }
            val mean = diff / (170.0 * 120.0 * 3.0)
            Log.i("PageStyles", "$style preview vs full: mean difference $mean")
            assertTrue("$style differs by $mean grey levels between sizes", mean <= 6.0)
        }
    }
}
