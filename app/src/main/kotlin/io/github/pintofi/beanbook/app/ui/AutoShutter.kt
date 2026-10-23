package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.CropQuad
import kotlin.math.hypot

/**
 * Decides when the scanner shoots by itself.
 *
 * The rule, taken from how OSS-DocumentScanner behaves (MIT; read, not copied): the page has to
 * hold still for a moment, which [progress] shows as a countdown, and after a shot the shutter
 * is **disarmed** until the page in view changes -- it is taken away, or another one is put in
 * its place. Without that, a receipt left on the table is shot again every second and a half and
 * one page becomes several.
 *
 * It sees one detection per sample and says whether to shoot. Whoever shoots, this or the
 * shutter button, tells it with [shot], so a page photographed by hand is not photographed again
 * by the timer either.
 */
internal class AutoShutter(private val stillSamples: Int = STILL_SAMPLES) {

    private var armed = true
    private var steady = 0
    private var previous: CropQuad? = null
    private var shotAt: CropQuad? = null
    private var missing = 0

    /** How far through the hold the page is, 0 to 1; 0 when disarmed or nothing is found. */
    val progress: Float
        get() = if (armed) (steady.toFloat() / stillSamples).coerceIn(0f, 1f) else 0f

    /** Whether the shutter would fire on its own right now. */
    val isArmed: Boolean get() = armed

    /**
     * One look at the viewfinder. [quad] is the page found in it, or null; [diagonal] is the
     * length of the frame's diagonal, which corner movement is measured against.
     * Returns true when the page has held still long enough and the shutter should fire.
     */
    fun sample(quad: CropQuad?, diagonal: Float): Boolean {
        if (!armed) {
            missing = if (quad == null) missing + 1 else 0
            val gone = missing >= GONE_SAMPLES
            val replaced = quad != null && shotAt?.let { !quad.within(it, diagonal * CHANGED_FRACTION) } != false
            if (gone || replaced) {
                armed = true
                steady = 0
            }
            previous = quad
            return false
        }
        val still = quad != null && previous?.let { quad.within(it, diagonal * STILL_FRACTION) } == true
        previous = quad
        steady = if (still) steady + 1 else 0
        return steady >= stillSamples
    }

    /** A page was shot, [quad] being the page that was in view. Disarms until it changes. */
    fun shot(quad: CropQuad?) {
        armed = false
        steady = 0
        previous = null
        shotAt = quad
        missing = 0
    }

    companion object {
        /** Consecutive still samples before the shutter fires: about a second at one sample every 0.4 s. */
        const val STILL_SAMPLES = 3

        /** A corner may move this far between samples, as a fraction of the diagonal, and still count as still. */
        const val STILL_FRACTION = 0.025f

        /** The page counts as a different one when a corner is this far from where the last shot found it. */
        const val CHANGED_FRACTION = 0.10f

        /** Samples with no page at all before the last page is taken to be gone. */
        const val GONE_SAMPLES = 3
    }
}

/** Whether every corner of this outline is within [limit] of the same corner of [other]. */
internal fun CropQuad.within(other: CropQuad, limit: Float): Boolean {
    val mine = listOf(topLeft, topRight, bottomRight, bottomLeft)
    val theirs = listOf(other.topLeft, other.topRight, other.bottomRight, other.bottomLeft)
    return mine.zip(theirs).all { (a, b) -> hypot(a.x - b.x, a.y - b.y) <= limit }
}
