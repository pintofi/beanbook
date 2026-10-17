package io.github.pintofi.beanbook.engine.remote

import io.github.pintofi.beanbook.core.engine.EngineClient
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.ReportPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A self-hosted Fava, behind the same [EngineClient] interface as the embedded one.
 *
 * That shared interface is what lets the WebView, the report routes and the origin allowlist
 * be written once (docs/architecture.md section 2). The differences that matter are the ones this
 * class cannot hide:
 *
 *  * **"Starting" means connecting**, not launching a server. Nothing here owns a process,
 *    so [stop] releases a connection and nothing else.
 *  * **The allowlist is exactly one origin** — the configured server. Fava's own pages link
 *    outward (documentation, its changelog); those are external links for the browser, not
 *    navigations for this WebView.
 *  * **There is no offline cache.** Remote is view plus quick-add. A cache would be a second
 *    copy of a ledger this app is not the owner of.
 */
class RemoteEngineClient(
    private val address: RemoteAddress,
    private val credentials: BasicCredentials? = null,
    private val transport: Transport = PlatformTransport(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EngineClient {

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val client = FavaClient(address, transport, credentials)

    @Volatile
    private var capabilities: ServerCapabilities? = null

    /** What the server said about itself, once connected. */
    fun capabilities(): ServerCapabilities? = capabilities

    /**
     * Connects and discovers the ledger.
     *
     * [projectPath] is ignored: a remote ledger is whichever one the server serves, and
     * pretending otherwise would let a local path decide what a remote server shows.
     */
    override suspend fun start(projectPath: String): EngineStatus = withContext(io) {
        _status.value = EngineStatus.Starting
        // A failed connect must not leave the previous connection's slug usable by urlFor.
        capabilities = null
        val started = System.currentTimeMillis()
        val next = try {
            val found = client.connect()
            capabilities = found
            EngineStatus.Serving(
                origin = address.origin,
                ledgerSlug = found.slug,
                startupMillis = System.currentTimeMillis() - started,
            )
        } catch (remote: RemoteException) {
            EngineStatus.Failed(
                message = remote.failure.describe(),
                // A certificate failure is not something retrying fixes: it needs the
                // server's configuration changed, and the app must not invite another go.
                recoverable = remote.failure !is RemoteFailure.CertificateRejected,
                cause = remote,
            )
        } catch (cancelled: CancellationException) {
            _status.value = EngineStatus.Stopped
            throw cancelled
        } catch (other: java.net.URISyntaxException) {
            failedUnexpectedly(other)
        } catch (other: IllegalArgumentException) {
            failedUnexpectedly(other)
        } catch (other: IllegalStateException) {
            failedUnexpectedly(other)
        }
        _status.value = next
        next
    }

    /**
     * A malformed address that only shows up at request time must end as a status the screen
     * can show, not as a crash.
     */
    private fun failedUnexpectedly(other: Exception) = EngineStatus.Failed(
        message = "Something went wrong talking to the server: " +
            "${other.message ?: other.javaClass.simpleName}.",
        recoverable = true,
        cause = other,
    )

    override suspend fun stop() {
        capabilities = null
        _status.value = EngineStatus.Stopped
    }

    override fun urlFor(report: ReportPath): String {
        val slug = capabilities?.slug ?: error("not connected: no ledger has been discovered yet")
        // The default report has no path of its own: "/slug//" would be a different URL.
        val tail = if (report.path.isEmpty()) "" else "${report.path}/"
        return address.resolve("/$slug/$tail")
    }

    /**
     * The one origin this WebView may load. Not the base path — an allowlist is about
     * authority, and a path prefix would let `https://host/anything-else` through.
     */
    override fun allowedOrigins(): Set<String> = setOf(address.allowlistOrigin)

    /**
     * Submits an entry, exactly once.
     *
     * @param key pass the same key to retry a submission whose outcome is unknown; see
     *   [FavaClient] for why that matters.
     */
    suspend fun submit(
        draft: io.github.pintofi.beanbook.core.domain.TransactionDraft,
        key: String = FavaClient.newKey(),
        isRetry: Boolean = false,
    ): SubmitResult = withContext(io) {
        val slug = capabilities?.slug
            ?: return@withContext SubmitResult.Failed(
                RemoteFailure.Unexpected("not connected to a server yet"),
            )
        if (isRetry) client.retry(slug, draft, key) else client.submit(slug, draft, key)
    }
}
