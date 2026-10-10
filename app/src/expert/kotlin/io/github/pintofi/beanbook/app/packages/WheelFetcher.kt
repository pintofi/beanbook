package io.github.pintofi.beanbook.app.packages

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Fetches a Python package on the user's request — expert edition only.
 *
 * The app bundles what Beancount and Fava need and no more. A few things a user might
 * genuinely want — beangrow's returns reports, and the Fava extensions built on them — need
 * numpy, pandas, matplotlib and protobuf: around 50 MB, which does not belong in every
 * download for a feature most people will not use.
 *
 * **This file does not exist in the Play edition.** Fetching executable code from a
 * non-Play source is what the Device and Network Abuse policy prohibits, and
 * `:verifyPlayFlavorBoundaries` fails the build if it ever compiles into `play`.
 *
 * Deliberately not pip. A wheel is a zip: resolving it, checking a digest and unzipping it is
 * a few hundred lines, where bundling pip is several megabytes of machinery for the same
 * result. What is given up is pip's dependency resolver, so [resolve] walks the metadata
 * itself and the caller is told exactly what will be installed before anything is fetched.
 */
class WheelFetcher(
    private val root: File,
    private val allowed: Boolean,
    private val allowNative: Boolean,
    /** Reads `assets/overlays/…`; null disables overlays, which only tests want. */
    private val assets: android.content.res.AssetManager? = null,
) {

    /**
     * Where an Android build of a package with native code comes from.
     *
     * PyPI has no Android wheels for anything with a C extension, so a native package can
     * only come from Chaquopy's index, and only for the interpreter it was built against.
     */
    private val chaquopyIndex = "https://chaquo.com/pypi-13.1"

    /**
     * Requirements read from a Chaquopy wheel's own METADATA, by package name.
     *
     * A Chaquopy wheel is not the PyPI wheel: its numpy declares `chaquopy-openblas` and
     * `chaquopy-libcxx`, the companion libraries its extension modules link against, and PyPI
     * has never heard of either. Asking PyPI what a Chaquopy wheel needs gets an answer about
     * a different build — which is how numpy installed cleanly and then failed to import with
     * `library "libopenblas.so" not found`.
     */
    private val chaquopyRequires = mutableMapOf<String, List<String>>()

    /**
     * Wheels already downloaded while resolving, spooled to disk, by URL.
     *
     * Chaquopy's index publishes no digests and no metadata endpoint, so resolving one of its
     * wheels means fetching it — to hash it, and to read the `Requires-Dist` lines that PyPI
     * does not have for that build. Fetching it a second time to install it doubles the
     * traffic: 60 MB for beangrow's 30 MB closure, over a phone connection.
     *
     * **On disk, not in memory.** The first version of this held every wheel as a ByteArray,
     * which for a closure containing OpenBLAS, numpy, pandas and matplotlib is a hundred
     * megabytes of heap — an out-of-memory kill on the exact device this feature is for. The
     * spool is deleted after an install, whether it succeeded or failed.
     */
    // Beside the install root, not inside it. A download in progress is not an installed
    // package, and putting it in there made `installed()` report `.spool` as one — and left a
    // wheel's own entries able to collide with it.
    private val spool = File(root.parentFile ?: root, "${root.name}-spool")

    private fun spooledAt(url: String): File =
        File(spool, url.substringAfterLast('/').ifBlank { url.hashCode().toString() })

    /**
     * Works out what installing [names] would actually fetch, without fetching it.
     *
     * Returns the closure in install order, so the caller can show a size and a list and ask
     * before anything crosses the network — which is the difference between a download the
     * user chose and one that happened to them.
     */
    fun resolve(names: List<String>): Result<Resolution> = runCatching {
        val resolved = LinkedHashMap<String, Wheel>()
        val unavailable = mutableListOf<String>()
        val queue = ArrayDeque(names)
        val seen = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val raw = queue.removeFirst()
            val name = normalise(raw)
            if (!seen.add(name)) continue
            if (isBundled(name)) continue

            val wheel = fromChaquopy(name) ?: fromPyPI(name)
            if (wheel == null) {
                // No Android build exists. Recorded rather than fatal: see [Resolution].
                // The one exception is a package the *user* asked for by name — failing
                // silently on that would install nothing and say nothing.
                if (name in names.map(::normalise)) {
                    error("no Android-compatible wheel for $name")
                }
                unavailable += name
                continue
            }
            if (wheel.native && !allowNative) {
                error("${wheel.name} contains native code, which this edition may not install")
            }
            resolved[name] = wheel
            val dropped = removedByOverlay(wheel)
            queue += (chaquopyRequires[name] ?: dependenciesOf(name, wheel.version))
                .filterNot { normalise(it) in dropped }
        }
        Resolution(resolved.values.toList(), unavailable.sorted())
    }

    /** Fetches and unpacks [wheels], verifying each digest before it is opened. */
    fun install(wheels: List<Wheel>): FetchResult {
        if (!allowed) {
            return FetchResult.Refused("this edition does not download packages")
        }
        // A root left half-merged by an earlier run is thrown away before this one starts.
        // Installing *over* it would bury the damage under a good install and leave the
        // broken files importable (B37).
        OnDemandPackages.discardIncomplete(root)
        root.mkdirs()

        // Unpacked into staging, moved into place only when every wheel has succeeded.
        // Previously a digest mismatch at wheel n left wheels 1..n-1 unpacked in the install
        // root and reported failure, and `installed()` then listed them as present -- while
        // the comment below said nothing already unpacked is trusted. Nothing deleted them
        // (R7). ProjectImporter and PluginStore both stage-then-rename for the same reason.
        val staging = File(root.parentFile ?: root, "${root.name}-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        fun abandon(reason: String): FetchResult {
            staging.deleteRecursively()
            spool.deleteRecursively()
            return FetchResult.Failed(reason)
        }

        var total = 0L
        wheels.forEach { wheel ->
            val spooled = spooledAt(wheel.url)
            val bytes = try {
                if (spooled.isFile) spooled.readBytes() else download(wheel.url)
            } catch (problem: java.io.IOException) {
                Log.w(TAG, "could not fetch ${wheel.name}", problem)
                return abandon("${wheel.name} could not be downloaded")
            }

            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (digest != wheel.sha256) {
                // The index said one thing and the bytes are another. There is no benign
                // explanation, so nothing is unpacked and nothing already unpacked is trusted.
                return abandon("${wheel.name} did not match the digest its index published")
            }

            runCatching { unpack(bytes, staging) }
                .onFailure { return abandon("${wheel.name} could not be unpacked") }
            overlay(wheel, staging)
            total += bytes.size
        }

        // Every wheel succeeded: merge staging into the install root. Not a rename, because
        // the root holds whatever was installed before and this is an addition to it.
        //
        // A file-by-file copy is not atomic, so the intent is journalled first: if the
        // process dies partway, the marker survives and the next run discards the root
        // instead of importing a half-copied package (B37). Written and flushed before a
        // single byte moves, because a marker that lands after the copy it describes would
        // protect nothing.
        val marker = OnDemandPackages.marker(root)
        runCatching {
            marker.parentFile?.mkdirs()
            java.io.FileOutputStream(marker).use { out ->
                out.write(wheels.joinToString("\n") { it.name }.toByteArray())
                out.fd.sync()
            }
        }.onFailure { return abandon("the install could not be journalled") }

        runCatching { staging.copyRecursively(root, overwrite = true) }
            .onFailure {
                // The copy failed rather than the process dying, so the damage is here and
                // now: clear the root and the marker together rather than leaving recovery
                // to the next run.
                root.deleteRecursively()
                marker.delete()
                return abandon("the packages could not be moved into place")
            }
        marker.delete()
        staging.deleteRecursively()
        spool.deleteRecursively()
        return FetchResult.Installed(wheels, total)
    }

    /**
     * Replaces a file in a freshly installed package, where this project ships a fix.
     *
     * Only `beangrow` needs one today: it imports SciPy for a single root find, and SciPy has
     * no CPython 3.13 build for Android. `patches/` holds the change as a diff for sending
     * upstream; `assets/overlays/` holds the same change as a whole file, because applying a
     * unified diff on a phone means shipping a patch program to do it.
     *
     * **Version-matched.** The overlay directory is named `<package>-<version>` and is used
     * only when the installed version is exactly that. A newer release is left alone — an
     * overlay that applies to whatever happens to be there is a way to break a package
     * quietly, and quietly is the problem.
     */
    private fun overlay(wheel: Wheel, into: File) {
        val manager = assets ?: return
        val prefix = "overlays/${wheel.name}-${wheel.version}"
        // `list` returns an empty array for a directory that does not exist as well as for
        // one that is empty, so "no children" cannot be read as "this is a file" — doing
        // that made a missing overlay look like a file and threw FileNotFoundException for
        // every package that has none, which is nearly all of them.
        if (manager.list(prefix).orEmpty().isEmpty()) return
        val files = runCatching { walkAssets(manager, prefix) }
            .getOrDefault(emptyList())
            .filterNot { it.endsWith("/REMOVES.txt") }
        if (files.isEmpty()) return

        files.forEach { path ->
            val target = File(into, path.removePrefix("$prefix/"))
            target.parentFile?.mkdirs()
            manager.open(path).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        Log.i(TAG, "overlaid ${files.size} file(s) onto ${wheel.name} ${wheel.version}")
    }

    /** Every file under [path]. A leaf — no children — is a file, given the caller checked. */
    private fun walkAssets(manager: android.content.res.AssetManager, path: String): List<String> {
        val children = manager.list(path).orEmpty()
        return if (children.isEmpty()) {
            listOf(path)
        } else {
            children.flatMap { walkAssets(manager, "$path/$it") }
        }
    }

    /**
     * Loads the companion libraries a fetched package's extension modules link against.
     *
     * Chaquopy splits a native package in two: numpy's `_multiarray_umath.so` has a
     * `DT_NEEDED` on `libopenblas.so`, which arrives in a separate `chaquopy-openblas` wheel
     * and unpacks to `chaquopy/lib/`. Nothing puts that directory on the dynamic linker's
     * search path — Chaquopy's own build does it at install time for the packages in the APK,
     * and a directory this app unpacked at runtime is not one of them. So numpy installs
     * perfectly and then fails to import with `library "libopenblas.so" not found`.
     *
     * Loading each by absolute path puts it in the process by soname, and the linker then
     * satisfies the extension module's `DT_NEEDED` from what is already loaded.
     *
     * Ordered by dependency: `libc++_shared` underpins the rest, so it goes first. Failures
     * are logged rather than thrown — a library that will not load here produces a clearer
     * error at the import that actually needs it.
     */
    fun preloadNativeLibraries(): List<String> {
        // `.so` is not always the last thing in the name: chaquopy-libgfortran ships
        // `libgfortran.so.3`, a versioned soname. Matching on endsWith(".so") skipped it
        // silently, and openblas — which needs it — then failed to load with a message about
        // openblas rather than about the file that was actually missing.
        val libraries = File(root, "chaquopy/lib").listFiles()
            ?.filter { it.isFile && it.name.contains(".so") }
            .orEmpty()

        // Repeat until nothing more will load, rather than guessing an order. These libraries
        // depend on each other — openblas needs libgfortran, which needs libc++ — and the
        // order `listFiles` returns is arbitrary. A fixed point costs a few failed dlopens
        // and cannot be wrong; a hand-written order is right until Chaquopy adds a library.
        val loaded = LinkedHashSet<String>()
        var progress = true
        while (progress) {
            progress = false
            libraries.filter { it.name !in loaded }.forEach { library ->
                if (runCatching { System.load(library.absolutePath) }.isSuccess) {
                    loaded += library.name
                    progress = true
                }
            }
        }

        val skipped = libraries.map { it.name } - loaded
        if (skipped.isNotEmpty()) {
            // Not fatal: a library nothing imports is allowed to be unloadable, and the
            // import that does need it will say so with its own name.
            Log.w(TAG, "could not preload: $skipped")
        }
        return loaded.toList()
    }

    /**
     * Packages already on this device, so a second run costs nothing.
     *
     * `chaquopy` is excluded: it is the shared library directory Chaquopy's native wheels
     * unpack into, not a package anyone asked for, and reporting it as installed would make
     * "is numpy here?" answerable with the wrong yes.
     */
    fun installed(): Set<String> =
        // A root left half-merged holds directories that look like packages and are not.
        // Reporting them would put a name on the screen that the engine cannot import.
        if (OnDemandPackages.isIncomplete(root)) emptySet()
        else root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "chaquopy" }
            ?.map { normalise(it.name) }
            ?.toSet()
            .orEmpty()

    private fun unpack(bytes: ByteArray, into: File) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                // A wheel is an archive from the network: a member named ../../something
                // would write outside the directory, which is the oldest trick there is.
                val target = File(into, entry.name).canonicalFile
                if (!target.path.startsWith(into.canonicalFile.path + File.separator)) {
                    error("wheel entry escapes the install directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        return connection.use { it.inputStream.readBytes() }
    }

    /**
     * The body, or `null` when the server says there is nothing there.
     *
     * **Not reachable and not present are different answers**, and this used to give the
     * same one for both: every failure became `null`, callers turned `null` into "no wheel",
     * and a timeout or a 503 was reported to the user as "no Android-compatible wheel for
     * $name" — telling them a package can never work on Android when the truth was to try
     * again in a minute. Seen for real: `packaging` resolved in one test and "had no wheel"
     * in another, in the same run, on the same emulator.
     *
     * So only 404 and 410 return `null`. Anything else — a refused connection, a timeout, a
     * 5xx, a rate limit — is thrown, and `resolve` reports it as what it is.
     */
    private fun get(url: String): String? =
        (URL(url).openConnection() as HttpURLConnection).use { connection ->
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            val code = runCatching { connection.responseCode }.getOrElse { cause ->
                throw java.io.IOException("could not reach $url: ${cause.message}", cause)
            }
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND -> null
                code == HttpURLConnection.HTTP_GONE -> null
                code >= 400 -> throw java.io.IOException("$url returned HTTP $code")
                else -> runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse { cause ->
                    throw java.io.IOException("could not read $url: ${cause.message}", cause)
                }
            }
        }

    /** An Android wheel from Chaquopy's index, if it has one for this interpreter. */
    private fun fromChaquopy(name: String): Wheel? {
        val listing = get("$chaquopyIndex/$name/") ?: return null
        val href = CHAQUOPY_WHEEL.findAll(listing)
            .map { it.groupValues[1] }
            .lastOrNull { chaquopyWheelFits(it) }
            ?: return null
        val url = "$chaquopyIndex/$name/$href"
        // Chaquopy's index publishes no digests, so the digest is of what we received. That
        // pins nothing about the index and everything about this install: a second run gets
        // the same bytes or fails.
        val bytes = runCatching { download(url) }.getOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        spool.mkdirs()
        // Written to one side and renamed, so a kill mid-write cannot leave a truncated
        // file that the next run reads as a complete wheel. The digest check in `install`
        // would catch those bytes, but it would report them as an index that served the
        // wrong content -- blaming the network for a local mishap.
        val spooledFile = spooledAt(url)
        val partial = File(spool, "${spooledFile.name}.part")
        java.io.FileOutputStream(partial).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!partial.renameTo(spooledFile)) {
            partial.delete()
            return null
        }
        chaquopyRequires[name] = requiresFrom(bytes)
        return Wheel(
            name = name,
            version = href.split('-').getOrElse(1) { "?" },
            url = url,
            sha256 = digest,
            native = !href.contains("py3-none-any"),
        )
    }

    /**
     * A wheel from PyPI this device can run: pure Python, or an Android wheel (B82).
     *
     * PyPI used to have nothing with native code for Android, so only `py3-none-any` was taken
     * from it. PEP 738 changed that: projects now publish `android_24_arm64_v8a` wheels
     * themselves -- `curl_cffi` does, and Chaquopy's index does not carry it -- and refusing
     * them sent a person to a "no Android-compatible wheel" error for a package that has one.
     * Taken only for this interpreter (`cp313`, or the stable ABI) and this device's ABI.
     */
    private fun fromPyPI(name: String): Wheel? {
        val body = get("https://pypi.org/pypi/$name/json") ?: return null
        val root = JSONObject(body)
        val version = root.getJSONObject("info").getString("version")
        val files = root.getJSONArray("urls")
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val filename = file.getString("filename")
            val pure = filename.endsWith("-py3-none-any.whl") ||
                filename.endsWith("-py2.py3-none-any.whl")
            val android = isAndroidWheelForThisDevice(filename)
            if (!pure && !android) continue
            return Wheel(
                name = name,
                version = version,
                url = file.getString("url"),
                sha256 = file.getJSONObject("digests").getString("sha256"),
                native = !pure,
            )
        }
        return null
    }

    /**
     * Whether a file from Chaquopy's index is for this interpreter *and this device's ABI*.
     *
     * The companion wheels (`chaquopy-openblas`, `chaquopy-libffi`, ...) are named
     * `py3-none-android_<api>_<arch>`. Matching on `py3-none` alone took the last one listed,
     * which is x86_64, so an arm64 phone unpacked an x86_64 `libffi.so` and `libopenblas.so`
     * that the linker could not use: "library not found" at the import (B94).
     */
    private fun chaquopyWheelFits(href: String): Boolean =
        href.contains(ABI_TAG) ||
            href.contains("py3-none-any") ||
            (href.contains("py3-none-android_") && href.endsWith("_$DEVICE_ARCH.whl"))

    private fun isAndroidWheelForThisDevice(filename: String): Boolean =
        androidWheelFits(filename.substringAfterLast('/'), DEVICE_ARCH)

    /** `Requires-Dist` lines from a wheel's own `*.dist-info/METADATA`. */
    private fun requiresFrom(wheelBytes: ByteArray): List<String> {
        val requires = mutableListOf<String>()
        ZipInputStream(wheelBytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.name.endsWith(".dist-info/METADATA")) return@forEach
                zip.bufferedReader().lineSequence().forEach { line ->
                    if (!line.startsWith("Requires-Dist:")) return@forEach
                    val spec = line.removePrefix("Requires-Dist:").trim()
                    // A marker is a condition this cannot evaluate; see dependenciesOf.
                    if (';' !in spec) {
                        spec.split(REQUIREMENT_END)[0].trim().takeIf { it.isNotEmpty() }
                            ?.let { requires += it }
                    }
                }
            }
        }
        return requires
    }

    /** What [name] needs, from PyPI's metadata. Extras and environment markers are skipped. */
    private fun dependenciesOf(name: String, version: String): List<String> {
        val body = get("https://pypi.org/pypi/$name/$version/json") ?: return emptyList()
        val requires = JSONObject(body).getJSONObject("info").optJSONArray("requires_dist")
            ?: return emptyList()
        return (0 until requires.length()).mapNotNull { index ->
            val spec = requires.getString(index)
            // `; extra == "test"` and `; python_version < "3.9"` are conditions this cannot
            // evaluate, and guessing wrong installs something unnecessary. Skipped, and the
            // resolved list is shown to the user, who can see what is missing.
            if (spec.contains(';')) null else spec.split(REQUIREMENT_END)[0].trim().ifBlank { null }
        }
    }

    /**
     * Dependencies an overlay makes unnecessary, read from the overlay's own `REMOVES.txt`.
     *
     * beangrow declares SciPy and, once its `returns.py` is replaced, no longer imports it.
     * Skipping it silently in the resolver would be a lie told in the wrong place; the
     * exclusion is declared beside the file that justifies it and read back from there.
     */
    private fun removedByOverlay(wheel: Wheel): Set<String> {
        val manager = assets ?: return emptySet()
        val path = "overlays/${wheel.name}-${wheel.version}/REMOVES.txt"
        val text = runCatching {
            manager.open(path).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptySet()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map(::normalise)
            .toSet()
    }

    /** Already in the APK, so never fetched. */
    private fun isBundled(name: String): Boolean = name in BUNDLED

    private fun normalise(name: String) = name.lowercase().replace('_', '-').trim()

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private companion object {
        const val TAG = "WheelFetcher"

        /** The ABI this build runs on. Chaquopy's wheels are per-ABI and per-interpreter. */
        val ABI_TAG: String = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
            "cp313-cp313-android_24_x86_64"
        } else {
            "cp313-cp313-android_24_arm64_v8a"
        }

        val CHAQUOPY_WHEEL = Regex("""href="([^"]+\.whl)"""")

        val DEVICE_ARCH: String = ABI_TAG.substringAfterLast("android_24_")
        val REQUIREMENT_END = Regex("""[\s(<>=!~\[]""")

        /**
         * Shipped in the APK. Fetching these would install a second copy on `sys.path` ahead
         * of the tested one, which is a good way to make a working app stop working.
         */
        val BUNDLED = setOf(
            "beancount", "beanquery", "beangulp", "fava", "click", "python-dateutil", "six",
            "regex", "lxml", "markupsafe", "jinja2", "werkzeug", "flask", "babel", "ply",
            "watchfiles", "simplejson", "chardet", "python-magic", "typing-extensions",
        )
    }
}

/** `name-version[-build]-<python>-<abi>-android_<api>_<arch>.whl`, per PEP 738. */
private val ANDROID_WHEEL =
    Regex("""^[^-]+-[^-]+(?:-\d[^-]*)?-(cp\d+)-(cp\d+|abi3)-android_(\d+)_(arm64_v8a|x86_64)\.whl$""")

/**
 * Whether an Android wheel runs here: built for CPython 3.13 (or the stable ABI from an older
 * CPython), for an API level no newer than this app's floor, and for [arch].
 */
internal fun androidWheelFits(filename: String, arch: String): Boolean {
    val match = ANDROID_WHEEL.matchEntire(filename) ?: return false
    val (python, abi, api, wheelArch) = match.destructured
    val minor = python.removePrefix("cp").toIntOrNull() ?: return false
    val interpreter = (abi == "cp313" && python == "cp313") || (abi == "abi3" && minor in 32..313)
    return interpreter && (api.toIntOrNull() ?: Int.MAX_VALUE) <= MIN_API && wheelArch == arch
}

/** The oldest Android this app runs on; a wheel needing a newer one would fail to load. */
private const val MIN_API = 29

