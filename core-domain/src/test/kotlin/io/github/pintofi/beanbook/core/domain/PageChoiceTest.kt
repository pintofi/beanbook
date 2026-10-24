package io.github.pintofi.beanbook.core.domain

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageChoiceTest {

    private val width = 400f
    private val height = 500f

    private fun rect(x: Float, y: Float, w: Float, h: Float) = CropQuad(
        CropPoint(x, y), CropPoint(x + w, y), CropPoint(x + w, y + h), CropPoint(x, y + h),
    )

    private fun jitter(q: CropQuad, by: Float) = CropQuad(
        CropPoint(q.topLeft.x + by, q.topLeft.y), CropPoint(q.topRight.x, q.topRight.y + by),
        CropPoint(q.bottomRight.x - by, q.bottomRight.y), CropPoint(q.bottomLeft.x, q.bottomLeft.y - by),
    )

    private fun choose(list: List<PageCandidate>, legacy: CropQuad? = null) =
        PageChoice.choose(list, width, height, legacy)

    // --- geometry -----------------------------------------------------------------------------

    @Test fun `area and the sharpest corner of known shapes`() {
        val r = rect(0f, 0f, 100f, 50f)
        assertEquals(5000f, r.area(), 0.01f)
        assertEquals(0f, r.maxCornerCosine(), 0.001f)
        val rhombus = CropQuad(
            CropPoint(0f, 0f), CropPoint(100f, 0f), CropPoint(150f, 86.6025f), CropPoint(50f, 86.6025f),
        )
        assertEquals(0.5f, rhombus.maxCornerCosine(), 0.01f)
    }

    @Test fun `contains and isNear`() {
        val r = rect(0f, 0f, 100f, 100f)
        assertTrue(r.contains(CropPoint(50f, 50f)))
        assertFalse(r.contains(CropPoint(150f, 50f)))
        assertFalse(r.contains(CropPoint(1f, 1f), tolerance = 5f))
        assertTrue(r.isNear(jitter(r, 2f), 3f))
        assertFalse(r.isNear(jitter(r, 20f), 3f))
    }

    // --- ordering corners ---------------------------------------------------------------------

    @Test fun `corners come out clockwise from the top-left however the page is turned`() {
        val random = Random(7)
        listOf(0, 10, 44, 45, 46, 90, 135, 300).forEach { degrees ->
            val a = Math.toRadians(degrees.toDouble())
            val base = listOf(CropPoint(-60f, -90f), CropPoint(60f, -90f), CropPoint(60f, 90f), CropPoint(-60f, 90f))
            val turned = base.map { p ->
                CropPoint(
                    (200 + p.x * cos(a) - p.y * sin(a)).toFloat(),
                    (250 + p.x * sin(a) + p.y * cos(a)).toFloat(),
                )
            }
            val ordered = PageChoice.orderCorners(turned.shuffled(random))
            val points = listOf(ordered.topLeft, ordered.topRight, ordered.bottomRight, ordered.bottomLeft)
            assertEquals(4, points.toSet().size, "four distinct corners at $degrees degrees")
            assertEquals(points.minOf { it.x + it.y }, ordered.topLeft.x + ordered.topLeft.y, 0.01f, "$degrees")
            // Clockwise on screen (y down) is a positive turn at every corner.
            for (i in 0 until 4) {
                val p = points[(i + 3) % 4]; val q = points[i]; val r = points[(i + 1) % 4]
                assertTrue((q.x - p.x) * (r.y - q.y) - (q.y - p.y) * (r.x - q.x) > 0f, "$degrees")
            }
        }
    }

    // --- choosing -----------------------------------------------------------------------------

    @Test fun `the larger of two valid pages wins`() {
        val small = rect(20f, 20f, 100f, 150f)
        val large = rect(150f, 150f, 220f, 300f)
        assertEquals(large, choose(listOf(PageCandidate(small, 0), PageCandidate(large, 1))))
    }

    @Test fun `a box printed on the page loses to the page around it, even found by more passes`() {
        val page = rect(20f, 20f, 360f, 450f)
        val box = rect(80f, 80f, 220f, 280f)
        val inner = List(5) { PageCandidate(box, it) }
        assertEquals(page, choose(inner + PageCandidate(page, 6)))
    }

    @Test fun `support counts distinct passes and the representative is the median`() {
        val page = rect(20f, 20f, 300f, 400f)
        val seen = listOf(0, 1, 2, 3).map { PageCandidate(jitter(page, it * 1f), it) } +
            listOf(PageCandidate(page, 0), PageCandidate(page, 0))
        val chosen = choose(seen)!!
        assertTrue(chosen.isNear(page, 3f))
        assertTrue(PageChoice.settled(seen, width, height))
    }

    @Test fun `the answer does not depend on the order the candidates arrive in`() {
        val a = rect(20f, 20f, 200f, 300f)
        val b = rect(200f, 100f, 190f, 380f)
        val list = listOf(
            PageCandidate(a, 0), PageCandidate(a, 3), PageCandidate(b, 1), PageCandidate(jitter(b, 2f), 2),
            PageCandidate(rect(30f, 30f, 60f, 60f), 4),
        )
        val expected = choose(list)
        repeat(50) { seed -> assertEquals(expected, choose(list.shuffled(Random(seed)))) }
    }

    @Test fun `when nothing qualifies the first pass's answer is returned`() {
        val sliver = CropQuad(CropPoint(10f, 10f), CropPoint(390f, 200f), CropPoint(380f, 210f), CropPoint(20f, 20f))
        val legacy = rect(10f, 10f, 380f, 480f)
        assertEquals(legacy, choose(listOf(PageCandidate(sliver, 1)), legacy))
        assertNull(choose(emptyList(), null))
    }

    @Test fun `the edge of a shadow that runs to the frame is not a page`() {
        val shadowEdge = rect(150f, 0f, 250f, 500f)
        val page = rect(60f, 80f, 150f, 250f)
        // Three corners on the frame's border: the frame's own shape, even though it is the biggest.
        assertEquals(page, choose(listOf(PageCandidate(shadowEdge, 0), PageCandidate(page, 1))))
        assertNull(choose(listOf(PageCandidate(shadowEdge, 0))))
    }

    @Test fun `too small and too large shapes are filtered out`() {
        assertNull(choose(listOf(PageCandidate(rect(0f, 0f, 40f, 40f), 0))))
        assertNull(choose(listOf(PageCandidate(rect(0f, 0f, 400f, 500f), 0))))
    }

    @Test fun `one pass is not settled and two agreeing passes on a clear page are`() {
        val page = rect(20f, 20f, 300f, 400f)
        assertFalse(PageChoice.settled(listOf(PageCandidate(page, 0)), width, height))
        val two = listOf(PageCandidate(page, 0), PageCandidate(jitter(page, 1f), 1))
        assertTrue(PageChoice.settled(two, width, height))
        assertNotEquals(null, choose(listOf(PageCandidate(page, 0))))
    }
}
