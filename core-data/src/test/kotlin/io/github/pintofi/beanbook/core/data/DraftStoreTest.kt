package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftStoreTest {

    private val root: File = Files.createTempDirectory("drafts").toFile()
    private val workspace = ProjectWorkspace(root)
    private val drafts = DraftStore(workspace)

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    @Test
    fun `a draft survives a restart, contents intact`() {
        val text = "2026-09-01 note Assets:Cash \"half-typed\"\n; with a comment\n"
        drafts.save("p", "files/transactions.beancount", text, "sha-1")

        val restored = DraftStore(workspace).load("p", "files/transactions.beancount")

        assertEquals(text, restored?.text)
        assertEquals("sha-1", restored?.baseSha)
        assertTrue((restored?.savedAt ?: 0) > 0)
    }

    @Test
    fun `text containing newlines and blank lines round-trips exactly`() {
        val text = "\n\nline one\n\n\nline two\n"
        drafts.save("p", "main.beancount", text, "sha")
        assertEquals(text, drafts.load("p", "main.beancount")?.text)
    }

    @Test
    fun `a draft knows when the file changed underneath it`() {
        drafts.save("p", "main.beancount", "typed", baseSha = "old-sha")
        val draft = drafts.load("p", "main.beancount")!!

        assertFalse(draft.isStaleAgainst("old-sha"))
        assertTrue(
            draft.isStaleAgainst("new-sha"),
            "restoring over a changed file would silently revert someone else's edit",
        )
    }

    @Test
    fun `clearing removes it`() {
        drafts.save("p", "main.beancount", "typed", "sha")
        drafts.clear("p", "main.beancount")
        assertNull(drafts.load("p", "main.beancount"))
    }

    @Test
    fun `drafts live outside the project tree`() {
        workspace.createProject("p", title = "P")
        drafts.save("p", "main.beancount", "typed", "sha")

        val projectFiles = workspace.projectDir("p").walkTopDown().map { it.name }.toList()
        assertFalse(projectFiles.any { it.endsWith(".draft") }, "a draft must never be exported")
        assertTrue(drafts.listFor("p").contains("main.beancount"))
    }

    @Test
    fun `a missing or truncated draft reads as none rather than throwing`() {
        assertNull(drafts.load("p", "never-written.beancount"))

        val broken = workspace.draftsDir.resolve("p").resolve("x.beancount.draft")
        broken.parentFile.mkdirs()
        broken.writeText("only-one-line")
        assertNull(drafts.load("p", "x.beancount"))
    }
}
