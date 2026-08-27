package dev.beancount.engine.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Speaks plain HTTP to a loopback fixture, for tests that are about request shaping and the
 * retry rule rather than about TLS.
 *
 * It rewrites `https://127.0.0.1:…` to `http://…` and **refuses anything else**, so it cannot
 * be pointed at a real server by accident. TLS itself is proven in `CertificateIsFatalTest`
 * against a real handshake; this double exists so those two concerns are tested separately
 * rather than one hiding the other.
 */
internal class PlainTransportForTests : Transport {

    override fun request(
        url: String,
        method: String,
        credentials: BasicCredentials?,
        body: String?,
        contentType: String,
        followRedirect: Boolean,
    ): RemoteResponse {
        require(url.startsWith("https://127.0.0.1:")) { "test transport is loopback-only: $url" }
        val plain = URI("http" + url.removePrefix("https"))

        val connection = (plain.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = followRedirect
            credentials?.let { setRequestProperty("Authorization", it.header()) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }

        return try {
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            RemoteResponse(
                status = status,
                body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                location = connection.getHeaderField("Location"),
            )
        } catch (io: IOException) {
            throw RemoteException(RemoteFailure.Unreachable(io.message ?: "io"), cause = io)
        } finally {
            connection.disconnect()
        }
    }
}
