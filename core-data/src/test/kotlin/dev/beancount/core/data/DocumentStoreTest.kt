package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStoreTest {

    private val root: File = Files.createTempDirectory("documents").toFile()
    private val workspace = ProjectWorkspace(root)
    private val store = DocumentStore(workspace)
    private val date = LocalDate.parse("2026-08-24")

    init {
        workspace.createProject("p", title = "Docs")
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun projectFile(path: String) = File(workspace.projectDir("p"), path)

    @Test
    fun `a document lands in the project, where an export will carry it`() {
        val stored = store.store("p", "Expenses:Food:Groceries", date, "receipt".toByteArray())

        assertTrue(projectFile(stored.relativePath).isFile)
        assertTrue(
            stored.relativePath.startsWith("documents/Expenses/Food/Groceries/2026-08-24."),
            stored.relativePath,
        )
        assertEquals("receipt", projectFile(stored.relativePath).readText())
    }

    @Test
    fun `saving the same capture twice stores one file, not two`() {
        val first = store.store("p", "Assets:Cash", date, "same".toByteArray())
        val second = store.store("p", "Assets:Cash", date, "same".toByteArray())

        assertEquals(first.relativePath, second.relativePath)
        assertFalse(first.alreadyExisted)
        assertTrue(second.alreadyExisted, "the second save must recognise its own work")
        assertEquals(1, store.list("p").size, "a retry left a duplicate: ${store.list("p")}")
    }

    @Test
    fun `two different receipts on the same day for the same account both survive`() {
        val first = store.store("p", "Assets:Cash", date, "one".toByteArray())
        val second = store.store("p", "Assets:Cash", date, "two".toByteArray())

        assertEquals(2, store.list("p").size)
        assertEquals("one", projectFile(first.relativePath).readText())
        assertEquals("two", projectFile(second.relativePath).readText())
    }

    @Test
    fun `a name collision takes the next ordinal instead of overwriting`() {
        // Force the collision: put different content at the name the hash would produce.
        val content = "receipt".toByteArray()
        val first = store.store("p", "Assets:Cash", date, content)
        projectFile(first.relativePath).writeText("something else entirely")

        val second = store.store("p", "Assets:Cash", date, content)

        assertTrue(second.relativePath.contains("-1."), second.relativePath)
        assertEquals(
            "something else entirely",
            projectFile(first.relativePath).readText(),
            "the file already there must not be overwritten",
        )
    }

    @Test
    fun `a half-written document is never left behind under its real name`() {
        store.store("p", "Assets:Cash", date, "receipt".toByteArray())

        assertTrue(
            store.list("p").none { it.endsWith(".part") },
            "a temporary file was left in the tree: ${store.list("p")}",
        )
    }

    @Test
    fun `deleting removes the file and tidies the account directory`() {
        val stored = store.store("p", "Expenses:Food:Groceries", date, "receipt".toByteArray())

        assertTrue(store.delete("p", stored.relativePath))

        assertFalse(projectFile(stored.relativePath).exists())
        assertFalse(
            projectFile("documents/Expenses/Food/Groceries").exists(),
            "an empty account directory should not be left behind",
        )
        assertTrue(
            projectFile("documents").isDirectory,
            "documents/ is part of the project's shape and stays",
        )
    }

    @Test
    fun `deleting one document leaves its neighbours alone`() {
        val kept = store.store("p", "Assets:Cash", date, "keep".toByteArray())
        val gone = store.store("p", "Assets:Cash", date, "drop".toByteArray())

        store.delete("p", gone.relativePath)

        assertEquals(listOf(kept.relativePath), store.list("p"))
        assertEquals("keep", projectFile(kept.relativePath).readText())
    }

    @Test
    fun `a link that points outside the project reads and deletes nothing`() {
        val outside = File(root, "outside.txt").apply { writeText("not yours") }

        val escapes = listOf(
            "../../outside.txt",
            "documents/../../outside.txt",
            "/etc/passwd",
        )
        escapes.forEach { path ->
            assertNull(store.read("p", path), "read escaped the project via \"$path\"")
            assertFalse(store.delete("p", path), "delete escaped the project via \"$path\"")
        }
        assertTrue(outside.isFile, "a file outside the project was touched")
    }

    @Test
    fun `listing a project with no documents is empty rather than an error`() {
        assertEquals(emptyList(), store.list("p"))
    }
}
