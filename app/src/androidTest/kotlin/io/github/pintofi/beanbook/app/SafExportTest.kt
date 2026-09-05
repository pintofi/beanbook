package io.github.pintofi.beanbook.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.pintofi.beanbook.app.storage.ContextHolder
import io.github.pintofi.beanbook.app.storage.SafExportTarget
import io.github.pintofi.beanbook.core.data.ProjectExporter
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exporting a project through a **real** DocumentsProvider (V14).
 *
 * Every other export test writes through `DirectoryExportTarget`, which is `java.io.File`
 * and proves nothing about SAF. `SafExportTarget` — creating nested folders through
 * `DocumentFile`, replacing an existing file, and having a provider refuse or accept a
 * stream — had no test at all, because obtaining a tree grant needs the system picker.
 *
 * So this drives the picker. It launches `ACTION_OPEN_DOCUMENT_TREE`, accepts the folder
 * DocumentsUI offers, takes the persistable grant, and exports into it. What it writes is
 * then read back **through the provider**, not off the filesystem, so a file the provider
 * did not really create cannot pass.
 */
@RunWith(AndroidJUnit4::class)
class SafExportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val workspaceRoot = File(context.cacheDir, "saf-${System.nanoTime()}")
    private val workspace = ProjectWorkspace(workspaceRoot)
    private var granted: Uri? = null

    @Before fun setUp() {
        ContextHolder.install(context)
    }

    @After fun tearDown() {
        // The grant is deliberately *not* released: it is what lets the next run skip the
        // picker entirely. Releasing it would make every run depend on driving DocumentsUI,
        // which is the fragile part this is trying to avoid.
        workspaceRoot.deleteRecursively()
        device.pressHome()
    }

    /**
     * A real tree grant: reused if one is already persisted, obtained from the picker if not.
     *
     * **Reuse first, deliberately.** A tree grant is persistable, so once a person has
     * granted a folder to this app it survives reboots and reinstalls of the *test* APK.
     * Driving DocumentsUI is the fragile part — its labels differ by version and locale, and
     * Android now refuses a grant on Download and the other shared collections outright — so
     * the picker is the fallback, not the happy path. On a machine where it has been granted
     * once, this test needs no UI at all and is as reliable as any other.
     *
     * When there is no grant and the picker cannot be driven, the test says so rather than
     * failing as though the exporter were broken.
     */
    private fun existingGrant(): Uri? =
        context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isWritePermission && it.isReadPermission }
            ?.uri

    private fun pickAFolder(): Uri? {
        // Launched through ActivityScenario rather than startActivity. The test process is
        // not in the foreground, and Android's background-activity-start rules silently drop
        // the launch -- the picker simply never appeared, with the launcher still focused.
        //
        // PickerActivity belongs to the app under test, not the test APK: the two run under
        // different uids, so a tree grant obtained by the test package would not be usable by
        // the app's ContentResolver, which is the thing being tested. It is therefore a
        // debug-only component of the app itself.
        val scenario = ActivityScenario.launch(PickerActivity::class.java)
        var activity: PickerActivity? = null
        scenario.onActivity { activity = it }
        val picker = activity ?: return null

        // **The picker is completed by a person, once per device.** Automating DocumentsUI
        // was tried and abandoned: its labels differ by version and locale, Android refuses a
        // grant on Download and the other shared collections outright, and a mis-aimed tap
        // cancels the dialog -- which returns a result, so the test cannot even tell
        // "cancelled" from "never driven". The automation was reliably producing that cancel,
        // which is worse than not trying.
        //
        // It only has to happen once per device. The grant is persistable and is deliberately
        // not released in tearDown, so every later run takes the existingGrant() path above
        // and needs no UI at all.
        device.wait(Until.hasObject(By.pkg("com.google.android.documentsui").depth(0)), 10_000)


        val result = picker.awaitResult(120_000) ?: return null
        val tree = result.data ?: return null
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        return tree
    }

    @Test fun aProjectExportsThroughARealDocumentsProvider() {
        val tree = existingGrant() ?: pickAFolder()
        // Said plainly rather than swallowed: an environment where the picker cannot be
        // driven has not verified anything, and must not look as though it has.
        assertNotNull(
            "the system document picker could not be driven on this device, so nothing was verified",
            tree,
        )
        granted = tree

        // A project with a nested folder and a document, because nested creation and
        // replacement are the parts of SafExportTarget that File-based tests never reach.
        val id = "saf-project"
        val project = workspace.projectDir(id).apply { mkdirs() }
        File(project, "main.beancount").writeText("option \"title\" \"Saf\"\n")
        File(project, "files").mkdirs()
        File(project, "files/accounts.beancount").writeText("2024-01-01 open Assets:Cash USD\n")
        File(project, "documents/2024").mkdirs()
        File(project, "documents/2024/receipt.pdf").writeText("%PDF-1.4 fake\n")

        val target = SafExportTarget(context.contentResolver, tree!!)
        val written = ProjectExporter(workspace).exportFolder(id, target)
        assertEquals("not every file was exported", 3, written)

        // Read back through the provider, so nothing here is checked off the filesystem.
        val root = DocumentFile.fromTreeUri(context, tree)!!
        fun child(path: String): DocumentFile? =
            path.split('/').fold(root as DocumentFile?) { dir, name -> dir?.findFile(name) }

        assertTrue("main.beancount is missing", child("main.beancount")?.isFile == true)
        assertTrue("the nested files/ folder was not created", child("files")?.isDirectory == true)
        assertTrue("accounts.beancount is missing", child("files/accounts.beancount")?.isFile == true)
        assertTrue("documents/2024 was not created", child("documents/2024")?.isDirectory == true)
        assertTrue("the receipt is missing", child("documents/2024/receipt.pdf")?.isFile == true)

        val readBack = context.contentResolver
            .openInputStream(child("files/accounts.beancount")!!.uri)!!
            .use { it.readBytes().decodeToString() }
        assertEquals("2024-01-01 open Assets:Cash USD\n", readBack)

        // Exporting again must replace rather than accumulate "accounts (1).beancount",
        // which is what a provider does when a name collides and nothing deletes first.
        File(project, "files/accounts.beancount").writeText("2024-01-02 open Assets:Bank USD\n")
        ProjectExporter(workspace).exportFolder(id, SafExportTarget(context.contentResolver, tree))

        val files = child("files")!!.listFiles().map { it.name }
        assertEquals("the second export duplicated a file instead of replacing it: $files", 1, files.size)
        val replaced = context.contentResolver
            .openInputStream(child("files/accounts.beancount")!!.uri)!!
            .use { it.readBytes().decodeToString() }
        assertEquals("the replacement kept the old contents", "2024-01-02 open Assets:Bank USD\n", replaced)
    }
}
