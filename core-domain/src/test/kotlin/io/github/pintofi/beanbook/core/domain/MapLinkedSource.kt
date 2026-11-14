package io.github.pintofi.beanbook.core.domain

/**
 * A [LinkedSource] over a map of files, for tests. [files] are in real coordinates (the root of the map);
 * the main file's folder is [mainDir] inside it, so `../x` can reach a sibling folder. It records everything
 * the walker asked, so a test can say what was never touched.
 */
class MapLinkedSource(files: Map<String, ByteArray>, private val mainDir: String = "") : LinkedSource {

    constructor(mainDir: String = "", vararg files: Pair<String, String>) :
        this(files.associate { it.first to it.second.toByteArray() }, mainDir)

    private val files: Map<String, ByteArray> = files.mapKeys { LinkPath.normalise(it.key) }

    val reads = mutableListOf<String>()
    val stats = mutableListOf<String>()
    val listed = mutableListOf<String>()

    private fun real(path: String): String? {
        val joined = LinkPath.normalise(if (mainDir.isEmpty()) path else "$mainDir/$path")
        return joined.takeUnless { it.startsWith("..") }
    }

    override fun read(path: String): ByteArray? {
        reads += path
        return real(path)?.let { files[it] }
    }

    override fun stat(path: String): LinkedStat? {
        stats += path
        val real = real(path) ?: return null
        files[real]?.let { return LinkedStat(it.size.toLong(), false) }
        return if (files.keys.any { real.isEmpty() || it.startsWith("$real/") }) LinkedStat(0, true) else null
    }

    override fun children(path: String): List<LinkedChild>? {
        listed += path
        val real = real(path) ?: return null
        if (files.containsKey(real)) return null
        val prefix = if (real.isEmpty()) "" else "$real/"
        val below = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        if (below.isEmpty() && real.isNotEmpty()) return null
        return below.map { it.substringBefore('/') to it.contains('/') }.distinct()
            .map { (name, isDir) -> LinkedChild(name, isDir) }
    }
}
