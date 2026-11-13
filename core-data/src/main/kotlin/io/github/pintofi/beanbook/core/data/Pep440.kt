package io.github.pintofi.beanbook.core.data

import java.math.BigInteger

/**
 * A Python version as PEP 440 orders it, and the specifiers packages use to say which versions they
 * work with (`>=2.1,<3`, `~=1.4.2`, `==1.*`).
 *
 * Needed to tell a newer version from a different one: `2.10` is newer than `2.9`, `1.0rc1` is older
 * than `1.0`, and `1.0.post1` is newer. Comparing the strings, as the catalogue did, offered a
 * downgrade as an update the moment an installed version was ahead of the one the app carried.
 * No dependency: this is the part of the specification an update check uses.
 */
class Pep440 private constructor(
    private val epoch: BigInteger,
    private val release: List<BigInteger>,
    private val pre: Pair<Int, BigInteger>?,
    private val post: BigInteger?,
    private val dev: BigInteger?,
    private val local: List<Any>?,
    val text: String,
) : Comparable<Pep440> {

    val isPreRelease: Boolean get() = pre != null || dev != null

    /** The release segments, for `==1.*` and `~=`. */
    internal val releaseParts: List<BigInteger> get() = release

    override fun compareTo(other: Pep440): Int {
        epoch.compareTo(other.epoch).let { if (it != 0) return it }
        compareRelease(release, other.release).let { if (it != 0) return it }
        compareRank(preRank(), other.preRank()).let { if (it != 0) return it }
        compareRank(postRank(), other.postRank()).let { if (it != 0) return it }
        compareRank(devRank(), other.devRank()).let { if (it != 0) return it }
        return compareLocal(local, other.local)
    }

    override fun equals(other: Any?): Boolean = other is Pep440 && compareTo(other) == 0
    override fun hashCode(): Int = (release.dropLastWhile { it == BigInteger.ZERO } + epoch).hashCode()
    override fun toString(): String = text

    /** Whether this is a development release of a pre-release version with nothing else, e.g. `1.0.dev1`. */
    private fun preRank(): Rank = when {
        pre == null && post == null && dev != null -> Rank.Low
        pre == null -> Rank.High
        else -> Rank.Value(pre.first.toBigInteger() * HUNDRED + pre.second)
    }

    private fun postRank(): Rank = post?.let { Rank.Value(it) } ?: Rank.Low
    private fun devRank(): Rank = dev?.let { Rank.Value(it) } ?: Rank.High

    private sealed interface Rank {
        data object Low : Rank
        data object High : Rank
        data class Value(val number: BigInteger) : Rank
    }

    private fun compareRank(a: Rank, b: Rank): Int {
        fun order(rank: Rank) = when (rank) {
            Rank.Low -> 0
            is Rank.Value -> 1
            Rank.High -> 2
        }
        if (a is Rank.Value && b is Rank.Value) return a.number.compareTo(b.number)
        return order(a).compareTo(order(b))
    }

    private fun compareRelease(a: List<BigInteger>, b: List<BigInteger>): Int {
        val x = a.dropLastWhile { it == BigInteger.ZERO }
        val y = b.dropLastWhile { it == BigInteger.ZERO }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: BigInteger.ZERO).compareTo(y.getOrNull(i) ?: BigInteger.ZERO)
            if (c != 0) return c
        }
        return 0
    }

    private fun compareLocal(a: List<Any>?, b: List<Any>?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            val c = when {
                x is BigInteger && y is BigInteger -> x.compareTo(y)
                x is BigInteger -> 1 // a number is newer than a word
                y is BigInteger -> -1
                else -> (x as String).compareTo(y as String)
            }
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    companion object {
        private val HUNDRED = BigInteger.valueOf(100)

        private val PATTERN = Regex(
            """^\s*v?(?:(\d+)!)?(\d+(?:\.\d+)*)""" +
                """(?:[-_.]?(alpha|a|beta|b|preview|pre|c|rc)[-_.]?(\d*))?""" +
                """(?:(?:-(\d+))|(?:[-_.]?(post|rev|r)[-_.]?(\d*)))?""" +
                """(?:[-_.]?(dev)[-_.]?(\d*))?""" +
                """(?:\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /** The version [text] names, or null when it is not a PEP 440 version (a commit, a tag like `v1-beta`). */
        fun parseOrNull(text: String): Pep440? {
            val m = PATTERN.matchEntire(text.lowercase()) ?: return null
            val g = m.groupValues
            fun number(s: String) = if (s.isEmpty()) BigInteger.ZERO else BigInteger(s)
            val pre = g[3].takeIf { it.isNotEmpty() }?.let {
                val kind = when (it) {
                    "alpha", "a" -> 0
                    "beta", "b" -> 1
                    else -> 2 // c, rc, pre, preview
                }
                kind to number(g[4])
            }
            val post = when {
                g[5].isNotEmpty() -> BigInteger(g[5])
                g[6].isNotEmpty() -> number(g[7])
                else -> null
            }
            val dev = g[8].takeIf { it.isNotEmpty() }?.let { number(g[9]) }
            val local = g[10].takeIf { it.isNotEmpty() }?.split('-', '_', '.')
                ?.map { part -> part.toBigIntegerOrNull() ?: part }
            return Pep440(
                epoch = number(g[1]),
                release = g[2].split('.').map { BigInteger(it) },
                pre = pre,
                post = post,
                dev = dev,
                local = local,
                text = text.trim(),
            )
        }

        fun parse(text: String): Pep440 = parseOrNull(text) ?: throw IllegalArgumentException("not a version: $text")
    }
}

/**
 * A set of version specifiers, `>=2.1,<3`, as `Requires-Python` and `Requires-Dist` write them.
 * An empty set matches every version.
 */
class PySpecifiers private constructor(private val clauses: List<Clause>) {

    private data class Clause(val operator: String, val text: String)

    /** Whether [version] satisfies every clause. Pre-releases only match a clause that names one. */
    fun matches(version: Pep440): Boolean {
        val allowPre = clauses.any { c -> Pep440.parseOrNull(c.text.removeSuffix(".*"))?.isPreRelease == true }
        if (version.isPreRelease && !allowPre) return false
        return clauses.all { holds(it, version) }
    }

    private fun holds(clause: Clause, version: Pep440): Boolean {
        val wildcard = clause.text.endsWith(".*")
        val spec = Pep440.parseOrNull(clause.text.removeSuffix(".*")) ?: return false
        return when (clause.operator) {
            "==" -> if (wildcard) startsWith(version, spec) else version.withoutLocalFor(spec) == spec
            "!=" -> if (wildcard) !startsWith(version, spec) else version.withoutLocalFor(spec) != spec
            "<=" -> version.withoutLocalFor(spec) <= spec
            ">=" -> version.withoutLocalFor(spec) >= spec
            "<" -> version < spec && !(!spec.isPreRelease && version.isPreRelease && sameRelease(version, spec))
            ">" -> version > spec && !(isPost(version) && !isPost(spec) && sameRelease(version, spec))
            "~=" -> compatible(version, spec)
            "===" -> version.text.equals(clause.text, ignoreCase = true)
            else -> false
        }
    }

    private fun isPost(v: Pep440): Boolean {
        val text = v.text.lowercase()
        return "post" in text || ".r" in text || "-r" in text || Regex("-\\d+$").containsMatchIn(text)
    }

    private fun sameRelease(a: Pep440, b: Pep440): Boolean =
        trimmed(a.releaseParts) == trimmed(b.releaseParts)

    private fun trimmed(parts: List<java.math.BigInteger>) = parts.dropLastWhile { it == java.math.BigInteger.ZERO }

    private fun startsWith(version: Pep440, prefix: Pep440): Boolean {
        val p = prefix.releaseParts
        val v = version.releaseParts
        return p.indices.all { (v.getOrNull(it) ?: java.math.BigInteger.ZERO) == p[it] }
    }

    private fun compatible(version: Pep440, spec: Pep440): Boolean {
        val parts = spec.releaseParts
        if (parts.size < 2) return false
        val prefix = parts.dropLast(1)
        val v = version.releaseParts
        val sameLead = prefix.indices.all { (v.getOrNull(it) ?: java.math.BigInteger.ZERO) == prefix[it] }
        return sameLead && version >= spec
    }

    /** A version with a local part compares as its public part against a specifier that has none. */
    private fun Pep440.withoutLocalFor(spec: Pep440): Pep440 =
        if (spec.text.contains('+') || !text.contains('+')) this else Pep440.parse(text.substringBefore('+'))

    companion object {
        private val CLAUSE = Regex("""^\s*(===|==|!=|<=|>=|~=|<|>)\s*([^\s,]+)\s*$""")

        /** The specifiers in [text], or null when one is not understood. */
        fun parseOrNull(text: String): PySpecifiers? {
            val parts = text.removeSurrounding("(", ")").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val clauses = parts.map { part ->
                val m = CLAUSE.matchEntire(part) ?: return null
                Clause(m.groupValues[1], m.groupValues[2])
            }
            return PySpecifiers(clauses)
        }
    }
}
