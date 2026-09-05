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

    override suspend fun start(projectPath: String): EngineStatus = lifecycleLock.withLock {
        (status.value as? EngineStatus.Serving)?.let { return it }

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
        return status
    }

    override suspend fun stop() = lifecycleLock.withLock {
        withContext(io) {
            connection.withEngine { it.stopEngine() }
                .onSuccess { Log.i(TAG, it) }
                .onFailure { Log.w(TAG, "engine stop failed", it) }
        }
        token = null
        _status.value = EngineStatus.Stopped
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
    }
}
