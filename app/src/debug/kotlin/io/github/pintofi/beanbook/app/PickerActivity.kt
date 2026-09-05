package io.github.pintofi.beanbook.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent

/**
 * A bare activity that opens the tree picker and holds the result.
 *
 * The test needs an `Activity` to receive `onActivityResult`, and must not use
 * `MainActivity`, which would start the engine and load a ledger for a test about files.
 */
class PickerActivity : Activity() {
    @Volatile private var result: Instrumentation.ActivityResult? = null
    private val lock = Object()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        synchronized(lock) {
            result = Instrumentation.ActivityResult(resultCode, data)
            lock.notifyAll()
        }
    }

    fun awaitResult(timeoutMs: Long): Intent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (result == null && System.currentTimeMillis() < deadline) {
                lock.wait(200)
            }
        }
        return result?.takeIf { it.resultCode == RESULT_OK }?.resultData
    }

    private companion object { const val REQUEST = 1 }
}
