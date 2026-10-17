package io.github.pintofi.beanbook.app.storage

/**
 * A file name taken from a document provider's display-name column, made safe to join onto a
 * folder.
 *
 * The column belongs to whatever app serves the document, so it can hold `../x` or a path.
 * Only the last segment is kept; a name that is empty, `.`, `..` or holds control characters
 * is replaced by [fallback].
 */
internal fun safeDisplayName(raw: String, fallback: String): String {
    val last = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val unusable = last.isEmpty() || last == "." || last == ".." || last.any { it.isISOControl() }
    return if (unusable) fallback else last.take(MAX_NAME)
}

private const val MAX_NAME = 120
