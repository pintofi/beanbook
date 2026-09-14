package io.github.pintofi.beanbook.app.packages

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a catalogue plugin's pinned archive (B82). Expert edition only.
 *
 * Only from the two hosts the catalogue pins to -- PyPI's file host and GitHub's archive host --
 * only over HTTPS, and never more than [MAX_BYTES]. The caller checks the SHA-256 against the
 * catalogue before a byte of it is unpacked, so this only has to get the bytes.
 */
class ArchiveFetcher {

    fun fetch(url: String): Result<ByteArray> = runCatching {
        val address = URL(url)
        check(address.protocol == "https" && address.host in HOSTS) {
            "the catalogue may only download from ${HOSTS.joinToString()}, not ${address.host}"
        }
        val connection = address.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            check(code in 200..299) { "$url returned HTTP $code" }
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    check(out.size() <= MAX_BYTES) { "the archive is larger than ${MAX_BYTES / 1_000_000} MB" }
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val HOSTS = setOf("files.pythonhosted.org", "codeload.github.com")
        const val MAX_BYTES = 50_000_000
        const val AVAILABLE = true
    }
}
