package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `trashing removes the file and tidies the account directory`() {
        val stored = store.store("p", "Expenses:Food:Groceries", date, "receipt".toByteArray())

        assertNotNull(store.trash("p", stored.relativePath))

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
    fun `trashing one document leaves its neighbours alone`() {
        val kept = store.store("p", "Assets:Cash", date, "keep".toByteArray())
        val gone = store.store("p", "Assets:Cash", date, "drop".toByteArray())

        store.trash("p", gone.relativePath)

        assertEquals(listOf(kept.relativePath), store.list("p"))
        assertEquals("keep", projectFile(kept.relativePath).readText())
    }

    @Test
    fun `a link that points outside the project reads and trashes nothing`() {
        val outside = File(root, "outside.txt").apply { writeText("not yours") }

        val escapes = listOf(
            "../../outside.txt",
            "documents/../../outside.txt",
            "/etc/passwd",
        )
        escapes.forEach { path ->
            assertNull(store.read("p", path), "read escaped the project via \"$path\"")
            assertNull(store.trash("p", path), "trash escaped the project via \"$path\"")
        }
        assertTrue(outside.isFile, "a file outside the project was touched")
    }

    @Test
    fun `listing a project with no documents is empty rather than an error`() {
        assertEquals(emptyList(), store.list("p"))
    }

    // A delete used to remove the file for good. It is now moved aside for a month, so the person
    // can take it back (#72), and the project no longer has it.
    @Test
    fun `a trashed document can be restored to where it was`() {
        val stored = store.store("p", "Expenses:Food:Groceries", date, "receipt".toByteArray())
        val trashed = assertNotNull(store.trash("p", stored.relativePath))

        assertFalse(projectFile(stored.relativePath).exists(), "still in the project")
        assertEquals(emptyList(), store.list("p"))

        assertTrue(store.restore(trashed))

        assertEquals("receipt", projectFile(stored.relativePath).readText())
        assertEquals(listOf(stored.relativePath), store.list("p"))
        assertFalse(
            File(workspace.documentTrashDir, "p/${trashed.stamp}").exists(),
            "the trash still holds a copy after a restore",
        )
    }

    @Test
    fun `the trash lists what was deleted, newest first, and can delete one or all for good`() {
        val first = store.store("p", "Expenses:Food:Groceries", date, "one".toByteArray())
        val second = store.store("p", "Expenses:Food:Dining", date, "three".toByteArray())
        val a = assertNotNull(store.trash("p", first.relativePath))
        Thread.sleep(5)
        val b = assertNotNull(store.trash("p", second.relativePath))

        val listed = store.trashedFiles("p")
        assertEquals(listOf(b, a), listed.map { it.trashed })
        assertEquals(listOf(5L, 3L), listed.map { it.bytes })

        store.deleteForever(b)
        assertEquals(listOf(a), store.trashedFiles("p").map { it.trashed })
        assertFalse(store.restore(b), "restored after deleting for good")

        store.emptyTrash("p")
        assertEquals(emptyList(), store.trashedFiles("p"))
        assertEquals(emptyList(), store.trashedFiles("other"))
    }

    @Test
    fun `a trashed document is not in the project tree, so a backup or an export cannot carry it`() {
        val stored = store.store("p", "Assets:Cash", date, "secret".toByteArray())

        store.trash("p", stored.relativePath)

        val inProject = workspace.projectDir("p").walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue("secret" !in inProject, "the trashed file is still under the project")
    }

    @Test
    fun `a restore refuses to overwrite a file that is there now`() {
        val stored = store.store("p", "Assets:Cash", date, "first".toByteArray())
        val trashed = assertNotNull(store.trash("p", stored.relativePath))
        projectFile(stored.relativePath).apply { parentFile.mkdirs() }.writeText("second")

        assertFalse(store.restore(trashed))

        assertEquals("second", projectFile(stored.relativePath).readText())
    }

    @Test
    fun `restoring twice, or something that was never trashed, does nothing`() {
        val stored = store.store("p", "Assets:Cash", date, "x".toByteArray())
        val trashed = assertNotNull(store.trash("p", stored.relativePath))

        assertTrue(store.restore(trashed))
        assertFalse(store.restore(trashed))
        assertFalse(store.restore(DocumentStore.Trashed("p", "documents/nothing.pdf", "0-0")))
    }

    @Test
    fun `trashing something that is not a file in the project returns nothing`() {
        assertNull(store.trash("p", "documents/not-there.pdf"))
        assertNull(store.trash("p", "documents"))
    }

    @Test
    fun `a month later the trash is emptied the next time anything is trashed`() {
        val old = store.store("p", "Assets:Cash", date, "old".toByteArray())
        val trashed = assertNotNull(store.trash("p", old.relativePath))
        // Age it: the stamp starts with when it was trashed.
        val aged = File(workspace.documentTrashDir, "p/${trashed.stamp}")
        val longAgo = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
        assertTrue(aged.renameTo(File(workspace.documentTrashDir, "p/$longAgo-1")))
        val fresh = store.store("p", "Assets:Cash", date, "new".toByteArray())

        store.trash("p", fresh.relativePath)

        assertFalse(File(workspace.documentTrashDir, "p/$longAgo-1").exists(), "a 31-day-old item was kept")
        assertTrue(File(workspace.documentTrashDir, "p").list().orEmpty().size == 1, "the new one was purged")
    }
}
