package io.github.pintofi.beanbook.app.ui

import android.content.Context

/**
 * Which basis Balances was last showing (B53).
 *
 * A one-line file beside the other display preferences, in app-private storage: this belongs
 * to the device rather than to a ledger, so it must not travel in an export, a backup or a
 * mirror. An unreadable or unknown value falls back to units, which is the basis that needs
 * no prices and is therefore always right to show.
 */
class ValuationPreference(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/balances-basis")

    fun read(): Valuation = runCatching {
        Valuation.valueOf(file.readText().trim())
    }.getOrDefault(Valuation.UNITS)

    fun write(basis: Valuation) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(basis.name)
        }
    }
}
