package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.LedgerSummary
import java.io.File
import java.math.BigDecimal

/**
 * Everything the home-screen widgets need, and nothing else.
 *
 * This file is the whole reason a widget can exist at all. Rendering one must not start the
 * Python interpreter or the Fava server — a home screen redrawing several widgets would
 * otherwise spin up an engine per redraw, on a device that has just woken up, for a number
 * that was already known (docs/ux.md §8). So the app writes this after every successful
 * parse, and the widgets read only this.
 *
 * It carries [generatedAtEpochMs] so a widget can say *when* rather than implying *now*. A
 * plausible but outdated number presented as current is the failure mode this whole design
 * is arranged against: someone glances at a balance to decide whether to buy something.
 */
data class WidgetSnapshot(
    val projectId: String,
    val projectTitle: String,
    val generatedAtEpochMs: Long,
    /** Account name to formatted amount, for the accounts a widget may show. */
    val balances: Map<String, String>,
    val netWorth: String?,
    val periodIncome: String?,
    val periodExpense: String?,
    /** True when the ledger parsed with no errors; a widget says so when it did not. */
    val clean: Boolean,
) {
    /**
     * Whether this is too old to show as current.
     *
     * Not a fixed rule about correctness — a ledger nobody has edited for a week is not
     * wrong, it is just old. The widget uses this to change how it *presents* the number,
     * never to hide it.
     */
    fun isStale(nowEpochMs: Long, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean =
        nowEpochMs - generatedAtEpochMs > maxAgeMs

    internal fun toJson(): Json.Obj = Json.Obj(
        buildMap {
            put("schema", Json.Num(1.0))
            put("project", Json.Str(projectId))
            put("title", Json.Str(projectTitle))
            put("generated_at", Json.Num(generatedAtEpochMs.toDouble()))
            put("clean", Json.Bool(clean))
            put(
                "balances",
                Json.Obj(balances.mapValues { (_, value) -> Json.Str(value) }),
            )
            netWorth?.let { put("net_worth", Json.Str(it)) }
            periodIncome?.let { put("period_income", Json.Str(it)) }
            periodExpense?.let { put("period_expense", Json.Str(it)) }
        },
    )

    /**
     * The account the balance widget shows, and its formatted figure.
     *
     * Deterministic, and never an arbitrary map entry: the widget used to take
     * `balances.entries.firstOrNull()`, which could name a different account between
     * refreshes and was rendered with no account name at all, so the number meant nothing
     *.
     *
     * Assets first, because a balance somebody puts on a home screen is nearly always a bank
     * account, then alphabetically. A ledger with no asset accounts still shows something
     * rather than an em dash.
     *
     * This is a default, not a preference. Choosing the account per widget needs a
     * configuration activity, which does not exist yet.
     */
    val primaryBalance: Pair<String, String>?
        get() = balances.entries
            .sortedWith(compareBy({ if (it.key.startsWith("Assets")) 0 else 1 }, { it.key }))
            .firstOrNull()
            ?.let { it.key to it.value }

    companion object {
        /** Six hours. Long enough that an untouched ledger is not nagging about itself. */
        const val DEFAULT_MAX_AGE_MS = 6L * 60 * 60 * 1000

        internal fun fromJson(obj: Json.Obj): WidgetSnapshot? {
            val balances = (obj["balances"] as? Json.Obj)?.fields.orEmpty()
                .mapNotNull { (name, value) -> (value as? Json.Str)?.let { name to it.value } }
                .toMap()
            return WidgetSnapshot(
                projectId = obj.string("project") ?: return null,
                projectTitle = obj.string("title") ?: return null,
                generatedAtEpochMs = obj.long("generated_at") ?: return null,
                balances = balances,
                netWorth = obj.string("net_worth"),
                periodIncome = obj.string("period_income"),
                periodExpense = obj.string("period_expense"),
                clean = (obj["clean"] as? Json.Bool)?.value ?: false,
            )
        }
    }
}

/**
 * Reads and writes the widget cache.
 *
 * Deliberately outside the project tree: a widget must be able to read it after the app has
 * been killed and without knowing which project is open, and it must never travel in a
 * backup or a mirror. It is derived data — losing it costs one refresh.
 */
class WidgetCache(stateDir: File) {

    private val file = File(stateDir, "widgets.json")

    private val dir = stateDir

    private fun parse(source: File): WidgetSnapshot? {
        if (!source.isFile) return null
        val root = runCatching { Json.parse(source.readText()) }.getOrNull() as? Json.Obj
            ?: return null
        return WidgetSnapshot.fromJson(root)
    }

    /**
     * The newest snapshot, or -- when [prefer] names accounts the newest one does not have --
     * the newest one of any project that does.
     *
     * The cache used to hold only the project open last, so opening a second project blanked
     * every widget chosen for the first: the account names matched nothing and each row
     * showed a dash until the first project was opened again. A widget is chosen by account
     * name, so it follows the project that has those accounts.
     */
    fun read(prefer: Collection<String> = emptyList()): WidgetSnapshot? {
        val latest = parse(file)
        if (prefer.isEmpty() || latest == null || prefer.any { it in latest.balances }) return latest
        return dir.listFiles { f -> f.name.startsWith("widgets-") && f.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { parse(it) }
            .firstOrNull { snapshot -> prefer.any { it in snapshot.balances } }
            ?: latest
    }

    /** Atomic, so a widget reading mid-write sees the old snapshot rather than half of one. */
    fun write(snapshot: WidgetSnapshot) {
        file.parentFile?.mkdirs()
        val text = snapshot.toJson().write() + "\n"
        // One file per project beside the newest, so another project does not erase it.
        // The characters a project id may have, so that `a_b` and `ab` do not share a file.
        val idPart = snapshot.projectId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        listOf(file, File(dir, "widgets-$idPart.json"))
            .forEach { target ->
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
    }

    fun clear() {
        file.delete()
        dir.listFiles { f -> f.name.startsWith("widgets-") }.orEmpty().forEach { it.delete() }
    }

    companion object {
        /**
         * Builds a snapshot from a parsed ledger.
         *
         * Amounts are formatted **here**, once, rather than in the widget. A widget runs in
         * the home screen's process with no access to the app's formatting, and rendering a
         * `BigDecimal` there would mean reimplementing it — which is how two places start
         * disagreeing about what a number looks like.
         */
        fun from(
            projectId: String,
            ledger: LedgerSnapshot,
            nowEpochMs: Long,
            accounts: Collection<String> = ledger.accounts.map { it.name },
            valuation: io.github.pintofi.beanbook.core.domain.Valuation =
                io.github.pintofi.beanbook.core.domain.Valuation.VALUE,
        ): WidgetSnapshot {
            // One definition of these three, shared with the home surface.
            val summary = LedgerSummary.of(ledger, valuation)
            val wanted = accounts.toSet()
            // An account with no positions has nothing to show, and a widget saying nothing
            // is worse than one the user never added.
            val balances = ledger.accounts
                .filter { it.name in wanted }
                // Through the ledger's own format, like the three headline figures above it:
                // this row used the plain default, so a file asking for thousands separators got
                // them on Home and in the headline widgets and not in the accounts widget.
                .mapNotNull { account ->
                    LedgerSummary.format(valuation.of(account), ledger.format)?.let { account.name to it }
                }
                .toMap()

            return WidgetSnapshot(
                projectId = projectId,
                projectTitle = ledger.title ?: projectId,
                generatedAtEpochMs = nowEpochMs,
                balances = balances,
                // Assets *plus* liabilities, not minus: Beancount already stores what you
                // owe as a negative balance. Negating it made debt increase net worth, which
                // is the most cheerful possible way to be wrong about someone's finances.
                // In Home's headline order: operating currencies first, then the largest.
                // Alphabetical put "80.00 AIRSF-E" above the rupees, and a widget with room for
                // one line showed that line.
                netWorth = headlineOrder(ledger, valuation) ?: summary.netWorth,
                periodIncome = summary.income,
                periodExpense = summary.expenses,
                clean = ledger.errors.isEmpty(),
            )
        }

        /** The net worth lines as Home orders them, or null when there is no non-zero one. */
        private fun headlineOrder(
            ledger: LedgerSnapshot,
            valuation: io.github.pintofi.beanbook.core.domain.Valuation,
        ): String? {
            val ordered = io.github.pintofi.beanbook.core.domain.HeadlineFigures.of(
                LedgerSummary.totals(ledger, valuation).netWorth,
                ledger.operatingCurrencies,
                max = Int.MAX_VALUE,
            ).shown
            return if (ordered.isEmpty()) null else LedgerSummary.format(ordered, ledger.format)
        }

    }
}
