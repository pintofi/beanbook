package io.github.pintofi.beanbook.app.engine

import io.github.pintofi.beanbook.core.entitlement.Entitlement
import io.github.pintofi.beanbook.core.entitlement.Entitlements
import io.github.pintofi.beanbook.core.entitlement.KeyCheck
import io.github.pintofi.beanbook.core.entitlement.KeyVerifier
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live entitlement, and the one place a key is applied or removed.
 *
 * **Nothing here logs a key, an email or an order reference.** Not at debug level, not
 * behind a flag: a key is the only secret this app holds, and a log line is the easiest way
 * for one to end up in a bug report (docs/threat-model.md). Failures are reported by their
 * [io.github.pintofi.beanbook.core.entitlement.KeyProblem], which describes the fault without quoting the
 * key back.
 */
class EntitlementState(
    private val entitlements: Entitlements,
    private val scope: CoroutineScope,
    private val verifier: KeyVerifier = KeyVerifier(),
    private val clock: () -> Instant = Instant::now,
) {
    private val _entitlement = MutableStateFlow(Entitlement.FREE)
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    /** What the Contribute screen last said back to the user. Never contains key material. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        _entitlement.value = entitlements.current(clock())
    }

    fun start(now: Instant = clock()) {
        _entitlement.value = entitlements.onAppStart(now)
    }

    /**
     * Verifies a pasted key and, if it is good, stores it. Verification happens off the main
     * thread: the signature check is fast, but reading and writing the key file is IO.
     */
    fun applyKey(input: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { verifier.check(input) }
            when (result) {
                is KeyCheck.Verified -> {
                    withContext(Dispatchers.IO) { entitlements.applyKey(result.key.stored()) }
                    refresh()
                    _message.value =
                        "Key accepted. ${result.key.tier.label} unlocked."
                }
                is KeyCheck.Rejected -> _message.value = result.problem.describe()
            }
        }
    }

    fun removeKey() {
        scope.launch {
            withContext(Dispatchers.IO) { entitlements.removeKey() }
            refresh()
            "Key removed. It still works and can be entered again."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
