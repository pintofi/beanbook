package io.github.pintofi.beanbook.core.domain

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** What the user picked: one module, a package directory, or a wheel/zip. */
enum class PluginKind { SOURCE_FILE, DIRECTORY, ARCHIVE }

/** One place user code reaches for the Java/Android bridge, with enough to show the user. */
data class BridgeImport(val file: String, val line: Int, val text: String)

/**
 * What a candidate plugin turned out to contain, before anything has been run.
 *
 * Everything here is decided by *reading* the files. That is worth being precise about: a
 * static scan of Python cannot be a security boundary, because Python can build an import
 * out of a string at runtime. It is a policy check and an honest summary for the user — the
 * bridge is actually withheld at import time, inside the interpreter.
 */
data class PluginScan(
    val name: String,
    val kind: PluginKind,
    val sha256: String,
    val sizeBytes: Long,
    val pythonModules: List<String>,
    val nativeLibraries: List<String>,
    val bridgeImports: List<BridgeImport>,
    /** Archive entries that would escape the extraction directory, or symlinks. */
    val unsafePaths: List<String>,
    /** True when the candidate exceeded a scan limit and was not read to the end. */
    val truncated: Boolean,
)

/** Why a candidate may not be installed under a given [PluginPolicy]. */
sealed interface PluginRejection {
    data object UserCodeNotAllowed : PluginRejection
    data class NativeCode(val paths: List<String>) : PluginRejection
    data class AndroidBridge(val imports: List<BridgeImport>) : PluginRejection
    data class UnsafePaths(val paths: List<String>) : PluginRejection
    data object NoPythonFound : PluginRejection
    data object TooLarge : PluginRejection

    /** Says what is wrong and what the user can do, never just "not allowed". */
    fun describe(): String = when (this) {
        UserCodeNotAllowed ->
            "This edition does not run user Python at all."
        is NativeCode ->
            "This package contains compiled native code (${paths.size} file" +
                "${if (paths.size == 1) "" else "s"}, for example ${paths.first()}). The " +
                "Play edition installs pure-Python plugins only. The same plugin works in " +
                "the Expert edition, or look for a pure-Python alternative."
        is AndroidBridge ->
            "This code imports the Java/Android bridge (${imports.first().file} line " +
                "${imports.first().line}). The Play edition does not make the bridge " +
                "available to plugins, so it would fail when it ran."
        is UnsafePaths ->
            "This archive contains ${paths.size} entr${if (paths.size == 1) "y" else "ies"} " +
                "that would be written outside the plugin folder, for example " +
                "\"${paths.first()}\". Nothing was installed."
        NoPythonFound ->
            "There is no Python in this file, so there is nothing to load."
        TooLarge ->
            "This is larger than a plugin is expected to be and was not read to the end. " +
                "Nothing was installed."
    }
}

/** The answer to "may this be installed?". */
sealed interface PluginVerdict {
    data object Allowed : PluginVerdict
    data class Rejected(val reasons: List<PluginRejection>) : PluginVerdict

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Reads a candidate plugin and decides what it is.
 *
 * The limits are not tuning knobs: a zip that expands to gigabytes is a denial-of-service on
 * a phone, and refusing to finish reading is the correct response to one.
 */
object PluginPreflight {

    const val MAX_ENTRIES = 5_000
    const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

    private val NATIVE_SUFFIXES = listOf(".so", ".dylib", ".pyd", ".dll")

    /**
     * Modules whose import means the Java/Android bridge.
     *
     * Matched on a module boundary, so `javalang` — a real package on PyPI — does not look
     * like `java`.
     */
    private val BRIDGE_MODULES = listOf("java", "javax", "jnius", "android", "com.chaquo.python")

    private val IMPORT_LINE = Regex("""^\s*(?:from|import)\s+([A-Za-z_][\w.]*)""")
    private val DYNAMIC_IMPORT = Regex(
        """(?:__import__|importlib\.import_module)\s*\(\s*['"]([\w.]+)['"]""",
    )

    fun scan(file: File): PluginScan = when {
        file.isDirectory -> scanDirectory(file)
        file.extension.equals("py", ignoreCase = true) -> scanSourceFile(file)
        else -> scanArchive(file)
    }

    private fun scanSourceFile(file: File): PluginScan {
        // A source file is read whole to look for imports, so it gets the same ceiling as
        // everything else: a multi-gigabyte "plugin.py" is refused unread, not loaded into memory.
        val tooLarge = file.length() > MAX_UNCOMPRESSED_BYTES
        return PluginScan(
            name = file.name,
            kind = PluginKind.SOURCE_FILE,
            sha256 = sha256(file),
            sizeBytes = file.length(),
            pythonModules = listOf(file.name),
            nativeLibraries = emptyList(),
            bridgeImports = if (tooLarge) emptyList() else bridgeImportsIn(file.name, file.readText()),
            unsafePaths = emptyList(),
            truncated = tooLarge,
        )
    }

    private fun scanDirectory(root: File): PluginScan {
        val modules = mutableListOf<String>()
        val native = mutableListOf<String>()
        val bridge = mutableListOf<BridgeImport>()
        val unsafe = mutableListOf<String>()
        val digests = mutableListOf<Pair<String, String>>()
        var total = 0L
        var count = 0
        var truncated = false

        for (entry in root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }) {
            if (entry.isDirectory) continue
            if (++count > MAX_ENTRIES) { truncated = true; break }

            val relative = entry.relativeTo(root).invariantSeparatorsPath
            if (Files.isSymbolicLink(entry.toPath())) {
                // A symlink can point anywhere the app can read, and copying it into the
                // plugin folder would carry that reach with it.
                unsafe += relative
                continue
            }

            total += entry.length()
            if (total > MAX_UNCOMPRESSED_BYTES) { truncated = true; break }

            digests += relative to sha256(entry.readBytes())
            when {
                NATIVE_SUFFIXES.any { relative.endsWith(it, ignoreCase = true) } -> native += relative
                relative.endsWith(".py", ignoreCase = true) -> {
                    modules += relative
                    bridge += bridgeImportsIn(relative, entry.readText())
                }
            }
        }

        return PluginScan(
            name = root.name,
            kind = PluginKind.DIRECTORY,
            sha256 = sha256OfTree(digests),
            sizeBytes = total,
            pythonModules = modules.sorted(),
            nativeLibraries = native.sorted(),
            bridgeImports = bridge,
            unsafePaths = unsafe.sorted(),
            truncated = truncated,
        )
    }

    private fun scanArchive(file: File): PluginScan {
        val modules = mutableListOf<String>()
        val native = mutableListOf<String>()
        val bridge = mutableListOf<BridgeImport>()
        val unsafe = mutableListOf<String>()
        var total = 0L
        var count = 0
        var truncated = false

        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                if (++count > MAX_ENTRIES) { truncated = true; break }

                val name = entry.name.replace('\\', '/')
                if (escapesRoot(name)) {
                    unsafe += entry.name
                    continue
                }

                // entry.size is what the archive claims; read against the real thing so a
                // lying header cannot buy a bigger read. The read itself is bounded too: an
                // entry that expands past what is left of the budget is abandoned as soon as it
                // is seen to, not read to the end and measured afterwards.
                val bytes = zip.getInputStream(entry).use { readAtMost(it, MAX_UNCOMPRESSED_BYTES - total) }
                if (bytes == null) { truncated = true; break }
                total += bytes.size

                when {
                    NATIVE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) } -> native += name
                    name.endsWith(".py", ignoreCase = true) -> {
                        modules += name
                        bridge += bridgeImportsIn(name, String(bytes))
                    }
                }
            }
        }

        return PluginScan(
            name = file.name,
            kind = PluginKind.ARCHIVE,
            sha256 = sha256(file),
            sizeBytes = file.length(),
            pythonModules = modules.sorted(),
            nativeLibraries = native.sorted(),
            bridgeImports = bridge,
            unsafePaths = unsafe.sorted(),
            truncated = truncated,
        )
    }

    /**
     * Zip-slip, in every form a zip can spell it.
     *
     * Public because the check has to happen twice: once here, so a hostile archive is
     * refused before anything is written, and again at extraction, which is the step where
     * an escape would actually land somewhere it should not. One check guarding two places
     * is one check away from guarding neither.
     */
    fun escapesRoot(name: String): Boolean {
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) return true
        var depth = 0
        for (part in name.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (--depth < 0) return true
                else -> depth++
            }
        }
        return false
    }

    internal fun bridgeImportsIn(file: String, source: String): List<BridgeImport> =
        source.lineSequence().withIndex().mapNotNull { (index, line) ->
            // A comment mentioning the bridge is not an import of it.
            if (line.trimStart().startsWith("#")) return@mapNotNull null

            val module = IMPORT_LINE.find(line)?.groupValues?.get(1)
                ?: DYNAMIC_IMPORT.find(line)?.groupValues?.get(1)
                ?: return@mapNotNull null

            if (!isBridge(module)) return@mapNotNull null
            BridgeImport(file, index + 1, line.trim())
        }.toList()

    private fun isBridge(module: String): Boolean =
        BRIDGE_MODULES.any { module == it || module.startsWith("$it.") }

    /**
     * Up to [limit] bytes of [input], or null as soon as it is seen to hold more, without
     * reading on. The point is that the cost of refusing a hostile archive does not grow with
     * the archive.
     */
    internal fun readAtMost(input: InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
    }

    private const val BUFFER_BYTES = 64 * 1024

    /** Streamed, so hashing a large file does not need it in memory. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Order-independent, so the same tree always hashes the same. */
    private fun sha256OfTree(entries: List<Pair<String, String>>): String =
        sha256(entries.sortedBy { it.first }.joinToString("\n") { "${it.first} ${it.second}" }.toByteArray())
}

/**
 * Applies an edition's rules to a scan.
 *
 * Both editions run user Python and neither pretends it is sandboxed. The differences are
 * Play policy compliance, so they live here rather than being scattered through the loader.
 */
fun PluginPolicy.judge(scan: PluginScan): PluginVerdict {
    val reasons = buildList {
        if (!allowsUserCode) add(PluginRejection.UserCodeNotAllowed)
        if (scan.truncated) add(PluginRejection.TooLarge)
        if (scan.unsafePaths.isNotEmpty()) add(PluginRejection.UnsafePaths(scan.unsafePaths))
        if (!allowsNativeCode && scan.nativeLibraries.isNotEmpty()) {
            add(PluginRejection.NativeCode(scan.nativeLibraries))
        }
        if (!allowsAndroidBridge && scan.bridgeImports.isNotEmpty()) {
            add(PluginRejection.AndroidBridge(scan.bridgeImports))
        }
        if (scan.pythonModules.isEmpty() && !scan.truncated) add(PluginRejection.NoPythonFound)
    }
    return if (reasons.isEmpty()) PluginVerdict.Allowed else PluginVerdict.Rejected(reasons)
}
