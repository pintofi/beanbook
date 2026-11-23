package io.github.pintofi.beanbook.app.ui

import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.backup.AppDataItem
import io.github.pintofi.beanbook.app.backup.AppDataRestorer
import io.github.pintofi.beanbook.app.backup.AppDataSource
import io.github.pintofi.beanbook.app.backup.Clash
import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.RestorePlan
import io.github.pintofi.beanbook.core.data.RestoreStaged
import io.github.pintofi.beanbook.core.data.TemplateStore
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The checklist and the restore preview of the Backup screen: grouping, defaults, warnings and clash choices. */
@RunWith(AndroidJUnit4::class)
class BackupAppDataScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "backup-screen-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() = base.deleteRecursively().let { }

    private fun item(key: String, group: AppDataGroup, title: String, detail: String = "", on: Boolean = true) =
        AppDataItem(key, group, title, detail, 900, on)

    private val available = listOf(
        item("plugin:one", AppDataGroup.PLUGINS, "Zakat helper", "Comes back turned off"),
        item("plugin:two", AppDataGroup.PLUGINS, "Prices", "Comes back turned off"),
        item("catalogue:fava-portfolio-returns", AppDataGroup.CATALOGUE, "fava-portfolio-returns", "Version 1.2"),
        item("template:Rent", AppDataGroup.TEMPLATES, "Rent", "2 postings"),
        item("template:Salary", AppDataGroup.TEMPLATES, "Salary", "2 postings"),
        item("script:check.py", AppDataGroup.SCRIPTS, "check", "Comes back saved", on = false),
        item("pref:appearance", AppDataGroup.PREFERENCES, "Appearance: theme, contrast, text size, motion", on = false),
        item("colour", AppDataGroup.COLOUR, "This project's colour", "teal"),
        item("remote", AppDataGroup.REMOTE, "Remote server address and user name", "https://fava.example", on = false),
    )

    private fun render(
        ui: AppDataUi,
        pending: RestoreStaged.Ready? = null,
        onBackUp: (CharArray?, Set<String>?) -> Unit = { _, _ -> },
        onRestore: (Set<String>, Clash) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MaterialTheme {
                BackupScreen(
                    projectTitle = "Main", pendingRestore = pending, passphraseNeeded = false,
                    scheduleEnabled = false, scheduleFolder = null, lastRun = null, rollbackPoints = emptyList(),
                    onBackUpNow = onBackUp, onUnlockRestore = {}, onChooseRestoreFile = {},
                    onAcceptRestore = onRestore, onDiscardRestore = {}, onChooseScheduleFolder = {},
                    onScheduleChanged = {}, scheduleEncryption = ScheduleEncryption(false) {},
                    loadAppData = { ui },
                )
            }
        }
    }

    private fun picture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("backupPictures") != "true") return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/beanbook-backup-pictures")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theChecklistIsGroupedAndStartsAsTheDefaultsSay() {
        var sent: Set<String>? = null
        render(AppDataUi(available = available), onBackUp = { _, keys -> sent = keys })
        compose.onNodeWithContentDescription("Also include app data").performScrollTo().performClick()

        for (heading in listOf(
            "Entry templates", "Plugins in this project", "Catalogue plugins, names only", "Saved scripts",
            "Preferences", "Remote server",
        )) compose.onNodeWithText(heading).performScrollTo().assertIsDisplayed()
        picture("backup-checklist-top")

        compose.onNodeWithContentDescription("Back up Main now").performScrollTo().performClick()
        // Plugins, catalogue, templates and the colour are ticked; scripts, preferences and the server are not.
        assertEquals(
            setOf("plugin:one", "plugin:two", "catalogue:fava-portfolio-returns", "template:Rent", "template:Salary", "colour"),
            sent,
        )
    }

    @Test fun allAndNoneTickAGroupAndAPlainBackupWarnsAboutScriptsAndTheServer() {
        var sent: Set<String>? = null
        render(AppDataUi(available = available), onBackUp = { _, keys -> sent = keys })
        compose.onNodeWithContentDescription("Also include app data").performScrollTo().performClick()

        compose.onNodeWithContentDescription("Clear every Entry templates item").performScrollTo().performClick()
        compose.onNodeWithText("Saved scripts").performScrollTo()
        compose.onNodeWithText("check").performScrollTo().performClick()
        compose.onNodeWithText("Without a passphrase, anyone with the file can read the scripts", substring = true)
            .performScrollTo().assertIsDisplayed()
        picture("backup-checklist-warning")
        compose.onNodeWithContentDescription("Back up Main now").performScrollTo().performClick()

        assertFalse(sent!!.any { it.startsWith("template:") })
        assertTrue("script:check.py" in sent!!)
    }

    @Test fun nothingIsOfferedWhenThereIsNoAppDataAndTheSwitchStartsOff() {
        var sent: Set<String>? = setOf("unset")
        render(AppDataUi(), onBackUp = { _, keys -> sent = keys })
        compose.onNodeWithContentDescription("Back up Main now").performScrollTo().performClick()
        assertEquals(null, sent)
    }

    @Test fun theRestorePreviewGroupsItemsOffersClashChoicesAndStartsSafe() {
        val workspace = ProjectWorkspace(File(base, "files"))
        val state = File(base, "state").apply { mkdirs() }
        workspace.createProject("main", title = "Main")
        val templates = TemplateStore(workspace)
        for (n in listOf("Rent", "Salary")) {
            templates.save("main", EntryTemplate(n, "EUR", listOf(TemplatePosting("Expenses:Rent"), TemplatePosting("Assets:Bank"))))
        }
        File(state, "scripts").mkdirs()
        File(state, "scripts/check.py").writeText("print(1)\n")
        File(state, "appearance").writeText("theme=DARK\nproject.main=TEAL\n")
        File(state, "remote-address").writeText("https://fava.example")
        val source = AppDataSource(workspace, state)
        val keys = source.available("main").filter { it.selectable }.map { it.key }.toSet()
        val archive = File(base, "b.bcaz")
        archive.outputStream().use {
            BackupArchive(workspace).write("main", it, appVersion = "1.0.6", appData = source.payloads("main", keys))
        }
        templates.delete("main", "Salary")
        File(state, "scripts/check.py").writeText("print('mine')\n")
        val staged = RestorePlan(workspace).stage("main", archive) as RestoreStaged.Ready
        val items = AppDataRestorer(workspace, state).preview(staged.appDataStaging!!, "main")

        var restored: Pair<Set<String>, Clash>? = null
        render(AppDataUi(restoreItems = items), pending = staged, onRestore = { k, c -> restored = k to c })

        compose.onNodeWithText("App data in this backup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Entry templates").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved scripts").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Already here", substring = true)[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Replaces what is set here", substring = true)[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep mine").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Keep mine") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Keep both") and isSelectable()).assertIsNotSelected()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        compose.onNodeWithText("Scripts come back saved. None runs until you run it.").performScrollTo().assertIsDisplayed()
        picture("backup-restore-preview")

        compose.onNodeWithText("Keep both").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Restore this backup over the current project").performScrollTo().performClick()

        val (ticked, clash) = restored!!
        assertEquals(Clash.KEEP_BOTH, clash)
        // Salary is new here, so it starts ticked; Rent and the script are already here and the preference and
        // server replace a setting, so none of those start ticked, and code and a server address never do.
        assertEquals(setOf("template:Salary"), ticked)
    }

    @Test fun aLongKindStartsFoldedWithItsCountAndOpens() {
        val many = (1..7).map { item("template:T$it", AppDataGroup.TEMPLATES, "Template $it") }
        render(AppDataUi(available = many))
        compose.onNodeWithContentDescription("Also include app data").performScrollTo().performClick()

        compose.onNodeWithText("7 of 7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Template 1").assertDoesNotExist()
        compose.onNodeWithText("Entry templates").performScrollTo().performClick()
        compose.onNodeWithText("Template 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear every Entry templates item").performScrollTo().performClick()
        compose.onNodeWithText("0 of 7").performScrollTo().assertIsDisplayed()
    }

    @Test fun headingsAndChoicesCarryTheirMeaningForAScreenReader() {
        render(AppDataUi(available = available))
        compose.onNodeWithContentDescription("Also include app data").performScrollTo().performClick()

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNode(heading and hasAnyDescendant(hasText("Entry templates"))).assertExists()
        compose.onNode(heading and hasAnyDescendant(hasText("Saved scripts"))).assertExists()
        // A tick row is one control that reads as on or off; an unticked script is off.
        compose.onNode(hasText("Rent") and isToggleable()).performScrollTo().assertIsOn()
        compose.onNode(hasText("check") and isToggleable()).performScrollTo().assertIsOff()
    }
}
