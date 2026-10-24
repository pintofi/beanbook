package io.github.pintofi.beanbook.core.domain

/**
 * The non-transaction directives, as the UI composes them.
 *
 * A ledger needs editors for *all* the directive types it uses, not just
 * transactions: a ledger where you can add a payment but not open the account it pays from
 * is not a usable ledger. Each type renders itself and validates itself, sharing
 * [BeancountText] so quoting and account rules cannot drift apart.
 */
sealed interface DirectiveDraft {

    val date: String

    /**
     * The exact text that will be written. Shown to the user before it is.
     *
     * @param currencyColumn where a figure is aligned, which is the ledger's own `currency-column`
     *   (Fava's option) and not a fixed 61: a file kept at column 80 gets its balance and price
     *   lines at column 80 too, in line with the transactions around them (#49).
     */
    fun render(currencyColumn: Int = ALIGN_COLUMN): String

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
        /** Metadata under the directive, as many keys as the person gives: `institution: "Bank"`. */
        val metadata: Map<String, String> = emptyMap(),
    ) : DirectiveDraft {
        override val label get() = "Open account"

        override fun render(currencyColumn: Int): String = buildString {
            append(date).append(" open ").append(account)
            if (currencies.isNotEmpty()) append(' ').append(currencies.joinToString(","))
            bookingMethod?.takeIf { it.isNotBlank() }
                ?.let { append(' ').append(BeancountText.quote(it)) }
            append('\n')
            append(renderMetadata(metadata))
        }

        override fun validate(): List<DraftProblem> = buildList {
            addAll(commonChecks(date, account))
            currencies.filterNot { BeancountText.CURRENCY.matches(it) }
                .forEach { add(DraftProblem.InvalidCurrency(it)) }
            addAll(metadataProblems(metadata))
        }
    }

    /** `2026-09-01 close Assets:Cash` */
    data class Close(override val date: String, val account: String) : DirectiveDraft {
        override val label get() = "Close account"
        override fun render(currencyColumn: Int): String = "$date close $account\n"
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

        override fun render(currencyColumn: Int): String {
            val left = "$date balance $account"
            val right = amount?.let { "${it.number.toPlainString()} ${it.currency}" } ?: ""
            return BeancountText.alignRight(left, right, currencyColumn) + "\n"
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
        override fun render(currencyColumn: Int): String = "$date pad $account $sourceAccount\n"
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
        override fun render(currencyColumn: Int): String = "$date note $account ${BeancountText.quote(text)}\n"
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

        override fun render(currencyColumn: Int): String {
            val left = "$date price $commodity"
            val right = amount?.let { "${it.number.toPlainString()} ${it.currency}" } ?: ""
            return BeancountText.alignRight(left, right, currencyColumn) + "\n"
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
        override fun render(currencyColumn: Int): String =
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
        override fun render(currencyColumn: Int): String =
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
        /** Metadata under the directive, such as the `name` a `commodity_attr` ledger requires. */
        val metadata: Map<String, String> = emptyMap(),
    ) : DirectiveDraft {
        override val label get() = "Commodity"
        override fun render(currencyColumn: Int): String =
            "$date commodity $currency\n" + renderMetadata(metadata)

        override fun validate(): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (!BeancountText.CURRENCY.matches(currency)) {
                add(DraftProblem.InvalidCurrency(currency))
            }
            addAll(metadataProblems(metadata))
        }
    }

    /** `2026-09-01 query "name" "SELECT ..."` */
    data class Query(
        override val date: String,
        val name: String,
        val query: String,
    ) : DirectiveDraft {
        override val label get() = "Query"
        override fun render(currencyColumn: Int): String =
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

        override fun render(currencyColumn: Int): String = buildString {
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

        // No leading zero: `0123` read back as a number is 123, and an account number must stay itself.
        private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?""")
        private val KEY = Regex("""[a-z][a-zA-Z0-9\-_]*""")

        /**
         * The metadata lines under a directive, one per key with a value, in the order given.
         * Numbers stay numbers (`precision: 2` is read back as a number, not text); everything
         * else is a quoted string.
         */
        private fun renderMetadata(metadata: Map<String, String>): String = buildString {
            for ((key, value) in metadata) {
                if (value.isBlank()) continue
                val shown = if (NUMBER.matches(value.trim())) value.trim() else BeancountText.quote(value.trim())
                append("  $key: $shown\n")
            }
        }

        /** A key that is not a Beancount metadata key would write a line the parser rejects. */
        private fun metadataProblems(metadata: Map<String, String>): List<DraftProblem> =
            metadata.filterValues { it.isNotBlank() }.keys
                .filterNot { KEY.matches(it) }
                .map { DraftProblem.MissingText("a valid name for \"$it\"") }

        private fun commonChecks(date: String, account: String): List<DraftProblem> = buildList {
            if (!BeancountText.DATE.matches(date)) add(DraftProblem.InvalidDate(date))
            if (!BeancountText.ACCOUNT.matches(account)) add(DraftProblem.InvalidAccount(account))
        }

        private fun String.toBigDecimalOrNull(): java.math.BigDecimal? =
            runCatching { java.math.BigDecimal(this) }.getOrNull()
    }
}
