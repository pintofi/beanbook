package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.LinkedKind
import io.github.pintofi.beanbook.core.domain.LinkedLedger

/** One line of the preview of what an import will bring in: a folder or a file, indented by its depth. */
data class PreviewLine(
    val name: String,
    val depth: Int,
    val isFolder: Boolean,
    val isMain: Boolean = false,
    val kind: LinkedKind? = null,
    val size: Long = 0,
)

/**
 * The files an import will bring in, as the tree the project will have: folders first at each level and
 * then files, each in name order, the main file marked. Exactly what [LinkedImportSource] would copy, and
 * nothing else: no other file of the folder is named here or anywhere.
 */
object ImportPreview {

    fun lines(ledger: LinkedLedger, mainFolders: List<String>): List<PreviewLine> {
        val source = LinkedImportSource(NO_SOURCE, ledger, mainFolders)
        val byPath = ledger.files.associateBy { source.projectPaths.getValue(it.path) }
        val tree = sortedMapOf<String, Any>()
        byPath.keys.forEach { path ->
            var level = tree
            val parts = path.split('/')
            parts.dropLast(1).forEach { folder ->
                @Suppress("UNCHECKED_CAST")
                level = level.getOrPut(folder) { sortedMapOf<String, Any>() } as java.util.SortedMap<String, Any>
            }
            level[parts.last()] = path
        }
        val out = mutableListOf<PreviewLine>()
        fun walk(level: Map<String, Any>, depth: Int) {
            level.entries.sortedWith(compareBy({ it.value is String }, { it.key })).forEach { (name, value) ->
                if (value is String) {
                    val file = byPath.getValue(value)
                    out += PreviewLine(
                        name, depth, isFolder = false, isMain = file.path == ledger.main, kind = file.kind,
                        size = file.size,
                    )
                } else {
                    out += PreviewLine(name, depth, isFolder = true)
                    @Suppress("UNCHECKED_CAST")
                    walk(value as Map<String, Any>, depth + 1)
                }
            }
        }
        walk(tree, 0)
        return out
    }

    private val NO_SOURCE = object : io.github.pintofi.beanbook.core.domain.LinkedSource {
        override fun read(path: String): ByteArray? = null
        override fun stat(path: String) = null
        override fun children(path: String) = null
    }
}
