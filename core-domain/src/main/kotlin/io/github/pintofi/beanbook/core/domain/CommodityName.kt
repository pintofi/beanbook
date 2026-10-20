package io.github.pintofi.beanbook.core.domain

/**
 * What Beancount accepts as a commodity (a currency, a fund, a coin): upper case to start, then
 * upper case, digits and `' . _ -`, ending in a letter or digit, at most 24 characters.
 *
 * [BeancountText.CURRENCY] is looser (it accepts a trailing `-`), which is fine for reading what
 * somebody typed; this is for what the app is about to write into a new ledger.
 */
object CommodityName {
    private val PATTERN = Regex("""[A-Z]([A-Z0-9'._\-]{0,22}[A-Z0-9])?""")

    fun isValid(code: String): Boolean = PATTERN.matches(code)
}
