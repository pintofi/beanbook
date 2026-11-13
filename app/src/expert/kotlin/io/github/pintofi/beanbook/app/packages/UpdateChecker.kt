package io.github.pintofi.beanbook.app.packages

import io.github.pintofi.beanbook.core.data.InstallRecord
import io.github.pintofi.beanbook.core.data.UpstreamRelease
import io.github.pintofi.beanbook.core.data.UpstreamReleases
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Asks where a plugin came from whether there is a newer version. Expert edition only, and only when
 * the person taps Check for updates: nothing here runs in the background.
 *
 * The questions go to the two places a catalogue plugin can come from, over HTTPS and nowhere else:
 * PyPI's JSON API and GitHub's REST API. What they answer is read by [UpstreamReleases], which holds
 * the rules for what counts as newer. A source that cannot be reached, or answers something unreadable,
 * is a failure that is reported, never "up to date".
 *
 * @param get the request, as (status, body); replaced in tests.
 */
class UpdateChecker(private val get: (String) -> Pair<Int, String> = ::request) {

    /**
     * The newer release for what [record] describes, or null when the installed [version] is current.
     * Fails when [record] names no upstream to ask, or the source could not be read.
     */
    fun check(record: InstallRecord, version: String): Result<UpstreamRelease?> = runCatching {
        val upstream = record.upstream ?: error("the app does not know where ${record.name} came from")
        when (record.source) {
            "pypi" -> pypi(upstream, version)
            "github" -> github(upstream, version)
            else -> error("${record.name} comes from a source the app cannot ask")
        }
    }

    private fun pypi(project: String, version: String): UpstreamRelease? {
        val (code, body) = get("https://pypi.org/pypi/${URLEncoder.encode(project, "UTF-8")}/json")
        check(code == 200) { "PyPI answered HTTP $code for $project" }
        return UpstreamReleases.pypi(body, version).getOrThrow()
    }

    private fun github(repo: String, version: String): UpstreamRelease? {
        val (latestCode, latest) = get("https://api.github.com/repos/$repo/releases/latest")
        val release = when (latestCode) {
            200 -> UpstreamReleases.githubLatest(latest, repo)
            404, 410 -> null
            else -> error(githubProblem(latestCode, repo))
        }
        // A repository with no releases is followed on its default branch, as the catalogue pinned it.
        val candidate = release ?: run {
            val (headCode, head) = get("https://api.github.com/repos/$repo/commits/HEAD")
            check(headCode == 200) { githubProblem(headCode, repo) }
            UpstreamReleases.githubHead(head, repo) ?: error("GitHub's answer for $repo could not be read")
        }
        UpstreamReleases.tagNewer(version, candidate.version)?.let { newer -> return candidate.takeIf { newer } }
        // Neither side orders as a version (a commit against a tag): ask GitHub which is ahead.
        val ref = candidate.archive.substringAfterLast("/zip/").removePrefix("refs/tags/")
        val (compareCode, compare) = get("https://api.github.com/repos/$repo/compare/$version...$ref")
        // 404: the installed commit is not in this repository's history (rewritten, or a fork). It cannot
        // be said to be older, so nothing is offered.
        if (compareCode == 404) return null
        check(compareCode == 200) { githubProblem(compareCode, repo) }
        return candidate.takeIf { UpstreamReleases.githubAhead(compare) }
    }

    private fun githubProblem(code: Int, repo: String) =
        if (code == 403 || code == 429) "GitHub is limiting requests right now. Try again later."
        else "GitHub answered HTTP $code for $repo"

    companion object {
        const val AVAILABLE = true

        /** The hosts a check talks to, shown on screen before and after. */
        val HOSTS = listOf("pypi.org", "api.github.com")

        private val ALLOWED = HOSTS.toSet()
        private const val MAX_BYTES = 3_000_000

        private fun request(url: String): Pair<Int, String> {
            val address = URL(url)
            check(address.protocol == "https" && address.host in ALLOWED) { "not a host an update check may ask" }
            val connection = address.openConnection() as HttpURLConnection
            return try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "Beanbook")
                connection.setRequestProperty("Accept", "application/json, application/vnd.github+json")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    // One byte past the limit is read to know it was exceeded.
                    val bytes = input.readBytesUpTo(MAX_BYTES + 1)
                    check(bytes.size <= MAX_BYTES) { "the answer is larger than ${MAX_BYTES / 1_000_000} MB" }
                    String(bytes, Charsets.UTF_8)
                }.orEmpty()
                code to body
            } finally {
                connection.disconnect()
            }
        }

        /** At most [limit] bytes of the stream; API 29 has no readNBytes. */
        private fun java.io.InputStream.readBytesUpTo(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
