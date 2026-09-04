package dev.beancount.app

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.beancount.core.domain.LedgerSnapshot
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.entitlement.Entitlement
import dev.beancount.core.entitlement.Grant
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
        Text("Unlocked by the $tier contribution key.")
        Text(
            "Your ledger, the journal, balances, raw editing, and import and export all " +
                "stay free. This app is open source: you can also build it yourself.",
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.TextButton(onClick = onContribute) {
            Text(dev.beancount.core.entitlement.PurchaseRoute.EnterKeyOnly.label)
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
) {
    androidx.compose.material3.TopAppBar(
        title = {
            Column {
                Text(
                    sectionTitle ?: projectName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (sectionTitle == null && health != null) {
                    Text(health, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (sectionTitle != null) {
                // An arrow, which is what a person expects in this corner. This was the
                // word "Back", on the grounds that the icon artifact was a whole dependency
                // for one glyph -- true when it was written, and no longer: the icon set
                // arrived with the navigation bar and measured +424 bytes in the shipped APK
                // (B23).
                //
                // AutoMirrored, so the arrow points the other way in a right-to-left layout.
                // A back arrow that keeps pointing left in Arabic or Hebrew points forward.
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to More" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        },
        actions = {
            if (sectionTitle == null) {
                TextButton(
                    onClick = onSwitchProject,
                    modifier = Modifier.semantics {
                        contentDescription = "Switch project. Currently open: $projectName"
                    },
                ) { Text("Switch") }
            }
        },
    )
}

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
internal fun MoreMenu(entitlement: Entitlement, onContribute: () -> Unit, onOpen: (MoreSection) -> Unit) {
    val descriptions = mapOf(
        MoreSection.PROJECTS to "Open, create, import or delete a ledger",
        MoreSection.FILES to "Read and edit the ledger files themselves",
        MoreSection.DOCUMENTS to "Receipts and statements filed against entries",
        MoreSection.BACKUP to "Write a backup, restore one, or schedule them",
        MoreSection.MIRROR to "Keep a copy in a folder you choose, kept in step",
        MoreSection.REMOTE to "Use a Fava server you host yourself",
        MoreSection.PLUGINS to "Extra checks and transformations, from files you supply",
        MoreSection.APPEARANCE to "Theme, dark mode and colours",
        MoreSection.UPDATES to "Check for a signed update to this app",
        MoreSection.SCRIPTS to "Run a Python script once against the open ledger",
        MoreSection.LICENCES to "This app's licence, and everything it is built on",
    )

    // Updates are the one section that is not in both editions: the Play build has no
    // self-update anything, by policy, so offering the entry would be offering a dead end.
    val sections = MoreSection.entries.filter {
        it != MoreSection.UPDATES || currentPluginPolicy().allowsSelfUpdate
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // The entitlement is stated here, once, instead of above every screen. It is a fact
        // worth being able to find, not one worth being told on the way to everything else
        // -- a line the user sees on every tab is a line they stop reading (B27).
        TrialCard(entitlement, onClick = onContribute)
        sections.forEach { entry ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(entry.label) },
                supportingContent = descriptions[entry]?.let { { Text(it) } },
                modifier = Modifier
                    .clickable { onOpen(entry) }
                    .semantics {
                        contentDescription = "${entry.label}. ${descriptions[entry].orEmpty()}"
                    },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Something had to be put right before the app could start, and the user is told what.
 *
 * Dismissible, and only ever shown when something actually happened: a banner that appears
 * on every launch is a banner nobody reads. It is not a dialog either — the app has already
 * recovered by the time this is on screen, so there is nothing to decide.
 */
@Composable
internal fun RecoveryBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
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
}

/**
 * Where the entitlement stands, stated once in More rather than above every screen (B27).
 *
 * Deliberately heavier than a journal row: this is a status about the app, not a line of the
 * user's data, and it sits among a list of plain rows where it would otherwise disappear. It
 * uses the theme's secondary container rather than a colour of its own, so it reads as
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$headline. $detail" },
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun LoadingOrError(
    status: EngineStatus,
    editionName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Beanbook", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Edition: $editionName",
            modifier = Modifier.semantics { contentDescription = "Edition $editionName" },
        )
        when (status) {
            is EngineStatus.Failed -> Text("The engine could not start: ${status.message}")
            else -> {
                CircularProgressIndicator()
                Text("Reading your ledger…")
            }
        }
    }
}
