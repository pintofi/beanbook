package io.github.pintofi.beanbook.core.domain

import kotlin.math.atan2
import kotlin.math.hypot

/** A four-cornered shape one detection pass found, in the working image's pixels. */
data class PageCandidate(val quad: CropQuad, val pass: Int)

/** What counts as a page, and how sure to be before stopping early. */
data class PageChoiceRules(
    /** Fraction of the frame below which a shape is a label or a stain. */
    val minArea: Float = 0.12f,
    /** Above this it is the frame itself. */
    val maxArea: Float = 0.98f,
    /** |cos| of every corner must be below this: angles between 66 and 114 degrees. */
    val maxCosine: Float = 0.40f,
    /** Corners within this fraction of the frame's diagonal are the same page. */
    val sameFraction: Float = 0.02f,
    /** Score bonus for every further pass that agrees. */
    val supportStep: Float = 0.10f,
    val supportCap: Int = 5,
    /** The early exit: two passes agree on a page that is big and square enough. */
    val goodEnoughCosine: Float = 0.30f,
    val goodEnoughArea: Float = 0.20f,
    val goodEnoughSupport: Int = 2,
    /** An outer shape that holds the winner, and is this much bigger, is the page instead. */
    val outerGain: Float = 1.15f,
    val containTolerance: Float = 0.01f,
    /**
     * A corner this close to the frame's edge (as a fraction of the diagonal) counts as on it. A shape
     * with more than [maxEdgeCorners] such corners is the frame's own shape -- the edge of a shadow
     * or of the table -- not a page lying inside it.
     */
    val edgeFraction: Float = 0.01f,
    val maxEdgeCorners: Int = 2,
)

/**
 * Choosing the page from what several detection passes found.
 *
 * Pure and deterministic: the answer depends on the set of candidates, never on the order they
 * arrive in or on how busy the phone was, so the live outline does not flicker between two
 * pages and auto-capture can see the page hold still.
 */
object PageChoice {

    private class Group(val members: List<PageCandidate>, val frameArea: Float) {
        val support: Int = members.map { it.pass }.toSet().size
        val rep: CropQuad = median(members.map { it.quad })
        val areaShare: Float = rep.area() / frameArea
        val cosine: Float = rep.maxCornerCosine()
        val firstPass: Int = members.minOf { it.pass }
    }

    /**
     * The page, or null. [legacy] is the first pass's own answer, returned when nothing else
     * qualifies, so adding passes can never lose a page the first pass found.
     */
    fun choose(
        candidates: List<PageCandidate>,
        width: Float,
        height: Float,
        legacy: CropQuad?,
        rules: PageChoiceRules = PageChoiceRules(),
    ): CropQuad? {
        val groups = ranked(candidates, width, height, rules)
        val winner = groups.firstOrNull() ?: return legacy
        var page = winner
        // The outermost page wins: a printed box on the page must not beat the page around it.
        val diagonal = hypot(width, height)
        while (true) {
            val outer = groups.firstOrNull { other ->
                other !== page && other.rep.area() >= rules.outerGain * page.rep.area() &&
                    page.rep.corners().all { other.rep.contains(it, rules.containTolerance * diagonal) }
            } ?: break
            page = outer
        }
        return page.rep
    }

    /** Whether the passes so far already agree on a clear page. */
    fun settled(
        candidates: List<PageCandidate>,
        width: Float,
        height: Float,
        rules: PageChoiceRules = PageChoiceRules(),
    ): Boolean {
        val best = ranked(candidates, width, height, rules).firstOrNull() ?: return false
        return best.support >= rules.goodEnoughSupport &&
            best.areaShare >= rules.goodEnoughArea &&
            best.cosine < rules.goodEnoughCosine
    }

    private fun ranked(
        candidates: List<PageCandidate>,
        width: Float,
        height: Float,
        rules: PageChoiceRules,
    ): List<Group> {
        val frameArea = width * height
        val diagonal = hypot(width, height)
        val margin = rules.edgeFraction * diagonal
        val usable = candidates.filter {
            val share = it.quad.area() / frameArea
            share >= rules.minArea && share <= rules.maxArea && it.quad.maxCornerCosine() < rules.maxCosine &&
                it.quad.corners().count { c -> onEdge(c, width, height, margin) } <= rules.maxEdgeCorners
        }.sortedWith(CANDIDATE_ORDER)

        val buckets = mutableListOf<MutableList<PageCandidate>>()
        for (candidate in usable) {
            val home = buckets.firstOrNull { it.first().quad.isNear(candidate.quad, rules.sameFraction * diagonal) }
            if (home != null) home += candidate else buckets += mutableListOf(candidate)
        }
        return buckets.map { Group(it, frameArea) }
            .sortedWith(
                compareByDescending<Group> { score(it, rules) }
                    .thenBy { it.firstPass }
                    .thenBy { it.rep.topLeft.x }
                    .thenBy { it.rep.topLeft.y }
                    .thenBy { it.rep.bottomRight.x }
                    .thenBy { it.rep.bottomRight.y },
            )
    }

    private fun score(group: Group, rules: PageChoiceRules): Float =
        group.areaShare * (1f - group.cosine) *
            (1f + rules.supportStep * minOf(group.support - 1, rules.supportCap))

    private val CANDIDATE_ORDER: Comparator<PageCandidate> = compareBy<PageCandidate> { it.pass }
        .thenBy { it.quad.topLeft.x }.thenBy { it.quad.topLeft.y }
        .thenBy { it.quad.topRight.x }.thenBy { it.quad.topRight.y }
        .thenBy { it.quad.bottomRight.x }.thenBy { it.quad.bottomRight.y }
        .thenBy { it.quad.bottomLeft.x }.thenBy { it.quad.bottomLeft.y }

    /** The corner-wise median: each of the eight coordinates on its own, taking the lower middle. */
    private fun median(quads: List<CropQuad>): CropQuad {
        fun middle(values: List<Float>) = values.sorted()[(values.size - 1) / 2]
        fun point(of: (CropQuad) -> CropPoint) =
            CropPoint(middle(quads.map { of(it).x }), middle(quads.map { of(it).y }))
        return CropQuad(
            point { it.topLeft }, point { it.topRight }, point { it.bottomRight }, point { it.bottomLeft },
        )
    }

    private fun onEdge(p: CropPoint, width: Float, height: Float, margin: Float) =
        p.x <= margin || p.y <= margin || p.x >= width - margin || p.y >= height - margin

    private fun CropQuad.corners() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * Four corners in a known order -- clockwise from the top-left -- whichever way the detector
     * listed them. Sorted by angle around the centre, then started at the point with the smallest
     * x + y. The sum-and-difference sort this replaces can return one corner twice for a page
     * turned about 45 degrees, where two corners have the same x + y.
     */
    fun orderCorners(points: List<CropPoint>): CropQuad {
        require(points.size == 4) { "a page has four corners, got ${points.size}" }
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4f
        val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = clockwise.indices.minWith(
            compareBy<Int> { clockwise[it].x + clockwise[it].y }.thenBy { clockwise[it].y },
        )
        val l = List(4) { clockwise[(start + it) % 4] }
        return CropQuad(l[0], l[1], l[2], l[3])
    }
}
