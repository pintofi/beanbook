package io.github.pintofi.beanbook.core.data

/** Where the newest version of something is asked for. */
sealed interface UpstreamRef {
    /** A project on PyPI, by its normalised name. */
    data class PyPI(val project: String) : UpstreamRef

    /** A GitHub repository, as `owner/repo`. */
    data class GitHub(val repo: String) : UpstreamRef

    /** Chaquopy's index of Android builds, by normalised package name. */
    data class Chaquopy(val name: String) : UpstreamRef

    companion object {
        private val WHEEL = Regex("""^([A-Za-z0-9][A-Za-z0-9._]*)-[0-9][^-]*-.+\.whl$""")
        private val CODELOAD = Regex("""^https://codeload\.github\.com/([^/]+/[^/]+)/zip/""")

        /** `Foo_Bar` and `foo.bar` and `foo-bar` are one name. */
        fun normalise(name: String): String = name.lowercase().replace(Regex("[-_.]+"), "-")

        /**
         * Where a catalogue install came from, read from what it downloaded: a wheel on PyPI names
         * its project in its file name, and a GitHub archive names its repository in its address.
         * Null when the archive says neither, which is an entry that cannot be checked.
         */
        fun of(install: PluginCatalogue.Install): UpstreamRef? = when (install.source) {
            "pypi" -> WHEEL.matchEntire(install.archive.substringAfterLast('/'))
                ?.let { PyPI(normalise(it.groupValues[1])) }
            "github" -> CODELOAD.find(install.archive)?.let { GitHub(it.groupValues[1]) }
            else -> null
        }
    }
}

/**
 * What the app knows about something it installed: enough to ask its source whether a newer
 * version exists, and to say how what arrived was checked.
 *
 * Written next to the installed files, because a version number alone does not say where to look.
 * Installs made before records existed have none; [CatalogueInstaller.record] reconstructs theirs.
 */
data class InstallRecord(
    val name: String,
    val version: String,
    /** `pypi`, `github` or `chaquopy`. */
    val source: String,
    /** `owner/repo` or the PyPI or Chaquopy project; null when the source cannot be asked. */
    val upstream: String?,
    val archive: String,
    val sha256: String,
    /**
     * `published-digest`: the source published the hash and it matched; `catalogue-pin`: the hash is
     * the one this app's catalogue pinned; `received-only`: nothing published one, and the hash is
     * what arrived, so only HTTPS stands behind it; `not-recorded`: an install from before records.
     */
    val verified: String,
    val installedAtEpochMs: Long,
) {
    internal fun toJson(): Json.Obj = Json.Obj(
        mapOf(
            "name" to Json.Str(name),
            "version" to Json.Str(version),
            "source" to Json.Str(source),
            "upstream" to (upstream?.let { Json.Str(it) } ?: Json.Null),
            "archive" to Json.Str(archive),
            "sha256" to Json.Str(sha256),
            "verified" to Json.Str(verified),
            // Text, not a JSON number: money-adjacent code in this module keeps off floating point.
            "installed_at" to Json.Str(installedAtEpochMs.toString()),
        ),
    )

    companion object {
        internal fun fromJson(obj: Json.Obj): InstallRecord? = InstallRecord(
            name = obj.string("name") ?: return null,
            version = obj.string("version") ?: return null,
            source = obj.string("source") ?: return null,
            upstream = obj.string("upstream"),
            archive = obj.string("archive").orEmpty(),
            sha256 = obj.string("sha256").orEmpty(),
            verified = obj.string("verified") ?: "not-recorded",
            installedAtEpochMs = obj.string("installed_at")?.toLongOrNull() ?: 0L,
        )

        /** The record for [entry] as the catalogue pins it, which is how a catalogue install is checked. */
        fun forCatalogue(entry: PluginCatalogue.Entry, now: Long): InstallRecord? {
            val install = entry.install ?: return null
            val ref = UpstreamRef.of(install)
            return InstallRecord(
                name = entry.name,
                version = install.version,
                source = install.source,
                upstream = when (ref) {
                    is UpstreamRef.PyPI -> ref.project
                    is UpstreamRef.GitHub -> ref.repo
                    is UpstreamRef.Chaquopy -> ref.name
                    null -> null
                },
                archive = install.archive,
                sha256 = install.sha256,
                verified = "catalogue-pin",
                installedAtEpochMs = now,
            )
        }
    }
}
