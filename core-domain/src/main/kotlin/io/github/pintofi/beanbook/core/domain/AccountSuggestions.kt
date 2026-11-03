package io.github.pintofi.beanbook.core.domain

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * Which accounts the entry form offers, and in what order, as Fava's add-entry form does it.
 *
 * Fava (1.30, `fava/core/attributes.py`, `fava/util/ranking.py` and the account input of its
 * frontend) works in four steps, and this follows each:
 *
 * 1. **Rank by use.** Every posting to an account counts as one "like" on the day of its
 *    transaction, and a like loses half its weight in a year. The accounts come back best first;
 *    accounts never posted to follow in alphabetical order.
 * 2. **Rank by payee.** Once a payee is typed, the accounts used with that payee are ranked the
 *    same way, over that payee's transactions only, and the others keep their overall order.
 * 3. **Leave out what is closed by then.** An account closed before the entry's date is not
 *    offered. On the closing day itself it still is. A date that is not a date filters nothing.
 * 4. **Match what is typed.** A substring match scores highest (case-insensitive when the typed text
 *    is all lower case, exact otherwise); failing that, the typed letters must appear in order, with
 *    longer runs scoring more. Anything that does not match is dropped.
 *
 * The ranking weights are floating point because they are a score that is only ever compared,
 * never an amount of money; this file is on the list in `NoFloatingPointTest` for that reason.
 */
object AccountSuggestions {

    /** A like loses half its weight in this many days, as in Fava. */
    private const val HALF_LIFE_DAYS = 365.0

    private val RATE = ln(2.0) / HALF_LIFE_DAYS

    /**
     * [accounts] ranked by how often and how recently the [journal] posts to them.
     *
     * With a [payee], only that payee's transactions count, and accounts they never touch stay in
     * the order [accounts] had (so pass the overall ranking as [accounts] to get Fava's two-step
     * order). The payee has to match exactly, as in Fava.
     */
    fun rank(accounts: List<String>, journal: List<JournalEntry>, payee: String? = null): List<String> {
        val scores = HashMap<String, Double>()
        for (entry in journal) {
            if (entry.postings.isEmpty()) continue
            if (payee != null && entry.payee != payee) continue
            val day = runCatching { LocalDate.parse(entry.date).toEpochDay() }.getOrNull() ?: continue
            val like = day * RATE
            for (posting in entry.postings) {
                val score = scores[posting.account] ?: 0.0
                val high = max(score, like)
                val low = min(score, like)
                scores[posting.account] = high + ln1p(exp(low - high))
            }
        }
        // A stable sort, best first: equal scores keep the order they came in.
        return accounts.sortedByDescending { scores[it] ?: 0.0 }
    }

    /** [accounts] ranked for a new entry: overall use, then the payee's use first when there is one. */
    fun forEntry(
        accounts: List<String>,
        journal: List<JournalEntry>,
        payee: String?,
    ): List<String> {
        val overall = rank(accounts.sorted(), journal)
        val named = payee?.trim().orEmpty()
        return if (named.isEmpty()) overall else rank(overall, journal, named)
    }

    /**
     * Whether an account closed on [closed] can still take an entry dated [date]: it can up to and
     * including the closing day. An account that is not closed, or an entry with no readable date,
     * always can, which is Fava's rule for what to suggest.
     */
    fun offeredOn(closed: String?, date: String): Boolean {
        if (closed == null) return true
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return true
        val closedDay = runCatching { LocalDate.parse(closed) }.getOrNull() ?: return true
        return !closedDay.isBefore(day)
    }

    /**
     * The suggestions that match [pattern], best match first, as Fava's account input orders them.
     * An empty pattern keeps everything in the order given.
     */
    fun matching(pattern: String, suggestions: List<String>): List<String> {
        if (pattern.isEmpty()) return suggestions
        return suggestions.map { it to score(pattern, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Fava's `fuzzytest`: 0 for no match, higher for a better one. */
    internal fun score(pattern: String, text: String): Int {
        val caseInsensitive = pattern == pattern.lowercase()
        val exact = if (caseInsensitive) text.lowercase().indexOf(pattern) else text.indexOf(pattern)
        if (exact > -1) return pattern.length * pattern.length
        var score = 0
        var run = 0
        var index = 0
        for (char in text) {
            val wanted = pattern.getOrNull(index)
            if (wanted != null && (char == wanted || char.lowercaseChar() == wanted)) {
                index += 1
                run += 1
            } else {
                run = 0
            }
            score += run
        }
        return if (index == pattern.length) score else 0
    }
}
