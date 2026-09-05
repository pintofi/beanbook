package io.github.pintofi.beanbook.engine.local

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * The single place the embedded interpreter is started.
 *
 * `Python.isStarted()` followed by `Python.start()` is a check-then-act race: the engine
 * and the ledger reader both need the runtime and both run on IO, so without this they
 * can call `start()` concurrently and one loses with
 * `IllegalStateException: Python already started`. Observed on device, not in tests —
 * the tests happened to start Python once up front.
 */
internal object PythonRuntime {

    @Synchronized
    fun ensureStarted(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }
}
