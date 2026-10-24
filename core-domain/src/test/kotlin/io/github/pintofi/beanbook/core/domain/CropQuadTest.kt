package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four corners a page is cropped to, and the size the flattened result should be.
 *
 * A photographed receipt is shot at an angle, so its outline on the sensor is a general
 * quadrilateral, not a rectangle. Straightening it needs the corners in a known order and an
 * output size that does not squash the page — which is arithmetic, and belongs here where it
 * can be checked without a camera or a screen.
 */
class CropQuadTest {

    private val square = CropQuad(
        topLeft = CropPoint(0f, 0f),
        topRight = CropPoint(100f, 0f),
        bottomRight = CropPoint(100f, 100f),
        bottomLeft = CropPoint(0f, 100f),
    )

    @Test
    fun `the whole image is the default crop`() {
        val whole = CropQuad.whole(width = 640f, height = 480f)
        assertEquals(CropPoint(0f, 0f), whole.topLeft)
        assertEquals(CropPoint(640f, 480f), whole.bottomRight)
    }

    @Test
    fun `output size takes the longer of each opposing pair`() {
        // Perspective makes the near edge longer than the far one. Taking the longer of each
        // pair keeps detail rather than downsampling the page to its most foreshortened edge.
        val keystoned = CropQuad(
            topLeft = CropPoint(20f, 0f),
            topRight = CropPoint(80f, 0f),      // far edge, 60 wide
            bottomRight = CropPoint(100f, 50f), // near edge, 100 wide
            bottomLeft = CropPoint(0f, 50f),
        )
        val (w, h) = keystoned.outputSize()
        assertEquals(100, w)
        // 54, not 50: the sides lean, which is what keystoning is. Each runs from (20,0) to
        // (0,50), so its length is hypot(20, 50) = 53.85. The first version of this test
        // asserted 50 by measuring the height as though the sides were vertical, which is the
        // one thing the fixture was built not to be.
        assertEquals(54, h)
    }

    @Test
    fun `a degenerate quad still yields a usable size`() {
        // Corners dragged onto each other must not produce a zero-sized bitmap, which throws
        // when it reaches Bitmap.createBitmap.
        val collapsed = CropQuad(
            CropPoint(10f, 10f), CropPoint(10f, 10f),
            CropPoint(10f, 10f), CropPoint(10f, 10f),
        )
        val (w, h) = collapsed.outputSize()
        assertTrue(w >= 1, "width was $w")
        assertTrue(h >= 1, "height was $h")
    }

    @Test
    fun `corners are clamped to the image so a drag cannot leave it`() {
        val quad = CropQuad.whole(100f, 100f)
            .move(CropCorner.TOP_LEFT, CropPoint(-40f, -40f), width = 100f, height = 100f)
        assertEquals(CropPoint(0f, 0f), quad.topLeft)

        val out = CropQuad.whole(100f, 100f)
            .move(CropCorner.BOTTOM_RIGHT, CropPoint(500f, 500f), width = 100f, height = 100f)
        assertEquals(CropPoint(100f, 100f), out.bottomRight)
    }

    @Test
    fun `moving one corner leaves the others alone`() {
        val moved = square.move(CropCorner.TOP_RIGHT, CropPoint(90f, 10f), 100f, 100f)
        assertEquals(CropPoint(90f, 10f), moved.topRight)
        assertEquals(square.topLeft, moved.topLeft)
        assertEquals(square.bottomLeft, moved.bottomLeft)
        assertEquals(square.bottomRight, moved.bottomRight)
    }
}
