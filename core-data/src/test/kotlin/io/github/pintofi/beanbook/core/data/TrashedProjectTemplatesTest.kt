package io.github.pintofi.beanbook.core.data

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A project's entry templates live outside its folder, so they have to move with it. */
class TrashedProjectTemplatesTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "trashtemplates-${System.nanoTime()}")
        .apply { mkdirs() }
    private val workspace = ProjectWorkspace(File(root, "app"))
    private val registry = ProjectRegistry(workspace, File(root, "state"))

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun templatesOf(id: String) = workspace.templatesDir.resolve("$id.templates")

    private fun projectWithTemplates(id: String, text: String) {
        workspace.createProject(id, title = id)
        workspace.templatesDir.mkdirs()
        templatesOf(id).writeText(text)
    }

    @Test
    fun `a deleted project takes its templates, and a new project of the same name does not inherit them`() {
        projectWithTemplates("books", "rent\n")

        registry.moveToTrash("books")
        assertFalse(templatesOf("books").exists(), "the templates stayed behind")

        workspace.createProject("books", title = "New books")
        assertFalse(templatesOf("books").exists(), "a new project started with the old one's templates")
    }

    @Test
    fun `restoring from the trash brings the templates back, under the id it comes back as`() {
        projectWithTemplates("books", "rent\n")
        val trashed = registry.moveToTrash("books")
        workspace.createProject("books", title = "Another")

        val id = registry.restoreFromTrash(trashed)

        assertTrue(id != "books", "the id was taken, so it comes back as another")
        assertEquals("rent\n", templatesOf(id).readText())
        assertFalse(templatesOf("books").exists())
    }

    @Test
    fun `deleting from the trash for good deletes the templates too`() {
        projectWithTemplates("a", "x\n")
        projectWithTemplates("b", "y\n")
        val first = registry.moveToTrash("a")
        registry.moveToTrash("b")

        registry.deleteFromTrash(first.name)
        assertFalse(File(first.parentFile, first.name + ".templates").exists())

        registry.emptyTrash()
        assertEquals(emptyList(), File(root, "state/trash").listFiles()?.toList().orEmpty())
    }
}
