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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import java.time.LocalDate
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.core.domain.CommodityDetails
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn

/**
 * One editable posting row. Empty amount means elided — Beancount works it out.
 *
 * [commodity] is per posting, blank meaning "the transaction's". A single commodity for the
 * whole transaction could not express an exchange or a foreign-card purchase, which are
 * ordinary things to want to record. Blank rather than a copy of the default, so changing
 * the transaction's commodity still moves every posting that never disagreed.
 */
private class PostingRow(
    account: String = "",
    amount: String = "",
    commodity: String = "",
    details: String = "",
) {
    var account by mutableStateOf(account)
    var amount by mutableStateOf(amount)
    var commodity by mutableStateOf(commodity)

    /**
     * The rest of the posting line after the units: a lot, a price, or both, as the file
     * writes them.
     *
     * One field rather than four boxes for a cost and a price. The boxes could not say a total
     * cost, a total price, an acquisition date, a lot label or an empty `{}`, and the file has
     * all of them; a person who needs this part of the line knows how it is written.
     */
    var details by mutableStateOf(details)

    /** Open when the row already carries details, so a clone shows what it is about to write. */
    var showDetails by mutableStateOf(details.isNotBlank())

    /** Keys describing this leg only. Empty for almost every posting. */
    val metadata = mutableStateListOf<MetadataRow>()

    /**
     * What the file has on this posting that the form has no box for: a comment at the end of the
     * line, and full-line comments above it. Carried through an edit so saving never drops them.
     */
    var comment: String? = null
    var notesBefore: List<String> = emptyList()
}

/** One editable metadata pair, on a transaction or on a posting. */
private class MetadataRow(
    key: String = "",
    value: String = "",
    /** Written bare in the file (`trip-days: 5`). */
    val bare: Boolean = false,
) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

/** One tag or link being typed. A class so each box keeps its own state as others are removed. */
private class WordRow(value: String = "") {
    var value by mutableStateOf(value)
}

/**
 * Transaction entry: fast for the common case, complete for the rest.
 *
 * **Laid out to be read in three groups, and to show only what is being used.** The form
 * had grown a control for everything a transaction can carry, and all of them were on screen
 * at once: two segmented rows, a cost-or-price button and a detail button under every posting,
 * and the commodity crammed into a box too narrow for its own label. What it does now, and the
 * rules it follows:
 *
 * - **Progressive disclosure, and Hick's law.** Tags, links, details, commodity details and a
 *   scanned document are one line of small actions; a field appears only when asked for. The
 *   common entry -- a payee, a description, two accounts and an amount -- has nothing else on
 *   the screen to choose between.
 * - **Proximity and common region (Gestalt).** Three groups -- when and who, the postings, and
 *   the extras -- with more space between groups than within them, so structure is seen before
 *   it is read.
 * - **One idea per line, and cognitive load.** The account takes the whole row, because an
 *   account name is the longest thing typed; the amount and commodity share the line beneath.
 *   A posting reads top to bottom as the file does: where, then how much.
 * - **Fitts's law.** The one primary action, Save, is the full width of the form. Removing a
 *   posting or a detail is a real 48dp button with a visible icon, not a glyph in a text button.
 *
 * The running imbalance is shown as you type, and **the app never balances the transaction
 * for you**: it shows the residual and offers an elided posting, which is how you ask Beancount
 * to compute it (docs/ux.md §4).
 */
@Composable
fun QuickAddScreen(
    vocabulary: LedgerVocabulary,
    /** The open ledger's own rendering and tolerance. */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
    /** What this ledger calls its five trees. Kept for callers; the form prefills none. */
    @Suppress("UNUSED_PARAMETER")
    names: io.github.pintofi.beanbook.core.domain.AccountNames =
        io.github.pintofi.beanbook.core.domain.AccountNames.DEFAULT,
    onSave: (TransactionDraft) -> Unit,
    modifier: Modifier = Modifier,
    /** The date the form opens on. Today, on the device's clock, unless a caller says otherwise. */
    today: LocalDate = LocalDate.now(),
    saveState: String? = null,
    /** Entries committed from this form this session: what the save sweep marks. */
    committed: Int = 0,
    /** A draft to start from -- an entry being entered again. Applied once. */
    prefill: TransactionDraft? = null,
    onPrefillApplied: () -> Unit = {},
    templates: List<EntryTemplate> = emptyList(),
    onSaveTemplate: (EntryTemplate) -> Unit = {},
    onDeleteTemplate: (String) -> Unit = {},
    /** Photographing a receipt while entering the transaction it belongs to. */
    capture: DocumentCaptureHandle? = null,
    /**
     * Whether anything has been typed that leaving would lose.
     *
     * Reported rather than guarded here: the form does not own the way out -- the close
     * button, the back gesture and the destination bar all do -- so it says what it holds
     * and lets whoever is closing it decide whether to ask.
     */
    onDirtyChange: (Boolean) -> Unit = {},
    /**
     * An entry already in the file, being edited. The same form as Add, field for field:
     * it used to be a dialog of its own with fewer options, and the two drifted apart with
     * every change to one of them. [prefill] carries the entry; this says what saving means.
     */
    editing: EntryEditing? = null,
    /**
     * Opens an account the entry names and the ledger has not opened, without leaving the form.
     * Null where it cannot be done (a remote server), and the buttons are then not shown.
     */
    onOpenAccount: ((io.github.pintofi.beanbook.core.domain.DirectiveDraft) -> Unit)? = null,
) {
    // Today, and intentionally so: almost every entry is for the day it is typed, and the
    // calendar is one tap away for the rest.
    var date by remember { mutableStateOf(today.toString()) }
    var payee by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    // "*" cleared, "!" pending. Beancount treats the second as "look at this again", and the
    // form had no way to say it, so every entry claimed to be settled.
    var flag by remember { mutableStateOf("*") }
    // Transaction-level metadata, as editable rows so a half-typed key cannot be committed
    // by accident.
    val metaRows = remember { mutableStateListOf<MetadataRow>() }
    // Comments the entry being edited carries. No box for them: held, written back where they
    // were, and said so on screen.
    var headerComment by remember { mutableStateOf<String?>(null) }
    var leadingNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var trailingNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    // Tags and links, one box each, shown only once one is asked for.
    val tagRows = remember { mutableStateListOf<WordRow>() }
    val linkRows = remember { mutableStateListOf<WordRow>() }
    // The default a posting uses when its own commodity box is left empty. There is no field
    // for it: a transaction does not have one commodity -- each posting does. It still moves
    // when a template or a cloned entry names a denomination.
    var commodity by remember {
        mutableStateOf(vocabulary.defaultCurrency ?: vocabulary.currencies.firstOrNull() ?: "USD")
    }

    // Two empty postings. The form used to preselect the first expense account and the
    // first asset account in alphabetical order, which for most ledgers were two accounts
    // nobody had chosen -- "Expenses:Books" and "Assets:Bank:Old" on every new entry. The
    // suggestions on focus make picking the right one two taps, and an empty box cannot be
    // saved by mistake the way a wrong prefilled one can.
    val rows = remember { mutableStateListOf(PostingRow(), PostingRow()) }

    val draft = TransactionDraft(
        date = date.trim(),
        flag = flag,
        payee = payee.trim().ifBlank { null },
        narration = narration.trim().ifBlank { null },
        tags = tagRows.map { it.value.trim().removePrefix("#") }.filter { it.isNotEmpty() },
        links = linkRows.map { it.value.trim().removePrefix("^") }.filter { it.isNotEmpty() },
        metadata = metaRows
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() },
        headerComment = headerComment,
        notes = leadingNotes,
        trailingNotes = trailingNotes,
        bareMetadata = metaRows.filter { it.bare && it.key.isNotBlank() }.map { it.key.trim() }.toSet(),
        postings = rows.filter { it.account.isNotBlank() }.map { row ->
            PostingDraft(
                comment = row.comment,
                notesBefore = row.notesBefore,
                bareMetadata = row.metadata.filter { it.bare && it.key.isNotBlank() }.map { it.key.trim() }.toSet(),
                metadata = row.metadata
                    .filter { it.key.isNotBlank() }
                    .associate { it.key.trim() to it.value.trim() },
                account = row.account.trim(),
                amount = row.amount.trim().takeIf { it.isNotBlank() }?.let { value ->
                    val denomination = row.commodity.trim().ifBlank { commodity.trim() }
                    parseTypedAmount(value)?.let { number -> runCatching { Amount(number, denomination) }.getOrNull() }
                },
                details = row.details.trim().takeIf { row.showDetails && it.isNotEmpty() },
            )
        },
    )
    val problems = draft.validate(format, vocabulary.openAccounts)
    // A box with something in it that is not a number. Without this the posting is built with
    // no amount, which reads as "let Beancount work it out" and drops what was typed.
    val unreadableAmounts = rows
        .filter { it.amount.isNotBlank() && parseTypedAmount(it.amount) == null }
        .map { unreadableAmountMessage(it.amount) }

    // What the session had saved when this form opened, so the sweep answers this form's own
    // save rather than running as soon as a second entry is started.
    val committedBefore = remember { committed }

    // Files attached as they are, beside the photographed pages.
    val attachedFiles by (capture?.attachedFiles ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) })
        .collectAsState()
    val pageCount = capture?.pages?.pages?.size ?: 0
    val hasDocuments = pageCount > 0 || attachedFiles.isNotEmpty()

    // What counts as something to lose: words typed, a figure entered, a page photographed.
    // Not the date, which the form fills in by itself.
    // What the form said the moment an entry being edited was put into it. Compared with
    // what it says now, so opening an entry and leaving it again asks nothing.
    var baseline by remember { mutableStateOf<String?>(null) }
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(prefilled) { if (prefilled && editing != null) baseline = draft.render() }
    val dirty = if (editing != null) {
        (baseline != null && draft.render() != baseline) || hasDocuments
    } else payee.isNotBlank() ||
        narration.isNotBlank() ||
        rows.any { it.account.isNotBlank() || it.amount.isNotBlank() || it.details.isNotBlank() } ||
        tagRows.any { it.value.isNotBlank() } ||
        linkRows.any { it.value.isNotBlank() } ||
        metaRows.any { it.key.isNotBlank() || it.value.isNotBlank() } ||
        hasDocuments
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
    val residuals = draft.residuals()

    var namingTemplate by remember { mutableStateOf(false) }
    // What the user typed under the pages, if anything. Held here because the Save button is
    // what files them now.
    var documentName by remember { mutableStateOf("") }
    // Scanning straight from the action row: one tap to the camera, with the permission
    // asked for at the moment it is needed, as the document section used to do behind a
    // second button.
    // A file the person already has -- an emailed invoice, a saved picture -- attached as it is.
    // The document picker needs no storage permission.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) capture?.onAttachFile?.invoke(uri)
    }
    val cameraGranted = rememberCameraPermission()
    var cameraAllowed by remember(cameraGranted) { mutableStateOf(cameraGranted) }
    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        cameraAllowed = allowed
        if (allowed) capture?.onStartScanning?.invoke()
    }

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
        commodity = template.currency
        rows.clear()
        applied.postings.forEach { posting ->
            // The same fields a clone fills: each leg's own commodity, and its lot or price,
            // so an exchange or a purchase at cost is applied as it was saved.
            rows.add(
                PostingRow(
                    account = posting.account,
                    amount = posting.amount?.number?.toPlainString().orEmpty(),
                    commodity = posting.amount?.currency.orEmpty(),
                    details = posting.details ?: CommodityDetails.textOf(posting.cost, posting.price),
                ).also { row ->
                    posting.metadata.forEach { (key, value) -> row.metadata.add(MetadataRow(key, value)) }
                },
            )
        }
    }

    // Applied once, then reported back so re-entering the tab does not silently overwrite
    // what the user has typed since.
    LaunchedEffect(prefill) {
        prefill?.let { draft ->
            date = draft.date
            flag = draft.flag
            payee = draft.payee.orEmpty()
            narration = draft.narration.orEmpty()
            tagRows.clear()
            draft.tags.forEach { tagRows.add(WordRow(it)) }
            linkRows.clear()
            draft.links.forEach { linkRows.add(WordRow(it)) }
            // Everything the draft carries goes into a field. A clone arrives without
            // metadata by design; an entry being edited arrives with it, and a detail that
            // was not shown would be dropped by the save.
            metaRows.clear()
            draft.metadata.forEach { (key, value) -> metaRows.add(MetadataRow(key, value, key in draft.bareMetadata)) }
            headerComment = draft.headerComment
            leadingNotes = draft.notes
            trailingNotes = draft.trailingNotes
            draft.postings.firstOrNull()?.amount?.currency?.let { commodity = it }
            rows.clear()
            draft.postings.forEach { posting ->
                rows.add(
                    PostingRow(
                        account = posting.account,
                        amount = posting.amount?.number?.toPlainString().orEmpty(),
                        // Each posting says its own commodity in its own box, so what is on
                        // screen is what the preview writes -- the rule "Use last" follows,
                        // and a multi-commodity entry is not quietly redenominated.
                        commodity = posting.amount?.currency.orEmpty(),
                        // A clone of a holding keeps the lot it was held at, so the form shows
                        // the entry it is about to write rather than a stripped one.
                        details = posting.details
                            ?: CommodityDetails.textOf(posting.cost, posting.price),
                    ).also { row ->
                        posting.metadata.forEach { (key, value) ->
                            row.metadata.add(MetadataRow(key, value, key in posting.bareMetadata))
                        }
                        row.comment = posting.comment
                        row.notesBefore = posting.notesBefore
                    },
                )
            }
            while (rows.size < 2) rows.add(PostingRow())
            prefilled = true
            onPrefillApplied()
        }
    }

    // At large text two fields side by side stop fitting; the flag goes under the date.
    val large = LocalDensity.current.fontScale > 1.3f

    // The link the pages will make, shown before the save that makes it. The preview
    // used to leave it out until the moment of saving, so a person who had just scanned a
    // receipt saw an entry with nothing connecting it to one. The name is the real one --
    // a hash of the PDF these pages assemble into, the same bytes the save files.
    val pendingAccount = rows.firstOrNull { it.account.isNotBlank() }?.account?.trim().orEmpty()
    val plannedPaths by androidx.compose.runtime.produceState(
        emptyList<String>(),
        capture?.pages,
        attachedFiles,
        pendingAccount,
        documentName,
    ) {
        value = if (capture == null || !hasDocuments) {
            emptyList()
        } else {
            kotlinx.coroutines.delay(PLANNED_NAME_DEBOUNCE_MS)
            capture.plannedPaths(pendingAccount, documentName.trim().ifBlank { null })
        }
    }
    val previewDraft = withDocuments(draft, plannedPaths)

    // What the form showed at the moment Save was tapped, held until the save is over.
    // Saving files the pages first, and filing empties the capture, so the form
    // recomputed itself with no pages mid-save: the `document:` line left the preview and
    // "Save entry with 1 page" became a greyed "Save entry", as if the pages had been lost.
    var saving by remember { mutableStateOf<SavingForm?>(null) }
    // The preview of the save that just succeeded, kept while the form still holds exactly what was
    // saved. Once the pages are filed the capture is empty, so the live preview loses the
    // `document:` lines they made -- for the moment before the form closes or clears, the entry
    // that had just been written looked as if it had been written without its document.
    var savedPreview by remember { mutableStateOf<String?>(null) }
    var retryable by remember { mutableStateOf(false) }
    LaunchedEffect(saveState) {
        if (saving != null && saveState != null && !saveState.startsWith("Saving")) {
            savedPreview = if (saveState.startsWith("Saved")) saving?.preview else null
            saving = null
            // A save that did not happen can be tried again as it stands. The duplicate
            // guard below is for a save that *did* (invariant 6).
            retryable = !saveState.startsWith("Saved")
        }
    }

    // Invariant 6: a retried write never creates a duplicate entry. `ProjectWriter.append`
    // cannot enforce that on its own, so the guard is the draft itself: once this exact
    // draft has been submitted the button is disabled, and it comes back the moment any
    // field changes.
    var submitted by remember { mutableStateOf<TransactionDraft?>(null) }
    LaunchedEffect(retryable) {
        if (retryable) {
            submitted = null
            retryable = false
        }
    }

    // Pages photographed, or files attached, and not yet filed: filed by
    // saving the entry.
    val documentAccount = rows.firstOrNull { it.account.isNotBlank() }?.account.orEmpty()
    val documentsWord = documentsLabel(pageCount, attachedFiles.size)

    // The form scrolls; its save area does not (FormSaveBar).
    Column(modifier = modifier.fillMaxSize()) {
    Column(
        // No `imePadding()` here: the Scaffold above insets the whole app for the keyboard,
        // and doing it again shrank the scrolling viewport a second time -- a blank band
        // above the keyboard, and a last field that could not be scrolled into view.
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.snug, bottom = Measure.section),
    ) {
        // Said before anything is changed, not after: the entry was written by hand, and
        // saving it through the form lines it up the way the app writes entries.
        if (editing?.reformats == true) {
            Notice(NoticeTone.QUIET, modifier = Modifier.padding(bottom = Measure.gap)) {
                Text(
                    "This entry was written by hand. Saving it here keeps every word and " +
                        "figure, and lines it up the way the app writes entries.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        val keptComments = (if (headerComment != null) 1 else 0) + leadingNotes.size + trailingNotes.size +
            rows.sumOf { it.notesBefore.size + (if (it.comment != null) 1 else 0) }
        if (editing != null && keptComments > 0) {
            Notice(NoticeTone.QUIET, modifier = Modifier.padding(bottom = Measure.gap)) {
                Text(
                    if (keptComments == 1) {
                        "This entry has a comment. It is kept where it is when you save."
                    } else {
                        "This entry has $keptComments comments. They are kept where they are when you save."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Templates come first because that is when they are useful: before anything has
        // been typed. Applying one fills the form and stops there — the entry is still
        // previewed and validated like any other (docs/ux.md 4).
        if (templates.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Measure.snug),
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
            FormGap()
        }

        // ---- When and who -------------------------------------------------------------
        FormGroup {
            val pendingChip: @Composable () -> Unit = {
                // One chip rather than a two-way segmented row: cleared is what nearly every
                // entry is, so the control that says otherwise is the one that needs to exist.
                // A chip stays the height of a finger rather than of a field.
                //
                // The label is the *state*, with the entry's own mark beside it; the spoken
                // description adds the action (device audit 2026-10-10: the chip read "Pending"
                // on a cleared entry, naming what a tap would do rather than what was true). A
                // flag the app does not interpret -- `P` -- is shown as it is, and the
                // description says plainly that a tap replaces it.
                val mark = markForFlag(flag)
                FilterChip(
                    selected = mark != LedgerMark.CLEARED,
                    onClick = { flag = if (flag == "!") "*" else "!" },
                    label = { Text(describeFlag(flag)) },
                    leadingIcon = { StampMark(mark, flag = flag, decorative = true) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = when (mark) {
                                LedgerMark.PENDING -> "Pending transaction. Tap to mark cleared"
                                LedgerMark.FLAGGED -> "Flagged $flag. Tap to mark pending instead"
                                else -> "Cleared transaction. Tap to mark pending"
                            }
                        },
                )
            }
            if (large) {
                DateField(
                    value = date,
                    onValueChange = { date = it },
                    modifier = Modifier.fillMaxWidth(),
                    today = today,
                )
                pendingChip()
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Measure.gap),
                ) {
                    DateField(
                        value = date,
                        onValueChange = { date = it },
                        modifier = Modifier.weight(1f),
                        today = today,
                    )
                    pendingChip()
                }
            }

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
                        // Grow to fit: reusing a three-posting entry into two rows would drop
                        // a posting silently, which is exactly the quiet loss the app must not do.
                        while (rows.size < last.postings.size) rows.add(PostingRow())
                        // And shrink back: rows left over from a longer entry would sit under
                        // the reused ones as blanks the person has to notice and remove.
                        while (rows.size > maxOf(2, last.postings.size) &&
                            rows.last().account.isBlank() && rows.last().amount.isBlank()
                        ) {
                            rows.removeAt(rows.lastIndex)
                        }
                        last.postings.forEachIndexed { index, posting ->
                            val row = rows[index]
                            row.account = posting.account
                            // With its sign. The absolute value was taken here, so a
                            // refund or a transfer out came back as money in, and the preview
                            // wrote the opposite of what was reused.
                            row.amount = posting.amount?.number?.toPlainString().orEmpty()
                            // The commodity goes in the box, not only into the text: the
                            // preview was right because it fell back to the default, while the
                            // field that says what it is stayed empty.
                            row.commodity = posting.amount?.currency.orEmpty()
                            val details = CommodityDetails.textOf(posting.cost, posting.price)
                            row.details = details
                            row.showDetails = details.isNotBlank()
                        }
                        // Only the first posting keeps an amount; the rest stay elided, so
                        // Beancount recomputes them rather than us reusing stale numbers. Their
                        // commodity stays, because it says what the elided posting is in.
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

            // The entry's own extras, right under the words that describe it. They sat
            // below the postings, where a tag for the whole entry read as belonging to the
            // last posting, and reaching them meant scrolling past every amount. The row stays
            // where it is as fields open beneath it, so the next tap lands where the last did.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Measure.snug),
                // A gap between lines as well, so a wrapped row does not sit against the one above.
                verticalArrangement = Arrangement.spacedBy(Measure.snug),
            ) {
                SmallAction(
                    text = if (tagRows.isEmpty()) "Tag" else "Another tag",
                    describe = "Add a tag to this transaction",
                    onClick = { tagRows.add(WordRow()) },
                )
                SmallAction(
                    text = if (linkRows.isEmpty()) "Link" else "Another link",
                    describe = "Add a link to this transaction",
                    onClick = { linkRows.add(WordRow()) },
                )
                SmallAction(
                    text = if (metaRows.isEmpty()) "Detail" else "Another detail",
                    describe = if (metaRows.isEmpty()) {
                        "Add a detail to this transaction"
                    } else {
                        "Add another detail to this transaction"
                    },
                    onClick = { metaRows.add(MetadataRow()) },
                )
                if (capture != null) {
                    SmallAction(
                        text = if (attachedFiles.isEmpty()) "Attach file" else "Another file",
                        describe = "Attach a file to this transaction",
                        onClick = { pickFile.launch(arrayOf("application/pdf", "image/*")) },
                    )
                }
                if (capture != null && capture.pages.isEmpty) {
                    SmallAction(
                        text = "Scan document",
                        describe = "Scan a document for this transaction",
                        onClick = {
                            if (cameraAllowed) {
                                capture.onStartScanning()
                            } else {
                                askForCamera.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                }
            }
            if (tagRows.isNotEmpty()) {
                WordEditor(
                    rows = tagRows,
                    kind = "Tag",
                    sigil = "#",
                    suggestions = vocabulary.tags,
                )
            }
            if (linkRows.isNotEmpty()) {
                WordEditor(
                    rows = linkRows,
                    kind = "Link",
                    sigil = "^",
                    suggestions = vocabulary.links,
                )
            }
            MetadataEditor(
                rows = metaRows,
                label = "These details are about the whole transaction.",
                describe = { "transaction" },
            )
            if (capture != null && hasDocuments) {
                AttachDocumentSection(
                    capture = capture,
                    attached = metaRows.filter { it.key.startsWith("document") }.map { it.value },
                    onNameChange = { documentName = it },
                )
            }
        }

        FormGap()

        // ---- The postings ---------------------------------------------------------------
        LedgerHeading("Postings")
        FormGroup {
            rows.forEachIndexed { index, row ->
                if (index > 0) LedgerHairline(Modifier.padding(vertical = Measure.tight))
                PostingEditor(
                    index = index,
                    row = row,
                    vocabulary = vocabulary,
                    defaultCommodity = commodity.trim(),
                    removable = rows.size > 2,
                    onRemove = { rows.removeAt(index) },
                )
            }
            SmallAction(
                text = "Add posting",
                describe = "Add another posting",
                onClick = { rows.add(PostingRow()) },
            )
        }

        // The imbalance is information, not an error: an elided posting is the normal way
        // to let Beancount finish the arithmetic.
        LedgerReveal(visible = residuals.isNotEmpty(), label = "residual") {
            val shownResiduals = remember(residuals) { residuals }
            val elided = draft.postings.count { it.isElided }
            Notice(
                if (elided == 1) NoticeTone.QUIET else NoticeTone.ATTENTION,
                modifier = Modifier.padding(top = Measure.gap),
            ) {
                Text(
                    // The residual is what the written postings add up to; the posting left
                    // blank receives its opposite, which is the figure the sentence names
                    // (-300.00 on one side must put 300.00 on the other, not -300.00).
                    text = if (elided == 1) {
                        "Beancount will put ${shownResiduals.joinToString(", ") { format.format(-it) }} " +
                            "on the posting with no amount."
                    } else {
                        "Does not balance: ${shownResiduals.joinToString(", ") { format.format(it) }}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        FormGap()

        // The promise the form makes, and the one moment in it that looks like the file: the
        // lines about to be written, in the source face, hung from the app's rule.
        RuledColumn {
            LedgerHeading("Will be written as")
            Text(
                // The entry, with the `document:` line its attachments will carry. No separate
                // `document` directive: an attachment belongs to this entry.
                text = saving?.preview
                    ?: savedPreview?.takeIf { draft == submitted }
                    ?: previewDraft.render(),
                style = LedgerType.source,
                // One line per line of the entry, however long: a long account name wrapped
                // under itself and made the preview look different from what is written. The box
                // scrolls sideways instead.
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    // The signature. When a save *commits*, the lines that were written
                    // are swept once by the app's rule. Keyed on the commit count, not on the
                    // message: the message changes to "Saving…" first and again to the
                    // result, and keying on it swept twice and swept refusals too.
                    .filedSweep(
                        key = committed.takeIf { it > committedBefore },
                        colour = BeancountTheme.ledger.rule,
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(Measure.indent)
                    .semantics { contentDescription = "Preview of the entry to be saved" },
            )
        }

        // Secondary actions stay with the form; Save is on the bar below it.
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(top = Measure.snug),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            OutlinedButton(
                onClick = { namingTemplate = true },
                // Also where saved templates are deleted, so it opens whenever there are any --
                // the form no longer arrives with accounts filled in, and gating it on one would
                // hide the only way to remove a template from an empty form.
                enabled = rows.any { it.account.isNotBlank() } || templates.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "Save this entry as a template" },
            ) { Text("Save as template") }

            // The text editor is still there for an entry being edited: for a layout of one's
            // own, or a change the fields cannot make.
            editing?.let { session ->
                OutlinedButton(
                    onClick = session.onEditAsText,
                    modifier = Modifier.semantics { contentDescription = "Edit this entry as text instead" },
                ) { Text("Edit as text instead") }
            }
        }

        if (namingTemplate) {
            TemplateDialog(
                draft = draft,
                fallbackCurrency = commodity.trim(),
                existing = templates,
                onSave = { template ->
                    onSaveTemplate(template)
                    namingTemplate = false
                },
                onDelete = onDeleteTemplate,
                onDismiss = { namingTemplate = false },
            )
        }
    }

    // Not before anything has been typed: an empty form is not wrong, it is empty, and
    // greeting it with "needs at least two postings" is noise the person did nothing to cause.
    // The button stays disabled either way.
    FormSaveBar(
        problems = if (dirty) unreadableAmounts + problems.map { it.describe() } else emptyList(),
        // What the write did, arriving beside the button that did it.
        message = saveState,
        problemActions = {
            val unopened = problems.filterIsInstance<DraftProblem.UnknownAccount>().map { it.account }.distinct()
            if (onOpenAccount != null && dirty) {
                unopened.forEach { account ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            // Opened on the entry's own date, or today when that is later: an account
                            // cannot be used before the day it opens.
                            val day = draft.date.takeIf { DATE_SHAPE.matches(it) } ?: today.toString()
                            onOpenAccount(
                                io.github.pintofi.beanbook.core.domain.DirectiveDraft.Open(
                                    date = minOf(day, today.toString()),
                                    account = account,
                                    currencies = emptyList(),
                                ),
                            )
                        },
                        modifier = Modifier.semantics { contentDescription = "Open the account $account" },
                    ) { Text("Open $account") }
                }
            }
        },
    ) {
        Button(
            onClick = {
                submitted = draft
                val what = if (editing != null) "Saving changes" else "Saving entry"
                saving = SavingForm(
                    preview = previewDraft.render(),
                    label = (if (hasDocuments) "$what with $documentsWord" else what) + "…",
                )
                // Pages first, then the entry that names them: `fava.plugins.link_documents`
                // reports "Document not found" for an entry whose `document:` metadata has no
                // `document` directive to link to, and a new error rolls the save back.
                if (capture != null && hasDocuments) {
                    capture.onFile(documentAccount, documentName.trim().ifBlank { null }) { paths ->
                        onSave(withDocuments(draft, paths))
                    }
                } else {
                    onSave(draft)
                }
            },
            enabled = saving == null && problems.isEmpty() && unreadableAmounts.isEmpty() && draft != submitted,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            val inFlight = saving
            if (inFlight != null) {
                // Working, and saying so with more than a word: a save that files pages
                // and re-reads a large ledger takes seconds.
                LedgerLoadingIndicator(
                    modifier = Modifier.padding(end = Measure.snug),
                    size = 20.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(inFlight.label)
            } else {
                val what = if (editing != null) "Save changes" else "Save entry"
                Text(if (hasDocuments) "$what with $documentsWord" else what)
            }
        }
    }
    }
}

/**
 * What saving means when the form holds an entry that is already in the file.
 *
 * @property original the entry as the form received it.
 * @property reformats whether saving rewrites the entry's spacing into the app's layout.
 * @property onEditAsText leaves the form for the entry's text.
 */
data class EntryEditing(
    val original: TransactionDraft,
    val reformats: Boolean,
    val onEditAsText: () -> Unit,
)

/**
 * [draft] with a `document:` key for each filed path, by file name: the plugin matches a
 * `document:` value against the basename of each `document` directive.
 */
private fun withDocuments(draft: TransactionDraft, paths: List<String>): TransactionDraft =
    paths.fold(draft) { current, path ->
        val key = BeancountText.nextDocumentKey(current.metadata.keys)
        current.copy(metadata = current.metadata + (key to path.substringAfterLast('/')))
    }

/** "2 pages", "1 file", "1 page and 2 files". */
internal fun documentsLabel(pages: Int, files: Int): String = listOfNotNull(
    pages.takeIf { it > 0 }?.let { "$it page${if (it == 1) "" else "s"}" },
    files.takeIf { it > 0 }?.let { "$it file${if (it == 1) "" else "s"}" },
).joinToString(" and ")

/** Long enough that typing a document's name does not assemble the PDF on every letter. */
private const val PLANNED_NAME_DEBOUNCE_MS = 350L

/** A group of related fields: tight inside, so the group reads as one thing. */
@Composable
private fun FormGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Measure.snug), content = content)
}

/** The space between two groups: wider than inside one, which is what makes them groups. */
@Composable
private fun FormGap() {
    androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = Measure.section))
}

/**
 * An action that adds something to the form: Material's own outlined button with a plus, so it reads
 * as something to press without competing with Save, which is filled. It is the same button as on
 * every other page, in the standard size and with the standard icon padding; several sit on one line,
 * so the row that holds them spaces its lines as well as its items.
 */
@Composable
private fun SmallAction(text: String, describe: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.material3.ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.semantics { contentDescription = describe },
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize),
        )
        androidx.compose.foundation.layout.Spacer(
            Modifier.size(androidx.compose.material3.ButtonDefaults.IconSpacing),
        )
        Text(text)
    }
}

/**
 * Explanation for a field or a group of fields, below it and inset to start where the text inside
 * the field starts.
 *
 * One place and one look for every explanation in this form. It had two: Material's own
 * supporting text under the commodity-details field, and a heading above the metadata rows,
 * which sat at different distances from the edge and read as two different kinds of thing.
 */
@Composable
private fun FieldCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, top = 4.dp),
    )
}

/**
 * Where a remove button sits beside a text field: the top of the row, pushed down so its centre
 * is the centre of the field's box.
 *
 * A row centred its button on the whole field, and a field with a hint or an error under it is
 * taller than its box, so the button slid down to the middle of box plus hint and no longer lined
 * up with anything. An outlined field is 56dp tall with 8dp above it for the floating label; the
 * button is 48dp, so (8 + 28) - 24 = 12dp puts the two centres together whatever sits below.
 */
private val RemoveButtonInset = 12.dp

/**
 * A remove button that looks like one.
 *
 * It was a "×" typed into a text button: a character the size of the label text, the colour of
 * a link, easy to miss and easy to hit instead of the field beside it. A tonal icon button is
 * the full 48dp target Material asks for, with an icon that reads as removal at a glance.
 */
@Composable
private fun RemoveButton(describe: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = describe },
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Filled.Close,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One posting: the account on its own line, the amount and commodity beneath it.
 *
 * The three used to share one line, which left the account -- the longest thing typed -- a
 * third of the width and the commodity a box too narrow for its own label. Top to bottom is
 * also how a posting reads in the file: where the money is, then how much of what.
 */
@Composable
private fun PostingEditor(
    index: Int,
    row: PostingRow,
    vocabulary: LedgerVocabulary,
    /** What an empty commodity box stands for: the ledger's operating currency, usually. */
    defaultCommodity: String,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
        Row(verticalAlignment = Alignment.Top) {
            SuggestingField(
                value = row.account,
                onValueChange = { row.account = it },
                label = "Account ${index + 1}",
                suggestions = vocabulary.accounts,
                describe = "Account for posting ${index + 1}",
                modifier = Modifier.weight(1f),
            )
            if (removable) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(Measure.tight))
                RemoveButton("Remove posting ${index + 1}", Modifier.padding(top = RemoveButtonInset), onRemove)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalAlignment = Alignment.Top,
        ) {
            AmountField(
                value = row.amount,
                onValueChange = { row.amount = it },
                placeholder = if (index == 0) null else "optional",
                // Even halves: at 3:2 the commodity box was too narrow for "Commodity · PKR",
                // and the default it falls back to was cut off its own label.
                modifier = Modifier.weight(1f),
            )
            SuggestingField(
                value = row.commodity,
                onValueChange = { row.commodity = it },
                label = "Commodity",
                suggestions = vocabulary.currencies,
                describe = "Commodity for posting ${index + 1}",
                transform = { it.uppercase().filterNot(Char::isWhitespace) },
                // The commodity an empty box falls back to, shown in it (device audit
                // 2026-10-10: the box was blank although the ledger declares one, so nothing on
                // screen said what the amount would be in).
                placeholder = defaultCommodity.ifBlank { null },
                modifier = Modifier.weight(1f),
            )
        }
        if (row.showDetails) {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = row.details,
                        onValueChange = { row.details = it },
                        label = { Text("Commodity details") },
                        placeholder = { Text("{400.00 PKR} @ 120.05 PKR") },
                        singleLine = true,
                        textStyle = LedgerType.source,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Commodity details for posting ${index + 1}" },
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(Measure.tight))
                    RemoveButton(
                        "Remove commodity details from posting ${index + 1}",
                        Modifier.padding(top = RemoveButtonInset),
                    ) {
                        row.details = ""
                        row.showDetails = false
                    }
                }
                FieldCaption("Written after the amount, as the file writes a lot or price.")
            }
        }
        MetadataEditor(
            rows = row.metadata,
            label = "These details are about this posting only.",
            describe = { "posting ${index + 1}" },
            showAddButton = false,
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            if (!row.showDetails) {
                SmallAction(
                    text = "Commodity details",
                    describe = "Add commodity details to posting ${index + 1}",
                    onClick = { row.showDetails = true },
                )
            }
            SmallAction(
                text = if (row.metadata.isEmpty()) "Detail" else "Another detail",
                describe = if (row.metadata.isEmpty()) {
                    "Add a detail to posting ${index + 1}"
                } else {
                    "Add another detail to posting ${index + 1}"
                },
                onClick = { row.metadata.add(MetadataRow()) },
            )
        }
    }
}

/**
 * Tags or links, one box each.
 *
 * Beancount writes them after the narration -- `#trip-2026` and `^invoice-4411` -- and neither
 * may contain a space, so a space typed here is dropped as it is typed rather than refused at
 * save. The sigil is accepted and stripped, because people type it. Suggestions are the ones
 * the ledger already uses, which is what keeps a tag from being spelled three ways.
 */
@Composable
private fun WordEditor(
    rows: MutableList<WordRow>,
    kind: String,
    sigil: String,
    suggestions: List<String>,
) {
    rows.forEachIndexed { index, word ->
        Row(verticalAlignment = Alignment.Top) {
            SuggestingField(
                value = word.value,
                onValueChange = { word.value = it },
                label = if (rows.size == 1) kind else "$kind ${index + 1}",
                suggestions = suggestions,
                describe = "$kind ${index + 1}",
                transform = { typed -> typed.removePrefix(sigil).filterNot(Char::isWhitespace) },
                trailingIcon = null,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(Measure.tight))
            RemoveButton(
                "Remove ${kind.lowercase()} ${index + 1}",
                Modifier.padding(top = RemoveButtonInset),
            ) { rows.removeAt(index) }
        }
    }
}

/**
 * The page editor, over the whole screen, from inside the entry form.
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
            PageReviewScreen(
                pages = capture.pages,
                fileFor = capture.fileFor,
                startAt = startAt,
                review = capture.review,
                onDone = onDone,
                onAddPage = { onDone(); capture.onStartScanning() },
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
    // currency field already saying USD.
    val offered = values.filter { it != current.trim() }
    if (offered.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // No cap: this row scrolls horizontally, so taking six hid matches the vocabulary had
        // already found rather than saving any space.
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
 * its scope (destructive actions are named and undoable): the row says which template goes, and nothing else in
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
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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
                            OutlinedButton(
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
            androidx.compose.material3.Button(
                onClick = { onSave(candidate) },
                enabled = problems.isEmpty(),
            ) { Text("Save template") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * Metadata pairs, on a transaction or on one posting.
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
    /** Whether this editor draws its own add button, or the form's action row does. */
    showAddButton: Boolean = false,
) {
    if (rows.isEmpty()) {
        if (showAddButton) {
            SmallAction(
                text = "Detail",
                describe = "Add a detail to ${describe()}",
                onClick = { rows.add(MetadataRow()) },
            )
        }
        return
    }

    rows.forEachIndexed { index, meta ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalAlignment = Alignment.Top,
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
            RemoveButton(
                "Remove detail ${index + 1} from ${describe()}",
                Modifier.padding(top = RemoveButtonInset),
            ) { rows.removeAt(index) }
        }
    }
    FieldCaption(label)
}

/**
 * Photograph a receipt while entering the transaction it belongs to.
 *
 * The pages are filed as soon as the user says so, before the transaction is saved, so the
 * ledger never names a file that is not there yet (invariant 5). The cost is that abandoning
 * the entry leaves the PDF on disk — visible in Organize as an unfiled document, where it can
 * be filed or deleted, rather than lost.
 */
@Composable
private fun AttachDocumentSection(
    capture: DocumentCaptureHandle,
    attached: List<String>,
    onNameChange: (String) -> Unit,
) {

    // What to call it, if the user wants to.
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

        // An action, drawn as one. Text that happens to respond to a tap reads as a
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

        // Attached files, each with its own way out.
        val files by capture.attachedFiles.collectAsState()
        files.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                RemoveButton("Remove the attached file $name") { capture.onRemoveFile(index) }
            }
        }

        if (!capture.pages.isEmpty || files.isNotEmpty()) {
            run {
                // One row of thumbnails and one way in, rather than four text buttons under
                // every page: tapping a page opens the editor at it, where rotating,
                // cropping and re-rendering are done on the page itself.
                if (!capture.pages.isEmpty) Row(
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
                                page = page,
                            )
                        }
                    }
                }
                if (!capture.pages.isEmpty) {
                    Text(
                        "${capture.pages.size} page${if (capture.pages.size == 1) "" else "s"}. " +
                            "Tap one to rotate, crop or change how it is rendered.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                // No button. Filing is part of saving the entry: a receipt photographed
                // *for this transaction* belongs to it, and asking the user to press a second
                // button to make that true was a step whose only possible outcome, if they
                // missed it, was a page filed under nothing.
                Text(
                    "${documentsLabel(capture.pages.size, files.size).replaceFirstChar { it.uppercase() }} " +
                        "will be saved with this entry and linked from it when you save.",
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

/** The preview and button label the form showed when Save was tapped. */
private data class SavingForm(val preview: String, val label: String)

private val DATE_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")
