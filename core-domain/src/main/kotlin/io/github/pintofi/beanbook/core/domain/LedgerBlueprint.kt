package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.MonthDay

/**
 * Everything a new ledger is made from, and the text of its files.
 *
 * Pure: the same blueprint renders the same files, so a golden test can hold the output and real
 * Beancount can be asked to read it. What is written is plain Beancount with comments that say what
 * each part is for, because the file is the user's and they will open it.
 *
 * Opening-day directives (`open`, `pad`, the Fava options) are dated the day *before* [start], so
 * an opening balance is true as the books begin.
 */
data class LedgerBlueprint(
    val title: String,
    val start: LocalDate,
    val mainCurrency: String,
    /** Currencies the ledger also uses, declared as commodities. */
    val otherCurrencies: List<String> = emptyList(),
    /** Extra `operating_currency` lines, so reports can also total in these. */
    val reportCurrencies: List<String> = emptyList(),
    /** Decimal places shown per commodity. A commodity not here is left to Beancount. */
    val decimals: Map<String, Int> = emptyMap(),
    val booking: String = "STRICT",
    val fiscalYearEnd: MonthDay? = null,
    /** The report Fava opens on (for example `balance_sheet/`), or null for Fava's own default. */
    val defaultPage: String? = null,
    val checks: Set<Check> = DEFAULT_CHECKS,
    /**
     * Currencies whose decimal places the person chose themselves. Each gets a tolerance of its own;
     * every other currency uses the ledger-wide 0.005.
     */
    val explicitDecimals: Set<String> = emptySet(),
    val accounts: List<AccountPlan> = STARTER_ACCOUNTS.filter { it.second }.map { it.first },
    /** Signed, in the account's currency: what each account held when the books begin. */
    val openingBalances: Map<String, Amount> = emptyMap(),
    /** Display names for commodities (`EUR` to `Euro`), passed in so the output is deterministic. */
    val commodityNames: Map<String, String> = emptyMap(),
) {

    /** Optional checks a person can switch on; each is a Beancount plugin. */
    enum class Check(val module: String, val label: String, val comment: String) {
        CHECK_COMMODITY(
            "beancount.plugins.check_commodity", "Every currency must be declared",
            "Every currency needs a commodity line in files/commodities.beancount:",
        ),
        LEAFONLY(
            "beancount.plugins.leafonly", "Post only to the last level",
            "Post only to the last level of an account, Expenses:Food:Groceries and not Expenses:Food:",
        ),
        UNIQUE_PRICES(
            "beancount.plugins.unique_prices", "No two prices for one day",
            "Two different prices for one commodity on the same day are an error:",
        ),
        SELLGAINS(
            "beancount.plugins.sellgains", "Check gains on sales",
            "A sale at a price must post its gain or loss to an Income account:",
        ),
        NOUNUSED(
            "beancount.plugins.nounused", "Every account must be used",
            "Every opened account must be used. Turn on once your accounts are in use:",
        ),
    }

    private val openingDay: LocalDate get() = start.minusDays(1)

    /** What is wrong with this blueprint, in words. Empty when it can be written. */
    fun problems(): List<String> = buildList {
        if (title.isBlank()) add("The ledger needs a name.")
        if (!CommodityName.isValid(mainCurrency)) add("\"$mainCurrency\" is not a currency code Beancount accepts.")
        (otherCurrencies + reportCurrencies).filterNot(CommodityName::isValid)
            .forEach { add("\"$it\" is not a currency code Beancount accepts.") }
        accounts.groupBy { it.name }.filterValues { it.size > 1 }.keys
            .forEach { add("$it is on the list twice.") }
        accounts.forEach { plan ->
            AccountName.problems(plan.name).forEach { add("${plan.name}: ${it.describe()}") }
            plan.currencies.filterNot(CommodityName::isValid)
                .forEach { add("${plan.name}: \"$it\" is not a currency code Beancount accepts.") }
        }
        if (accounts.none { it.root == AccountNames.DEFAULT.assets }) {
            add("Choose at least one Assets account. It is where your money is.")
        }
    }

    /** Relative path to file text, for every file a new ledger starts with. */
    fun render(): Map<String, String> = mapOf(
        "main.beancount" to main(),
        "files/commodities.beancount" to commodities(),
        "files/accounts.beancount" to accountsFile(),
        "files/transactions.beancount" to transactions(),
        "prices/prices.beancount" to PRICES,
    )

    /** The accounts as written: with `Equity:Opening-Balances` accepting everything the others do. */
    fun plannedAccounts(): List<AccountPlan> {
        val others = accounts.filter { it.name != OPENING }
        val union = when {
            others.any { it.currencies.isEmpty() } -> emptyList()
            else -> others.flatMap { it.currencies }.distinct()
        }
        val equity = accounts.firstOrNull { it.name == OPENING }
            ?.copy(currencies = union)
            ?: AccountPlan(OPENING, union, locked = true, origin = AccountPlan.Origin.STARTER)
        return accounts.filter { it.name != OPENING } + equity
    }

    /** Every commodity the ledger names, main currency first. */
    fun commodityCodes(): List<String> =
        (listOf(mainCurrency) + otherCurrencies + reportCurrencies + accounts.flatMap { it.currencies })
            .distinct()

    private fun main(): String = buildString {
        val name = title.replace(Regex("""[\r\n]+"""), " ").trim()
        appendLine("; $name")
        appendLine(";")
        appendLine("; The top of your ledger: Beancount and Fava start reading here.")
        appendLine("; It is plain text and yours to edit, in Beanbook or on a computer. Beanbook")
        appendLine("; never rewrites parts of it you did not change. Lines that start with a")
        appendLine("; semicolon are comments, and Beancount ignores them.")
        appendLine()
        appendLine("option \"title\" ${BeancountText.quote(name)}")
        appendLine()
        appendLine(";; Currencies")
        appendLine("; The first operating currency is the one totals and the dashboard use.")
        appendLine("; Add another operating_currency line to see reports in that currency too.")
        (listOf(mainCurrency) + reportCurrencies).distinct().forEach {
            appendLine("option \"operating_currency\" \"$it\"")
        }
        appendLine()
        appendLine("; How a sale chooses which purchase it comes from. STRICT asks you whenever it")
        appendLine("; is not clear. Others: FIFO, LIFO, HIFO, STRICT_WITH_SIZE. One account can")
        appendLine("; differ: put the method after its currency in its open line.")
        appendLine("option \"booking_method\" \"$booking\"")
        appendLine()
        appendLine(";; Precision")
        appendLine("; How far apart the two sides of an entry may be and still balance, when the")
        appendLine("; amounts do not say (an amount written as 12, with no decimals). The star is")
        appendLine("; every currency; a currency named on its own line uses that figure instead.")
        appendLine("; https://beancount.github.io/docs/precision_tolerances.html")
        appendLine("option \"inferred_tolerance_default\" \"*:$DEFAULT_TOLERANCE\"")
        decimals.toSortedMap().filterKeys { it in explicitDecimals }.forEach { (code, places) ->
            appendLine("option \"inferred_tolerance_default\" \"$code:${tolerance(places)}\"")
        }
        appendLine()
        if (decimals.isNotEmpty()) {
            appendLine("; How many decimal places figures are shown with.")
            decimals.toSortedMap().forEach { (code, places) ->
                appendLine("option \"display_precision\" \"$code:${step(places)}\"")
            }
            appendLine()
        }
        appendLine(";; Documents")
        appendLine("; Receipts and statements are kept in this folder, one folder per account.")
        appendLine("option \"documents\" \"documents\"")
        appendLine()
        appendLine(";; Plugins")
        appendLine("; Plugins check or add to entries as the ledger loads. Their order matters.")
        appendLine()
        appendLine("; Opens the receipt named in an entry's document: line. Part of Fava.")
        appendLine("plugin \"fava.plugins.link_documents\"")
        appendLine()
        appendLine("; More checks. Remove the semicolon at the start of a line to turn one on.")
        Check.entries.forEach { check ->
            appendLine("; ${check.comment}")
            appendLine("${if (check in checks) "" else "; "}plugin \"${check.module}\"")
        }
        appendLine()
        appendLine(";; Fava")
        appendLine("; Settings for the reports. Fava reads them from these dated lines; the date")
        appendLine("; means nothing else.")
        appendLine("$openingDay custom \"fava-option\" \"default-file\" \"files/transactions.beancount\"")
        defaultPage?.let { appendLine("$openingDay custom \"fava-option\" \"default-page\" \"$it\"") }
        appendLine("; The day your financial year ends, as month-day. 12-31 when left out.")
        val year = fiscalYearEnd
        if (year != null) {
            val day = "%02d-%02d".format(year.monthValue, year.dayOfMonth)
            appendLine("$openingDay custom \"fava-option\" \"fiscal-year-end\" \"$day\"")
        } else {
            appendLine("; $openingDay custom \"fava-option\" \"fiscal-year-end\" \"06-30\"")
        }
        appendLine()
        appendLine(";; Files")
        appendLine("include \"files/commodities.beancount\"")
        appendLine("include \"files/accounts.beancount\"")
        appendLine("include \"prices/prices.beancount\"")
        appendLine("include \"files/transactions.beancount\"")
    }

    private fun commodities(): String = buildString {
        appendLine("; The currencies and other commodities this ledger uses, one commodity line each.")
        appendLine("; The name is what Fava shows beside the code.")
        appendLine()
        commodityCodes().forEach { code ->
            appendLine("$openingDay commodity $code")
            commodityNames[code]?.takeIf { it.isNotBlank() }?.let {
                appendLine("  name: ${BeancountText.quote(it)}")
            }
        }
    }

    private fun accountsFile(): String = buildString {
        appendLine("; The accounts in this ledger. An account has to be opened before an entry can")
        appendLine("; use it. The currency after a name is the only one that account accepts;")
        appendLine("; leave it off to accept any. Accounts you open in Beanbook are added at the end.")
        appendLine()
        val planned = plannedAccounts()
        val width = maxOf(MIN_NAME_WIDTH, (planned.maxOfOrNull { it.name.length } ?: 0) + 2)
        append(AccountPlan.openLines(planned, openingDay.toString(), width))
    }

    private fun transactions(): String = buildString {
        appendLine("; Your transactions. Add them here, or let Beanbook write them for you.")
        val balances = openingBalances.filterKeys { name -> accounts.any { it.name == name } }
        if (balances.isNotEmpty()) {
            appendLine()
            appendLine(";; Opening balances")
            appendLine("; What each account held as these books begin. The pad line fills the account")
            appendLine("; from $OPENING, and the balance line checks the result.")
            appendLine("; To correct an opening balance, change the figure on its balance line.")
            balances.forEach { (account, amount) ->
                appendLine()
                append(DirectiveDraft.Pad(openingDay.toString(), account, OPENING).render())
                append(DirectiveDraft.Balance(start.toString(), account, amount).render())
            }
        }
    }

    private fun step(places: Int): String = BigDecimal.ONE.movePointLeft(places).toPlainString()

    /** Half the last shown place: the tolerance Beancount itself would infer for that precision. */
    private fun tolerance(places: Int): String = BigDecimal("5").movePointLeft(places + 1).toPlainString()

    companion object {
        const val OPENING = "Equity:Opening-Balances"

        /**
         * The checks a new ledger starts with: all but [Check.NOUNUSED], which Beancount reports as an
         * error for every account not yet used, so a ledger made with it would open full of errors.
         */
        val DEFAULT_CHECKS: Set<Check> = Check.entries.toSet() - Check.NOUNUSED

        /** The tolerance of every currency not given one of its own. */
        const val DEFAULT_TOLERANCE = "0.005"
        private const val MIN_NAME_WIDTH = 28

        private const val PRICES = "; Prices, one per line, for example:  2026-10-01 price USD  0.92 EUR\n" +
            "; Beanbook does not fetch prices. Write them here, or point a price fetcher at this file.\n"

        /** The starter list, with the ones that are on unless somebody turns them off. */
        val STARTER_ACCOUNTS: List<Pair<AccountPlan, Boolean>> = listOf(
            starter("Assets:Cash", true),
            starter("Assets:Bank:Checking", true),
            starter("Assets:Bank:Savings", false),
            starter("Liabilities:CreditCard", false),
            starter("Income:Salary", true),
            starter("Income:Interest", false),
            starter("Expenses:Food:Groceries", true),
            starter("Expenses:Food:Restaurants", false),
            starter("Expenses:Home:Rent", true),
            starter("Expenses:Home:Utilities", false),
            starter("Expenses:Transport", true),
            starter("Expenses:Health", false),
            starter("Expenses:Shopping", false),
            starter("Expenses:Fees:Bank", false),
            AccountPlan(OPENING, locked = true, origin = AccountPlan.Origin.STARTER) to true,
        )

        private fun starter(name: String, on: Boolean) =
            AccountPlan(name, origin = AccountPlan.Origin.STARTER) to on

        /**
         * A ledger with nothing chosen: the main currency, the starter accounts that are on, and
         * books beginning on the first of [today]'s month.
         */
        fun defaults(
            title: String,
            currency: String,
            today: LocalDate,
            commodityNames: Map<String, String> = emptyMap(),
            places: Int = 2,
        ) = LedgerBlueprint(
            title = title,
            start = today.withDayOfMonth(1),
            mainCurrency = currency,
            decimals = mapOf(currency to places),
            accounts = STARTER_ACCOUNTS.filter { it.second }.map { it.first.copy(currencies = listOf(currency)) },
            commodityNames = commodityNames,
        )
    }
}
