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
import io.github.pintofi.beanbook.core.domain.ExpressionAnswer

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

    fun setExpression(expression: String) {
        filter = filter.copy(expression = expression)
    }

    fun clear() {
        filter = EntryFilter()
    }

    /**
     * Fava's answer to the current expression, or null before there is one. It is only ever read
     * together with the expression and the ledger it was made for (see `filteredBy`).
     */
    var answer by mutableStateOf<ExpressionAnswer?>(null)
        private set

    /** True while the engine is working out the current expression. */
    var applying by mutableStateOf(false)
        private set

    /** Why there is no answer when the engine itself could not be asked, not when Fava refused. */
    var unavailable by mutableStateOf<String?>(null)
        private set

    /** Asks the engine for Fava's answer; set by the application, null where there is no engine. */
    var matcher: (suspend (String) -> Result<ExpressionAnswer>?)? = null

    /**
     * Works out the current expression. Called again whenever the ledger or the expression changes,
     * and cancelled by the caller when either does, so a late answer for an old one is never kept.
     */
    suspend fun resolve() {
        val expression = filter.expression
        unavailable = null
        if (expression.isBlank()) {
            answer = null
            applying = false
            return
        }
        applying = true
        try {
            val result = matcher?.invoke(expression)
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
            if (filter.expression.isNotBlank()) {
                val terms = filter.expression.trim()
                FilterChipWithCross(
                    "Filter: $terms",
                    "Remove the filter ${terms.replace(Regex("-(?=[#^])"), "without ")}",
                ) { state.setExpression("") }
            }
            OutlinedButton(
                onClick = state::clear,
                modifier = Modifier.semantics { contentDescription = "Remove every filter" },
            ) { Text("Clear all") }
        }
        // Why nothing is shown, in Fava's own words when it refused the filter. Never left to look like
        // an empty ledger.
        val refusal = (state.answer as? ExpressionAnswer.Rejected)?.takeIf { it.expression == filter.expression }
        val status = refusal?.message ?: state.unavailable
        if (filter.expression.isNotBlank() && status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = Measure.snug),
            )
        } else if (filter.expression.isNotBlank() && state.applying) {
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
