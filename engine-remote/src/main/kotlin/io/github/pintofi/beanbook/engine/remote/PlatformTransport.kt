package io.github.pintofi.beanbook.engine.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.net.ssl.SSLException

/** One HTTP exchange, reduced to what this app needs. */
data class RemoteResponse(
    val status: Int,
    val body: String,
    val location: String? = null,
) {
    val isSuccess: Boolean get() = status in 200..299
    val isRedirect: Boolean get() = status in 300..399
}

/**
 * Why a request did not produce an answer.
 *
 * [CertificateRejected] is deliberately its own case rather than one flavour of network
 * error: it is the one failure the user must never be offered a way past, so it is
 * impossible to fold it into "something went wrong, try again".
 */
sealed interface RemoteFailure {
    data class CertificateRejected(val detail: String) : RemoteFailure
    data class Unreachable(val detail: String) : RemoteFailure
    data class Unauthorised(val status: Int) : RemoteFailure
    data class Rejected(val status: Int, val body: String) : RemoteFailure
    data class Unexpected(val detail: String) : RemoteFailure

    fun describe(): String = when (this) {
        is CertificateRejected ->
            "The server's certificate could not be verified, so the connection was stopped " +
                "($detail). This app has no way to continue anyway — a certificate that " +
                "does not check out means something is between you and your server."
        is Unreachable -> "Could not reach the server: $detail."
        is Unauthorised ->
            "The server refused the credentials (HTTP $status). Check the username and " +
                "password your server expects."
        is Rejected -> "The server refused the request (HTTP $status): ${body.take(200)}"
        is Unexpected -> "Something went wrong talking to the server: $detail."
    }
}

class RemoteException(
    val failure: RemoteFailure,
    cause: Throwable? = null,
) : IOException(failure.describe(), cause)

/** Credentials for a reverse proxy. Fava itself has no login. */
data class BasicCredentials(val username: String, val password: CharArray) {
    fun header(): String =
        "Basic " + Base64.getEncoder()
            .encodeToString("$username:${String(password)}".toByteArray())

    override fun equals(other: Any?): Boolean =
        other is BasicCredentials && username == other.username &&
            password.contentEquals(other.password)

    override fun hashCode(): Int = username.hashCode() * 31 + password.contentHashCode()

    /** Never let a password reach a log line, a crash report or a diagnostics bundle. */
    override fun toString(): String = "BasicCredentials(username=$username, password=…)"
}

/**
 * One request, however it is carried.
 *
 * This exists so tests can drive a loopback fixture without weakening anything: the only
 * implementation shipped is [PlatformTransport], `FavaClient` defaults to it, and
 * [RemoteAddress] cannot express a cleartext address in the first place. A test double lives
 * in the test source set and never reaches a build.
 */
interface Transport {
    fun request(
        url: String,
        method: String = "GET",
        credentials: BasicCredentials? = null,
        body: String? = null,
        contentType: String = "application/json",
        followRedirect: Boolean = false,
    ): RemoteResponse
}

/**
 * HTTP and HTTPS, through the platform's own stack.
 *
 * **There is no `TrustManager` here, no `HostnameVerifier`, and no way to add one.** That
 * is not an omission: when a request is `https`, the default verification is the strict one,
 * and every mechanism for weakening it is simply absent from this class, so no future call
 * site can pass a flag that turns it off. A certificate that does not verify raises
 * [RemoteFailure.CertificateRejected] and the request ends there (docs/threat-model.md
 * section 3.5).
 *
 * Cleartext is a separate matter and a separate decision. `http` is carried because a user
 * may deliberately choose it for a LAN or VPN host, and the app shows what that means rather
 * than pretending otherwise. What it must never become is a *fallback*: nothing here retries
 * an `https` address over `http`, and a failed handshake stays a failed handshake. Choosing
 * cleartext is the user's decision to make once, not something the app can arrive at on
 * their behalf after something went wrong.
 */
class PlatformTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : Transport {

    override fun request(
        url: String,
        method: String,
        credentials: BasicCredentials?,
        body: String?,
        contentType: String,
        followRedirect: Boolean,
    ): RemoteResponse {
        val parsed = URI(url).toURL()
        val scheme = parsed.protocol.lowercase()
        check(scheme == "https" || scheme == "http") {
            // Belt and braces behind RemoteAddress: nothing constructs a URL here by hand,
            // and if something ever does, it does not get to be a `file:` or `jar:` URL with
            // an HTTP-shaped name.
            "refusing a $scheme request to $url"
        }

        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = followRedirect
            setRequestProperty("Accept", "application/json")
            credentials?.let { setRequestProperty("Authorization", it.header()) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                // Streaming mode, and it is load-bearing for data invariant 6.
                //
                // With a buffered body the platform stack is free to re-send the whole
                // request when a connection dies before the response arrives — silently,
                // inside a single call. Against a Fava that does not deduplicate, that puts
                // the entry in the ledger twice while the app reports one success. Seen
                // happening on a device. A streamed body cannot be
                // replayed, so the stack raises an IOException instead and the retry becomes
                // the caller's decision, made with the idempotency key.
                setFixedLengthStreamingMode(body.toByteArray().size)
            }
        }

        try {
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }

            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.use { readBounded(it) }.orEmpty()

            if (status == 401 || status == 403) {
                throw RemoteException(RemoteFailure.Unauthorised(status))
            }

            return RemoteResponse(
                status = status,
                body = text,
                location = connection.getHeaderField("Location"),
            )
        } catch (ssl: SSLException) {
            // Every certificate problem lands here: expired, self-signed, wrong host, a
            // proxy in the middle. All of them stop the request.
            throw RemoteException(
                RemoteFailure.CertificateRejected(ssl.message ?: ssl.javaClass.simpleName),
                cause = ssl,
            )
        } catch (remote: RemoteException) {
            throw remote
        } catch (io: IOException) {
            throw RemoteException(
                RemoteFailure.Unreachable(io.message ?: io.javaClass.simpleName),
                cause = io,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads at most [MAX_BODY_BYTES]. Every answer this app needs is small; a server that
     * keeps sending is not one worth waiting for or holding in memory.
     */
    private fun readBounded(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BODY_BYTES) {
                throw RemoteException(RemoteFailure.Unexpected("the server's answer was too large"))
            }
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
