package io.github.pintofi.beanbook.app.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.BeancountApplication
import io.github.pintofi.beanbook.app.plugins.CatalogueState
import io.github.pintofi.beanbook.app.ui.ValuationPreference
import io.github.pintofi.beanbook.app.ui.theme.Comfort
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.AppDataPayload
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.CatalogueInstaller
import io.github.pintofi.beanbook.core.domain.Valuation
import java.io.File
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Backup screen's own controller, in the running app: a backup written to a file, a restore through the
 * same staging and apply steps the screen uses, and what the app shows afterwards.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as BeancountApplication
    private val dir = File(context.cacheDir, "e2e-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() = dir.deleteRecursively().let { }

    private fun waitFor(what: String, timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for $what")
            Thread.sleep(200)
        }
    }

    @Test fun restoredPreferencesShowAtOnceAndAreNotWrittenOverByTheNextChange() {
        val id = "e2e-prefs"
        app.workspace.createProject(id, title = "E2E")
        app.themeSettings.setChoice(ThemeChoice.DARK)
        ValuationPreference(context).write(Valuation.COST)
        val controller = app.backupController
        val file = File(dir, "prefs.bcaz")
        controller.appDataForNextBackup = setOf("pref:appearance", "pref:balances-basis")
        controller.backupTo(context.contentResolver, Uri.fromFile(file), id)
        waitFor("the backup") { controller.message.value?.startsWith("Backed up") == true }

        app.themeSettings.setChoice(ThemeChoice.LIGHT)
        ValuationPreference(context).write(Valuation.VALUE)
        controller.stageRestore(context.contentResolver, Uri.fromFile(file), id)
        waitFor("the staged restore") { controller.pendingRestore.value != null }
        val preview = controller.appDataUi(id).restoreItems
        assertEquals(setOf("pref:appearance", "pref:balances-basis"), preview.map { it.key }.toSet())
        controller.acceptRestore(id, preview.map { it.key }.toSet(), Clash.KEEP_MINE) {}
        waitFor("the restore") { controller.message.value?.startsWith("Restored") == true }

        assertEquals(ThemeChoice.DARK, app.themeSettings.choice.value)
        assertEquals(Valuation.COST, ValuationPreference(context).read())
        // A change made after the restore must not bring the old theme back from memory.
        app.themeSettings.setComfort(Comfort(highContrast = true))
        assertTrue(File(context.filesDir, "state/appearance").readText().contains("theme=DARK"))
        assertEquals(ThemeChoice.DARK, app.themeSettings.choice.value)
    }

    @Test fun installAgainInstallsTheCataloguePluginAListedBackupNamed() {
        val catalogue: CatalogueState = app.catalogueState
        assumeTrue("this edition cannot install from the catalogue", catalogue.canInstall)
        val online = runCatching { URL("https://pypi.org").openConnection().apply { connectTimeout = 8000 }.connect() }
        assumeTrue("no network", online.isSuccess)
        val plugin = "beancount-balexpr"
        val installer = CatalogueInstaller(CatalogueState.rootOf(context))
        assumeTrue("already installed here", plugin !in installer.installed())
        val id = "e2e-install"
        app.workspace.createProject(id, title = "E2E install")
        val file = File(dir, "listed.bcaz")
        file.outputStream().use {
            BackupArchive(app.workspace).write(
                id, it, appVersion = "1.0.6",
                appData = listOf(
                    AppDataPayload(
                        AppDataGroup.CATALOGUE, "catalogue.json",
                        "{\"plugins\":[{\"id\":\"$plugin\",\"version\":\"0.5.0\"}]}\n".toByteArray(),
                    ),
                ),
            )
        }
        val controller = app.backupController
        controller.stageRestore(context.contentResolver, Uri.fromFile(file), id)
        waitFor("the staged restore") { controller.pendingRestore.value != null }
        controller.acceptRestore(id, emptySet(), Clash.KEEP_MINE) {}
        waitFor("the restore") { controller.message.value?.startsWith("Restored") == true }

        val ui = controller.appDataUi(id)
        assertEquals(listOf(plugin), ui.reinstall)
        assertNotNull(ui.installAgain)
        ui.installAgain!!.invoke()
        waitFor("the install", timeoutMs = 420_000) { plugin in installer.installed() }
        assertTrue(controller.appDataUi(id).reinstall.isEmpty())
    }
}
