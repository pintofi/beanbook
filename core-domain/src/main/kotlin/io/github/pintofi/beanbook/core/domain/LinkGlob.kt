package io.github.pintofi.beanbook.core.domain

/**
 * Expands an `include` glob the way Beancount does: Python's `glob.glob(pattern, recursive=True)`,
 * relative to the folder of the including file.
 *
 * `*`, `?` and `[seq]` match inside one path segment and never a name that starts with a dot (unless
 * the pattern's segment starts with one); `**` as a whole segment matches any number of folders,
 * including none. Matches are files, in sorted order. Only the folders a pattern passes through are
 * listed, and nothing it does not match is returned.
 */
internal object LinkGlob {

    fun hasMagic(pattern: String): Boolean = pattern.any { it == '*' || it == '?' || it == '[' }

    fun expand(source: LinkedSource, baseDir: String, pattern: String, limit: Int): List<String> {
        val found = sortedSetOf<String>()
        val segments = pattern.split('/').filter { it.isNotEmpty() && it != "." }
        fun walk(current: String, remaining: List<String>) {
            if (remaining.isEmpty() || found.size > limit) return
            val segment = remaining.first()
            val rest = remaining.drop(1)
            when {
                segment == "**" -> {
                    if (rest.isEmpty()) {
                        everything(source, current, found, limit)
                    } else {
                        walk(current, rest)
                        source.children(current).orEmpty()
                            .filter { it.isDirectory && !it.name.startsWith('.') }
                            .forEach { walk(join(current, it.name), remaining) }
                    }
                }
                hasMagic(segment) -> {
                    val matcher = regexFor(segment)
                    source.children(current).orEmpty()
                        .filter { matcher.matches(it.name) && (!it.name.startsWith('.') || segment.startsWith('.')) }
                        .forEach { child ->
                            val path = join(current, child.name)
                            if (rest.isEmpty()) {
                                if (!child.isDirectory) found += path
                            } else if (child.isDirectory) {
                                walk(path, rest)
                            }
                        }
                }
                else -> {
                    val path = join(current, segment)
                    val stat = source.stat(path) ?: return
                    if (rest.isEmpty()) {
                        if (!stat.isDirectory) found += path
                    } else if (stat.isDirectory) {
                        walk(path, rest)
                    }
                }
            }
        }
        walk(baseDir, segments)
        return found.toList()
    }

    private fun everything(source: LinkedSource, dir: String, found: MutableSet<String>, limit: Int) {
        for (child in source.children(dir).orEmpty()) {
            if (child.name.startsWith('.') || found.size > limit) continue
            val path = join(dir, child.name)
            if (child.isDirectory) everything(source, path, found, limit) else found += path
        }
    }

    fun join(dir: String, name: String): String = LinkPath.normalise(if (dir.isEmpty()) name else "$dir/$name")

    /** `fnmatch.translate` for one segment: `*`, `?`, `[seq]` and `[!seq]`, case sensitive. */
    private fun regexFor(segment: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '[' -> {
                    var j = i + 1
                    if (j < segment.length && segment[j] == '!') j++
                    if (j < segment.length && segment[j] == ']') j++
                    while (j < segment.length && segment[j] != ']') j++
                    if (j >= segment.length) {
                        out.append("\\[")
                    } else {
                        val negated = segment[i + 1] == '!'
                        // Inside a class only these are special to Java's regex and not to fnmatch.
                        val body = segment.substring(if (negated) i + 2 else i + 1, j)
                            .replace(Regex("""([\\\[&~|^])"""), "\\\\$1")
                        out.append('[').append(if (negated) "^" else "").append(body).append(']')
                        i = j
                    }
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }
}

/** Paths in the main file's coordinates: segments joined by `/`, with leading `..` for folders above it. */
internal object LinkPath {

    /** Collapses `.` and `..`; a `..` with nothing to remove stays, so a path above the main folder says so. */
    fun normalise(path: String): String {
        val parts = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." ->
                    if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.size - 1) else parts += ".."
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    /** How many folders above the main file's own [path] reaches. */
    fun levelsUp(path: String): Int = path.split('/').takeWhile { it == ".." }.size

    fun directoryOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun resolve(fromDirectory: String, link: String): String =
        normalise(if (fromDirectory.isEmpty()) link else "$fromDirectory/$link")

    /** Absolute in the sense Beancount means: it does not hang off the including file's folder. */
    fun isAbsolute(link: String): Boolean =
        link.startsWith("/") || link.startsWith("\\") || DRIVE.containsMatchIn(link)

    private val DRIVE = Regex("^[A-Za-z]:[\\\\/]")
}
