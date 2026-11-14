package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A project's main file keeps the name and place it was imported with, and the record is never in the project. */
class ProjectRootTest {

    private val base: File = Files.createTempDirectory("roots").toFile()
    private val workspace = ProjectWorkspace(base)
    private val registry = ProjectRegistry(workspace, File(base, "state"))

    @AfterTest fun cleanUp() = base.deleteRecursively().let { }

    private fun project(id: String, vararg files: Pair<String, String>) {
        files.forEach { (path, text) ->
            File(workspace.projectDir(id), path).apply { parentFile.mkdirs() }.writeText(text)
        }
    }

    @Test
    fun `with no record the main file is main dot beancount`() {
        project("a", "main.beancount" to "")

        assertEquals("main.beancount", workspace.rootPath("a"))
        assertEquals(File(workspace.projectDir("a"), "main.beancount"), workspace.mainFile("a"))
        assertEquals(listOf("a"), workspace.listProjects())
    }

    @Test
    fun `a recorded root keeps its own name and place`() {
        project("a", "books/hmw" to "option \"title\" \"Mine\"\n")

        workspace.setRoot("a", "books/hmw")

        assertEquals("books/hmw", workspace.rootPath("a"))
        assertEquals(File(workspace.projectDir("a"), "books/hmw"), workspace.mainFile("a"))
        assertTrue(workspace.exists("a"))
        assertEquals(listOf("a"), workspace.listProjects())
        assertEquals("Mine", registry.summarise("a").title)
    }

    @Test
    fun `a project whose main file has another name is not listed until it says so`() {
        project("a", "hmw" to "")

        assertEquals(emptyList(), workspace.listProjects())
        workspace.setRoot("a", "hmw")
        assertEquals(listOf("a"), workspace.listProjects())
    }

    @Test
    fun `the record is kept outside the project folder`() {
        project("a", "hmw" to "")

        workspace.setRoot("a", "hmw")

        assertTrue(workspace.rootRecord("a").isFile)
        assertFalse(workspace.rootRecord("a").canonicalPath.startsWith(workspace.projectDir("a").canonicalPath))
        assertEquals(listOf("hmw"), workspace.projectDir("a").list()!!.toList())
    }

    @Test
    fun `a root that could leave the project, or is not a file in it, is refused`() {
        project("a", "main.beancount" to "", "x/y" to "")
        for (bad in listOf("", " ", "../x", "x/../../y", "/etc/passwd", "x\\y", "a\u0000b", ".", "./")) {
            assertFailsWith<IllegalArgumentException>("\"$bad\"") { workspace.setRoot("a", bad) }
        }
        assertFailsWith<IllegalArgumentException> { workspace.setRoot("a", "nothing") }
        assertFailsWith<IllegalArgumentException> { workspace.setRoot("a", "x") } // a folder
        assertEquals("main.beancount", workspace.rootPath("a"))
    }

    @Test
    fun `a path is normalised and setting main dot beancount forgets the record`() {
        project("a", "main.beancount" to "", "x/y" to "")

        workspace.setRoot("a", "./x//y")
        assertEquals("x/y", workspace.rootPath("a"))

        workspace.setRoot("a", "main.beancount")
        assertFalse(workspace.rootRecord("a").exists())
    }

    @Test
    fun `a record that says something unusable is read as main dot beancount`() {
        project("a", "main.beancount" to "")
        workspace.rootsDir.mkdirs()
        workspace.rootRecord("a").writeText("../../outside")

        assertEquals("main.beancount", workspace.rootPath("a"))
    }

    @Test
    fun `trashing a project takes its record along and restoring puts it back under the id it gets`() {
        project("a", "books/hmw" to "option \"title\" \"Mine\"\n")
        workspace.setRoot("a", "books/hmw")

        val trashed = registry.moveToTrash("a")
        assertFalse(workspace.rootRecord("a").exists())
        assertEquals("Mine", registry.trashSummaries().single().title)

        // Another project took the id meanwhile, so the restored one gets a new id.
        project("a", "main.beancount" to "")
        val restored = registry.restoreFromTrash(trashed)

        assertEquals("a-2", restored)
        assertEquals("books/hmw", workspace.rootPath(restored))
        assertEquals("main.beancount", workspace.rootPath("a"))
        assertTrue(workspace.exists(restored))
    }

    @Test
    fun `deleting from the trash, or emptying it, deletes the record too`() {
        project("a", "hmw" to "")
        workspace.setRoot("a", "hmw")
        val trashed = registry.moveToTrash("a")
        val record = File(trashed.parentFile, trashed.name + ".root")
        assertTrue(record.isFile)

        registry.deleteFromTrash(trashed.name)
        assertFalse(record.exists())

        project("b", "hmw" to "")
        workspace.setRoot("b", "hmw")
        val second = registry.moveToTrash("b")
        registry.emptyTrash()
        assertFalse(File(second.parentFile, second.name + ".root").exists())
    }

    @Test
    fun `a documents folder written above the main file is inside the project and is made`() {
        project("a", "books/hmw" to "option \"documents\" \"../documents\"\n")
        val main = File(workspace.projectDir("a"), "books/hmw")

        val made = DocumentRoots.ensure(main)

        assertEquals(listOf("documents"), made)
        assertTrue(File(workspace.projectDir("a"), "documents").isDirectory)
    }

    @Test
    fun `a documents folder that leaves the project is still left alone`() {
        project("a", "main.beancount" to "option \"documents\" \"../../outside\"\n")

        assertEquals(emptyList(), DocumentRoots.ensure(workspace.mainFile("a")))
    }
}
