package dev.beancount.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.abs

/**
 * Checks made on the **pixels**, because the semantics tree cannot see them.
 *
 * `AccessibilityTest` works over the semantics tree, and there is a class of bug it is
 * structurally unable to catch: a child that Compose lays out *inside* its parent and then
 * draws clipped. A `Switch` shipped that way in two screens — visibly cut in half at the
 * right edge of the phone, and unusable — while every semantics check stayed green, because
 * the node's reported bounds were inside the window. Three attempts to make the tree see it
 * failed (docs/milestones/m12-part1.md). Only looking at what was drawn works.
 *
 * This is deliberately **not** golden-image comparison. A golden catches any change, which
 * sounds better and is worse: it fails on every intentional edit, on a font metric changing
 * between API levels, and on antialiasing, until someone regenerates the goldens without
 * reading the diff — at which point it catches nothing. What is here instead is a small
 * number of rules about what a correct render looks like, each of which fails only when
 * something is actually wrong.
 */
object PixelGate {

    /** How far two colours may differ and still count as the same. */
    private const val CHANNEL_TOLERANCE = 8

    /**
     * Fails if anything is drawn within [marginPx] of the left or right edge.
     *
     * Every screen in this app has padding, so its outer ring is background. Content there
     * means something has run off the edge — which is exactly what a clipped control looks
     * like, and is invisible from the semantics tree.
     *
     * @param marginPx how much padding the screen under test actually has, in pixels. Stated
     *   by the caller rather than guessed: a screen with a full-bleed element legitimately
     *   touches its edges, and a check that silently assumed otherwise would be wrong about
     *   the WebView the first time someone pointed it at one.
     */
    /**
     * @param includeVertical whether to check the top and bottom edges too. Off by default:
     *   every screen here scrolls, so content continuing past the bottom of the viewport is
     *   scrolling working, not a defect. **Horizontal** overflow is the bug class this exists
     *   for — a control pushed off the right edge, which no amount of scrolling reveals.
     */
    fun assertNothingDrawnInMargin(
        image: ImageBitmap,
        marginPx: Int,
        includeVertical: Boolean = false,
    ): List<String> {
        val bitmap = image.asAndroidBitmap()
        val width = bitmap.width
        val height = bitmap.height
        if (width < marginPx * 2 || height < marginPx * 2) return emptyList()

        val background = backgroundOf(bitmap)
            ?: return listOf("the four corners disagree about the background colour")

        val problems = mutableListOf<String>()

        fun scan(name: String, xs: IntRange, ys: IntRange) {
            var offenders = 0
            var firstX = -1
            var firstY = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var minX = Int.MAX_VALUE
            var maxX = -1
            for (y in ys) {
                for (x in xs) {
                    if (!similar(bitmap.getPixel(x, y), background)) {
                        if (offenders == 0) {
                            firstX = x
                            firstY = y
                        }
                        offenders++
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
            }
            if (offenders > 0) {
                // The colour matters more than the count when diagnosing: it says whether
                // this is the app's own content or something else in the captured surface.
                val colour = "#%08x".format(bitmap.getPixel(firstX, firstY))
                problems += "$name edge: $offenders pixels drawn in the ${marginPx}px margin, " +
                    "rows ${minY}..${maxY}, cols ${minX}..${maxX}, " +
                    "first at ($firstX, $firstY) colour $colour against background " +
                    "#%08x".format(background) + " — something is running off the edge"
            }
        }

        scan("left", 0 until marginPx, 0 until height)
        scan("right", (width - marginPx) until width, 0 until height)
        if (includeVertical) {
            scan("top", 0 until width, 0 until marginPx)
            scan("bottom", 0 until width, (height - marginPx) until height)
        }
        return problems
    }

    /**
     * The screen's background, agreed by all four corners.
     *
     * If they disagree the screen is not the plain padded surface this check assumes, and
     * saying so beats guessing which corner was right.
     */
    private fun backgroundOf(bitmap: android.graphics.Bitmap): Int? {
        val corners = listOf(
            bitmap.getPixel(0, 0),
            bitmap.getPixel(bitmap.width - 1, 0),
            bitmap.getPixel(0, bitmap.height - 1),
            bitmap.getPixel(bitmap.width - 1, bitmap.height - 1),
        )
        return corners.first().takeIf { first -> corners.all { similar(it, first) } }
    }

    private fun similar(a: Int, b: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= CHANNEL_TOLERANCE &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= CHANNEL_TOLERANCE &&
            abs((a and 0xFF) - (b and 0xFF)) <= CHANNEL_TOLERANCE
}
