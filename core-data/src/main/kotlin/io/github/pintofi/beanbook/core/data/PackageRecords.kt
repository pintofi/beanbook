package io.github.pintofi.beanbook.core.data

import java.io.File

/**
 * One fetched Python package, as the app knows it: enough to ask its source for a newer version and
 * to say how what arrived was checked.
 *
 * @property source `pypi` or `chaquopy`; where a newer version is looked for. Never across sources: PyPI's
 *   numpy is newer than Chaquopy's but has no Android build.
 * @property asked whether the person asked for it by name, as opposed to it arriving as a dependency. An
 *   update re-resolves the packages asked for, so their dependencies follow.
 * @property verified `published-digest` (PyPI's SHA-256 matched), `received-only` (the source published
 *   none, so only HTTPS stands behind it) or `not-recorded` (installed before records existed).
 */
data class PackageRecord(
    val name: String,
    val version: String,
    val source: String,
    val sha256: String,
    val verified: String,
    val asked: Boolean,
)

/**
 * The records of the fetched packages in [root], kept in [file] beside it (not inside: the folder is
 * what the engine imports from, and a file in it would be one more thing on `sys.path`).
 *
 * A list that cannot be read, or that names a package that is not there, is not believed: the folder
 * itself is the truth and [current] rebuilds from it, so an install made before records existed, or a
 * damaged list, costs the hash and the source, never the package.
 */
class PackageRecords(private val root: File, private val file: File) {

    /** What is installed, with what is known of each. */
    fun current(): List<PackageRecord> {
        val onDisk = Legacy.read(root)
        val written = read().associateBy { it.name }
        return onDisk.map { found -> written[found.name]?.takeIf { it.version == found.version } ?: found }
    }

    fun write(records: List<PackageRecord>) {
        file.parentFile?.mkdirs()
        val json = Json.Obj(
            mapOf(
                "schema" to Json.Str("1"),
                "packages" to Json.Arr(
                    records.sortedBy { it.name }.map {
                        Json.Obj(
                            mapOf(
                                "name" to Json.Str(it.name), "version" to Json.Str(it.version),
                                "source" to Json.Str(it.source), "sha256" to Json.Str(it.sha256),
                                "verified" to Json.Str(it.verified), "asked" to Json.Bool(it.asked),
                            ),
                        )
                    },
                ),
            ),
        )
        val temporary = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temporary).use { out ->
            out.write((json.write() + "\n").toByteArray())
            out.fd.sync()
        }
        check(temporary.renameTo(file)) { "could not save the package records" }
    }

    private fun read(): List<PackageRecord> = runCatching {
        val root = Json.parse(file.readText()) as Json.Obj
        root.array("packages").orEmpty().mapNotNull { item ->
            val o = item as? Json.Obj ?: return@mapNotNull null
            PackageRecord(
                name = o.string("name") ?: return@mapNotNull null,
                version = o.string("version") ?: return@mapNotNull null,
                source = o.string("source").orEmpty(),
                sha256 = o.string("sha256").orEmpty(),
                verified = o.string("verified") ?: "not-recorded",
                asked = (o.fields["asked"] as? Json.Bool)?.value ?: false,
            )
        }
    }.getOrDefault(emptyList())

    /** Reads what is installed from the packages' own `*.dist-info` folders. */
    object Legacy {
        private val DIST = Regex("""^(.+?)-([0-9][^-]*)\.dist-info$""")

        fun read(root: File): List<PackageRecord> {
            val dists = root.listFiles().orEmpty().filter { it.isDirectory && DIST.matches(it.name) }
            val parsed = dists.mapNotNull { dir ->
                val (name, version) = DIST.matchEntire(dir.name)!!.destructured
                val normal = UpstreamRef.normalise(name)
                val tags = lines(File(dir, "WHEEL")).filter { it.startsWith("Tag:") }
                    .map { it.removePrefix("Tag:").trim() }
                Triple(normal, version, tags) to requirements(File(dir, "METADATA"))
            }
            val needed = parsed.flatMap { it.second }.toSet()
            return parsed.map { (identity, _) ->
                val (name, version, tags) = identity
                PackageRecord(
                    name = name,
                    version = version,
                    // A build for Android came from Chaquopy's index. A pure wheel is guessed to be PyPI's,
                    // which is the order the fetcher itself tries them in last.
                    source = if (tags.any { "android" in it }) "chaquopy" else "pypi",
                    sha256 = "",
                    verified = "not-recorded",
                    // Companion libraries of a native package are never something a person asked for.
                    asked = name !in needed && !name.startsWith("chaquopy-"),
                )
            }.sortedBy { it.name }
        }

        private fun lines(file: File): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList())

        private fun requirements(metadata: File): Set<String> =
            lines(metadata).filter { it.startsWith("Requires-Dist:") }
                .filterNot { it.substringAfter(';', "").contains("extra") }
                .map { it.removePrefix("Requires-Dist:").trim().split(Regex("""[\s<>=!~;\[(]"""), limit = 2)[0] }
                .filter { it.isNotEmpty() }
                .map(UpstreamRef::normalise)
                .toSet()
    }
}
