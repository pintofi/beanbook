package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.BeancountText
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.DraftProblem
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.math.BigDecimal
import java.time.LocalDate
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme

/**
 * One editable posting row. Empty amount means elided — Beancount works it out.
 *
 * [currency] is per posting, blank meaning "the transaction's". A single currency for the
 * whole transaction could not express a currency exchange or a foreign-card purchase, which
 * are ordinary things to want to record (B4). Blank rather than a copy of the default, so
 * changing the transaction's currency still moves every posting that never disagreed.
 */
private class PostingRow(
    account: String = "",
    amount: String = "",
    currency: String = "",
    cost: String = "",
    costCurrency: String = "",
    price: String = "",
    priceCurrency: String = "",
) {
    var account by mutableStateOf(account)
    var amount by mutableStateOf(amount)
    var currency by mutableStateOf(currency)

    /**
     * What a unit was acquired at (`{...}`) and what it was exchanged at (`@ ...`) (B63).
     *
     * Behind a disclosure, because almost no posting has either and a shop purchase must not
     * grow four boxes to pay for the ones that do. Per unit, which is how Beancount stores
     * both: a total price is divided before it is recorded, so a form offering a total would
     * be offering a figure the file never keeps.
     */
    var cost by mutableStateOf(cost)
    var costCurrency by mutableStateOf(costCurrency)
    var price by mutableStateOf(price)
    var priceCurrency by mutableStateOf(priceCurrency)

    /** Open when the row already carries one, so a clone shows what it is about to write. */
    var showLot by mutableStateOf(cost.isNotBlank() || price.isNotBlank())

    /** One of the lot figures as an amount, or null when the box is empty or not a number. */
    fun lotAmount(value: String, denomination: String, fallback: String): Amount? =
        value.trim().takeIf { it.isNotBlank() }?.let { number ->
            runCatching {
                Amount(BigDecimal(number), denomination.trim().ifBlank { fallback.trim() })
            }.getOrNull()
        }

    /** Keys describing this leg only (B16). Empty for almost every posting. */
    val metadata = mutableStateListOf<MetadataRow>()
}

/** One editable metadata pair, on a transaction or on a posting. */
private class MetadataRow(key: String = "", value: String = "") {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

/**
 * Transaction entry: fast for the common case, complete for the rest.
 *
 * The first two rows are prefilled so a shop purchase is amount-plus-two-taps, but any
 * number of postings can be added — a split bill, a paycheque with deductions, a transfer
 * with a fee. The running imbalance is shown as you type, and **the app never balances the
 * transaction for you**: it shows the residual and offers an elided posting, which is how
 * you ask Beancount to compute it (docs/ux.md §4).
 */
@Composable
fun QuickAddScreen(
    vocabulary: LedgerVocabulary,
    /** The open ledger's own rendering and tolerance (B59). */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
    /** What this ledger calls its five trees, for the two accounts a form pre-selects (B70). */
    names: io.github.pintofi.beanbook.core.domain.AccountNames =
        io.github.pintofi.beanbook.core.domain.AccountNames.DEFAULT,
    onSave: (TransactionDraft) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    saveState: String? = null,
    /** A draft to start from -- an entry being entered again (B11). Applied once. */
    prefill: TransactionDraft? = null,
    onPrefillApplied: () -> Unit = {},
    templates: List<EntryTemplate> = emptyList(),
    onSaveTemplate: (EntryTemplate) -> Unit = {},
    onDeleteTemplate: (String) -> Unit = {},
    /** Photographing a receipt while entering the transaction it belongs to (B17). */
    capture: DocumentCaptureHandle? = null,
    /**
     * Whether anything has been typed that leaving would lose (B61).
     *
     * Reported rather than guarded here: the form does not own the way out -- the close
     * button, the back gesture and the destination bar all do -- so it says what it holds
     * and lets whoever is closing it decide whether to ask.
     */
    onDirtyChange: (Boolean) -> Unit = {},
) {
    var date by remember { mutableStateOf(today.toString()) }
    var payee by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    // "*" cleared, "!" pending. Beancount treats the second as "look at this again", and the
    // form had no way to say it, so every entry claimed to be settled (B15).
    var flag by remember { mutableStateOf("*") }
    // Transaction-level metadata, as editable rows so a half-typed key cannot be committed
    // by accident (B16).
    val metaRows = remember { mutableStateListOf<MetadataRow>() }
    // The default a posting uses when its own currency box is left empty. There is no field
    // for it: a transaction does not have one currency -- each posting does, and the boxes
    // beside the amounts are where that is said. A second, transaction-wide currency field
    // above them asked the same question twice and answered neither, since it set nothing on
    // its own. It still moves when a template or a cloned entry names a denomination.
    var currency by remember {
        mutableStateOf(vocabulary.defaultCurrency ?: vocabulary.currencies.firstOrNull() ?: "USD")
    }

    val rows = remember {
        mutableStateListOf(
            PostingRow(
                vocabulary.accounts.firstOrNull { it.startsWith(names.expenses) }.orEmpty(),
            ),
            // The second posting is elided by default: the counter-amount is Beancount's
            // to compute, not ours to guess.
            PostingRow(
                vocabulary.accounts.firstOrNull { it.startsWith(names.assets) }.orEmpty(),
            ),
        )
    }

    val draft = TransactionDraft(
        date = date.trim(),
        flag = flag,
        payee = payee.trim().ifBlank { null },
        narration = narration.trim().ifBlank { null },
        metadata = metaRows
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() },
        postings = rows.filter { it.account.isNotBlank() }.map { row ->
            PostingDraft(
                metadata = row.metadata
                    .filter { it.key.isNotBlank() }
                    .associate { it.key.trim() to it.value.trim() },
                account = row.account.trim(),
                amount = row.amount.trim().takeIf { it.isNotBlank() }?.let { value ->
                    val denomination = row.currency.trim().ifBlank { currency.trim() }
                    runCatching { Amount(BigDecimal(value), denomination) }.getOrNull()
                },
                // The lot's own currency defaults to the transaction's, the way the amount's
                // does. A cost with no figure is not written at all: `{}` asks Beancount to
                // book against whatever it can find, which is a different instruction from
                // the one an empty box means.
                cost = row.lotAmount(row.cost, row.costCurrency, currency)
                    ?.let { io.github.pintofi.beanbook.core.domain.Cost(it) },
                price = row.lotAmount(row.price, row.priceCurrency, currency),
            )
        },
    )
    val problems = draft.validate(format)

    // What counts as something to lose: words typed, a figure entered, a page photographed.
    // Not the date, which the form fills in by itself, and not an account the form
    // pre-selected -- neither is anything the user would miss.
    val dirty = payee.isNotBlank() ||
        narration.isNotBlank() ||
        rows.any { it.amount.isNotBlank() || it.cost.isNotBlank() || it.price.isNotBlank() } ||
        metaRows.any { it.key.isNotBlank() || it.value.isNotBlank() } ||
        capture?.pages?.isEmpty == false
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
    val residuals = draft.residuals()

    var namingTemplate by remember { mutableStateOf(false) }
    // What the user typed under the pages, if anything. Held here because the Save button is
    // what files them now (B66).
    var documentName by remember { mutableStateOf("") }

    /**
     * Applies a template through the same domain call the tests exercise, so there is one
     * definition of what a template becomes. The date stays as typed when it is a date at
     * all; a half-typed one falls back to today rather than being carried into the form.
     */
    fun applyTemplate(template: EntryTemplate) {
        val applied = template.apply(
            runCatching { LocalDate.parse(date.trim()) }.getOrDefault(today),
        )
        date = applied.date
        payee = applied.payee.orEmpty()
        narration = applied.narration.orEmpty()
        currency = template.currency
        rows.clear()
        applied.postings.forEach { posting ->
            rows.add(PostingRow(posting.account, posting.amount?.number?.toPlainString().orEmpty()))
        }
    }

    // Applied once, then reported back so re-entering the tab does not silently overwrite
    // what the user has typed since.
    LaunchedEffect(prefill) {
        prefill?.let { draft ->
            date = draft.date
            payee = draft.payee.orEmpty()
            narration = draft.narration.orEmpty()
            draft.postings.firstOrNull()?.amount?.currency?.let { currency = it }
            rows.clear()
            draft.postings.forEach { posting ->
                // Each posting keeps its own currency, so cloning a multi-currency entry
                // does not quietly redenominate half of it into the transaction's default.
                val denomination = posting.amount?.currency.orEmpty()
                rows.add(
                    PostingRow(
                        account = posting.account,
                        amount = posting.amount?.number?.toPlainString().orEmpty(),
                        currency = if (denomination == currency) "" else denomination,
                        // A clone of a holding keeps the lot it was held at, so the form shows
                        // the entry it is about to write rather than a stripped one.
                        cost = posting.cost?.amount?.number?.toPlainString().orEmpty(),
                        costCurrency = posting.cost?.amount?.currency.orEmpty(),
                        price = posting.price?.number?.toPlainString().orEmpty(),
                        priceCurrency = posting.price?.currency.orEmpty(),
                    )
                )
            }
            onPrefillApplied()
        }
    }

    Column(
        // No `imePadding()` here: the Scaffold above insets the whole app for the keyboard,
        // and doing it again shrank the scrolling viewport a second time -- a blank band
        // above the keyboard, and a last field that could not be scrolled into view (B54).
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(Measure.gap),
    ) {
        // Templates come first because that is when they are useful: before anything has
        // been typed. Applying one fills the form and stops there — the entry is still
        // previewed and validated like any other, so a template cannot write anything the
        // user has not seen (docs/ux.md 4).
        if (templates.isNotEmpty()) {
            LedgerHeading("Templates")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                templates.forEach { template ->
                    AssistChip(
                        onClick = { applyTemplate(template) },
                        label = { Text(template.name) },
                        modifier = Modifier.semantics {
                            contentDescription = "Start from the template ${template.name}"
                        },
                    )
                }
            }
        }

        // Cleared or pending, as two chips rather than free text: these are the only two
        // flags Beancount accepts on a transaction, and typing is not the right way to pick
        // from a closed set of two (B15).
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = flag == "*",
                onClick = { flag = "*" },
                shape = SegmentedButtonDefaults.itemShape(0, 2, baseShape = BeanbookShape.control),
                modifier = Modifier.semantics { contentDescription = "Cleared transaction" },
            ) { Text("Cleared") }
            SegmentedButton(
                selected = flag == "!",
                onClick = { flag = "!" },
                shape = SegmentedButtonDefaults.itemShape(1, 2, baseShape = BeanbookShape.control),
                modifier = Modifier.semantics { contentDescription = "Pending transaction" },
            ) { Text("Pending") }
        }

        DateField(
            value = date,
            onValueChange = { date = it },
            modifier = Modifier.fillMaxWidth(),
            today = today,
        )

        SuggestingField(
            value = payee,
            onValueChange = { payee = it },
            label = "Payee",
            suggestions = vocabulary.payees,
            modifier = Modifier.fillMaxWidth(),
        )

        // "Use what you used last time" — offered, never applied silently, because reusing
        // an old amount without being asked is a way to record the wrong number.
        vocabulary.lastEntryFor(payee.trim())?.let { last ->
            AssistChip(
                onClick = {
                    narration = last.narration.orEmpty()
                    // Grow to fit: reusing a three-posting entry into two rows would drop a
                    // posting silently, which is exactly the kind of quiet loss the app
                    // must not do.
                    while (rows.size < last.postings.size) rows.add(PostingRow())
                    last.postings.forEachIndexed { index, posting ->
                        rows[index].account = posting.account
                        rows[index].amount =
                            posting.amount?.number?.abs()?.toPlainString().orEmpty()
                    }
                    // Only the first posting keeps an amount; the rest stay elided, so
                    // Beancount recomputes them rather than us reusing stale numbers.
                    rows.drop(1).forEach { it.amount = "" }
                },
                label = { Text("Use last: ${last.postings.firstOrNull()?.account?.substringAfterLast(':')}") },
                modifier = Modifier.semantics {
                    contentDescription = "Reuse the last entry for ${payee.trim()}"
                },
            )
        }

        SuggestingField(
            value = narration,
            onValueChange = { narration = it },
            label = "Description",
            suggestions = vocabulary.narrations,
            modifier = Modifier.fillMaxWidth(),
        )

        MetadataEditor(
            rows = metaRows,
            label = "Details about the whole transaction",
            describe = { "transaction" },
        )

        if (capture != null) {
            AttachDocumentSection(
                capture = capture,
                // Filed under the account the document is evidence for. Blank until the
                // first posting has one, because the account decides where the file lands
                // and guessing it would put receipts somewhere nobody looks.
                account = rows.firstOrNull { it.account.isNotBlank() }?.account.orEmpty(),
                attached = metaRows.filter { it.key.startsWith("document") }.map { it.value },
                onNameChange = { documentName = it },
            )
        }

        LedgerHeading("Postings")

        // Side by side, an account and an amount stop fitting once the text is large: at
        // 200% font the amount field is narrow enough that its own label wraps mid-word.
        // Above 130% they stack instead, which costs a line and keeps both readable.
        // Below this the three fields sit in a row; above it the account takes its own line.
        // The row layout is sized for the label text at ordinary scales, so the threshold is
        // the point past which no fixed width would hold rather than an arbitrary cutoff.
        val stacked = LocalDensity.current.fontScale > 1.3f

        rows.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val accountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    SuggestingField(
                        value = row.account,
                        onValueChange = { row.account = it },
                        label = "Account ${index + 1}",
                        suggestions = vocabulary.accounts,
                        describe = "Account for posting ${index + 1}",
                        modifier = fieldModifier,
                    )
                }
                val amountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(
                        value = row.amount,
                        onValueChange = { row.amount = it },
                        label = { Text("Amount") },
                        // "optional" as supporting text rather than inside the label. In the
                        // label it made the string long enough to wrap in a field this width,
                        // so one posting's Amount box came out two lines taller than the one
                        // above it. Below the field it says the same thing and costs the row
                        // nothing -- and it is where Material puts this kind of qualifier.
                        supportingText = if (index == 0) null else { { Text("optional") } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = fieldModifier,
                    )
                }
                val currencyField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    SuggestingField(
                        value = row.currency,
                        onValueChange = { row.currency = it.uppercase() },
                        label = "Cur.",
                        suggestions = vocabulary.currencies,
                        describe = "Currency for posting ${index + 1}",
                        modifier = fieldModifier,
                    )
                }
                val removeButton: @Composable () -> Unit = {
                    if (rows.size > 2) {
                        TextButton(
                            onClick = { rows.removeAt(index) },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove posting ${index + 1}"
                            },
                        ) { Text("×") }
                    }
                }

                if (stacked) {
                    accountField(Modifier.fillMaxWidth())
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        amountField(Modifier.weight(2f))
                        currencyField(Modifier.weight(1f))
                        removeButton()
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        // Top, not centre. These fields do not always come out the same
                        // height -- "Amount (optional)" wraps to two lines where "Amount"
                        // does not -- and centring ragged heights is what made the row look
                        // like it was sagging in the middle.
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Amount and currency are sized to their contents; the account name
                        // takes whatever is left, because it is the one that is never long
                        // enough. Currency was `weight(1f)`, which at this width left it too
                        // narrow for its own label: "Cur." wrapped, putting the full stop on a
                        // line of its own and making the field taller than its neighbours.
                        // That stray dot under the label was the label.
                        accountField(Modifier.weight(1f))
                        amountField(Modifier.width(AMOUNT_FIELD_WIDTH))
                        currencyField(Modifier.width(CURRENCY_FIELD_WIDTH))
                        removeButton()
                    }
                }
                if (row.showLot) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        OutlinedTextField(
                            value = row.cost,
                            onValueChange = { row.cost = it },
                            label = { Text("Cost each") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(2f).semantics {
                                contentDescription = "Cost per unit for posting ${index + 1}"
                            },
                        )
                        SuggestingField(
                            value = row.costCurrency,
                            onValueChange = { row.costCurrency = it.uppercase() },
                            label = "Cur.",
                            suggestions = vocabulary.currencies,
                            describe = "Cost currency for posting ${index + 1}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        OutlinedTextField(
                            value = row.price,
                            onValueChange = { row.price = it },
                            label = { Text("Price each") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(2f).semantics {
                                contentDescription = "Price per unit for posting ${index + 1}"
                            },
                        )
                        SuggestingField(
                            value = row.priceCurrency,
                            onValueChange = { row.priceCurrency = it.uppercase() },
                            label = "Cur.",
                            suggestions = vocabulary.currencies,
                            describe = "Price currency for posting ${index + 1}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { row.showLot = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Add a cost or price to posting ${index + 1}"
                        },
                    ) { Text("Cost or price") }
                }
                MetadataEditor(
                    rows = row.metadata,
                    label = "Details about this posting only",
                    describe = { "posting ${index + 1}" },
                )
            }
        }

        OutlinedButton(
            onClick = { rows.add(PostingRow()) },
            modifier = Modifier.semantics { contentDescription = "Add another posting" },
        ) { Text("Add posting") }

        // The imbalance is information, not an error: an elided posting is the normal way
        // to let Beancount finish the arithmetic.
        if (residuals.isNotEmpty()) {
            val elided = draft.postings.count { it.isElided }
            Notice(if (elided == 1) NoticeTone.QUIET else NoticeTone.ATTENTION) {
                Text(
                    text = if (elided == 1) {
                        "Beancount will put ${residuals.joinToString(", ") { format.format(it) }} " +
                            "on the posting with no amount."
                    } else {
                        "Does not balance: ${residuals.joinToString(", ") { format.format(it) }}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // The promise the form makes, and the one moment in it that looks like the file:
        // the lines about to be written, in the source face, on a surface a shade off the
        // page and hung from the same rule as everything else the app states as fact.
        RuledColumn(modifier = Modifier.padding(top = Measure.snug)) {
            LedgerHeading("Will be written as")
            Text(
                text = draft.render(),
                style = LedgerType.source,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    // The signature (B76). When a save lands, the lines that were written
                    // are swept once by the app's own rule, left to right, and it is over in
                    // a third of a second. It is the only moment in the app that marks an
                    // action rather than explaining a change, and it marks the right one: the
                    // entry going into the book is what the whole form is for. Drawn rather
                    // than laid out, so the preview does not move while it happens.
                    .filedSweep(key = saveState, colour = BeancountTheme.ledger.rule)
                    .padding(Measure.indent)
                    .semantics { contentDescription = "Preview of the entry to be saved" },
            )
        }

        // A validation message pushes the form down rather than appearing between two fields
        // that were already there: what changed is that the form has something to say.
        LedgerReveal(visible = problems.isNotEmpty(), label = "problems") {
            val shown = androidx.compose.runtime.remember(problems) { problems }
            Notice(NoticeTone.PROBLEM) {
                shown.forEach { problem ->
                    Text(problem.describe(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Invariant 6: a retried write never creates a duplicate entry. `ProjectWriter.append`
        // cannot enforce that on its own -- it computes `expectedSha` from the file at call
        // time, so the stale-hash check that protects `replace` sees nothing wrong with the
        // second write, and the write mutex serialises the two taps rather than collapsing
        // them. Two taps on this button therefore produced two identical transactions, both
        // reported as saved.
        //
        // The guard is the draft itself: once this exact draft has been submitted the button
        // is disabled, and it comes back the moment any field changes. Somebody entering two
        // genuinely identical transactions edits something and edits it back, which is a
        // deliberate act rather than a slip.
        var submitted by remember { mutableStateOf<TransactionDraft?>(null) }

        // Pages photographed but never filed (B57). Saving the entry writes the transaction
        // and leaves the captures in a cache the next scan will reuse -- so a receipt taken
        // *for this entry* silently belongs to nothing. Said before the save, not after, and
        // it does not block: the entry is the user's to write, and the app's job is to make
        // sure the loss is a choice.
        val unfiledPages = capture?.pages?.pages?.size ?: 0
        val documentAccount = rows.firstOrNull { it.account.isNotBlank() }?.account.orEmpty()

        // A page photographed for this entry is filed by saving the entry (B66). What used to
        // stand here was a warning that pages were "not attached", pointing at a second button
        // the user had to find; missing it filed the receipt under nothing.
        if (unfiledPages > 0 && documentAccount.isBlank()) {
            Notice(
                NoticeTone.ATTENTION,
                modifier = Modifier.semantics {
                    contentDescription =
                        "$unfiledPages photographed page(s) cannot be filed without an account"
                },
            ) {
                Text(
                    "$unfiledPages page${if (unfiledPages == 1) "" else "s"} photographed. " +
                        "Give the first posting an account so they can be filed under it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(
            onClick = {
                submitted = draft
                // Pages first, then the entry that names them. The order is what makes the
                // pair legal: `fava.plugins.link_documents` reports "Document not found" for
                // an entry whose `document:` metadata has no `document` directive to link to,
                // and a new error is what the write protocol rolls a save back for.
                if (capture != null && unfiledPages > 0 && documentAccount.isNotBlank()) {
                    capture.onFile(documentAccount, documentName.trim().ifBlank { null }) { path ->
                        val key = BeancountText.nextDocumentKey(
                            draft.metadata.keys + metaRows.map { it.key }.toSet(),
                        )
                        // The file's own name, not the path to it. `fava.plugins.link_documents`
                        // matches a `document:` value against the *basename* of each
                        // `document` directive (and against a path resolved relative to the
                        // file the entry is written in, which for an entry in
                        // files/transactions.beancount is not where the documents live). A
                        // project-relative path matches neither, and the plugin then reports
                        // "Document not found" -- a new error, which rolls the save back.
                        onSave(
                            draft.copy(
                                metadata = draft.metadata + (key to path.substringAfterLast('/')),
                            ),
                        )
                    }
                } else {
                    onSave(draft)
                }
            },
            enabled = problems.isEmpty() && draft != submitted &&
                (unfiledPages == 0 || documentAccount.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (unfiledPages > 0) {
                    "Save entry with $unfiledPages page${if (unfiledPages == 1) "" else "s"}"
                } else {
                    "Save entry"
                },
            )
        }

        OutlinedButton(
            onClick = { namingTemplate = true },
            enabled = rows.any { it.account.isNotBlank() },
            modifier = Modifier.semantics { contentDescription = "Save this entry as a template" },
        ) { Text("Save as template") }

        if (namingTemplate) {
            TemplateDialog(
                draft = draft,
                fallbackCurrency = currency.trim(),
                existing = templates,
                onSave = { template ->
                    onSaveTemplate(template)
                    namingTemplate = false
                },
                onDelete = onDeleteTemplate,
                onDismiss = { namingTemplate = false },
            )
        }

        // What the write did, arriving under the button that did it.
        LedgerReveal(visible = saveState != null, label = "saveState") {
            val shown = androidx.compose.runtime.remember(saveState) { saveState }
            Text(shown.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The page editor, over the whole screen, from inside the entry form (B65).
 *
 * It used to be composed straight into the form's scrolling column, so a page you were trying
 * to straighten appeared as a panel a few hundred pixels tall between two text fields --
 * while the same editor opened from Documents took the whole screen. A dialog with the
 * platform's width limit turned off is the ordinary way to give a screen-sized task a screen
 * from a place that is not one.
 */
@Composable
private fun PageEditorOverlay(
    capture: DocumentCaptureHandle,
    startAt: Int,
    onDone: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
            PageEditorScreen(
                pages = capture.pages,
                fileFor = capture.fileFor,
                startAt = startAt,
                onRotate = capture.onRotateBy,
                onCrop = capture.onCrop,
                onRestyle = capture.onRestyle,
                onRemove = capture.onRemovePage,
                onMove = capture.onMove,
                onDone = onDone,
            )
        }
    }
}

@Composable
internal fun Suggestions(
    values: List<String>,
    kind: String,
    current: String = "",
    onPick: (String) -> Unit,
) {
    // A suggestion identical to what is already in the field does nothing when tapped, and
    // reads as though the app has not noticed what was typed -- a lone "USD" chip under a
    // currency field already saying USD (D9).
    val offered = values.filter { it != current.trim() }
    if (offered.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // No cap: this row scrolls horizontally, so taking six hid matches the vocabulary had
        // already found rather than saving any space (B7).
        offered.forEach { value ->
            AssistChip(
                onClick = { onPick(value) },
                label = { Text(value.substringAfterLast(':')) },
                modifier = Modifier.semantics { contentDescription = "Use $kind $value" },
            )
        }
    }
}

/**
 * Names a template, and manages the ones already saved.
 *
 * Deleting is here rather than behind a long-press because a destructive action has to name
 * its scope (CLAUDE.md, invariant 7): the row says which template goes, and nothing else in
 * the app is touched — a template is prefill, so removing one cannot affect the ledger.
 */
@Composable
private fun TemplateDialog(
    draft: TransactionDraft,
    fallbackCurrency: String,
    existing: List<EntryTemplate>,
    onSave: (EntryTemplate) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val candidate = EntryTemplate.from(name, draft, fallbackCurrency)
    val problems = candidate.validate()
    val replaces = existing.any { it.name.equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The accounts, payee, description and any amounts on the form are kept. " +
                        "The date is not: a template is applied to the date you are entering.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Template name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (replaces) {
                    Text(
                        "Replaces the template already called \"${name.trim()}\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Shown while typing rather than on save: a template that could never
                // produce a valid entry is refused now, not on the day it is used.
                problems.filterNot { it is DraftProblem.MissingText }.forEach { problem ->
                    Text(
                        problem.describe(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (existing.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Saved templates", style = MaterialTheme.typography.titleSmall)
                    existing.forEach { template ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(template.name, style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                onClick = { onDelete(template.name) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Delete the template ${template.name}"
                                },
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(candidate) },
                enabled = problems.isEmpty(),
            ) { Text("Save template") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * Metadata pairs, on a transaction or on one posting (B16).
 *
 * Collapsed until asked for. Almost no entry carries metadata, and a pair of empty fields on
 * every posting would make the common case worse to serve the rare one.
 *
 * Keys are validated by `TransactionDraft.validate`, so a key Beancount cannot parse is
 * reported beside the others rather than written into the file and discovered on the next
 * load.
 */
@Composable
private fun MetadataEditor(
    rows: MutableList<MetadataRow>,
    label: String,
    describe: () -> String,
) {
    if (rows.isEmpty()) {
        OutlinedButton(
            onClick = { rows.add(MetadataRow()) },
            modifier = Modifier.semantics { contentDescription = "Add a detail to ${describe()}" },
        ) { Text("Add a detail") }
        return
    }

    Text(label, style = MaterialTheme.typography.labelMedium)
    rows.forEachIndexed { index, meta ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = meta.key,
                onValueChange = { meta.key = it },
                label = { Text("Key") },
                singleLine = true,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Detail ${index + 1} key for ${describe()}"
                },
            )
            OutlinedTextField(
                value = meta.value,
                onValueChange = { meta.value = it },
                label = { Text("Value") },
                singleLine = true,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Detail ${index + 1} value for ${describe()}"
                },
            )
            TextButton(
                onClick = { rows.removeAt(index) },
                modifier = Modifier.semantics {
                    contentDescription = "Remove detail ${index + 1} from ${describe()}"
                },
            ) { Text("\u00d7") }
        }
    }
    OutlinedButton(
        onClick = { rows.add(MetadataRow()) },
        modifier = Modifier.semantics { contentDescription = "Add another detail to ${describe()}" },
    ) { Text("Add another detail") }
}

/**
 * Photograph a receipt while entering the transaction it belongs to (B17).
 *
 * The pages are filed as soon as the user says so, before the transaction is saved, so the
 * ledger never names a file that is not there yet (invariant 5). The cost is that abandoning
 * the entry leaves the PDF on disk — visible in Organize as an unfiled document, where it can
 * be filed or deleted, rather than lost.
 */
@Composable
private fun AttachDocumentSection(
    capture: DocumentCaptureHandle,
    account: String,
    attached: List<String>,
    onNameChange: (String) -> Unit,
) {

    // What to call it, if the user wants to (B57).
    var documentName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(documentName) { onNameChange(documentName) }
    var editingPages by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attached.forEach { path ->
            Text(
                "Attached: ${path.substringAfterLast('/')}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Straight into the scanner. The permission is requested here, at the moment the
        // camera is actually wanted, rather than being assumed from a screen the user may
        // never have opened -- attaching a photo from entry used to fail silently on a device
        // that had never visited Documents.
        val cameraGranted = rememberCameraPermission()
        var granted by remember(cameraGranted) { mutableStateOf(cameraGranted) }
        val askForCamera = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { allowed ->
            granted = allowed
            if (allowed) capture.onStartScanning()
        }

        // An action, drawn as one (B66). Text that happens to respond to a tap reads as a
        // label until somebody guesses; an outlined button says what it is before it is
        // touched, which is what Material means by an affordance.
        OutlinedButton(
            onClick = {
                if (granted) capture.onStartScanning()
                else askForCamera.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.semantics {
                contentDescription = "Scan a document for this transaction"
            },
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Scan a document")
        }

        if (!capture.pages.isEmpty) {
            if (account.isBlank()) {
                // Said rather than silently disabled: the reason is not guessable from a
                // greyed-out button, and the fix is one field away.
                Text(
                    "Give the first posting an account first — it decides where the " +
                        "document is filed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // One row of thumbnails and one way in, rather than four text buttons under
                // every page (B58): tapping a page opens the editor at it, where rotating,
                // cropping and re-rendering are done on the page itself.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    capture.pages.pages.forEachIndexed { index, page ->
                        androidx.compose.material3.Surface(
                            onClick = { editingPages = index },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.semantics {
                                contentDescription = "Edit page ${index + 1} of ${capture.pages.size}"
                            },
                        ) {
                            PageThumbnail(
                                file = capture.fileFor(page.source),
                                label = "Page ${index + 1}",
                            )
                        }
                    }
                }
                Text(
                    "${capture.pages.size} page${if (capture.pages.size == 1) "" else "s"}. " +
                        "Tap one to rotate, crop or change how it is rendered.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = documentName,
                    onValueChange = { documentName = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("grocery-receipt") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "What to call this document" },
                )
                // No button. Filing is part of saving the entry (B66): a receipt photographed
                // *for this transaction* belongs to it, and asking the user to press a second
                // button to make that true was a step whose only possible outcome, if they
                // missed it, was a page filed under nothing.
                Text(
                    "These pages are filed under $account and linked to this entry when you " +
                        "save it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editingPages?.let { index ->
        PageEditorOverlay(capture, index) { editingPages = null }
    }
}

/** Wide enough for a figure with two decimal places, and for the "Amount" label above it. */
internal val AMOUNT_FIELD_WIDTH = 116.dp

/** Wide enough for a three-letter code, and for the "Cur." label that used to wrap onto two. */
internal val CURRENCY_FIELD_WIDTH = 92.dp
