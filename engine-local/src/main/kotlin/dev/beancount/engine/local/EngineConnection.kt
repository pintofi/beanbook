package dev.beancount.engine.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the connection to the engine process.
 *
 * Binding is lazy and re-established on demand: the engine process can be killed by the
 * system at any time, and the correct response is to bind again rather than to fail. Every
 * call therefore goes through [withEngine], which binds if needed.
 *
 * Large payloads are exchanged through files in the app's cache directory, not through
 * Binder — see docs/milestones/m5.5-process-split-analysis.md.
 */
class EngineConnection(private val context: Context) {

    private val bindLock = Mutex()

    @Volatile
    private var service: IEngineService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IEngineService.Stub.asInterface(binder)
            Log.i(TAG, "engine process connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The engine process died — a plugin crash, or the low-memory killer. The
            // ledger is unaffected: nothing in that process writes to a project.
            service = null
            Log.w(TAG, "engine process disconnected")
        }
    }

    /** Runs [block] against the engine, binding first if necessary. */
    suspend fun <T> withEngine(block: (IEngineService) -> T): Result<T> {
        val engine = connect() ?: return Result.failure(IllegalStateException("engine unavailable"))
        return runCatching { block(engine) }
    }

    private suspend fun connect(): IEngineService? {
        service?.let { return it }
        return bindLock.withLock {
            service?.let { return it }

            val intent = Intent(context, EngineService::class.java)
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Log.w(TAG, "bindService refused")
                return null
            }
            // Process fork plus bind measured at ~100-300 ms; the timeout is generous
            // because a cold start also pays for Chaquopy's asset extraction.
            withTimeoutOrNull(BIND_TIMEOUT_MILLIS) {
                while (service == null) kotlinx.coroutines.delay(20)
                service
            }
        }
    }

    fun release() {
        runCatching { context.unbindService(connection) }
        service = null
    }

    /** A scratch path in the app's cache for a payload too large for Binder. */
    fun payloadFile(name: String): File =
        File(context.cacheDir, "engine-payload").apply { mkdirs() }
            .resolve("$name-${System.nanoTime()}")

    private companion object {
        const val TAG = "EngineConnection"
        const val BIND_TIMEOUT_MILLIS = 30_000L
    }
}

/** Reads and deletes a payload file the engine produced. */
internal fun File.consumeText(): String = try {
    readText()
} finally {
    delete()
}
