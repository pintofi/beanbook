package io.github.pintofi.beanbook.app.ui

import android.content.Context
import io.github.pintofi.beanbook.core.domain.Valuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which basis the app shows figures on -- units, at cost, or market value (B53, B95).
 *
 * One choice for the whole app, as in Fava: Home, Balances and the widgets read the same
 * value, so switching it in one place changes every figure rather than leaving a heading that
 * disagrees with the list under it. A one-line file beside the other display preferences, in
 * app-private storage: it belongs to the device rather than to a ledger, so it must not
 * travel in an export, a backup or a mirror.
 *
 * Market value is the default, as it is for a Fava ledger that opens on its balance sheet at
 * value: it is the basis that answers "what am I worth", and it falls back to cost and then
 * to units where there is no price, so it never invents a figure.
 */
class ValuationPreference(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/balances-basis")

    /** The current basis, observable, so every screen follows a change made on another. */
    val flow: StateFlow<Valuation> get() {
        if (!loaded) {
            shared.value = runCatching { Valuation.valueOf(file.readText().trim()) }
                .getOrDefault(Valuation.VALUE)
            loaded = true
        }
        return shared
    }

    fun read(): Valuation = flow.value

    fun write(basis: Valuation) {
        flow
        shared.value = basis
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(basis.name)
        }
    }

    private companion object {
        val shared = MutableStateFlow(Valuation.VALUE)
        @Volatile var loaded = false
    }
}
