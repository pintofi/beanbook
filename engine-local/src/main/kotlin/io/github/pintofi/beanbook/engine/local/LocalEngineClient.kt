package io.github.pintofi.beanbook.engine.local

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.pintofi.beanbook.core.engine.EngineClient
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.ReportPath
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs Fava in this process, bound to loopback, behind a per-process token.
 *
 * The token is generated here — never in Python, never persisted, never logged — and
 * handed to the WebView as a cookie by the caller ([sessionToken]).
 */
class LocalEngineClient(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connection: EngineConnection = EngineConnection(context),
) : EngineClient {

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val lifecycleLock = Mutex()

    /** Set while this client is ending the process itself, so that is not reported as a loss. */
    @Volatile
    private var endingProcess = false

    /**
     * Told when the engine process ended on its own -- Android reclaimed it in the background,
     * or a plugin crashed it. What it served is gone, so the status goes back to stopped
     * rather than keeping an address that now refuses connections.
     */
    @Volatile
    var onProcessLost: (() -> Unit)? = null

    init {
        connection.onProcessLost = {
            if (!endingProcess) {
                token = null
                _status.value = EngineStatus.Stopped
                onProcessLost?.invoke()
            }
        }
    }

    @Volatile
    private var token: String? = null

    /** The per-process token, valid only while the engine is serving. */
    val sessionToken: String? get() = token

    /**
     * What the engine is running: Python, Beancount, Fava, Beanquery.
     *
     * Read from the engine rather than from our version catalogue, so a backup manifest
     * records what actually wrote the archive.
     */
    suspend fun versions(): Map<String, String> = withContext(io) {
        connection.withEngine { it.engineVersions() }
            .getOrNull()
            ?.split(' ')
            ?.mapNotNull { pair ->
                val (name, value) = pair.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: return@mapNotNull null
                name to value
            }
            ?.toMap()
            .orEmpty()
    }

    /**
     * Directories to make importable while Fava is served, for Fava extensions. Set before
     * [start]; empty means none, which is what every caller did until M14.
     */
    var pluginDirs: List<String> = emptyList()

    /** The ledger the running server was started on, so a different one is not answered by it. */
    @Volatile
    private var servingPath: String? = null

    override suspend fun start(projectPath: String): EngineStatus = lifecycleLock.withLock {
        // Already serving *this* ledger: nothing to do. Serving another one is not "already
        // started" -- after opening a second project the Reports tab kept showing the first
        // project's entries under the second one's name, so the old server is stopped
        // and the new ledger takes its place.
        (status.value as? EngineStatus.Serving)?.let { running ->
            if (servingPath == projectPath) return running
            withContext(io) {
                connection.withEngine { it.stopEngine() }
                    .onFailure { Log.w(TAG, "engine stop before switching ledger failed", it) }
            }
            token = null
            servingPath = null
        }

        _status.value = EngineStatus.Starting
        val newToken = newToken()
        val started = System.currentTimeMillis()
        val result = withContext(io) {
            connection.withEngine { engine ->
                engine.startEngine(projectPath, newToken, pluginDirs.joinToString(","))
            }
                .mapCatching { report ->
                    if (report.startsWith("error: ")) error(report.removePrefix("error: "))
                    token = newToken
                    parseStatus(report, System.currentTimeMillis() - started)
                }
        }

        val status = result.getOrElse { error ->
            token = null
            // The message is safe to surface: it never contains the token, and Fava's
            // parse errors are exactly what the user needs to see.
            EngineStatus.Failed(
                message = error.message ?: error.javaClass.simpleName,
                recoverable = error !is UnsatisfiedLinkError,
                cause = error,
            )
        }
        _status.value = status
        servingPath = if (status is EngineStatus.Serving) projectPath else null
        return status
    }

    /**
     * Shares the running Reports on the network, behind a sign-in. Returns the port it
     * listens on. Fails if Reports are not running, or the address is not a private one.
     */
    suspend fun startSharing(host: String, port: Int, username: String, passwordHash: String): Result<Int> =
        withContext(io) {
            connection.withEngine { it.startSharing(host, port, username, passwordHash) }
                .mapCatching { report ->
                    if (report.startsWith("error: ")) error(report.removePrefix("error: "))
                    report.split(' ')
                        .firstOrNull { it.startsWith("port=") }
                        ?.removePrefix("port=")
                        ?.toIntOrNull()
                        ?: error("the engine did not say which port it is sharing on")
                }
        }

    /** Stops sharing. Harmless when it was not on. */
    suspend fun stopSharing(): Result<String> = withContext(io) {
        connection.withEngine(retryIfDead = false) { it.stopSharing() }
    }

    override suspend fun stop() = lifecycleLock.withLock {
        withContext(io) {
            connection.withEngine { it.stopEngine() }
                .onSuccess { Log.i(TAG, it) }
                .onFailure { Log.w(TAG, "engine stop failed", it) }
        }
        token = null
        servingPath = null
        _status.value = EngineStatus.Stopped
    }

    /**
     * Ends the engine process, so the next start reads the packages and plugins on disk now.
     *
     * Stopping the server is not enough: Python caches every module it has imported, so a
     * package fetched after the interpreter started is not importable and an upgraded one
     * keeps its old code. The connection binds again on the next call, which forks a
     * new process.
     */
    suspend fun restartProcess() = lifecycleLock.withLock {
        withContext(io) {
            endingProcess = true
            try {
                val before = connection.withEngine { it.processPid() }.getOrNull()
                // The reply never arrives -- the process is killed inside the call -- so a dead
                // binder here is success, not a failure worth reporting, and not a reason to
                // try again: that would end the new process too.
                connection.withEngine(retryIfDead = false) { it.quitProcess() }
                connection.release()
                awaitNewProcess(before)
            } finally {
                endingProcess = false
            }
        }
        token = null
        _status.value = EngineStatus.Stopped
    }

    /** The pid of the process the engine is answering from, for tests and diagnostics. */
    suspend fun enginePid(): Int? = withContext(io) {
        connection.withEngine { it.processPid() }.getOrNull()
    }

    /**
     * Waits until the engine answers from a pid that is not [before].
     *
     * By pid, because every other signal lies: a bind succeeds while the old process is
     * still shutting down, and the module it answers from is the one with Fava already
     * running and the old imports cached.
     */
    private suspend fun awaitNewProcess(before: Int?) {
        repeat(PROCESS_EXIT_POLLS) {
            kotlinx.coroutines.delay(PROCESS_EXIT_POLL_MILLIS)
            val now = connection.withEngine { it.processPid() }.getOrNull()
            if (now != null && now != before) return
            if (now == before) connection.release()
        }
        Log.w(TAG, "the engine process did not come back within the wait")
    }

    override fun urlFor(report: ReportPath): String {
        val serving = status.value as? EngineStatus.Serving
            ?: error("engine is not serving")
        return "${serving.origin}/${serving.ledgerSlug}/${report.path}/"
    }

    override fun allowedOrigins(): Set<String> =
        setOfNotNull((status.value as? EngineStatus.Serving)?.origin)

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    private fun parseStatus(report: String, wallMillis: Long): EngineStatus {
        val fields = report.split(' ')
            .mapNotNull { field ->
                field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }
            .toMap()
        val port = fields["port"]?.toIntOrNull()
            ?: return EngineStatus.Failed("engine did not report a port", recoverable = true)
        val slug = fields["slug"]
            ?: return EngineStatus.Failed("engine did not report a ledger slug", recoverable = true)
        return EngineStatus.Serving(
            origin = "http://127.0.0.1:$port",
            ledgerSlug = slug,
            startupMillis = wallMillis,
        )
    }

    private companion object {
        const val TAG = "LocalEngine"
        const val TOKEN_BYTES = 32

        /** Two seconds in total, which is far longer than a process takes to go and come back. */
        const val PROCESS_EXIT_POLLS = 20
        const val PROCESS_EXIT_POLL_MILLIS = 100L
    }
}
