package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropEditingTest {
    private val whole = CropQuad.whole(1000f, 1000f)
    private val minEdge = CropLimits.minEdge(1000f, 1000f) // 20 px

    @Test fun `the whole photograph is warpable`() = assertTrue(whole.isWarpable(minEdge))

    @Test fun `a crossed quad is not`() {
        val bowTie = whole.copy(topLeft = CropPoint(1000f, 0f), topRight = CropPoint(0f, 0f))
        assertFalse(bowTie.isWarpable(minEdge))
    }

    @Test fun `a concave quad is not`() {
        val dart = whole.copy(topLeft = CropPoint(700f, 700f))   // inside the triangle TR, BR, BL
        assertFalse(dart.isWarpable(minEdge))
    }

    @Test fun `three corners in a line are not`() {
        val flat = whole.copy(topRight = CropPoint(1000f, 500f), bottomRight = CropPoint(1000f, 1000f),
            topLeft = CropPoint(1000f, 0f))
        assertFalse(flat.isWarpable(minEdge))
    }

    @Test fun `a mirrored quad is not`() {
        val mirrored = CropQuad(whole.topRight, whole.topLeft, whole.bottomLeft, whole.bottomRight)
        assertFalse(mirrored.isWarpable(minEdge))
    }

    @Test fun `a corner sharper than 15 degrees is not, and 16 degrees is`() {
        // Parallelograms with angles 14/166 and 16/164 degrees: tan 14° x 1000 = 249, tan 16° x 1000 = 287.
        fun leaning(h: Float) =
            CropQuad(CropPoint(0f, 0f), CropPoint(1000f, 0f), CropPoint(2000f, h), CropPoint(1000f, h))
        assertFalse(leaning(249f).isWarpable(minEdge))
        assertTrue(leaning(287f).isWarpable(minEdge))
    }

    @Test fun `a side shorter than the minimum is not`() {
        val pinched = whole.copy(topRight = CropPoint(10f, 0f))
        assertFalse(pinched.isWarpable(minEdge))
    }

    @Test fun `a corner goes exactly where it is dragged when that is allowed`() {
        val moved = whole.dragCorner(CropCorner.TOP_LEFT, CropPoint(100f, 120f), 1000f, 1000f)
        assertEquals(CropPoint(100f, 120f), moved.topLeft)
    }

    @Test fun `a corner dragged across the page stops before it folds`() {
        val moved = whole.dragCorner(CropCorner.TOP_LEFT, CropPoint(900f, 900f), 1000f, 1000f)
        assertTrue(moved.isWarpable(minEdge))
        assertEquals(moved.topLeft.x, moved.topLeft.y, 0.01f)        // on the line it was dragged along
        assertTrue(moved.topLeft.x in 1f..500f)                      // moved, but not past the diagonal
    }

    @Test fun `a corner dragged off the photograph is clamped to it`() {
        val start = whole.dragCorner(CropCorner.TOP_LEFT, CropPoint(10f, 10f), 1000f, 1000f)
        val off = start.dragCorner(CropCorner.TOP_LEFT, CropPoint(-50f, -50f), 1000f, 1000f)
        assertEquals(CropPoint(0f, 0f), off.topLeft)
    }

    @Test fun `an already crossed quad can still be pulled apart`() {
        val bowTie = whole.copy(topLeft = CropPoint(1000f, 0f), topRight = CropPoint(0f, 0f))
        val fixed = bowTie.dragCorner(CropCorner.TOP_LEFT, CropPoint(0f, 0f), 1000f, 1000f)
            .dragCorner(CropCorner.TOP_RIGHT, CropPoint(1000f, 0f), 1000f, 1000f)
        assertTrue(fixed.isWarpable(minEdge))
    }

    @Test fun `an edge slides across itself only`() {
        val moved = whole.dragEdge(CropEdge.TOP, CropPoint(300f, 100f), 1000f, 1000f)
        assertEquals(CropPoint(0f, 100f), moved.topLeft)
        assertEquals(CropPoint(1000f, 100f), moved.topRight)
        assertEquals(whole.bottomLeft, moved.bottomLeft)
    }

    @Test fun `an edge stops at the photograph's border with both ends`() {
        val slanted = CropQuad(
            CropPoint(100f, 50f), CropPoint(900f, 150f), CropPoint(900f, 900f), CropPoint(100f, 900f),
        )
        val moved = slanted.dragEdge(CropEdge.TOP, CropPoint(0f, -500f), 1000f, 1000f)
        // A small tolerance: reach() divides, so the end that stops may land a float step off 0.
        assertTrue(moved.topLeft.y >= -0.01f && moved.topRight.y >= -0.01f)
        assertTrue(moved.topLeft.y < 1f || moved.topRight.y < 1f)    // one end reached the border
        // still parallel
        assertEquals(slanted.topRight.y - slanted.topLeft.y, moved.topRight.y - moved.topLeft.y, 0.01f)
    }

    @Test fun `an edge cannot be pushed past the opposite one`() {
        val moved = whole.dragEdge(CropEdge.TOP, CropPoint(0f, 2000f), 1000f, 1000f)
        assertTrue(moved.isWarpable(minEdge))
        assertTrue(moved.topLeft.y <= 1000f - minEdge)
    }

    @Test fun `a corner wins over an edge middle within reach of both`() {
        val small = CropQuad.whole(60f, 60f)
        assertEquals(CropHandle.Corner(CropCorner.TOP_LEFT), small.handleAt(CropPoint(10f, 2f), radius = 24f))
    }

    @Test fun `the middle of a side is an edge handle, and far away is nothing`() {
        assertEquals(CropHandle.Edge(CropEdge.TOP), whole.handleAt(CropPoint(500f, 10f), radius = 24f))
        assertNull(whole.handleAt(CropPoint(500f, 500f), radius = 24f))
    }

    @Test fun `short sides have no middle handle`() {
        assertTrue(CropQuad.whole(60f, 60f).edgeHandles(radius = 24f).isEmpty())
    }

    @Test fun `ten thousand random drags never leave an unwarpable quad`() {
        val random = kotlin.random.Random(182)
        var quad = whole
        repeat(10_000) {
            val to = CropPoint(random.nextFloat() * 1200f - 100f, random.nextFloat() * 1200f - 100f)
            quad = if (random.nextBoolean()) {
                quad.dragCorner(CropCorner.entries[random.nextInt(4)], to, 1000f, 1000f)
            } else {
                quad.dragEdge(CropEdge.entries[random.nextInt(4)], CropPoint(to.x - 500f, to.y - 500f), 1000f, 1000f)
            }
            assertTrue(quad.isWarpable(minEdge), "unwarpable after step $it: $quad")
        }
    }

    @Test fun `turning and unturning a point gives it back`() {
        val p = CropPoint(123f, 456f)
        listOf(0, 90, 180, 270).forEach { r -> assertEquals(p, p.turned(r, 800f, 600f).unturned(r, 800f, 600f)) }
    }

    @Test fun `a quarter turn shows the top left corner at the top right`() {
        assertEquals(CropPoint(600f, 0f), CropPoint(0f, 0f).turned(90, 800f, 600f))
        assertEquals(CropCorner.TOP_RIGHT, CropCorner.TOP_LEFT.shownAs(90))
        assertEquals(listOf(CropCorner.TOP_LEFT, CropCorner.TOP_RIGHT, CropCorner.BOTTOM_RIGHT, CropCorner.BOTTOM_LEFT),
            CropCorner.entries)                                        // shownAs depends on this order
    }

    @Test fun `warpability does not depend on how the picture is turned`() {
        val q = CropQuad(CropPoint(100f, 80f), CropPoint(700f, 120f), CropPoint(760f, 560f), CropPoint(60f, 500f))
        listOf(90, 180, 270).forEach { r ->
            val t = CropQuad(q.topLeft.turned(r, 800f, 600f), q.topRight.turned(r, 800f, 600f),
                q.bottomRight.turned(r, 800f, 600f), q.bottomLeft.turned(r, 800f, 600f))
            assertEquals(q.isWarpable(minEdge), t.isWarpable(minEdge))
        }
    }

    private fun editor() = CropEditor(quad = whole, width = 1000f, height = 1000f)

    @Test fun `a press away from any handle holds nothing and a drag then changes nothing`() {
        val e = editor().press(CropPoint(500f, 500f), radius = 24f).drag(CropPoint(700f, 700f))
        assertNull(e.holding)
        assertEquals(whole, e.quad)
        assertEquals(whole, e.release().quad)
        assertTrue(e.release().history.isEmpty())
    }

    @Test fun `the grab offset keeps a corner from jumping under the finger`() {
        val e = editor().press(CropPoint(10f, 10f), radius = 24f).drag(CropPoint(60f, 10f))
        assertEquals(CropPoint(50f, 0f), e.quad.topLeft)
    }

    @Test fun `a gesture is one step of history and undo restores it`() {
        val e = editor().press(CropPoint(0f, 0f), 24f).drag(CropPoint(50f, 40f)).drag(CropPoint(100f, 80f)).release()
        assertEquals(CropPoint(100f, 80f), e.quad.topLeft)
        assertEquals(listOf(whole), e.history)
        assertEquals(whole, e.undo().quad)
        assertFalse(e.undo().canUndo)
    }

    @Test fun `history is capped`() {
        var e = editor()
        repeat(CropEditor.HISTORY + 5) { e = e.nudge(CropHandle.Corner(CropCorner.TOP_LEFT), 1f, 0f) }
        assertEquals(CropEditor.HISTORY, e.history.size)
    }

    @Test fun `turning four times changes nothing about the quad`() {
        var e = editor()
        repeat(4) { e = e.turn(clockwise = true) }
        assertEquals(0, e.rotation)
        assertEquals(whole, e.quad)
        assertEquals(270, editor().turn(clockwise = false).rotation)
    }

    @Test fun `reset is one undoable step`() {
        val other = whole.copy(topLeft = CropPoint(100f, 100f))
        val e = editor().reset(other)
        assertEquals(other, e.quad)
        assertEquals(whole, e.undo().quad)
    }

    @Test fun `a nudge respects the rule and a blocked one changes nothing`() {
        val small = CropEditor(quad = CropQuad.whole(100f, 100f), width = 100f, height = 100f)
        val blocked = small.nudge(CropHandle.Corner(CropCorner.TOP_LEFT), -5f, -5f)
        assertEquals(small, blocked)
        assertTrue(editor().nudge(CropHandle.Corner(CropCorner.TOP_LEFT), 10f, 10f).canConfirm)
    }

    @Test fun `limited is true only while the finger is past where the corner can follow`() {
        val held = editor().press(CropPoint(0f, 0f), 24f)
        assertFalse(held.drag(CropPoint(200f, 200f)).limited)
        assertTrue(held.drag(CropPoint(990f, 990f)).limited)
    }
}
