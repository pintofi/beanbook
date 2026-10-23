package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner used to shoot a page again every second and a half for as long as it stayed in
 * view, because the shutter reset itself after each shot and the page was still there.
 */
class AutoShutterTest {

    private val diagonal = 1000f

    /** A page of about 400 x 600 whose corner sits at ([x], [y]). */
    private fun page(x: Float = 100f, y: Float = 100f) = CropQuad(
        CropPoint(x, y), CropPoint(x + 400, y), CropPoint(x + 400, y + 600), CropPoint(x, y + 600),
    )

    private fun AutoShutter.samples(quad: CropQuad?, times: Int): List<Boolean> =
        List(times) { sample(quad, diagonal) }

    @Test
    fun `a page that holds still is shot after the hold, not before`() {
        val shutter = AutoShutter(stillSamples = 3)

        // The first sample has nothing to compare with, so three still ones follow it.
        assertEquals(listOf(false, false, false, true), shutter.samples(page(), 4))
    }

    @Test
    fun `the countdown fills as the page holds still and empties when it moves`() {
        val shutter = AutoShutter(stillSamples = 4)
        shutter.sample(page(), diagonal)
        shutter.sample(page(), diagonal)
        assertEquals(0.25f, shutter.progress, 0.001f)
        shutter.sample(page(), diagonal)
        assertEquals(0.5f, shutter.progress, 0.001f)

        shutter.sample(page(x = 300f), diagonal)

        assertEquals(0f, shutter.progress, 0.001f)
    }

    @Test
    fun `a page that keeps moving is never shot`() {
        val shutter = AutoShutter()
        val fired = (0 until 12).map { shutter.sample(page(x = 100f + it * 80f), diagonal) }
        assertFalse(fired.any { it })
    }

    @Test
    fun `a page left in view is shot once, not again`() {
        val shutter = AutoShutter()
        shutter.samples(page(), 4).also { assertTrue(it.last()) }
        shutter.shot(page())

        val after = shutter.samples(page(), 30)

        assertFalse("the same page was shot again", after.any { it })
        assertEquals(0f, shutter.progress, 0.001f)
    }

    @Test
    fun `a page that shifts a little in the hand is still the same page`() {
        val shutter = AutoShutter()
        shutter.shot(page())

        val fired = (0 until 12).map { shutter.sample(page(x = 100f + (it % 2) * 30f), diagonal) }

        assertFalse(fired.any { it })
        assertFalse(shutter.isArmed)
    }

    @Test
    fun `taking the page away and putting it back is a new page`() {
        val shutter = AutoShutter()
        shutter.shot(page())
        shutter.samples(null, AutoShutter.GONE_SAMPLES)

        assertTrue(shutter.isArmed)
        assertEquals(listOf(false, false, false, true), shutter.samples(page(), 4))
    }

    @Test
    fun `a flicker of one missed detection does not rearm the same page`() {
        val shutter = AutoShutter()
        shutter.shot(page())

        shutter.sample(null, diagonal)
        shutter.sample(page(), diagonal)

        assertFalse(shutter.isArmed)
    }

    @Test
    fun `a different page put in the same place is shot after the old one`() {
        val shutter = AutoShutter()
        shutter.shot(page())

        // The first look at the new page re-arms the shutter and counts as the sample to compare with.
        shutter.sample(page(x = 420f, y = 300f), diagonal)

        assertTrue(shutter.isArmed)
        assertEquals(listOf(false, false, true), shutter.samples(page(x = 420f, y = 300f), 3))
    }

    @Test
    fun `a shot taken by hand also disarms the timer`() {
        val shutter = AutoShutter()
        shutter.samples(page(), 2)

        shutter.shot(page()) // the shutter button

        assertFalse(shutter.samples(page(), 20).any { it })
    }

    @Test
    fun `a shot with no page found rearms as soon as one appears`() {
        val shutter = AutoShutter()
        shutter.shot(null)

        shutter.sample(page(), diagonal)

        assertTrue(shutter.isArmed)
    }
}
