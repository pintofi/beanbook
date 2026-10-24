package io.github.pintofi.beanbook.scanner

import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.scanner.SyntheticScenes.quad
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * Several passes find pages that one pass misses (#183), without losing any it found.
 *
 * Drawn scenes, so the corners are known. The scenes a single pass cannot find are asserted to be
 * ones it cannot find: if a later change makes the old pass succeed on one, that scene no longer
 * proves anything and the test says so.
 */
@RunWith(AndroidJUnit4::class)
class MultiPassDetectionTest {

    @Before fun loadOpenCv() {
        assertTrue("OpenCV did not load; every case here would be vacuous", Scanner.available)
    }

    private class Run(val chosen: CropQuad?, val legacy: CropQuad?)

    private fun run(scene: SyntheticScenes.Scene, thorough: Boolean = true): Run {
        val mat = Mat()
        Utils.bitmapToMat(scene.bitmap, mat)
        try {
            val passes = if (thorough) DetectionPasses.FULL else DetectionPasses.LIVE
            val report = requireNotNull(PageDetector.report(mat, passes))
            return Run(report.chosen, report.legacy)
        } finally {
            mat.release()
        }
    }

    private fun CropQuad.near(truth: CropQuad, limit: Float = 25f) = isNear(truth, limit)

    private fun assertFound(name: String, run: Run, truth: CropQuad) {
        assertNotNull("$name: no page found", run.chosen)
        assertTrue("$name: found ${run.chosen}, expected about $truth", run.chosen!!.near(truth))
    }

    // --- scenes -------------------------------------------------------------------------------

    private val page = quad(180f, 240f, 700f, 300f, 760f, 980f, 140f, 900f)
    private val receipt = quad(330f, 150f, 560f, 170f, 585f, 1050f, 300f, 1030f)

    private fun darkTable() = SyntheticScenes.scene(Color.rgb(28, 28, 32), Color.rgb(246, 246, 240), page, 1)

    private fun whiteReceiptOnLightTable() = SyntheticScenes.scene(
        table = Color.rgb(232, 230, 224), page = Color.rgb(246, 246, 243), corners = receipt, seed = 2,
        edgeSoftness = 1.5f, noise = 2.0, text = true,
    )

    private fun whitePageOnBeigeTable() = SyntheticScenes.scene(
        table = Color.rgb(255, 236, 205), page = Color.rgb(240, 240, 240), corners = page, seed = 3, noise = 1.5,
    )

    private fun skewed() = SyntheticScenes.scene(
        Color.rgb(30, 30, 34), Color.rgb(245, 245, 238), quad(260f, 200f, 640f, 260f, 800f, 1000f, 100f, 930f), 4,
    )

    private fun steep() = SyntheticScenes.scene(
        Color.rgb(30, 30, 34), Color.rgb(245, 245, 238), quad(300f, 250f, 600f, 250f, 800f, 1050f, 100f, 1050f), 5,
    )

    private fun darkPageOnLightTable() = SyntheticScenes.scene(
        Color.rgb(220, 220, 215), Color.rgb(20, 30, 70), page, 6,
    )

    private fun pageWithABox() = SyntheticScenes.scene(
        Color.rgb(28, 28, 32), Color.rgb(246, 246, 240), page, 7, innerBox = true,
    )

    // --- finding it ---------------------------------------------------------------------------

    @Test fun aPageOnADarkTableIsFoundAsBefore() {
        assertFound("dark table", run(darkTable()), page)
    }

    @Test fun aWhiteReceiptOnALightTableIsFoundWhereOnePassWasBlind() {
        val scene = whiteReceiptOnLightTable()
        val result = run(scene)
        // The guard: if the old pass finds this, the scene no longer shows what several passes add.
        assertTrue(
            "the single pass now finds the light-on-light receipt; make the scene harder",
            result.legacy == null || !result.legacy.near(receipt),
        )
        assertFound("white receipt on light table", result, receipt)
    }

    @Test fun aWhitePageOnABeigeTableOfTheSameBrightnessIsFound() {
        val scene = whitePageOnBeigeTable()
        val result = run(scene)
        assertTrue(
            "the single pass now finds the page on beige; make the scene harder",
            result.legacy == null || !result.legacy.near(page),
        )
        assertFound("white page on beige", result, page)
    }

    @Test fun aSkewedPageIsFound() = assertFound("skewed", run(skewed()), requireNotNull(skewed().truth))

    @Test fun aVerySteepPageIsStillFoundThroughTheFirstPass() {
        val truth = requireNotNull(steep().truth)
        assertFound("steep", run(steep()), truth)
    }

    @Test fun aDarkPageOnALightTableIsFound() = assertFound("dark page", run(darkPageOnLightTable()), page)

    @Test fun aBoxPrintedOnThePageDoesNotBeatThePage() = assertFound("page with a box", run(pageWithABox()), page)

    // --- not inventing one --------------------------------------------------------------------

    @Test fun framesWithNoPageFindNone() {
        val empty = listOf(
            "empty dark" to SyntheticScenes.scene(Color.rgb(28, 28, 32), 0, null, 11),
            "empty light, noisy, graded" to SyntheticScenes.scene(
                Color.rgb(225, 225, 220), 0, null, 12, noise = 3.0, gradient = Color.rgb(225, 225, 220) to Color.rgb(205, 205, 200),
            ),
            "wood-like stripes" to SyntheticScenes.scene(Color.rgb(150, 110, 70), 0, null, 13, stripes = true),
        )
        empty.forEach { (name, scene) ->
            assertNull("$name (all passes)", run(scene, thorough = true).chosen)
            assertNull("$name (live passes)", run(scene, thorough = false).chosen)
        }
    }

    @Test fun aSmallLabelAloneIsNotAPage() {
        val label = quad(100f, 100f, 260f, 100f, 260f, 190f, 100f, 190f)
        val scene = SyntheticScenes.scene(Color.rgb(228, 226, 220), Color.rgb(246, 246, 243), label, 14)
        assertNull(run(scene).chosen)
    }

    // --- nothing lost -------------------------------------------------------------------------

    @Test fun whereTheOldPassFoundThePageSoDoesTheNewOneAcrossASweep() {
        val random = Random(183)
        var legacyRight = 0
        var chosenRight = 0
        val total = 60
        repeat(total) { n ->
            val tables = listOf(Color.rgb(28, 28, 32), Color.rgb(228, 226, 220), Color.rgb(150, 110, 70))
            val pages = listOf(Color.rgb(246, 246, 240), Color.rgb(20, 30, 70), Color.rgb(250, 245, 230))
            val ratio = 0.6f + random.nextFloat() * 0.4f
            val cx = 450f + (random.nextFloat() - 0.5f) * 60f
            val w = 300f + random.nextFloat() * 220f
            val top = 160f + random.nextFloat() * 120f
            val h = 520f + random.nextFloat() * 380f
            val tilt = (random.nextFloat() - 0.5f) * 80f
            val topHalf = w * ratio / 2f
            val truth = quad(
                cx - topHalf + tilt, top, cx + topHalf + tilt, top + 10f,
                cx + w / 2f, top + h, cx - w / 2f, top + h - 10f,
            )
            val scene = SyntheticScenes.scene(
                table = tables[random.nextInt(tables.size)], page = pages[random.nextInt(pages.size)],
                corners = truth, seed = n, noise = 1.5, shadow = if (random.nextBoolean()) 0.4f else 0f,
                jpegQuality = 85,
            )
            val result = run(scene)
            val legacyOk = result.legacy?.near(truth) == true
            val chosenOk = result.chosen?.near(truth) == true
            if (legacyOk) legacyRight++
            if (chosenOk) chosenRight++
            if (result.legacy != null) assertNotNull("scene $n: the old pass found a page and the new one lost it", result.chosen)
            if (legacyOk) {
                assertTrue(
                    "scene $n: the old pass had the page right and the new one does not: " +
                        "truth $truth chosen ${result.chosen} legacy ${result.legacy}",
                    chosenOk,
                )
            }
        }
        Log.i("MultiPass", "sweep: old pass right $legacyRight, new right $chosenRight of $total")
        assertTrue("new $chosenRight < old $legacyRight", chosenRight >= legacyRight)
        assertTrue("only $chosenRight of $total found", chosenRight >= total * 9 / 10)
    }

    // --- the live preview ---------------------------------------------------------------------

    @Test fun theLiveOutlineHoldsStillFromFrameToFrame() {
        val diagonal = kotlin.math.hypot(SyntheticScenes.WIDTH.toFloat(), SyntheticScenes.HEIGHT.toFloat())
        listOf<(Int) -> SyntheticScenes.Scene>(
            { s -> SyntheticScenes.scene(Color.rgb(28, 28, 32), Color.rgb(246, 246, 240), page, s, noise = 2.0) },
            { s -> SyntheticScenes.scene(Color.rgb(232, 230, 224), Color.rgb(246, 246, 243), receipt, s, 1.5f, 2.0, true) },
        ).forEachIndexed { index, make ->
            val found = (1..5).map { run(make(100 + it), thorough = false).chosen }
            found.zipWithNext().forEach { (a, b) ->
                if (a != null && b != null) {
                    assertTrue("scene $index jumped between frames", a.isNear(b, 0.025f * diagonal))
                }
            }
        }
    }

    @Test fun theLiveAndThoroughRunsAgreeOnTheEasyScenes() {
        listOf(darkTable(), skewed()).forEach { scene ->
            val live = run(scene, thorough = false).chosen
            val full = run(scene, thorough = true).chosen
            assertNotNull(live); assertNotNull(full)
            assertTrue(live!!.near(full!!))
        }
    }

    @Test fun aTwelveMegapixelPageIsFoundInTime() {
        val scene = whiteReceiptOnLightTable()
        val times = (1..5).map {
            val start = System.nanoTime()
            run(scene)
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        Log.i("MultiPass", "thorough detection ms: $times")
        assertTrue("median ${times[2]} ms", times[2] <= 450)
        assertEquals(5, times.size)
    }
}
