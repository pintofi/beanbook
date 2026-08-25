package dev.beancount.engine.remote

import dev.beancount.core.engine.EngineClient
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.engine.ReportPath
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
 * be written once (docs/architecture.md §2). The differences that matter are the ones this
 * class cannot hide:
 *
 *  * **"Starting" means connecting**, not launching a server. Nothing here owns a process,
 *    so [stop] releases a connection and nothing else.
 *  * **The allowlist is exactly one origin** — the configured server. Fava's own pages link
 *    outward (documentation, its changelog); those are external links for the browser, not
 *    navigations for this WebView.
 *  * **There is no offline cache.** SPEC.md §"Remote" is explicit: remote is view plus
 *    quick-add. A cache would be a second copy of a ledger this app is not the owner of.
 */
class RemoteEngineClient(
    private val address: RemoteAddress,
    private val credentials: BasicCredentials? = null,
    private val transport: Transport = HttpsTransport(),
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
        }
        _status.value = next
        next
    }

    override suspend fun stop() {
        capabilities = null
        _status.value = EngineStatus.Stopped
    }

    override fun urlFor(report: ReportPath): String {
        val slug = capabilities?.slug ?: error("not connected: no ledger has been discovered yet")
        return address.resolve("/$slug/${report.path}/")
    }

    /**
     * The one origin this WebView may load. Not the base path — an allowlist is about
     * authority, and a path prefix would let `https://host/anything-else` through.
     */
    override fun allowedOrigins(): Set<String> = setOf(address.origin)

    /**
     * Submits an entry, exactly once.
     *
     * @param key pass the same key to retry a submission whose outcome is unknown; see
     *   [FavaClient] for why that matters.
     */
    suspend fun submit(
        draft: dev.beancount.core.domain.TransactionDraft,
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
