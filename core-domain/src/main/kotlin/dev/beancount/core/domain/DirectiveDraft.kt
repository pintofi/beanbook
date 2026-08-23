package dev.beancount.core.domain

/**
 * The non-transaction directives, as the UI composes them.
 *
 * SPEC.md §2.1 requires editors for *all* supported directive types, not just
 * transactions: a ledger where you can add a payment but not open the account it pays from
 * is not a usable ledger. Each type renders itself and validates itself, sharing
 * [BeancountText] so quoting and account rules cannot drift apart.
 */
sealed interface DirectiveDraft {

    val date: String

    /** The exact text that will be written. Shown to the user before it is. */
    fun render(): String

    fun validate(): List<DraftProblem>

    /** What the type is called in the UI. */
    val label: String

    // --- accounts -------------------------------------------------------------------

    /**
     * `2026-09-01 open Assets:Cash USD`
     *
     * Beancount requires an account to be opened before use, so this is the directive
     * people need first and most often.
     */
    data class Open(
        override val date: String,
        val account: String,
        val currencies: List<String> = emptyList(),
        val bookingMethod: String? = null,
    ) : DirectiveDraft {
        override val label get() = "Open account"

        override fun render(): String = buildString {
            append(date).append(" open ").append(account)
            if (currencies.isNotEmpty()) append(' ').append(currencies.joinToString(","))
            bookingMethod?.takeIf { it.isNotBlank() }
                ?.let { append(' ').append(BeancountText.quote(it)) }
            append('\n')
        }

        override fun validate(): List<DraftProblem> = buildList {
            addAll(commonChecks(date, account))
            currencies.filterNot { BeancountText.CURRENCY.matches(it) }
                .forEach { add(DraftProblem.InvalidCurrency(it)) }
        }
    }

    /** `2026-09-01 close Assets:Cash` */
    data class Close(override val date: String, val account: String) : DirectiveDraft {
        override val label get() = "Close account"
        override fun render(): String = "$date close $account\n"
        override fun validate(): List<DraftProblem> = commonChecks(date, account)
    }

    // --- assertions and corrections ---------------------------------------------------

    /**
     * `2026-09-01 balance Assets:Cash   120.00 USD`
     *
     * An assertion, not an adjustment: if it disagrees with the ledger, Beancount reports
     * an error rather than moving money to make it true.
     */
    data class Balance(
        override val date: String,
        val account: String,
        val amount: Amount?,
    ) : DirectiveDraft {
        override val label get() = "Balance assertion"

        override fun render(): String {
            val left = "$date balance $account"
            val right = amount?.let { "${it.number.toPlainString()} ${it.currency}" } ?: ""
            return BeancountText.alignRight(left, right, ALIGN_COLUMN) + "\n"
        }

        override fun validate(): List<DraftProblem> = buildList {
            addAll(commonChecks(date, account))
            if (amount == null) add(DraftProblem.MissingAmount)
        }
    }

    /**
     * `2026-09-01 pad Assets:Cash Equity:Opening-Balances`
     *
     * Inserts whatever is needed to make the *next* balance assertion pass — the one
     * directive that does move money, which is why the UI must never add it silently.
     */
    data class Pad(
        override val date: String,
        val account: String,
        val sourceAccount: String,
    ) : DirectiveDraft {
        override val label get() = "Pad"
        override fun render(): String = "$date pad $account $sourceAccount\n"
        override fun validate(): List<DraftProblem> = buildList {
            addAll(commonChecks(date, account))
            if (!BeancountText.ACCOUNT.matches(sourceAccount)) {
                add(DraftProblem.InvalidAccount(sourceAccount))
            }
        }
    }

    // --- notes, prices, documents -----------------------------------------------------

    /** `2026-09-01 note Assets:Cash "text"` */
    data class Note(
        override val date: String,
        val account: String,
        val text: String,
    ) : DirectiveDraft {
        override val label get() = "Note"
        override fun render(): String = "$date note $account ${BeancountText.quote(text)}\n"
        override fun validate(): List<DraftProblem> = buildList {
            addAll(commonChecks(date, account))
            if (text.isBlank()) add(DraftProblem.MissingText("note"))
        }
    }

    /** `2026-09-01 price ACME  12.50 USD` */
    data class Price(
        override val date: String,
        val commodity: String,
        val amount: Amount?,
    ) : DirectiveDraft {
        override val label get() = "Price"

        override fun render(): String {
            val left = "$date price $commodity"
            val right = amount?.let { "${it.number.toPlainString()} ${it.currency}" } ?: ""
            return BeancountText.alignRight(left, right, ALIGN_COLUMN) + "\n"
        }

        override fun validate(): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (!BeancountText.CURRENCY.matches(commodity)) {
                add(DraftProblem.InvalidCurrency(commodity))
            }
            if (amount == null) add(DraftProblem.MissingAmount)
        }
    }

    /** `2026-09-01 document Assets:Cash "documents/receipt.pdf"` */
    data class Document(
        override val date: String,
        val account: String,
        val path: String,
    ) : DirectiveDraft {
        override val label get() = "Document"
        override fun render(): String =
            "$date document $account ${BeancountText.quote(path)}\n"

        override fun validate(): List<DraftProblem> = buildList {
            addAll(commonChecks(date, account))
            if (path.isBlank()) add(DraftProblem.MissingText("document path"))
            // A document link that escapes the project would break on export
            // (invariant 5), so it is refused at entry rather than discovered later.
            if (path.startsWith("/") || path.contains("..")) {
                add(DraftProblem.PathOutsideProject(path))
            }
        }
    }

    /** `2026-09-01 event "location" "Berlin"` */
    data class Event(
        override val date: String,
        val type: String,
        val description: String,
    ) : DirectiveDraft {
        override val label get() = "Event"
        override fun render(): String =
            "$date event ${BeancountText.quote(type)} ${BeancountText.quote(description)}\n"

        override fun validate(): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (type.isBlank()) add(DraftProblem.MissingText("event type"))
            if (description.isBlank()) add(DraftProblem.MissingText("event description"))
        }
    }

    /** `2026-09-01 commodity ACME` */
    data class Commodity(
        override val date: String,
        val currency: String,
    ) : DirectiveDraft {
        override val label get() = "Commodity"
        override fun render(): String = "$date commodity $currency\n"
        override fun validate(): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (!BeancountText.CURRENCY.matches(currency)) {
                add(DraftProblem.InvalidCurrency(currency))
            }
        }
    }

    /** `2026-09-01 query "name" "SELECT ..."` */
    data class Query(
        override val date: String,
        val name: String,
        val query: String,
    ) : DirectiveDraft {
        override val label get() = "Query"
        override fun render(): String =
            "$date query ${BeancountText.quote(name)} ${BeancountText.quote(query)}\n"

        override fun validate(): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (name.isBlank()) add(DraftProblem.MissingText("query name"))
            if (query.isBlank()) add(DraftProblem.MissingText("query"))
        }
    }

    /**
     * `2026-09-01 custom "budget" Expenses:Food "monthly" 400.00 USD`
     *
     * Beancount does not interpret custom directives, and neither do we: the values are
     * written through verbatim, quoted only where they are strings.
     */
    data class Custom(
        override val date: String,
        val type: String,
        val values: List<String>,
    ) : DirectiveDraft {
        override val label get() = "Custom"

        override fun render(): String = buildString {
            append(date).append(" custom ").append(BeancountText.quote(type))
            values.filter { it.isNotBlank() }.forEach { value ->
                // Accounts, numbers, dates and currencies are bare; anything else is a
                // string. Quoting an account would change its meaning.
                val bare = BeancountText.ACCOUNT.matches(value) ||
                    BeancountText.DATE.matches(value) ||
                    value.toBigDecimalOrNull() != null ||
                    BeancountText.CURRENCY.matches(value)
                append(' ').append(if (bare) value else BeancountText.quote(value))
            }
            append('\n')
        }

        override fun validate(): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (type.isBlank()) add(DraftProblem.MissingText("custom type"))
        }
    }

    companion object {
        const val ALIGN_COLUMN = 61

        private fun commonChecks(date: String, account: String): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (!BeancountText.ACCOUNT.matches(account)) add(DraftProblem.InvalidAccount(account))
        }

        private fun String.toBigDecimalOrNull(): java.math.BigDecimal? =
            runCatching { java.math.BigDecimal(this) }.getOrNull()
    }
}
