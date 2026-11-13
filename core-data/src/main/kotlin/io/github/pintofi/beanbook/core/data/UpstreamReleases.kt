package io.github.pintofi.beanbook.core.data

/**
 * A newer version found upstream, and what is needed to fetch and judge it.
 *
 * [sha256] is the digest the source published (PyPI does), checked before anything is unpacked; null
 * where the source publishes none (GitHub), and then only HTTPS stands behind what arrives, which
 * the record says ([verified] is `received-only`).
 */
data class UpstreamRelease(
    val version: String,
    val archive: String,
    val sha256: String?,
    val verified: String,
    /** The packages this version needs, or null when the source did not say (the entry's own are kept). */
    val requires: List<String>?,
    val source: String,
)

/**
 * Reads what PyPI and GitHub answer when asked for the newest version. Pure: the network is the
 * caller's, so the rules (which release counts, what is newer, what is refused) are tested with
 * recorded answers.
 */
object UpstreamReleases {

    private val NONE_ANY = Regex(""".*-none-any\.whl$""")

    /**
     * The newest version of a PyPI project that this app can use and that is newer than [installed],
     * or null when the installed one is current.
     *
     * Not `info.version`: the newest release may be a pre-release, be yanked, or have no pure-Python
     * wheel, while an older one that is still newer than what is installed does. A release counts when
     * it has a `py3-none-any` wheel that is not yanked and whose `requires_python` allows
     * [pythonVersion]; pre-releases count only when the installed one is already a pre-release.
     */
    fun pypi(json: String, installed: String?, pythonVersion: String = "3.13"): Result<UpstreamRelease?> = runCatching {
        val root = Json.parse(json) as? Json.Obj ?: error("PyPI's answer could not be read")
        val info = root.obj("info")
        val releases = root.obj("releases") ?: error("PyPI's answer has no releases")
        val python = Pep440.parse(pythonVersion)
        val have = installed?.let(Pep440::parseOrNull)

        data class Candidate(val version: Pep440, val file: Json.Obj)

        val candidates = releases.fields.mapNotNull { (text, files) ->
            val version = Pep440.parseOrNull(text) ?: return@mapNotNull null
            if (version.isPreRelease && have?.isPreRelease != true) return@mapNotNull null
            val file = (files as? Json.Arr)?.items.orEmpty().mapNotNull { it as? Json.Obj }.firstOrNull { f ->
                NONE_ANY.matches(f.string("filename").orEmpty()) &&
                    (f.fields["yanked"] as? Json.Bool)?.value != true &&
                    pythonAllows(f.string("requires_python"), python)
            } ?: return@mapNotNull null
            Candidate(version, file)
        }
        val best = candidates.maxByOrNull { it.version } ?: return@runCatching null
        if (have != null && best.version <= have) return@runCatching null
        if (have == null && installed != null && best.version.text == installed) return@runCatching null
        val url = best.file.string("url")?.takeIf { it.startsWith("https://files.pythonhosted.org/") }
            ?: error("PyPI named a file from somewhere other than its file host")
        val digest = best.file.obj("digests")?.string("sha256")?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            ?: error("PyPI published no SHA-256 for ${best.version}")
        // Only the latest release's requirements are in this answer.
        val requires = if (info?.string("version") == best.version.text) {
            info.array("requires_dist")?.mapNotNull { (it as? Json.Str)?.value }?.let(::plainRequirements)
        } else {
            null
        }
        UpstreamRelease(best.version.text, url, digest, "published-digest", requires, "pypi")
    }

    private fun pythonAllows(spec: String?, python: Pep440): Boolean =
        spec.isNullOrBlank() || (PySpecifiers.parseOrNull(spec)?.matches(python) ?: false)

    /**
     * The package names in `Requires-Dist` lines, the way the catalogue lists them: without version
     * specifiers, and without anything only wanted for an extra.
     */
    fun plainRequirements(lines: List<String>): List<String> =
        lines.filterNot { it.substringAfter(';', "").contains("extra") }
            .map { it.trim().split(Regex("""[\s<>=!~;\[(]"""), limit = 2)[0].lowercase().replace('_', '-') }
            .filter { it.isNotEmpty() }
            .distinct()

    /** The latest GitHub release, as a download of its tag, or null for a repository with none. */
    fun githubLatest(json: String, repo: String): UpstreamRelease? {
        val obj = Json.parse(json) as? Json.Obj ?: return null
        val tag = obj.string("tag_name")?.takeIf { SAFE_REF.matches(it) } ?: return null
        if ((obj.fields["draft"] as? Json.Bool)?.value == true) return null
        if ((obj.fields["prerelease"] as? Json.Bool)?.value == true) return null
        return UpstreamRelease(
            version = tag,
            archive = "https://codeload.github.com/$repo/zip/refs/tags/$tag",
            sha256 = null, verified = "received-only", requires = null, source = "github",
        )
    }

    /** The newest commit of a repository's default branch, as a download of that commit. */
    fun githubHead(json: String, repo: String): UpstreamRelease? {
        val sha = (Json.parse(json) as? Json.Obj)?.string("sha")?.takeIf { Regex("[0-9a-f]{40}").matches(it) }
            ?: return null
        return UpstreamRelease(
            version = sha.take(12), archive = "https://codeload.github.com/$repo/zip/$sha",
            sha256 = null, verified = "received-only", requires = null, source = "github",
        )
    }

    /** Whether GitHub's compare of installed...candidate says the candidate is strictly ahead. */
    fun githubAhead(json: String): Boolean =
        ((Json.parse(json) as? Json.Obj)?.string("status")) == "ahead"

    /** Whether a GitHub candidate is newer than the installed version when both are release tags. */
    fun tagNewer(installed: String, candidate: String): Boolean? {
        val have = Pep440.parseOrNull(installed) ?: return null
        val offered = Pep440.parseOrNull(candidate) ?: return null
        return offered > have
    }

    /**
     * [entry] as it would be installed at [release], given the bytes that arrived.
     *
     * Where the source published a digest it must be the one that arrived, or this refuses: nothing is
     * unpacked from a file that is not the one named. Otherwise the digest of what arrived is the one
     * recorded, and the record says it was checked only by HTTPS. A GitHub archive's top folder is
     * named after the tag or commit, so it is read from the archive and not carried over; the
     * paths, import name and modules are the tested ones, and an update that moved them fails to
     * stage and changes nothing.
     */
    fun entryFor(entry: PluginCatalogue.Entry, release: UpstreamRelease, archive: ByteArray): PluginCatalogue.Entry {
        val install = checkNotNull(entry.install) { "${entry.name} has no install source" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it) }
        check(release.sha256 == null || release.sha256 == digest) {
            "the download of ${entry.name} ${release.version} is not the file ${release.source} published " +
                "(expected ${release.sha256?.take(12)}…, got ${digest.take(12)}…)"
        }
        val strip = if (install.source == "github" || release.source == "github") topFolder(archive) else install.strip
        return entry.copy(
            install = install.copy(
                source = release.source,
                archive = release.archive,
                sha256 = digest,
                version = release.version,
                strip = strip,
                requires = release.requires ?: install.requires,
            ),
        )
    }

    /** The folder every member of a GitHub zip sits in, with its trailing slash, or "" when there is none. */
    fun topFolder(archive: ByteArray): String =
        java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            val name = zip.nextEntry?.name.orEmpty()
            if ('/' in name) name.substringBefore('/') + "/" else ""
        }

    private val SAFE_REF = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,100}")
}
