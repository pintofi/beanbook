package io.github.pintofi.beanbook.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import io.github.pintofi.beanbook.app.onboarding.Creation
import io.github.pintofi.beanbook.app.onboarding.NewLedgerSetup
import io.github.pintofi.beanbook.app.onboarding.SetupDraft
import io.github.pintofi.beanbook.app.onboarding.SetupStep
import io.github.pintofi.beanbook.app.ui.FolioBody
import io.github.pintofi.beanbook.app.ui.FolioGroup
import io.github.pintofi.beanbook.app.ui.FolioHeading
import io.github.pintofi.beanbook.app.ui.FolioNote
import io.github.pintofi.beanbook.app.ui.FolioPage
import io.github.pintofi.beanbook.app.ui.FolioRow
import io.github.pintofi.beanbook.app.ui.ProjectTrash
import io.github.pintofi.beanbook.app.ui.StatusLine
import io.github.pintofi.beanbook.app.ui.TrashSection
import io.github.pintofi.beanbook.app.ui.WelcomeHeader
import io.github.pintofi.beanbook.app.ui.WhatThisIs
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred

/** What the setup needs from the app around it, so the pages stay plain. */
class SetupActions(
    val onCreate: () -> Unit,
    val onCreateWithDefaults: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onOpenArchive: () -> Unit,
    val onPickMirrorFolder: () -> Unit,
    val onAskNotifications: () -> Unit,
    val onThemeChoice: (ThemeChoice) -> Unit,
    /** Leaves the setup for the new ledger; true opens the entry form straight away. */
    val onFinish: (projectId: String, addEntry: Boolean) -> Unit,
    /** Saves a Fava server's address and credentials and connects to it. */
    val onRemoteConnect: (String, String?, CharArray?) -> Unit = { _, _, _ -> },
)

/**
 * First run: welcome, the ways in, then three steps (basics, accounts, get set) and a last page
 * that creates the ledger. Back is one step back with the answers kept; nothing is written under
 * `projects/` before the last page.
 */
@Composable
fun OnboardingFlow(
    setup: NewLedgerSetup,
    actions: SetupActions,
    themeChoice: ThemeChoice,
    modifier: Modifier = Modifier,
    busyMessage: String? = null,
    trash: ProjectTrash = ProjectTrash(),
    /** What the server connection is doing, in words; null before anything was tried. */
    remoteNote: String? = null,
) {
    val draft by setup.draft.collectAsState()
    val creation by setup.creation.collectAsState()
    val notificationsGranted by setup.notificationsGranted.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(draft.step) { if (notificationsAllowed(context)) setup.notificationsGranted.value = true }
    val update = setup::update
    when (draft.step) {
        SetupStep.WELCOME -> WelcomePage(
            resumable = setup.resumable,
            onContinue = { setup.go(SetupStep.WAYS_IN) },
            modifier = modifier,
        )
        SetupStep.WAYS_IN -> WaysInPage(
            draft = draft,
            onName = { name -> update { it.copy(name = name) } },
            onBack = { setup.go(SetupStep.WELCOME) },
            onSetUp = { setup.go(SetupStep.BASICS) },
            actions = actions,
            modifier = modifier,
            busyMessage = busyMessage,
            trash = trash,
            remoteNote = remoteNote,
        )
        SetupStep.BASICS -> SetupPage(
            step = 1, title = "The basics",
            onBack = { setup.go(SetupStep.WAYS_IN) },
            primaryLabel = "Next: accounts",
            onPrimary = { setup.go(SetupStep.ACCOUNTS) },
            primaryEnabled = draft.basicsReady(),
            modifier = modifier,
        ) { BasicsStep(draft, update) }
        SetupStep.ACCOUNTS -> SetupPage(
            step = 2, title = "Accounts",
            onBack = { setup.go(SetupStep.BASICS) },
            primaryLabel = "Next: get set",
            onPrimary = { setup.go(SetupStep.GET_SET) },
            primaryEnabled = draft.accountsReady(),
            modifier = modifier,
        ) { AccountsStep(draft, update) }
        SetupStep.GET_SET -> SetupPage(
            step = 3, title = "Get set",
            onBack = { setup.go(SetupStep.ACCOUNTS) },
            primaryLabel = "Create my ledger",
            onPrimary = { setup.go(SetupStep.FINISH); actions.onCreate() },
            modifier = modifier,
        ) { GetSetStep(draft, update, themeChoice, actions, notificationsGranted) }
        SetupStep.FINISH -> FinishPage(
            creation = creation,
            onBack = {
                if (creation !is Creation.Working && creation !is Creation.Done) setup.go(SetupStep.GET_SET)
            },
            onRetry = actions.onCreate,
            onDefaults = actions.onCreateWithDefaults,
            onFinish = actions.onFinish,
            modifier = modifier,
        )
    }
}

private fun SetupDraft.basicsReady() = name.isNotBlank() &&
    io.github.pintofi.beanbook.core.domain.CommodityName.isValid(mainCurrency.trim().uppercase())

private fun SetupDraft.accountsReady() = accounts.any { it.on && it.name.startsWith("Assets:") }

@Composable
private fun WelcomePage(resumable: Boolean, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onContinue,
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                text = { Text(if (resumable) "Pick up where I left off" else "Get started") },
                modifier = Modifier.semantics {
                    contentDescription = if (resumable) {
                        "Pick up where I left off. Goes back to the setup you started."
                    } else {
                        "Get started. Opens the ways to add a ledger."
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readableMeasureCentred()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WelcomeHeader(mark = { ArrivingMark() })
            WelcomeExample()
            WhatThisIs()
            Text(
                "Nothing leaves this phone unless you set up a copy yourself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 56.dp))
        }
    }
}

@Composable
private fun WaysInPage(
    draft: SetupDraft,
    onName: (String) -> Unit,
    onBack: () -> Unit,
    onSetUp: () -> Unit,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    busyMessage: String? = null,
    trash: ProjectTrash = ProjectTrash(),
    remoteNote: String? = null,
) {
    BackHandler(onBack = onBack)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Back to the welcome" },
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Text("Add a ledger", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readableMeasureCentred(),
        ) {
            // The same folios as the project list, which has the same three jobs: start one,
            // bring one in, and (here) connect a server.
            FolioPage {
                FolioHeading("Start a new ledger")
                FolioGroup {
                    item {
                        FolioBody {
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = onName,
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Hint(
                                "Three short steps: currency and dates, accounts, and a few preferences. " +
                                    "You can change all of it later by editing the files.",
                            )
                            Button(
                                onClick = onSetUp,
                                enabled = draft.name.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Set it up") }
                            // Both start the same setup; the plain one skips the questions.
                            OutlinedButton(
                                onClick = actions.onCreateWithDefaults,
                                enabled = draft.name.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Create with defaults") }
                        }
                    }
                }
                FolioNote("Defaults: your device's currency, a short list of everyday accounts, and the usual checks.")

                FolioHeading("Open an existing ledger")
                FolioGroup {
                    item {
                        FolioRow(
                            title = "Choose folder",
                            detail = "A folder of Beancount files, on this device or in cloud storage",
                            icon = androidx.compose.material.icons.Icons.Outlined.DriveFolderUpload,
                            onClick = actions.onOpenFolder,
                            opens = true,
                        )
                    }
                    item {
                        FolioRow(
                            title = "Choose .zip",
                            detail = "A project exported from Beanbook, or any archive of a ledger",
                            icon = androidx.compose.material.icons.Icons.Outlined.FolderZip,
                            onClick = actions.onOpenArchive,
                            opens = true,
                        )
                    }
                }
                busyMessage?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.indent)) }

                RemoteCard(onConnect = actions.onRemoteConnect, note = remoteNote)
                TrashSection(trash)
            }
        }
    }
}

/** Whether notifications may be shown already: always before Android 13, which has no prompt. */
private fun notificationsAllowed(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
