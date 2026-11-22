package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.EntryFilter
import io.github.pintofi.beanbook.core.domain.ViewAnswer
import io.github.pintofi.beanbook.core.domain.ViewRequest

/**
 * The one filter that Home, Journal and Balances share.
 *
 * Held by the application rather than by a screen, so it survives switching tabs and turning the
 * phone, and is gone when the app is. Not written to disk: a filter somebody forgot about would
 * make the first look at the books after a restart a look at part of them.
 */
class LedgerFilterState {
    var filter by mutableStateOf(EntryFilter())
        private set

    fun setQuery(query: String) {
        filter = filter.copy(query = query)
    }

    fun setAccount(account: String) {
        filter = filter.copy(account = account)
    }

    /** Sets what Fava is asked, all at once: the sheet applies its fields together when it closes. */
    fun setView(view: ViewRequest) {
        filter = filter.copy(view = view)
    }

    fun clear() {
        filter = EntryFilter()
    }

    /**
     * Fava's answer to the current view request, or null before there is one. It is only ever read together with
     * the request and the ledger it was made for (see `filteredBy`).
     */
    var answer by mutableStateOf<ViewAnswer?>(null)
        private set

    /** True while the engine is working out the current request. */
    var applying by mutableStateOf(false)
        private set

    /** Why there is no answer when the engine itself could not be asked, not when Fava refused an input. */
    var unavailable by mutableStateOf<String?>(null)
        private set

    /** Asks the engine for Fava's answer; set by the application, null where there is no engine. */
    var matcher: (suspend (ViewRequest) -> Result<ViewAnswer>?)? = null

    /**
     * Works out the current request. Called again whenever the ledger or the request changes, and cancelled by
     * the caller when either does, so a late answer for an old one is never kept.
     */
    suspend fun resolve() {
        val request = filter.view
        unavailable = null
        if (!request.isActive) {
            answer = null
            applying = false
            return
        }
        applying = true
        try {
            val result = matcher?.invoke(request)
            answer = result?.getOrNull()
            unavailable = when {
                result == null -> "This ledger has no engine to apply a filter with."
                result.isFailure -> "The filter could not be applied: ${result.exceptionOrNull()?.message}"
                else -> null
            }
        } finally {
            applying = false
        }
    }
}

/**
 * The filter the app provides. Null where nothing provides one (a screen on its own, as in a UI
 * test), and [ledgerFilter] then gives that screen a filter of its own. A default shared by
 * everything that reads it would carry one screen's search into the next one's.
 */
val LocalLedgerFilter = staticCompositionLocalOf<LedgerFilterState?> { null }

/** The app's shared filter, or a private one for a screen shown without it. */
@Composable
fun ledgerFilter(): LedgerFilterState =
    LocalLedgerFilter.current ?: androidx.compose.runtime.remember { LedgerFilterState() }

/**
 * What the three screens are filtered by, one chip each with a cross, and a way to drop them all.
 *
 * Shown at the top of every screen that obeys the filter, so a narrowed Home or Balances never
 * looks like the whole ledger. Draws nothing when there is no filter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerFilterBar(modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = Measure.gutter) {
    val state = ledgerFilter()
    val filter = state.filter
    if (!filter.isActive) return
    // One block, so a parent that spaces its children out does not pull the chips and the
    // sentence beneath them apart.
    androidx.compose.foundation.layout.Column(modifier = modifier.padding(horizontal = inset)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (filter.query.isNotBlank()) {
                val words = filter.query.trim()
                FilterChipWithCross("Search: $words", "Remove the search $words") { state.setQuery("") }
            }
            val view = filter.view
            val spoken = { text: String -> text.trim().replace(Regex("-(?=[#^])"), "without ") }
            if (filter.account.isNotBlank()) {
                val account = filter.account.trim()
                FilterChipWithCross("Account: $account", "Remove the account $account") { state.setAccount("") }
            }
            if (view.time.isNotBlank()) {
                val time = view.time.trim()
                FilterChipWithCross("Time: $time", "Remove the period $time") { state.setView(view.copy(time = "")) }
            }
            if (view.text.isNotBlank()) {
                val text = view.text.trim()
                FilterChipWithCross("Text: $text", "Remove the text $text") { state.setView(view.copy(text = "")) }
            }
            if (view.tags.isNotBlank()) {
                FilterChipWithCross("Tags: ${view.tags.trim()}", "Remove the tags and links ${spoken(view.tags)}") {
                    state.setView(view.copy(tags = ""))
                }
            }
            if (view.amount.isNotBlank()) {
                FilterChipWithCross("Amount: ${view.amount.trim()}", "Remove the amount ${spoken(view.amount)}") {
                    state.setView(view.copy(amount = ""))
                }
            }
            OutlinedButton(
                onClick = state::clear,
                modifier = Modifier.semantics { contentDescription = "Remove every filter" },
            ) { Text("Clear all") }
        }
        // Why nothing is shown, in Fava's own words when it refused the filter. Never left to look like
        // an empty ledger.
        val refusal = (state.answer as? ViewAnswer.Refused)?.takeIf { it.request == filter.view }
        val status = refusal?.let { "${fieldName(it.field)}: ${it.message}" } ?: state.unavailable
        if (filter.view.isActive && status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = Measure.snug),
            )
        } else if (filter.view.isActive && state.applying) {
            Text(
                "Applying the filter…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Measure.snug),
            )
        }
        Text(
            "Home, Journal and Balances show only the matching entries. Balances are at cost while this is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Measure.snug),
        )
    }
}

@Composable
private fun FilterChipWithCross(text: String, describe: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text, maxLines = 1) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
        modifier = Modifier.semantics { contentDescription = describe },
    )
}

/** What a person calls the box an input was typed in. */
internal fun fieldName(field: String): String = when (field) {
    "time" -> "Time"
    "text" -> "Text"
    "amount" -> "Amount"
    else -> "Tags and links"
}
