package io.github.pintofi.beanbook.app.onboarding

import io.github.pintofi.beanbook.core.data.Json
import io.github.pintofi.beanbook.core.domain.AccountPlan
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.CommodityName
import io.github.pintofi.beanbook.core.domain.LedgerBlueprint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.MonthDay
import java.util.Currency
import java.util.Locale

/** Where in the first-run flow somebody is. */
enum class SetupStep { WELCOME, WAYS_IN, BASICS, ACCOUNTS, GET_SET, FINISH }

/** One line of the account checklist. */
data class AccountChoice(
    val name: String,
    val on: Boolean,
    /** What this account accepts, as typed (comma or space separated); blank means any currency. */
    val currencies: String = "",
    /** Added by the person rather than offered by the app. */
    val user: Boolean = false,
    /** Kept whatever else is chosen. */
    val locked: Boolean = false,
)

/**
 * Everything the first-run setup has asked so far, as the person typed it.
 *
 * Held as text where a person types text (an amount, a currency list) and turned into a
 * [LedgerBlueprint] only when the ledger is created, so half-typed input is never an error and a
 * rotation or a killed process loses nothing. See [toBlueprint] for how it becomes files.
 */
data class SetupDraft(
    val step: SetupStep = SetupStep.WELCOME,
    val name: String = "My ledger",
    val mainCurrency: String = "USD",
    val otherCurrencies: List<String> = emptyList(),
    /** ISO date the books begin; the first of the month by default. */
    val start: String = LocalDate.now().withDayOfMonth(1).toString(),
    /** Decimal places the person set, by currency; a currency not here uses its own usual number. */
    val places: Map<String, Int> = emptyMap(),
    val booking: String = "STRICT",
    /** Other currencies that reports should also be totalled in. */
    val reportIn: Set<String> = emptySet(),
    /** `MM-dd`, or null for Fava's own 12-31. */
    val fiscalYearEnd: String? = null,
    /** Fava page the reports open on, or null for its own default. */
    val reportsOpenOn: String? = null,
    val checks: Set<LedgerBlueprint.Check> = emptySet(),
    val everyAccountUsesMain: Boolean = true,
    val accounts: List<AccountChoice> = starterChoices(),
    /** What each chosen account held on the first day, as typed. A liability is typed as what is owed. */
    val opening: Map<String, String> = emptyMap(),
    /** A folder to keep a copy of the ledger in (a content URI), chosen on the last step. */
    val mirrorFolder: String? = null,
) {

    /** The currencies the ledger names, main first. */
    val currencies: List<String> get() = (listOf(mainCurrency) + otherCurrencies).distinct()

    /** What is wrong with the answers so far, step by step, in words. Empty when they can be used. */
    fun problems(): List<String> = runCatching { toBlueprint(LocalDate.now(), emptyMap()) }
        .map { it.problems() }
        .getOrElse { listOf(it.message ?: "Something typed could not be read.") }

    /**
     * The blueprint these answers describe.
     *
     * @param names display names for currencies, passed in so the result is deterministic.
     */
    fun toBlueprint(today: LocalDate, names: Map<String, String>): LedgerBlueprint {
        val start = runCatching { LocalDate.parse(start) }.getOrDefault(today.withDayOfMonth(1))
        val main = mainCurrency.trim().uppercase(Locale.ROOT)
        val others = otherCurrencies.map { it.trim().uppercase(Locale.ROOT) }.filter { it != main }.distinct()
        val plans = accounts.filter { it.on || it.locked }.map { choice ->
            val accepted = if (everyAccountUsesMain) listOf(main) else parseCurrencies(choice.currencies)
            AccountPlan(
                name = choice.name.trim(),
                currencies = if (choice.name == LedgerBlueprint.OPENING) emptyList() else accepted,
                locked = choice.locked,
                origin = if (choice.user) AccountPlan.Origin.USER else AccountPlan.Origin.STARTER,
            )
        }
        val balances = buildMap {
            plans.forEach { plan ->
                val typed = opening[plan.name]?.trim().orEmpty()
                val number = typed.takeIf { it.isNotEmpty() }?.replace(",", "")?.toBigDecimalOrNull() ?: return@forEach
                val currency = plan.currencies.firstOrNull() ?: main
                // A liability is typed as what is owed and written as a negative balance.
                val signed = if (plan.root == "Liabilities") number.negate() else number
                put(plan.name, Amount(signed, currency))
            }
        }
        val everything = (listOf(main) + others + plans.flatMap { it.currencies }).distinct()
        return LedgerBlueprint(
            title = name,
            start = start,
            mainCurrency = main,
            otherCurrencies = others,
            reportCurrencies = reportIn.filter { it in others },
            decimals = everything.associateWith { places[it] ?: usualPlaces(it) },
            booking = booking,
            fiscalYearEnd = fiscalYearEnd?.let { runCatching { MonthDay.parse("--$it") }.getOrNull() },
            defaultPage = reportsOpenOn,
            checks = checks,
            accounts = plans,
            openingBalances = balances,
            commodityNames = names,
        )
    }

    /** Written as JSON, so a setup that was left half done survives the process being killed. */
    fun toJson(): Json.Obj = Json.obj(
        "schema" to Json.of(SCHEMA),
        "step" to Json.of(step.name),
        "name" to Json.of(name),
        "main" to Json.of(mainCurrency),
        "others" to Json.arr(otherCurrencies.map { Json.of(it) }),
        "start" to Json.of(start),
        "places" to Json.Obj(places.mapValues { Json.of(it.value) }),
        "booking" to Json.of(booking),
        "reportIn" to Json.arr(reportIn.sorted().map { Json.of(it) }),
        "fiscal" to (fiscalYearEnd?.let { Json.of(it) } ?: Json.Null),
        "page" to (reportsOpenOn?.let { Json.of(it) } ?: Json.Null),
        "checks" to Json.arr(checks.map { Json.of(it.name) }),
        "followMain" to Json.of(everyAccountUsesMain),
        "accounts" to Json.arr(
            accounts.map {
                Json.obj(
                    "name" to Json.of(it.name), "on" to Json.of(it.on), "cur" to Json.of(it.currencies),
                    "user" to Json.of(it.user), "locked" to Json.of(it.locked),
                )
            },
        ),
        "opening" to Json.Obj(opening.mapValues { Json.of(it.value) }),
        "mirror" to (mirrorFolder?.let { Json.of(it) } ?: Json.Null),
    )

    companion object {
        private const val SCHEMA = 1

        /** The starter list as choices: the ones that are on are on. */
        fun starterChoices(): List<AccountChoice> = LedgerBlueprint.STARTER_ACCOUNTS.map { (plan, on) ->
            AccountChoice(plan.name, on, locked = plan.locked)
        }

        /** What a device in [locale] would call its own money, or USD when it has none. */
        fun deviceCurrency(locale: Locale = Locale.getDefault()): String =
            runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()
                ?.takeIf(CommodityName::isValid) ?: "USD"

        /** The places a currency is usually written to (JPY 0, KWD 3), or 2 for anything else. */
        fun usualPlaces(code: String): Int =
            runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()
                ?.takeIf { it in 0..8 } ?: 2

        fun parseCurrencies(text: String): List<String> =
            text.split(',', ' ', ';').map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()

        /** Back from JSON, or null for anything this version does not understand. */
        fun fromJson(json: Json?): SetupDraft? {
            val obj = json as? Json.Obj ?: return null
            if (obj.number("schema")?.toInt() != SCHEMA) return null
            fun strings(name: String) = obj.array(name)?.mapNotNull { (it as? Json.Str)?.value }.orEmpty()
            val accounts = obj.array("accounts")?.mapNotNull { item ->
                val a = item as? Json.Obj ?: return@mapNotNull null
                AccountChoice(
                    name = a.string("name") ?: return@mapNotNull null,
                    on = (a["on"] as? Json.Bool)?.value ?: false,
                    currencies = a.string("cur").orEmpty(),
                    user = (a["user"] as? Json.Bool)?.value ?: false,
                    locked = (a["locked"] as? Json.Bool)?.value ?: false,
                )
            }.orEmpty().ifEmpty { starterChoices() }
            return SetupDraft(
                step = runCatching { SetupStep.valueOf(obj.string("step").orEmpty()) }.getOrDefault(SetupStep.WELCOME),
                name = obj.string("name") ?: "My ledger",
                mainCurrency = obj.string("main") ?: deviceCurrency(),
                otherCurrencies = strings("others"),
                start = obj.string("start") ?: LocalDate.now().withDayOfMonth(1).toString(),
                places = obj.obj("places")?.fields.orEmpty()
                    .mapNotNull { (k, v) -> (v as? Json.Num)?.let { k to it.value.toInt() } }.toMap(),
                booking = obj.string("booking") ?: "STRICT",
                reportIn = strings("reportIn").toSet(),
                fiscalYearEnd = obj.string("fiscal"),
                reportsOpenOn = obj.string("page"),
                checks = strings("checks").mapNotNull {
                    runCatching { LedgerBlueprint.Check.valueOf(it) }.getOrNull()
                }.toSet(),
                everyAccountUsesMain = (obj["followMain"] as? Json.Bool)?.value ?: true,
                accounts = accounts,
                opening = obj.obj("opening")?.fields.orEmpty()
                    .mapNotNull { (k, v) -> (v as? Json.Str)?.let { k to it.value } }.toMap(),
                mirrorFolder = obj.string("mirror"),
            )
        }
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
