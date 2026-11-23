package io.github.pintofi.beanbook.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.currentPluginPolicy
import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.ArchiveProblem
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.KdfCost
import io.github.pintofi.beanbook.core.data.PluginStore
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.RestoreStaged
import io.github.pintofi.beanbook.core.data.RestoredNew
import io.github.pintofi.beanbook.core.data.RestorePlan
import io.github.pintofi.beanbook.core.data.TemplateStore
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backing up app data and restoring it, end to end on a device: the real archive writer, the real Argon2id,
 * the real plugin store, and a second, empty "device" to restore into.
 */
@RunWith(AndroidJUnit4::class)
class BackupAppDataScenarioTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "app-data-scenario-${System.nanoTime()}").apply { mkdirs() }
    private val cost = KdfCost(iterations = 1, memoryKib = 8 * 1024, parallelism = 1)
    private val derivation = Argon2idKeyDerivation()

    /** One device: its own projects folder and its own state folder. */
    private inner class Device(name: String) {
        val workspace = ProjectWorkspace(File(base, "$name-files"))
        // The catalogue sits beside the state folder, as in the app, so each device has its own.
        val state = File(base, "$name/state").apply { mkdirs() }
        val source = AppDataSource(workspace, state)
        val restorer = AppDataRestorer(workspace, state)
        val templates = TemplateStore(workspace)
        val plan = RestorePlan(workspace, currentPluginPolicy())
    }

    private val a = Device("a")

    @After fun cleanUp() = base.deleteRecursively().let { }

    private fun template(name: String) =
        EntryTemplate(name, "EUR", listOf(TemplatePosting("Expenses:Rent"), TemplatePosting("Assets:Bank")))

    private fun fillDeviceA() {
        a.workspace.createProject("main", title = "Main")
        File(a.workspace.projectDir("main"), "main.beancount").writeText("; ledger\n")
        listOf("Rent", "Salary", "Coffee").forEach { a.templates.save("main", template(it)) }
        for (name in listOf("one", "two")) {
            val file = File(base, "$name.py").apply { writeText("def $name():\n    return 1\n") }
            val result = PluginStore(a.workspace.projectDir("main")).install(file, currentPluginPolicy(), "$name.py")
            assertTrue("plugin $name: $result", result is InstallResult.Installed)
        }
        File(a.state, "scripts").mkdirs()
        File(a.state, "scripts/check.py").writeText("print(1)\n")
        File(a.state, "appearance").writeText("theme=DARK\nmotion=reduced\nproject.main=TEAL\n")
        File(a.state, "balances-basis").writeText("COST")
        File(a.state, "home-cards").writeText("cards=NET_WORTH\n")
        File(a.state, "remote-address").writeText("https://fava.example")
        File(a.state, "remote-username").writeText("me")
        File(a.state, "remote-password").writeText("hunter2-secret")
        File(base, "a/catalogue/fava-portfolio-returns").apply { mkdirs() }
        File(base, "a/catalogue/fava-portfolio-returns/installed-version").writeText("1.2\n")
    }

    private fun backup(keys: Set<String>, passphrase: String?): File =
        File(base, "backup-${System.nanoTime()}.bcaz").also { file ->
            file.outputStream().use {
                BackupArchive(a.workspace).write(
                    "main", it, appVersion = "1.0.6", now = Instant.parse("2026-11-23T10:00:00Z"),
                    passphrase = passphrase?.toCharArray(), derivation = passphrase?.let { derivation }, cost = cost,
                    appData = a.source.payloads("main", keys),
                    leaveOutPlugins = a.source.pluginsLeftOut("main", keys),
                )
            }
        }

    private fun everything() = a.source.available("main").filter { it.selectable }.map { it.key }.toSet()

    private fun allText(archive: File): String = ZipFile(archive).use { zip ->
        zip.entries().asSequence().joinToString("\n") { String(zip.getInputStream(it).readBytes(), Charsets.ISO_8859_1) }
    }

    @Test fun everythingTickedRoundTripsPlainAndSealedWithoutAnyPassword() {
        fillDeviceA()
        for (passphrase in listOf<String?>(null, "correct horse battery")) {
            val archive = backup(everything(), passphrase)
            if (passphrase == null) {
                val text = allText(archive)
                assertFalse("a password is in a plain archive", text.contains("hunter2-secret"))
                assertTrue(text.contains("\"app_data\""))
            }
            val b = Device("b-${passphrase != null}")
            val restored = b.plan.restoreAsNew(archive, passphrase?.toCharArray(), passphrase?.let { derivation })
            restored as RestoredNew.Restored
            val staging = restored.appDataStaging!!
            val preview = b.restorer.preview(staging, restored.projectId)
            assertEquals(
                setOf(
                    AppDataGroup.TEMPLATES, AppDataGroup.CATALOGUE, AppDataGroup.COLOUR, AppDataGroup.PREFERENCES,
                    AppDataGroup.SCRIPTS, AppDataGroup.REMOTE,
                ),
                preview.map { it.group }.toSet(),
            )
            val applied = b.restorer.apply(staging, restored.projectId, preview.filter { it.selectable }.map { it.key }.toSet(),
                Clash.KEEP_MINE)
            // The new project got its own id, and the templates and colour followed it.
            assertEquals(listOf("Coffee", "Rent", "Salary"), b.templates.load(restored.projectId).map { it.name }.sorted())
            assertTrue(File(b.state, "appearance").readText().contains("project.${restored.projectId}=TEAL"))
            assertEquals("print(1)\n", File(b.state, "scripts/check.py").readText())
            assertEquals("https://fava.example", File(b.state, "remote-address").readText())
            assertFalse("no password comes back", File(b.state, "remote-password").exists())
            assertTrue(applied.notes.any { it.contains("password") })
            assertEquals(listOf("fava-portfolio-returns"), applied.catalogue)
            // Plugins arrive, every one of them turned off.
            val plugins = PluginStore(b.workspace.projectDir(restored.projectId)).list()
            assertEquals(2, plugins.size)
            assertTrue(plugins.none { it.enabled })
        }
    }

    @Test fun anUntickedPluginIsLeftOutAndTheOthersComeBackOff() {
        fillDeviceA()
        val plugins = PluginStore(a.workspace.projectDir("main")).list()
        val left = plugins.first()
        val archive = backup(everything() - "plugin:${left.id}", null)

        val b = Device("b")
        val restored = b.plan.restoreAsNew(archive) as RestoredNew.Restored

        val here = PluginStore(b.workspace.projectDir(restored.projectId)).list()
        assertEquals(listOf(plugins.last().id), here.map { it.id })
        assertFalse(File(b.workspace.projectDir(restored.projectId), "plugins/${left.id}").exists())
        assertTrue("the original is untouched", File(a.workspace.projectDir("main"), "plugins/${left.id}").exists())
    }

    @Test fun clashChoicesKeepReplaceOrKeepBoth() {
        fillDeviceA()
        val archive = backup(setOf("template:Rent", "script:check.py"), null)
        File(a.state, "scripts/check.py").writeText("print('mine')\n")
        a.templates.save("main", template("Rent").copy(narration = "mine"))

        val staged = a.plan.stage("main", archive) as RestoreStaged.Ready
        val dir = staged.appDataStaging!!
        val keys = setOf("template:Rent", "script:check.py")
        assertTrue(a.restorer.preview(dir, "main").all { it.status.name == "CLASH" })

        a.restorer.apply(dir, "main", keys, Clash.KEEP_MINE)
        assertEquals("print('mine')\n", File(a.state, "scripts/check.py").readText())
        assertEquals("mine", a.templates.load("main").single { it.name == "Rent" }.narration)

        a.restorer.apply(dir, "main", keys, Clash.KEEP_BOTH)
        a.restorer.apply(dir, "main", keys, Clash.KEEP_BOTH)
        val names = a.templates.load("main").map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("Rent", "Rent (restored)", "Rent (restored 2)")))
        assertTrue(File(a.state, "scripts/check-restored.py").isFile && File(a.state, "scripts/check-restored-2.py").isFile)

        a.restorer.apply(dir, "main", keys, Clash.REPLACE)
        assertEquals("print(1)\n", File(a.state, "scripts/check.py").readText())
        assertNull(a.templates.load("main").single { it.name == "Rent" }.narration)
        a.plan.discardStaging("main")
        assertFalse("staging is removed", dir.exists())
    }

    @Test fun aTamperedOrWrongPassphraseBackupIsRefusedAndLeavesNothingBehind() {
        fillDeviceA()
        val sealed = backup(everything(), "right passphrase")
        val wrong = a.plan.stage("main", sealed, "wrong passphrase".toCharArray(), derivation)
        assertTrue(wrong is RestoreStaged.Refused)
        assertTrue((wrong as RestoreStaged.Refused).problems.any { it is ArchiveProblem.WrongPassphrase })
        assertFalse(a.plan.appDataStagingDir("main").exists())

        val plain = backup(everything(), null)
        val broken = File(base, "broken.bcaz")
        ZipFile(plain).use { zip ->
            java.util.zip.ZipOutputStream(broken.outputStream()).use { out ->
                zip.entries().asSequence().forEach { e ->
                    out.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val bytes = zip.getInputStream(e).readBytes()
                    out.write(if (e.name == "appdata/templates.templates") bytes.reversedArray() else bytes)
                    out.closeEntry()
                }
            }
        }
        val refused = a.plan.stage("main", broken)
        assertTrue(refused is RestoreStaged.Refused)
        assertFalse(a.plan.appDataStagingDir("main").exists())
        assertFalse(a.plan.stagingDir("main").exists())
    }

    @Test fun ticksOfNothingChangeNothingAndAPreferenceRestoreReplacesOnlyWhatWasTicked() {
        fillDeviceA()
        val archive = backup(everything(), null)
        File(a.state, "appearance").writeText("theme=LIGHT\nproject.main=PLUM\n")
        File(a.state, "balances-basis").writeText("VALUE")

        val dir = (a.plan.stage("main", archive) as RestoreStaged.Ready).appDataStaging!!
        assertEquals(0, a.restorer.apply(dir, "main", emptySet(), Clash.KEEP_MINE).count)
        assertEquals("VALUE", File(a.state, "balances-basis").readText())

        a.restorer.apply(dir, "main", setOf("pref:balances-basis"), Clash.KEEP_MINE)
        assertEquals("COST", File(a.state, "balances-basis").readText().trim())
        assertTrue("appearance untouched", File(a.state, "appearance").readText().contains("theme=LIGHT"))
    }
}
