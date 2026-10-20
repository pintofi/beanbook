package io.github.pintofi.beanbook.core.domain

/**
 * An account that is going to be opened: its name, the currencies it accepts, and how it books.
 *
 * One definition for what setup writes into a new ledger and what the app appends to an existing
 * one, so the two cannot produce different `open` lines for the same account.
 *
 * @property currencies what the account accepts; empty means any currency.
 * @property booking a booking method for this account alone, written after its currencies. Only
 *   valid with at least one currency, because that is where Beancount's grammar puts it.
 * @property locked the account setup keeps whatever else is chosen (opening balances come from it).
 */
data class AccountPlan(
    val name: String,
    val currencies: List<String> = emptyList(),
    val booking: String? = null,
    val locked: Boolean = false,
    val origin: Origin = Origin.USER,
) {
    enum class Origin { STARTER, USER }

    val root: String get() = name.substringBefore(':')

    companion object {
        /**
         * `open` lines for [plans], dated [date]. With [nameWidth] above 0 the currencies line up
         * in a column that wide, as a file written by hand does; 0 puts one space between.
         */
        fun openLines(plans: List<AccountPlan>, date: String, nameWidth: Int = 0): String =
            plans.joinToString("") { plan ->
                val left = "$date open ${plan.name}"
                val right = buildString {
                    append(plan.currencies.joinToString(","))
                    plan.booking?.takeIf { it.isNotBlank() && plan.currencies.isNotEmpty() }
                        ?.let { append(' ').append(BeancountText.quote(it)) }
                }
                if (right.isEmpty()) {
                    "$left\n"
                } else {
                    val pad = (nameWidth - plan.name.length).coerceAtLeast(1)
                    left + " ".repeat(pad) + right + "\n"
                }
            }
    }
}
