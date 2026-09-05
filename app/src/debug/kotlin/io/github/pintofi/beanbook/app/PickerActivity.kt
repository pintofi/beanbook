package io.github.pintofi.beanbook.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A bare activity that opens the tree picker and holds the result.
 *
 * The test needs an `Activity` to receive `onActivityResult`, and must not use
 * `MainActivity`, which would start the engine and load a ledger for a test about files.
 */
class PickerActivity : Activity() {
    @Volatile private var result: Instrumentation.ActivityResult? = null

    // A latch rather than synchronized/wait/notify on a bare lock object. The old form used
    // `java.lang.Object` as the monitor, which Kotlin warns against, and `kotlin.Any` is not
    // a drop-in replacement because it does not expose wait/notify at all. A one-shot latch
    // is what this actually needs: one result, awaited once, with a timeout.
    private val arrived = CountDownLatch(1)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        result = Instrumentation.ActivityResult(resultCode, data)
        arrived.countDown()
    }

    fun awaitResult(timeoutMs: Long): Intent? {
        arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result?.takeIf { it.resultCode == RESULT_OK }?.resultData
    }

    private companion object { const val REQUEST = 1 }
}
