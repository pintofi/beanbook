package io.github.pintofi.beanbook.core.data

import java.io.File

/** One group of a release's entries: Added, Changed, Fixed and so on. */
data class WhatsNewGroup(val title: String, val entries: List<String>)

/** One release as the changelog lists it. [date] is `YYYY-MM-DD`, or null when the heading has none. */
data class WhatsNewRelease(val version: String, val date: String?, val groups: List<WhatsNewGroup>)

/**
 * The changelog as the "What's new" sheet shows it: the releases up to the installed version, newest first,
 * with the references to pull requests and issues taken out, since the sheet opens nothing and works with no
 * network.
 *
 * The text is CHANGELOG.md itself, copied into the app when it is built, so there is nothing to keep in step by
 * hand. `[Unreleased]` is skipped, so a debug build does not announce work that has not shipped.
 */
object WhatsNew {

    /** Entries that only the Expert edition has start with this, and the Play build is made without them. */
    const val EXPERT_PREFIX = "Expert edition:"

    private val RELEASE = Regex("""^##\s+\[(\d+\.\d+\.\d+)](?:\s+-\s+(\d{4}-\d{2}-\d{2}))?.*$""")
    private val GROUP = Regex("""^###\s+(.+?)\s*$""")
    private val TRAILING_REFERENCES = Regex("""\s*\([^()]*\[#\d+][^()]*\)\s*$""")
    private val LOOSE_REFERENCE = Regex("""\s*\[#\d+]""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val VERSION = Regex("""\d+\.\d+\.\d+""")

    /** Whether [text] is a version number of the form `major.minor.patch`. */
    fun isVersion(text: String) = VERSION.matches(text)

    /** Compares two `major.minor.patch` versions numerically. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** One entry without its trailing references and without link targets. */
    fun clean(entry: String): String =
        entry.replace(TRAILING_REFERENCES, "").replace(LOOSE_REFERENCE, "").replace(LINK, "$1").trim()

    /**
     * The releases in [changelog] that are [upTo] the installed version or older, newest first. [expert] says
     * whether the Expert-only entries stay in; a Play build is made without them already, and this keeps a
     * hand-made copy honest.
     */
    fun parse(changelog: String, upTo: String, expert: Boolean): List<WhatsNewRelease> {
        val releases = mutableListOf<WhatsNewRelease>()
        var version: String? = null
        var date: String? = null
        var groups = mutableListOf<WhatsNewGroup>()
        var group: String? = null
        var entries = mutableListOf<String>()
        var open = false

        fun closeGroup() {
            val title = group
            if (title != null) {
                val kept = entries.map(::clean).filter { it.isNotEmpty() && (expert || !it.startsWith(EXPERT_PREFIX)) }
                if (kept.isNotEmpty()) groups += WhatsNewGroup(title, kept)
            }
            group = null
            entries = mutableListOf()
        }

        fun closeRelease() {
            closeGroup()
            val v = version
            if (v != null && groups.isNotEmpty() && compare(v, upTo) <= 0) releases += WhatsNewRelease(v, date, groups)
            version = null
            date = null
            groups = mutableListOf()
        }

        for (line in changelog.lines()) {
            if (line.startsWith("## ")) {
                closeRelease()
                val match = RELEASE.matchEntire(line.trimEnd())
                version = match?.groupValues?.get(1)
                date = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
                open = version != null
                continue
            }
            if (!open) continue
            val heading = GROUP.matchEntire(line.trimEnd())
            when {
                heading != null -> {
                    closeGroup()
                    group = heading.groupValues[1]
                }
                line.startsWith("- ") && group != null -> entries += line.removePrefix("- ").trim()
                line.isNotBlank() && line.first().isWhitespace() && entries.isNotEmpty() ->
                    entries[entries.lastIndex] = entries.last() + " " + line.trim()
            }
        }
        closeRelease()
        return releases.sortedWith { a, b -> compare(b.version, a.version) }
    }
}

/**
 * Which version's changes this install has been shown, kept in the app's state folder as one line.
 *
 * It is not about the person or the ledger, so it is not in a backup or a mirror. The rules are conservative: a
 * fresh install, or a file that cannot be read, is recorded without showing anything; an update from a version
 * that did not keep the file shows the sheet once; a version no newer than the one recorded (a downgrade, or the
 * same one again) shows nothing and never lowers the record.
 */
class WhatsNewRecord(private val stateDir: File) {

    private val file get() = File(stateDir, "whats-new")

    /** The version last shown, or null when there is none or the file is not a version. */
    fun seen(): String? =
        runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(WhatsNew::isVersion)

    /** Whether to show what is new for [current]. [freshInstall] is true for a first install or cleared data. */
    fun isDue(current: String, freshInstall: Boolean): Boolean {
        val seen = seen()
        if (seen == null) {
            if (freshInstall || file.isFile) {
                markSeen(current)
                return false
            }
            return true
        }
        return WhatsNew.compare(current, seen) > 0
    }

    /** Records that [current] has been shown, never going back to an older version. */
    fun markSeen(current: String) {
        val seen = seen()
        val keep = if (seen != null && WhatsNew.compare(seen, current) > 0) seen else current
        stateDir.mkdirs()
        val tmp = File(stateDir, "whats-new.tmp")
        tmp.writeText("$keep\n")
        check(tmp.renameTo(file)) { "could not record the version shown" }
    }
}
