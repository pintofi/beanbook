package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.engine.EntitlementState
import io.github.pintofi.beanbook.core.entitlement.EntitlementStorage
import io.github.pintofi.beanbook.core.entitlement.Entitlements
import io.github.pintofi.beanbook.core.entitlement.StoredKey
import io.github.pintofi.beanbook.core.entitlement.StoredPurchase
import io.github.pintofi.beanbook.core.entitlement.TrialRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removing a key says so.
 *
 * `removeKey` built its confirmation string and dropped it: the line was a bare expression,
 * not an assignment, so the tier changed and the Contribute screen said nothing — while
 * applying a key said "Key accepted". Nothing failed, which is why it survived; the only
 * signal was a compiler warning that "Expression is unused".
 *
 * The assertion is therefore on the **message**, not on the tier. The tier change was never
 * broken and is already covered in `core-entitlement`; what was missing is the part a user
 * can actually see.
 */
@RunWith(AndroidJUnit4::class)
class EntitlementMessageTest {

    /** In memory: this is about what the state object reports, not about storage. */
    private class Storage : EntitlementStorage {
        private var trial: TrialRecord? = null
        private var key: StoredKey? = null
        private var purchase: StoredPurchase? = null
        override fun readTrial() = trial
        override fun writeTrial(record: TrialRecord) { trial = record }
        override fun readKey() = key
        override fun writeKey(key: StoredKey?) { this.key = key }
        override fun readPurchase() = purchase
        override fun writePurchase(purchase: StoredPurchase?) { this.purchase = purchase }
    }

    private fun state() = EntitlementState(
        entitlements = Entitlements(Storage()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    @Test fun removingAKeyReportsItToTheUser() = runBlocking {
        val state = state()
        assertNull("nothing should have been said yet", state.message.value)

        state.removeKey()

        val said = withTimeout(5_000) { state.message.first { it != null } }
        assertEquals("Key removed. It still works and can be entered again.", said)
    }

    /**
     * The negative control for the assertion above: if the message flow could never carry
     * anything, the test would pass for the wrong reason. `clearMessage` proves it moves in
     * both directions.
     */
    @Test fun theMessageCanBeClearedAgain() = runBlocking {
        val state = state()
        state.removeKey()
        withTimeout(5_000) { state.message.first { it != null } }

        state.clearMessage()

        assertNull(state.message.value)
    }
}
