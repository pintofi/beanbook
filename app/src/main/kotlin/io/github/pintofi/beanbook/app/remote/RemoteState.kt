package io.github.pintofi.beanbook.app.remote

import android.util.Log
import io.github.pintofi.beanbook.core.data.PendingSubmission
import io.github.pintofi.beanbook.core.data.PendingSubmissionStore
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.remote.BasicCredentials
import io.github.pintofi.beanbook.engine.remote.FavaClient
import io.github.pintofi.beanbook.engine.remote.RemoteAddress
import io.github.pintofi.beanbook.engine.remote.RemoteAddressRejected
import io.github.pintofi.beanbook.engine.remote.RemoteEngineClient
import io.github.pintofi.beanbook.engine.remote.ServerCapabilities
import io.github.pintofi.beanbook.engine.remote.SubmitResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Remote mode, from the app's point of view: an address, optional credentials, and whatever
 * the server last said.
 *
 * **Nothing here logs a password, an address or a server response.** A remote address is
 * identifying, and a password is a secret; both are exactly what ends up pasted into a bug
 * report if the app writes them down.
 */
class RemoteState(
    stateDir: File,
    private val scope: CoroutineScope,
) {
    private val addressFile = File(stateDir, "remote-address")
    private val usernameFile = File(stateDir, "remote-username")
    private val password = KeystoreSecret(File(stateDir, "remote-password"))
    private val pendingStore = PendingSubmissionStore(File(stateDir, "remote-pending"))

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _capabilities = MutableStateFlow<ServerCapabilities?>(null)
    val capabilities: StateFlow<ServerCapabilities?> = _capabilities.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * The submission waiting to be retried: one that failed, or that was still being sent when the
     * app was closed. It is kept on disk from before it is sent until the server confirms it or the
     * person discards it, so the key survives the process.
     */
    private val _pendingRetry = MutableStateFlow(pendingStore.load())
    val pendingRetry: StateFlow<PendingSubmission?> = _pendingRetry.asStateFlow()

    @Volatile
    private var client: RemoteEngineClient? = null

    val savedAddress: String? get() = addressFile.takeIf { it.isFile }?.readText()?.trim()
    val savedUsername: String? get() = usernameFile.takeIf { it.isFile }?.readText()?.trim()
    val isConfigured: Boolean get() = savedAddress != null

    /**
     * Saves an address and credentials, then connects.
     *
     * The address is validated before anything is written: a rejected address never becomes
     * saved state, so the app cannot come back later and try to use it.
     */
    fun connect(address: String, username: String?, secret: CharArray?) {
        val parsed = RemoteAddress.parse(address).getOrElse { error ->
            _message.value = (error as? RemoteAddressRejected)?.problem?.describe()
                ?: "That address cannot be used."
            return
        }

        // Copied before the coroutine, then wiped here — the caller wipes its own array as
        // soon as this returns, and reading it later would send an empty password (the same
        // mistake backup encryption once made).
        val ownSecret = secret?.copyOf()

        scope.launch {
            addressFile.parentFile?.mkdirs()
            addressFile.writeText(parsed.base)
            if (username.isNullOrBlank()) usernameFile.delete() else usernameFile.writeText(username)
            val stored = runCatching {
                if (ownSecret == null || ownSecret.isEmpty()) password.clear() else password.store(ownSecret)
            }
            ownSecret?.fill('\u0000')
            if (stored.isFailure) {
                // Not connected without it: the server would be asked for a password this
                // device just failed to keep, and the next start would ask again with none.
                Log.w(TAG, "the password could not be stored", stored.exceptionOrNull())
                _message.value = "The password could not be kept safely on this device, so nothing " +
                    "was saved. Try again; if it keeps failing, check the screen lock."
                return@launch
            }

            open(parsed, credentials(username, savedSecret()))
        }
    }

    /** Reconnects with what was saved, at startup or after the app comes back. */
    fun reconnect() {
        val saved = savedAddress ?: return
        val parsed = RemoteAddress.parse(saved).getOrNull() ?: return
        scope.launch { open(parsed, credentials(savedUsername, savedSecret())) }
    }

    private suspend fun open(address: RemoteAddress, credentials: BasicCredentials?) {
        val engine = RemoteEngineClient(address, credentials)
        client = engine
        _status.value = EngineStatus.Starting

        val status = engine.start(address.base)
        _status.value = status
        _capabilities.value = engine.capabilities()
        // On success the capabilities card already says it; a second identical line below
        // it just reads as a stutter.
        _message.value = (status as? EngineStatus.Failed)?.message
    }

    fun disconnect() {
        scope.launch {
            client?.stop()
            client = null
            _status.value = EngineStatus.Stopped
            _capabilities.value = null
            _message.value = "Disconnected. The address and credentials are still saved."
        }
    }

    /** Forgets everything about the server, including the password. */
    fun forget() {
        scope.launch {
            client?.stop()
            client = null
            addressFile.delete()
            usernameFile.delete()
            password.destroyKey()
            _status.value = EngineStatus.Stopped
            _capabilities.value = null
            pendingStore.clear()
            _pendingRetry.value = null
            _message.value = "Server details removed from this device."
        }
    }

    /** The origins the WebView may load; empty when nothing is connected. */
    fun allowedOrigins(): Set<String> = client?.allowedOrigins().orEmpty()

    /**
     * The credentials the WebView should answer a Basic-auth challenge with, if any.
     *
     * Read fresh from the Keystore each time rather than held in a field: this process
     * should hold the password for as long as one request takes, and no longer.
     */
    fun httpAuth(): Pair<String, CharArray>? {
        val user = savedUsername ?: return null
        val secret = savedSecret() ?: return null
        return user to secret
    }

    fun reportUrl(report: io.github.pintofi.beanbook.core.engine.ReportPath): String? =
        runCatching { client?.urlFor(report) }.getOrNull()

    /**
     * Posts an entry to the server.
     *
     * A failure keeps the draft *and its key*, so [retryPending] can ask the server whether
     * the entry arrived rather than sending a second one.
     */
    fun submit(draft: TransactionDraft) {
        val engine = client ?: run {
            _message.value = "Not connected to a server."
            return
        }
        val key = FavaClient.newKey()
        scope.launch {
            // Before it is sent, not after it fails: if the app is killed while the request is
            // in flight, the next start still has the key to ask the server about.
            runCatching { pendingStore.save(PendingSubmission(draft, key)) }
                .onFailure { Log.w(TAG, "the pending entry could not be saved", it) }
            report(engine.submit(draft, key), draft, key)
        }
    }

    /** Retries the pending submission — asking first whether it is already there. */
    fun retryPending() {
        val engine = client ?: return
        val pending = _pendingRetry.value ?: return
        scope.launch { report(engine.submit(pending.draft, pending.key, isRetry = true), pending.draft, pending.key) }
    }

    fun discardPending() {
        pendingStore.clear()
        _pendingRetry.value = null
        _message.value = null
    }

    private fun report(result: SubmitResult, draft: TransactionDraft, key: String) {
        when (result) {
            is SubmitResult.Stored -> {
                pendingStore.clear()
                _pendingRetry.value = null
                _message.value = "Saved on the server."
            }

            is SubmitResult.AlreadyPresent -> {
                // The retry found it: the first attempt had arrived after all.
                pendingStore.clear()
                _pendingRetry.value = null
                _message.value = "Already saved. The earlier attempt did reach the server."
            }

            is SubmitResult.Failed -> {
                _pendingRetry.value = PendingSubmission(draft, key)
                Log.w(TAG, "remote submit failed: ${result.failure.javaClass.simpleName}")
                _message.value = result.failure.describe() +
                    " Your entry is kept. Retrying will check whether it arrived first."
            }
        }
    }

    private fun savedSecret(): CharArray? = password.read()

    private fun credentials(username: String?, secret: CharArray?): BasicCredentials? =
        if (username.isNullOrBlank() || secret == null) null else BasicCredentials(username, secret)

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "RemoteState"
    }
}
