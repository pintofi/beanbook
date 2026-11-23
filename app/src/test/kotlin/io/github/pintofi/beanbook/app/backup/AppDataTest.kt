package io.github.pintofi.beanbook.app.backup

import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.TemplateStore
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a backup offers from the app's own files, and what restoring does with the ticked ones. */
class AppDataTest {

    private val base: File = Files.createTempDirectory("appdata").toFile()
    private val workspace = ProjectWorkspace(File(base, "ws"))
    private val state = File(base, "state").apply { mkdirs() }
    private val source = AppDataSource(workspace, state)
    private val restorer = AppDataRestorer(workspace, state)
    private val templates = TemplateStore(workspace)

    @After fun cleanUp() = base.deleteRecursively().let { }

    private fun template(name: String) =
        EntryTemplate(name, "EUR", listOf(TemplatePosting("Expenses:Rent"), TemplatePosting("Assets:Bank")))

    /** Stages [keys] of [project] as a restore would see them, in a folder of their own. */
    private fun staged(project: String, keys: Set<String>): File {
        val dir = File(base, "staged-${System.nanoTime()}")
        source.payloads(project, keys).forEach {
            File(dir, it.path).apply { parentFile.mkdirs() }.writeBytes(it.bytes)
        }
        return dir
    }

    @Test
    fun `nothing from a secret is offered, and scripts and the server are off by default`() {
        workspace.createProject("a", title = "a")
        templates.save("a", template("Rent"))
        File(state, "scripts").mkdirs()
        File(state, "scripts/check.py").writeText("print(1)\n")
        File(state, "remote-address").writeText("https://fava.example")
        File(state, "remote-password").writeText("hunter2")
        File(state, "appearance").writeText("theme=DARK\nproject.a=TEAL\n")

        val offered = source.available("a")

        assertTrue(offered.none { it.title.contains("password", ignoreCase = true) })
        assertTrue(offered.single { it.key == "template:Rent" }.onByDefault)
        assertFalse(offered.single { it.key == "script:check.py" }.onByDefault)
        assertFalse(offered.single { it.group == AppDataGroup.REMOTE }.onByDefault)
        val everything = source.payloads("a", offered.map { it.key }.toSet())
        assertFalse(everything.any { String(it.bytes).contains("hunter2") })
    }

    @Test
    fun `the appearance preference leaves other projects' colours behind`() {
        File(state, "appearance").writeText("theme=DARK\nproject.a=TEAL\nproject.b=DYNAMIC\n")

        val text = String(source.payloads("a", setOf("pref:appearance")).single().bytes)

        assertEquals("theme=DARK\n", text)
    }

    @Test
    fun `only the ticked templates travel and they come back under the new project id`() {
        workspace.createProject("a", title = "a")
        workspace.createProject("b", title = "b")
        templates.save("a", template("Rent"))
        templates.save("a", template("Salary"))

        val dir = staged("a", setOf("template:Rent"))
        val applied = restorer.apply(dir, "b", setOf("template:Rent"), Clash.KEEP_MINE)

        assertEquals(1, applied.count)
        assertEquals(listOf("Rent"), templates.load("b").map { it.name })
    }

    @Test
    fun `a clash is kept, replaced or kept both as chosen`() {
        workspace.createProject("a", title = "a")
        workspace.createProject("b", title = "b")
        templates.save("a", template("Rent").copy(narration = "backup"))
        val dir = staged("a", setOf("template:Rent"))
        templates.save("b", template("Rent").copy(narration = "mine"))
        val keys = setOf("template:Rent")

        restorer.apply(dir, "b", keys, Clash.KEEP_MINE)
        assertEquals(listOf("mine"), templates.load("b").map { it.narration })

        restorer.apply(dir, "b", keys, Clash.KEEP_BOTH)
        assertEquals(listOf("mine", "backup"), templates.load("b").map { it.narration })

        restorer.apply(dir, "b", keys, Clash.REPLACE)
        assertEquals(listOf("backup", "backup"), templates.load("b").map { it.narration })
    }

    @Test
    fun `an unticked item is not applied and a restored script is only saved`() {
        File(state, "scripts").mkdirs()
        File(state, "scripts/check.py").writeText("print(1)\n")
        val dir = staged("a", setOf("script:check.py"))
        File(state, "scripts/check.py").delete()

        assertEquals(0, restorer.apply(dir, "a", emptySet(), Clash.KEEP_MINE).count)
        assertFalse(File(state, "scripts/check.py").exists())

        restorer.apply(dir, "a", setOf("script:check.py"), Clash.KEEP_MINE)
        assertEquals("print(1)\n", File(state, "scripts/check.py").readText())
    }

    @Test
    fun `a project colour that is not a plain name is refused`() {
        val dir = File(base, "bad").apply { mkdirs() }
        File(dir, "colour").writeText("TEAL\nproject.x=DYNAMIC")

        val applied = restorer.apply(dir, "a", setOf("colour"), Clash.KEEP_MINE)

        assertEquals(0, applied.count)
        assertFalse(File(state, "appearance").exists())
    }

    @Test
    fun `a server address is restored without a password and only if it is a web address`() {
        File(state, "remote-address").writeText("https://fava.example")
        File(state, "remote-username").writeText("me")
        val dir = staged("a", setOf("remote"))
        File(state, "remote-address").delete()

        val applied = restorer.apply(dir, "a", setOf("remote"), Clash.KEEP_MINE)

        assertEquals("https://fava.example", File(state, "remote-address").readText())
        assertEquals("me", File(state, "remote-username").readText())
        assertTrue(applied.notes.any { it.contains("password") })
        File(dir, "remote.json").writeText("{\"address\":\"file:///etc/passwd\",\"username\":\"x\"}")
        File(state, "remote-address").delete()
        assertEquals(0, restorer.apply(dir, "a", setOf("remote"), Clash.KEEP_MINE).count)
    }

    @Test
    fun `catalogue plugins are listed by name and version and a restore only reminds`() {
        File(base, "catalogue/fava-portfolio-returns").apply { mkdirs() }
        File(base, "catalogue/fava-portfolio-returns/installed-version").writeText("1.2\n")

        val offered = source.available("a").single { it.group == AppDataGroup.CATALOGUE }
        assertEquals("fava-portfolio-returns", offered.title)

        val dir = staged("a", setOf(offered.key))
        File(base, "catalogue").deleteRecursively()
        val preview = restorer.preview(dir, "a").single()
        assertEquals(RestoreStatus.REMINDER, preview.status)
        assertFalse(preview.selectable)

        val applied = restorer.apply(dir, "a", setOf(preview.key), Clash.KEEP_MINE)
        assertEquals(0, applied.count)
        assertEquals(listOf("fava-portfolio-returns"), applied.catalogue)
        assertTrue(applied.notes.single().contains("fava-portfolio-returns"))
        assertFalse(File(base, "catalogue").exists())
    }

    @Test
    fun `a catalogue plugin that is installed already is said to be, and is not offered again`() {
        File(base, "catalogue/fava-portfolio-returns").apply { mkdirs() }
        File(base, "catalogue/fava-portfolio-returns/installed-version").writeText("1.2\n")
        val dir = staged("a", setOf("catalogue:fava-portfolio-returns"))

        val preview = restorer.preview(dir, "a").single()
        assertEquals(RestoreStatus.INSTALLED, preview.status)
        assertFalse(preview.selectable)
        assertTrue(restorer.apply(dir, "a", setOf(preview.key), Clash.KEEP_MINE).catalogue.isEmpty())
    }
}
