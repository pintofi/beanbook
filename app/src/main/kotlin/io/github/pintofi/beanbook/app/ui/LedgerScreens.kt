package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.pintofi.beanbook.core.domain.LedgerSummary
import io.github.pintofi.beanbook.core.domain.Valuation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import io.github.pintofi.beanbook.app.ui.theme.AmountTextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountNode
import io.github.pintofi.beanbook.core.domain.AccountTree
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalErrors
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The native surfaces: the account balances list and the journal
 * (docs/distribution.md §4.4). Both read from a [LedgerSnapshot] that Beancount produced,
 * so the app displays accounting facts rather than computing its own.
 */

/**
 * Amounts are announced with sign and currency in words, and carry an explicit sign in
 * text — never colour alone (docs/ux.md §6).
 */
private fun Amount.accessibleDescription(
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
): String {
    val sign = when (number.signum()) {
        -1 -> "minus "
        else -> ""
    }
    // The same digits that are on the screen. Spoken from the raw value, a figure the file
    // asks to be shown to two places was read out to five, so what TalkBack said and what
    // the screen showed were different numbers.
    return "$sign${format.format(Amount(number.abs(), currency))}"
}

/**
 * One figure, in the ledger's own type.
 *
 * [signed] colours it by direction, and defaults to *off*, which is the part worth
 * explaining. Fava — whose reports this app also shows, so the two are seen together —
 * renders balances in the ordinary text colour (`td.num { color: var(--text-color) }`) and
 * reserves colour for a *difference* (`.tree-table .diff.negative`). That is the right rule
 * for double-entry rather than a stylistic one: an Income or Equity account carries a credit
 * balance and shows as negative all the time, so colouring balances by sign would paint the
 * healthiest accounts in the ledger as though something were wrong with them.
 *
 * So a *movement* is coloured — a posting is money going one way or the other, and the
 * direction is the point of it — and a *balance* is not.
 *
 * The colours stay this app's green and amber rather than Fava's green and red. That choice
 * is already argued in LedgerColours: red and green alone are the classic accessibility
 * failure, and the sign, not the colour, is what carries the meaning here.
 */
/**
 * Money, drawn one way.
 *
 * Everything that shows a figure goes through here so the fixed-width face and the trailing
 * alignment are properties of *money in this app*, not of whichever screen remembered. Home
 * used to build its own strings in the proportional default face, which is precisely the
 * column the Appearance preview points at when it says decimal points line up.
 *
 * [text] rather than an `Amount` because a figure is sometimes not one: Home shows an em dash
 * for "nothing to say" and bullets when amounts are hidden, and those should sit in the same
 * column, in the same face, as the numbers they stand in for.
 */
@Composable
internal fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    // Applied after the merge, because `merge` lets the argument win and the argument here is
    // AmountTextStyle -- so a caller passing `style = ....copy(textAlign = Start)` was
    // silently overridden back to End. A figure standing alone under its own label reads
    // better beneath it than pushed to the far edge of the card; a figure in a column does
    // not. Both are money, so both keep the face, and only the alignment differs.
    textAlign: TextAlign = TextAlign.End,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.merge(AmountTextStyle).copy(textAlign = textAlign),
        color = color,
    )
}

@Composable
internal fun AmountText(
    amount: Amount,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    /** How the open ledger asks its figures to be written (B59). */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
    /** Which size of the figure face: a row in a list, or a figure heading a group. */
    style: TextStyle = LedgerType.row,
) {
    // The helper below existed from M6 and was never applied, so every amount was announced
    // as its raw text: TalkBack reads "-12.34 USD" by pronouncing the hyphen or dropping it,
    // which is the one thing ux.md 6 says must not happen. detekt found it as dead code
    // (docs/milestones/m14-part16.md).
    val spoken = amount.accessibleDescription(format)
    MoneyText(
        text = format.format(amount),
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = when {
            !signed -> MaterialTheme.colorScheme.onSurface
            amount.number.signum() < 0 -> BeancountTheme.ledger.negative
            else -> BeancountTheme.ledger.positive
        },
    )
}

/**
 * What a posting was held or exchanged at, under its amount (B63).
 *
 * Beside the figure rather than in place of it: `-151.88 ASSF` and `{400.00 PKR} @ 483.0500
 * PKR` answer different questions, and a row that shows only the first cannot tell two lots
 * of the same fund apart or say what a sale realised. On its own line because a cost, a date,
 * a label and a price do not fit beside an account name on a phone.
 *
 * The date and label are part of the cost's identity -- they are what `{"avcoassf"}` selects
 * -- so they are shown as the file writes them rather than summarised away.
 */
@Composable
internal fun PostingLotText(
    posting: io.github.pintofi.beanbook.core.domain.JournalPosting,
    modifier: Modifier = Modifier,
    /** How the open ledger asks its figures to be written (B59). */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
) {
    val text = buildString {
        posting.cost?.let { append(format.format(it)) }
        posting.price?.let {
            if (isNotEmpty()) append(' ')
            append("@ ").append(format.format(it))
        }
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = buildString {
                posting.cost?.let {
                    append("held at ").append(it.amount.accessibleDescription(format))
                    it.date?.let { date -> append(", acquired ").append(date) }
                    it.label?.let { label -> append(", lot ").append(label) }
                }
                posting.price?.let {
                    if (isNotEmpty()) append("; ")
                    append("at ").append(it.accessibleDescription(format)).append(" each")
                }
            }
        },
    )
}

@Composable
fun JournalScreen(
    snapshot: LedgerSnapshot,
    modifier: Modifier = Modifier,
    onOpenEntry: (JournalEntry) -> Unit = {},
    /** Deletes the entries picked by holding one and tapping others (B89). */
    onDeleteEntries: (List<JournalEntry>) -> Unit = {},
) {
    // Picked by holding a row; then a tap adds or removes rather than opening (B89). Keyed by
    // file and line, which is what identifies an entry until the ledger is read again.
    var picked by remember(snapshot) { mutableStateOf(emptySet<String>()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    fun keyOf(entry: JournalEntry) = "${entry.file}:${entry.line}"
    val selecting = picked.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { picked = emptySet() }
    // How many rows are composed, not how many the engine returned. The engine now sends the
    // whole journal, and drawing 25 000 rows at once is neither necessary nor free -- but the
    // entries are already in memory, so growing this window costs a recomposition rather than
    // a re-parse. That is why it grows on scroll instead of behind a button: there is nothing
    // to wait for, so asking the user to ask would be ceremony.
    var shown by rememberSaveable(snapshot.journal.size) { mutableStateOf(JOURNAL_PAGE) }
    // Kept while the ledger reloads, because a save or a pull-to-refresh should not throw
    // away what the reader was looking for.
    var query by rememberSaveable { mutableStateOf("") }
    // Which entries the ledger's errors are about (B64). They stay in date order with every
    // other entry (B77): pinning them to the top moved the journal's own ordering every time a
    // file had a problem, so the list no longer read as the book. They are marked where they
    // sit, the message is on the entry's own page, and a filter shows only them.
    val faults = remember(snapshot.journal, snapshot.errors) {
        JournalErrors.attach(snapshot.journal, snapshot.errors)
    }
    var onlyErrors by rememberSaveable { mutableStateOf(false) }
    // Pending is `!` -- seen, not settled -- and Flagged is any other mark but the plain `*`.
    // Together they are one choice of "which marks", so they add up; errors narrow further.
    var onlyPending by rememberSaveable { mutableStateOf(false) }
    var onlyFlagged by rememberSaveable { mutableStateOf(false) }
    // The chips are always on screen; a switch that is on with nothing to match simply shows
    // an empty list and says why, rather than hiding.
    val found = remember(snapshot.journal, faults, query, onlyErrors, onlyPending, onlyFlagged) {
        snapshot.journal
            .let { all -> if (onlyErrors) all.filter { JournalErrors.keyOf(it) in faults } else all }
            .let { all ->
                if (!onlyPending && !onlyFlagged) {
                    all
                } else {
                    all.filter { it.hasMark(onlyPending, onlyFlagged) }
                }
            }
            .let { rows -> if (query.isBlank()) rows else rows.filter { it.matches(query) } }
    }
    val visible = remember(found, shown) { found.take(shown) }
    // Two entries can share a position (a plugin that generates entries points them at the
    // line that caused them), and a lazy list throws on a repeated key, which crashed the
    // search. The first keeps its plain key; later ones get a suffix. Identity-keyed so equal
    // entries are still told apart.
    val rowKeys = remember(visible) {
        val seen = HashMap<String, Int>()
        val keys = java.util.IdentityHashMap<JournalEntry, String>()
        for (e in visible) {
            val base = "${e.file}:${e.line}"
            val n = seen.merge(base, 1, Int::plus)!!
            keys[e] = if (n == 1) base else "$base#$n"
        }
        keys
    }
    // What no entry owns. The rest is shown on the entries themselves, and repeating it here
    // would say the same thing twice at the top of the screen.
    val loose = remember(snapshot.journal, snapshot.errors) {
        JournalErrors.unattached(snapshot.journal, snapshot.errors)
    }

    Column(modifier = modifier.fillMaxSize()) {
    if (selecting) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter, vertical = Measure.snug),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { picked = emptySet() },
                modifier = Modifier.semantics { contentDescription = "Cancel selection" },
            ) { Icon(Icons.Filled.Close, contentDescription = null) }
            Text(
                "${picked.size} selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            )
            androidx.compose.material3.TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.semantics { contentDescription = "Delete the selected entries" },
            ) { Text("Delete") }
        }
    } else {
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search entries") },
        singleLine = true,
        leadingIcon = {
            Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.semantics { contentDescription = "Clear the search" },
                ) { Icon(Icons.Filled.Close, contentDescription = null) }
            }
        },
        shape = BeanbookShape.control,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Measure.gutter, vertical = Measure.snug)
            .semantics { contentDescription = "Search entries by date, payee, description or account" },
    )
    }

    if (confirmingDelete) {
        val chosen = snapshot.journal.filter { keyOf(it) in picked }
        val count = chosen.size
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(if (count == 1) "Delete 1 entry?" else "Delete $count entries?") },
            text = {
                // Which ones, by date, words and first figure (device audit 2026-10-10): "Delete 1
                // entry?" alone asked a person to remember what they had selected.
                Column(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
                    chosen.take(DELETE_NAMED).forEach { entry ->
                        Text(
                            entrySummary(entry, snapshot.format),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (count > DELETE_NAMED) {
                        Text(
                            "and ${count - DELETE_NAMED} more",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (count == 1) {
                            "It is removed from your ledger files."
                        } else {
                            "They are removed from your ledger files."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        confirmingDelete = false
                        picked = emptySet()
                        onDeleteEntries(chosen)
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = if (count == 1) "Delete this entry" else "Delete these $count entries"
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().readableMeasure(),
        contentPadding = PaddingValues(bottom = Measure.actionClearance),
    ) {
        if (loose.isNotEmpty()) {
            item { ErrorBanner(loose) }
        }
        // Always shown (B95). The filters used to appear only when an error landed on an entry,
        // which made them look like a feature that had gone; they are part of the Journal.
        item(key = "filters") {
            val count = faults.values.sumOf { it.size }
            Column(modifier = Modifier.padding(horizontal = Measure.gutter, vertical = Measure.tight)) {
                JournalFilterChips(
                    onlyErrors = onlyErrors,
                    onErrors = { onlyErrors = it },
                    onlyPending = onlyPending,
                    onPending = { onlyPending = it },
                    onlyFlagged = onlyFlagged,
                    onFlagged = { onlyFlagged = it },
                )
                val note = when {
                    snapshot.errors.isEmpty() -> null
                    count == 0 -> "No error is on a single entry; they are listed above."
                    count == 1 -> "1 error is on an entry."
                    else -> "$count errors are on entries."
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Measure.tight),
                    )
                }
            }
        }
        val filtering = onlyErrors || onlyPending || onlyFlagged
        if (found.isEmpty() && filtering && query.isBlank()) {
            item {
                EmptyState(
                    title = "No entries match these filters",
                    detail = "Turn a filter off to see more.",
                    art = EmptyArt.SEARCH,
                    modifier = Modifier.padding(horizontal = Measure.gutter),
                )
            }
        }
        if (found.isEmpty() && query.isNotBlank()) {
            item {
                EmptyState(
                    title = "Nothing matches \"$query\"",
                    detail = "Every word has to appear somewhere in the entry. Try fewer of them.",
                    art = EmptyArt.SEARCH,
                    modifier = Modifier.padding(horizontal = Measure.gutter),
                )
            }
        }
        if (snapshot.journal.isEmpty()) {
            item {
                // Centred in the viewport rather than pinned to the top: an empty state
                // anchored to the top reads as content that failed to load, and this one is
                // the screen's whole message. It says what to do next, not only that there
                // is nothing (D9).
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(horizontal = Measure.gutter),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        title = "No transactions yet",
                        detail = "Tap + to write your first one.",
                        art = EmptyArt.SHEET,
                    )
                }
            }
        }
        items(visible, key = { rowKeys[it] ?: "${it.file}:${it.line}" }) { entry ->
            // No placement animation (B77). Filtering moved the surviving rows up while the rows
            // that left vanished and the rows that stayed slid past each other, so for a
            // fraction of a second while typing in the search box two entries were drawn on
            // top of one another. A journal is read row by row; a filter that simply shows its
            // result is clearer than one that shows its working.
            val key = keyOf(entry)
            JournalRow(
                entry = entry,
                format = snapshot.format,
                onClick = {
                    if (selecting) {
                        picked = if (key in picked) picked - key else picked + key
                    } else {
                        onOpenEntry(entry)
                    }
                },
                onLongClick = { picked = picked + key },
                selected = key in picked,
                selecting = selecting,
                errors = faults[JournalErrors.keyOf(entry)].orEmpty(),
            )
            // Indented to where the entry's own text starts, so the hairlines run down the
            // page as one column rather than cutting across the marks in the gutter.
            LedgerHairline(
                Modifier.padding(start = Measure.gutter + Measure.stamp + Measure.gap),
            )
        }
        if (shown < found.size) {
            item {
                // Composing this row is what asks for the next page: reaching it means the
                // reader is at the end of what is drawn. It states the count rather than
                // spinning, because the wait is a frame and a spinner would imply otherwise.
                val remaining = found.size - shown
                LaunchedEffect(shown, found.size) {
                    shown = (shown + JOURNAL_PAGE).coerceAtMost(found.size)
                }
                Text(
                    text = "$remaining older " + if (remaining == 1) "entry" else "entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Measure.indent),
                )
            }
        }
    }
    }
}

/** Rows added each time the reader reaches the end of what is drawn. */
private const val JOURNAL_PAGE = 30

/** How many entries a delete confirmation names before "and N more". */
private const val DELETE_NAMED = 3

/**
 * One entry in a line of words: "2026-09-03 · Restaurant · Dinner · 4,300.00 PKR". For a
 * confirmation that has to say which entry it is about.
 */
internal fun entrySummary(
    entry: JournalEntry,
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat,
): String = listOfNotNull(
    entry.date,
    entry.description.ifBlank { NO_DESCRIPTION },
    entry.postings.firstNotNullOfOrNull { it.amount }?.let { format.format(it) },
).joinToString(" · ")

/**
 * One entry in the journal, as a line of a ruled book (B74).
 *
 * The row is a gutter and a column. In the gutter is the mark -- what the file's flag says,
 * or that Beancount could not read this entry -- and in the column are the words, the date,
 * and the postings underneath, with every amount right-aligned on the same edge so a page of
 * entries can be read down rather than across. The postings hang off a hairline of their own,
 * which is what makes an entry read as one thing with parts rather than as five loose lines.
 */
@Composable
private fun JournalRow(
    entry: JournalEntry,
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** What Beancount said about this entry, when it said anything (B64). */
    errors: List<LedgerError> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    /** Whether rows are being picked, so every row shows whether it is one of them. */
    selecting: Boolean = false,
) {
    val mark = when {
        errors.isNotEmpty() -> LedgerMark.ATTENTION
        else -> markForFlag(entry.flag)
    }
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressResponse(interactions)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Select",
                interactionSource = interactions,
                indication = androidx.compose.material3.ripple(),
            )
            // The whole row, not the text inside it: a row that reads as one thing and
            // responds only where the words happen to be is one people tap twice.
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = buildString {
                    append(entry.date).append(", ").append(entry.description.ifBlank { NO_DESCRIPTION })
                    // Said only when it is not the ordinary case, so a page of cleared entries
                    // is not "cleared" read twenty times.
                    if (mark == LedgerMark.PENDING || mark == LedgerMark.FLAGGED) {
                        append(". ").append(describeFlag(entry.flag))
                    }
                    if (errors.isNotEmpty()) {
                        append(if (errors.size == 1) ". Has an error: " else ". Has errors: ")
                        append(errors.joinToString(". ") { it.message })
                    }
                }
            }
            .padding(horizontal = Measure.gutter, vertical = Measure.rowPadding),
    ) {
        // While picking, the gutter holds a tick box in place of the mark (design-direction §7):
        // a tint alone said "selected" only to someone who could see the difference in colour.
        if (selecting) {
            androidx.compose.material3.Icon(
                imageVector = if (selected) {
                    androidx.compose.material.icons.Icons.Filled.CheckCircle
                } else {
                    androidx.compose.material.icons.Icons.Outlined.Circle
                },
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(end = Measure.gap)
                    .size(Measure.stamp + Measure.tight),
            )
        } else {
            // Decorative here: the row's description already says pending or flagged.
            StampMark(
                mark,
                flag = entry.flag,
                decorative = true,
                modifier = Modifier.padding(top = Measure.tight, end = Measure.gap),
            )
        }
        Column(Modifier.weight(1f)) {
            // The message is on the entry's own page (B77). A list row says only that there is
            // one, next to the mark, so a page of entries stays a page of entries.
            if (errors.isNotEmpty()) {
                Text(
                    text = if (errors.size == 1) "Has an error" else "Has ${errors.size} errors",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Measure.tight),
                )
            }
            // The same words the entry page uses when there are none (device audit 2026-10-10).
            Text(
                entry.description.ifBlank { NO_DESCRIPTION },
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.description.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Measure.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.date,
                    style = LedgerType.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The letter itself, beside the date, for a flag the app does not interpret: the
                // pennant says "marked", this says by what.
                if (mark == LedgerMark.FLAGGED) {
                    Text(
                        "Flag ${entry.flag}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BeancountTheme.ledger.flagged,
                        modifier = Modifier.padding(start = Measure.gap),
                    )
                }
                // Says a receipt is attached without opening anything: the row is a list
                // item, and the document itself belongs on the detail screen (B17).
                if (entry.linkedDocuments.isNotEmpty()) {
                    Text(
                        if (entry.linkedDocuments.size == 1) {
                            "Has a document"
                        } else {
                            "Has ${entry.linkedDocuments.size} documents"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BeancountTheme.ledger.rule,
                        modifier = Modifier.padding(start = Measure.gap),
                    )
                }
            }
            if (entry.tags.isNotEmpty() || entry.links.isNotEmpty()) {
                Text(
                    text = (entry.tags.map { "#$it" } + entry.links.map { "^$it" }).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Measure.tight),
                )
            }
            if (entry.postings.isNotEmpty()) {
                val hairline = MaterialTheme.colorScheme.outlineVariant
                Column(
                    modifier = Modifier
                        .padding(top = Measure.snug)
                        // The postings hang off a hairline of their own, so an entry reads as
                        // one thing with parts rather than as five loose lines.
                        .drawBehind {
                            drawRect(
                                color = hairline,
                                size = Size(width = 1.dp.toPx(), height = size.height),
                            )
                        }
                        .padding(start = Measure.gap),
                ) {
                    entry.postings.forEach { posting ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Measure.tight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Breaks between the segments of the name, never inside a word.
                            Text(
                                text = breakableAccount(posting.account),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(end = Measure.snug),
                            )
                            // In ink, like every balance: the sign says which way it went, and
                            // colour is kept for a difference (design-direction §4).
                            posting.amount?.let {
                                AmountText(it, format = format, style = LedgerType.row)
                            }
                        }
                        PostingLotText(posting, format = format)
                    }
                }
            }
        }
    }
}

/**
 * A ledger with errors is a normal state, so this informs rather than blocks
 * (docs/ux.md §2). Positions are shown in `file:line` form, which is what the user needs
 * to fix them.
 *
 * **Collapsed by default, because it was reading as an alarm.** It used to print five
 * messages plus "and N more" unconditionally, which on a phone took about a third of the
 * viewport and pushed the journal below the fold — permanently, since a ledger with errors
 * stays that way until someone fixes it, and ux.md calls that normal. In dark mode the
 * error container is a strong red, so a third of the screen went vivid for a state the
 * specification says is ordinary.
 *
 * So the count leads and the detail is behind a tap. The count is where the emphasis
 * belongs anyway: the header above already says "14 errors" in plain body text, and this
 * was shouting the same fact underneath it.
 */
@Composable
fun ErrorBanner(errors: List<LedgerError>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count = if (errors.size == 1) "1 error in this ledger" else "${errors.size} errors in this ledger"

    // A notice, inset and rounded like every other time the app speaks (design-direction §3),
    // with the attention mark leading and the count in the error colour. It was a full-width
    // red bar, the loudest thing on a screen for a state ux.md calls ordinary: the mark and the
    // words say "error" now, and the ground stays calm.
    Column(
        modifier = modifier
            .padding(horizontal = Measure.gutter, vertical = Measure.tight)
            .fillMaxWidth()
            .clip(io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.notice)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { expanded = !expanded }
            .padding(horizontal = Measure.indent, vertical = Measure.gap)
            .semantics {
                contentDescription = if (expanded) {
                    "$count. Showing the first ${minOf(errors.size, 5)}. Tap to hide them."
                } else {
                    "$count. Tap to see them."
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StampMark(LedgerMark.ATTENTION, modifier = Modifier.padding(end = Measure.gap))
            Text(
                text = count,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                // The row already carries a description saying what tapping does; announcing
                // the chevron separately would say it twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The messages push the banner open rather than appearing inside a box that was
        // already the right size: what a tap did is then visible as the page making room.
        LedgerReveal(visible = expanded, label = "errorDetail") {
            Column {
            errors.take(5).forEach { error ->
                Text(
                    text = listOfNotNull(error.position, error.message).joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp, start = Measure.stamp + Measure.gap),
                )
            }
            if (errors.size > 5) {
                Text(
                    text = "and ${errors.size - 5} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp, start = Measure.stamp + Measure.gap),
                )
            }
            }
        }
    }
}

/**
 * The mark on a row that opens: one chevron, turned.
 *
 * Two icons swapped between frames say "something changed"; one icon rotating says *this row
 * opened*, which is the thing that actually happened (B76). Rotation is a graphics-layer
 * property, so the row is redrawn rather than measured again.
 */
@Composable
internal fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val animate = io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled.current
    val turn by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (animate) {
            io.github.pintofi.beanbook.app.ui.theme.MotionRole.local()
        } else {
            androidx.compose.animation.core.snap()
        },
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Filled.ExpandMore,
        contentDescription = null,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = turn },
    )
}

/** Whether the entry carries a mark the Journal's flag filters ask for. */
private fun JournalEntry.hasMark(pending: Boolean, flagged: Boolean): Boolean {
    val mark = flag ?: return false
    return (pending && mark == "!") || (flagged && mark != "*" && mark != "!")
}
