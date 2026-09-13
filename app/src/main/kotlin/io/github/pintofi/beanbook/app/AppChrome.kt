package io.github.pintofi.beanbook.app

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.entitlement.Entitlement
import io.github.pintofi.beanbook.core.entitlement.Grant
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.RuledColumn
import io.github.pintofi.beanbook.app.ui.NoticeTone
import io.github.pintofi.beanbook.app.ui.Notice
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
// The smaller pieces the shell is built from: the rail and top bar, the banners, and the
// states a surface falls back to. Split out of MainActivity.kt; see LedgerApp.kt.
/**
 * Why the remote reports are not on screen.
 *
 * A WebView that refuses a load shows a blank white page, which is indistinguishable from a
 * server with nothing to say. This is the difference.
 */
@Composable
internal fun RemoteReportProblem(message: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Text(
            "Reports could not be loaded",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        androidx.compose.material3.Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A locked feature says what it is, which tier unlocks it, and nothing else. No countdown,
 * no modal, no nagging (docs/ux.md §10) — and the user's data is never involved.
 */
@Composable
internal fun LockedFeature(
    title: String,
    tier: String,
    onContribute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Part of the $tier tier.")
        Text(
            "The ledger, journal, balances, raw editing, import and export stay free.",
            style = MaterialTheme.typography.bodySmall,
        )
        // The route decides the wording: the Play edition sells, the Expert edition takes a
        // pasted key. Hardcoding "Enter key" showed a Play user a control that is not there.
        androidx.compose.material3.TextButton(onClick = onContribute) {
            Text(io.github.pintofi.beanbook.app.billing.currentPurchaseRoute().label)
        }
    }
}

/** Which of the three layouts in docs/ux.md §7 the current window calls for. */
internal enum class PaneLayout { COMPACT, MEDIUM, EXPANDED }

/**
 * The navigation rail, for windows too wide for a bottom bar.
 *
 * At 600dp and up a bottom bar wastes the shape of the screen: it puts the destinations
 * along the short edge of a landscape window and leaves a strip of empty space under a
 * ledger that would rather be taller. The rail moves them to the side, which is also where
 * a hand holding a tablet already is.
 */
@Composable
internal fun LedgerNavigationRail(selected: Surface, onSelect: (Surface) -> Unit) {
    androidx.compose.material3.NavigationRail {
        Surface.entries.forEach { surface ->
            androidx.compose.material3.NavigationRailItem(
                selected = selected == surface,
                onClick = { onSelect(surface) },
                // The label used to go in the icon slot, because these destinations had no
                // icons and an empty slot drew the selection indicator around nothing while
                // the label sat outside it. They have icons now, so the slots hold what they
                // are for and the rail matches the bar.
                icon = { Icon(surface.icon, contentDescription = null) },
                label = { Text(surface.label) },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * The app bar: whose ledger this is, and whether it is healthy.
 *
 * ux.md §2 asks for project name and health here, and the reason is that neither was
 * anywhere before — the open project's name appeared only inside the Projects screen, so
 * anyone with two ledgers had no way to tell at a glance which one they were adding an
 * entry to.
 *
 * Inside More it doubles as a section header with a back arrow, because a list you cannot
 * get back to is a list you visit once.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LedgerTopBar(
    projectName: String,
    health: String?,
    sectionTitle: String?,
    onBack: () -> Unit,
    onSwitchProject: () -> Unit,
    /**
     * Whether the entry form is what is on screen (B61).
     *
     * The form is a task, not a destination: it is opened by the action button, it has no
     * place in the bar of destinations, and the way out of it is a close, not a step back
     * through a hierarchy. Material calls this a full-screen dialog, and the difference from
     * a section is exactly the icon in this corner -- a cross closes something you opened,
     * an arrow walks back through where you have been.
     */
    adding: Boolean = false,
    /** The form is open on an entry already in the file (B79). */
    editing: Boolean = false,
) {
    androidx.compose.material3.TopAppBar(
        // The band: a tint one step off the paper, so the chrome reads as a strip laid over
        // the page rather than as more page (B74). The rule below starts here and continues
        // down the surface, which is what ties the chrome to the ledger under it.
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            // The name of the open ledger *is* the control that switches it. A separate
            // "Switch" button said the same thing twice -- the name, and a word meaning
            // "change the name" -- and spent the right-hand corner of every screen on it.
            // The caret is what says the title is a control; the description says what
            // tapping it does, so nothing depends on noticing the caret.
            val switchable = !adding && sectionTitle == null
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = if (switchable) {
                    Modifier
                        .clip(io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.control)
                        .clickable(role = Role.Button, onClick = onSwitchProject)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Switch project. Currently open: $projectName"
                        }
                } else {
                    Modifier
                },
            ) {
                // The rule, in the chrome. Two rules where the ledger is healthy and one
                // where it is not: the same vocabulary as the mark beside an entry, so the
                // shape says "in balance" before the words under it are read.
                if (switchable) {
                    HealthRule(healthy = health?.contains("no errors") == true)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                }
                Column {
                    // The title changes when the app moves, and it changes *as* the app
                    // moves: a section's name rises into place and the project's name settles
                    // back, so the bar and the page are telling the same story (B76).
                    io.github.pintofi.beanbook.app.ui.LedgerContent(
                        targetState = when {
                            editing -> "Edit entry"
                            adding -> "New entry"
                            sectionTitle != null -> sectionTitle
                            else -> projectName
                        },
                        label = "title",
                        move = { _, _ ->
                            io.github.pintofi.beanbook.app.ui.Move.label(deeper = !switchable)
                        },
                    ) { title ->
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                    }
                    io.github.pintofi.beanbook.app.ui.LedgerReveal(
                        visible = switchable && health != null,
                        label = "health",
                    ) {
                        val shown = remember(health) { health }
                        Text(
                            shown.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (switchable) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        navigationIcon = {
            // The corner changes with the page rather than a frame before it: a cross for a
            // form you opened, an arrow for a section you walked into, and nothing at the top
            // level. Cross-fades in place, because the two icons mean different things and
            // sliding one into the other would imply they were the same control (B76).
            io.github.pintofi.beanbook.app.ui.LedgerContent(
                targetState = when {
                    adding -> NavigationAffordance.CLOSE
                    sectionTitle != null -> NavigationAffordance.BACK
                    else -> NavigationAffordance.NONE
                },
                label = "navigationIcon",
                move = { _, _ -> io.github.pintofi.beanbook.app.ui.Move.none },
            ) { affordance ->
                when (affordance) {
                    NavigationAffordance.CLOSE -> IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Close the entry form"
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }

                    // An arrow, which is what a person expects in this corner. AutoMirrored,
                    // so it points the other way in a right-to-left layout.
                    NavigationAffordance.BACK -> IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back to More" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }

                    NavigationAffordance.NONE -> Unit
                }
            }
        },
    )
}

/**
 * The two rules from the launcher icon, at the size of a glyph, saying whether the open
 * ledger parses (B74).
 *
 * Equal rules means every entry balanced and the file loaded. A short second rule means
 * Beancount had something to say about it. The line of text beside this says the same thing
 * in words and in numbers -- this is the glance version, and it is why the app's own mark is
 * a pair of rules in the first place.
 */
@Composable
private fun HealthRule(healthy: Boolean) {
    val ledger = io.github.pintofi.beanbook.app.ui.theme.BeancountTheme.ledger
    val colour = if (healthy) ledger.rule else ledger.pending
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .width(14.dp)
            .androidHeight()
            .semantics {
                contentDescription = if (healthy) "In balance" else "Needs attention"
            },
    ) {
        val thickness = 2.5.dp.toPx()
        val gap = 6.dp.toPx()
        val top = (size.height - (thickness * 2 + gap)) / 2f
        drawRect(
            color = colour,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, thickness),
        )
        drawRect(
            color = colour,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top + thickness + gap),
            size = androidx.compose.ui.geometry.Size(
                if (healthy) size.width else size.width * 0.45f,
                thickness,
            ),
        )
    }
}

/** The glyph's own height: tall enough to carry two rules and their gap, no taller. */
private fun Modifier.androidHeight(): Modifier = this.then(Modifier.height(20.dp))

/**
 * One line saying whether the ledger is in good order.
 *
 * Parse errors are an ordinary state for a plain-text ledger, not a failure — you get them
 * halfway through typing an entry in the raw editor — so they are reported as a count, in
 * the same quiet line as everything else, and never as an error screen (docs/ux.md §2).
 */
internal fun ledgerHealth(status: EngineStatus, ledger: LedgerSnapshot?): String? = when {
    ledger == null && status is EngineStatus.Failed -> "Engine unavailable"
    ledger == null -> "Loading…"
    ledger.errors.isEmpty() -> "${ledger.entryCount} entries · no errors"
    ledger.errors.size == 1 -> "${ledger.entryCount} entries · 1 error"
    else -> "${ledger.entryCount} entries · ${ledger.errors.size} errors"
}

/**
 * The sections behind More, as a list.
 *
 * Each carries a line of explanation: "Mirror" and "Remote" mean nothing on their own, and a
 * list of bare nouns makes people open things to find out what they are.
 */
@Composable
internal fun MoreMenu(
    entitlement: Entitlement,
    onContribute: () -> Unit,
    onOpen: (MoreSection) -> Unit,
    /** Ends the engine and the app and opens it again (B77). */
    onRestartApp: () -> Unit = {},
) {
    var confirmingRestart by remember { androidx.compose.runtime.mutableStateOf(false) }
    val descriptions = mapOf(
        MoreSection.PROJECTS to "Open, create, import or delete a ledger",
        MoreSection.FILES to "Read and edit the ledger files themselves",
        MoreSection.DOCUMENTS to "Receipts and statements filed against entries",
        MoreSection.BACKUP to "Write a backup, restore one, or schedule them",
        MoreSection.MIRROR to "Keep a copy in a folder you choose, kept in step",
        MoreSection.REMOTE to "Use a Fava server you host yourself",
        MoreSection.PLUGINS to "Extra checks and transformations, from files you supply",
        MoreSection.APPEARANCE to "Theme, dark mode and colours",
        MoreSection.SCRIPTS to "Run a Python script once against the open ledger",
        MoreSection.LICENCES to "This app's licence, and everything it is built on",
        MoreSection.DIAGNOSTICS to "What the app recorded, for reporting a problem",
        MoreSection.ABOUT to "Version, licence and contact",
    )

    // Every section is in both editions. Updates used to be the exception -- the Play build
    // has no self-update anything, by policy, so the entry would have been a dead end -- but
    // neither edition self-updates now, and both have a version worth showing.
    val sections = MoreSection.entries

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // The entitlement is stated here, once, instead of above every screen. It is a fact
        // worth being able to find, not one worth being told on the way to everything else
        // -- a line the user sees on every tab is a line they stop reading (B27).
        TrialCard(entitlement, onClick = onContribute)
        sections.forEach { entry ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(entry.label) },
                supportingContent = descriptions[entry]?.let { { Text(it) } },
                // An anchor for the eye. Eleven rows of two-line text read as one block, and
                // the row you want is found by reading each label rather than by recognising
                // a shape. Decorative in the semantics sense -- the description beside it
                // already names the row -- so it carries no contentDescription of its own.
                leadingContent = {
                    Icon(
                        iconFor(entry),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier
                    .clickable(role = Role.Button) { onOpen(entry) }
                    .padding(horizontal = Measure.tight)
                    .semantics {
                        contentDescription = "${entry.label}. ${descriptions[entry].orEmpty()}"
                    },
            )
            // Indented to where the labels start rather than cut edge to edge, so the list
            // reads as ruled paper and the icons sit in a margin of their own.
            io.github.pintofi.beanbook.app.ui.LedgerHairline(
                Modifier.padding(start = Measure.gutter + Measure.chapter),
            )
        }
        // Last, and an action rather than a section: it does not open a screen, it starts the
        // app again (B77).
        androidx.compose.material3.ListItem(
            headlineContent = { Text("Restart the app") },
            supportingContent = { Text("Stop the engine and open the app again, reading everything fresh") },
            leadingContent = {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier
                .clickable(role = Role.Button) { confirmingRestart = true }
                .padding(horizontal = Measure.tight)
                .semantics {
                    contentDescription = "Restart the app. Stops the engine and opens the app again"
                },
        )
    }

    if (confirmingRestart) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingRestart = false },
            title = { Text("Restart the app?") },
            text = {
                Text(
                    "The engine and the app close and open again. Your ledger is unchanged; " +
                        "anything typed into a form and not saved is lost.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        confirmingRestart = false
                        onRestartApp()
                    },
                    modifier = Modifier.semantics { contentDescription = "Restart the app now" },
                ) { Text("Restart") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmingRestart = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** What the top-left corner offers: nothing, a way back, or a way out of a form. */
private enum class NavigationAffordance { NONE, BACK, CLOSE }

/**
 * Something had to be put right before the app could start, and the user is told what.
 *
 * Dismissible, and only ever shown when something actually happened: a banner that appears
 * on every launch is a banner nobody reads. It is not a dialog either — the app has already
 * recovered by the time this is on screen, so there is nothing to decide.
 */
@Composable
internal fun RecoveryBanner(message: String, onDismiss: () -> Unit) {
    Notice(
        NoticeTone.ATTENTION,
        modifier = Modifier.padding(horizontal = Measure.gutter, vertical = Measure.snug),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = "Something was put right at startup: $message"
            },
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.semantics { contentDescription = "Dismiss this notice" },
        ) { Text("Got it") }
    }
}

/**
 * Where the entitlement stands, stated once in More rather than above every screen (B27).
 *
 * Deliberately heavier than a journal row: this is a status about the app, not a line of the
 * user's data, and it sits among a list of plain rows where it would otherwise disappear. It
 * It is a notice in the app's own vocabulary rather than a colour of its own, so it reads as
 * "notice" and not as "error" -- nothing here is wrong, and a trial running out is not a
 * failure.
 *
 * It replaced a line above every screen. A countdown that demands attention on the way to
 * everything else is the dark pattern this app promised not to have
 * (docs/distribution.md 4.7); told once and then available where it can be looked up is how
 * somebody actually keeps the fact.
 */
@Composable
internal fun TrialCard(entitlement: Entitlement, onClick: () -> Unit) {
    val (headline, detail) = when (val grant = entitlement.grant) {
        is Grant.Trial -> {
            val days = entitlement.trialDaysRemaining ?: 0
            val head = if (days <= 1) "Last day of your trial" else "$days days left in your trial"
            head to "Everything is unlocked. Tap to see what happens when it ends."
        }
        is Grant.TrialEnded ->
            "Your trial has ended" to
                "Your ledger is untouched and stays fully editable. Tap to see the extras."
        // The tier, not the email: the address a key was issued to belongs on Contribute
        // (docs/distribution.md 4.5), and this card is in any screenshot of More.
        is Grant.Key -> "${grant.tier.label} key" to "Tap to manage your key."
        // Named as a purchase rather than a key, because the two are restored and refunded
        // differently and the user needs to know which one they have.
        is Grant.Purchase -> "${grant.tier.label}" to "Tap to see what you have."
        Grant.Free -> return
    }
    // Quiet while the trial is running, and in the attention tone once it has ended, so the
    // one state that changes what the app offers is the one that speaks up.
    val tone = if (entitlement.grant is Grant.TrialEnded) NoticeTone.ATTENTION else NoticeTone.QUIET
    Notice(
        tone,
        modifier = Modifier
            .padding(horizontal = Measure.gutter, vertical = Measure.gap)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$headline. $detail" },
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LoadingOrError(
    status: EngineStatus,
    editionName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Measure.gutter, vertical = Measure.chapter),
        verticalArrangement = Arrangement.spacedBy(Measure.section),
    ) {
        RuledColumn {
            Text("Beanbook", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Edition: $editionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = Measure.tight)
                    .semantics { contentDescription = "Edition $editionName" },
            )
        }
        when (status) {
            is EngineStatus.Failed -> Notice(NoticeTone.PROBLEM) {
                Text(
                    "The engine could not start: ${status.message}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                // A rule filling, rather than a spinner: the mark the rest of the app uses to
                // say "this is the ledger", drawn while there is not a ledger yet.
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(Measure.rule),
                    color = BeancountTheme.ledger.rule,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    gapSize = 0.dp,
                )
                Text(
                    "Reading your ledger…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One icon per More section. Chosen to be recognisable rather than clever. */
private fun iconFor(section: MoreSection): ImageVector = when (section) {
    MoreSection.PROJECTS -> Icons.Filled.FolderOpen
    MoreSection.FILES -> Icons.Filled.Description
    MoreSection.DOCUMENTS -> Icons.Filled.PhotoLibrary
    MoreSection.BACKUP -> Icons.Filled.Backup
    MoreSection.MIRROR -> Icons.Filled.Sync
    MoreSection.REMOTE -> Icons.Filled.Dns
    MoreSection.PLUGINS -> Icons.Filled.Extension
    MoreSection.APPEARANCE -> Icons.Filled.Palette
    MoreSection.SCRIPTS -> Icons.Filled.Terminal
    MoreSection.LICENCES -> Icons.Filled.Gavel
    MoreSection.DIAGNOSTICS -> Icons.Filled.BugReport
    MoreSection.ABOUT -> Icons.Filled.Info
}
