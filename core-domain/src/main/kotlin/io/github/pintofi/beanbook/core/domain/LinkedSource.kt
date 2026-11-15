package io.github.pintofi.beanbook.core.domain

/** What a source says about one path. */
data class LinkedStat(val size: Long, val isDirectory: Boolean)

/** One entry in a folder, by name. */
data class LinkedChild(val name: String, val isDirectory: Boolean)

/**
 * Where a ledger's files are read from while its links are followed: a folder the person picked, a
 * test's map of files, anything that can answer these three questions.
 *
 * Paths are in the main file's own coordinates: the main file's folder is `""`, a file beside it is
 * its name, a folder under it is `sub/file`, and a folder above it is reached with `..` segments
 * (`../shared.bean`). The walker only ever asks about a path a link names, or the children of a
 * folder a link points into (a glob, a documents folder); it never asks for the rest of a folder.
 *
 * Implementations must not write, and must not keep what [children] returns beyond the call.
 */
interface LinkedSource {
    /** The bytes of the file at [path], or null when there is no such file. */
    fun read(path: String): ByteArray?

    /**
     * The file at [path] as a stream, for copying one that may be large (a scan, a statement), or null
     * when there is no such file. Defaults to [read]; a source over real storage should stream.
     */
    fun open(path: String): java.io.InputStream? = read(path)?.inputStream()

    /** What is at [path], or null when nothing is. */
    fun stat(path: String): LinkedStat?

    /** The entries of the folder at [path], or null when it is not a folder. Names only. */
    fun children(path: String): List<LinkedChild>?
}
