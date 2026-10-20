package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.LedgerBlueprint
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CreateFromBlueprintTest {

    private val root: File = Files.createTempDirectory("blueprint-test").toFile()
    private val workspace = ProjectWorkspace(root)
    private val blueprint = LedgerBlueprint.defaults("Books", "EUR", LocalDate.of(2026, 10, 20))

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun create(validate: (suspend (File) -> Result<List<String>>)? = null) =
        runBlocking { workspace.createFromBlueprint("books", blueprint, validate) }

    @Test fun `the project has every file and folder the blueprint names`() {
        val main = create()
        assertTrue(main.isFile)
        val dir = workspace.projectDir("books")
        blueprint.render().keys.forEach { assertTrue(dir.resolve(it).isFile, "missing $it") }
        listOf("files", "documents", "prices", "scripts", "plugins").forEach { assertTrue(dir.resolve(it).isDirectory, it) }
        assertEquals(blueprint.render().getValue("main.beancount"), main.readText())
    }

    @Test fun `the validator is shown the staged ledger, which is not yet a project`() {
        var seen: File? = null
        var existedThen = true
        create { main ->
            seen = main
            existedThen = workspace.exists("books")
            Result.success(emptyList())
        }
        assertFalse(existedThen, "the project was in place before it was validated")
        assertTrue(seen!!.path.contains(".creating-books"))
        assertTrue(workspace.exists("books"))
    }

    @Test fun `errors from Beancount leave no project and no staging folder`() {
        val failure = assertFailsWith<BlueprintRejected> {
            create { Result.success(listOf("main.beancount:3 Invalid option")) }
        }
        assertEquals(listOf("main.beancount:3 Invalid option"), failure.errors)
        assertFalse(workspace.exists("books"))
        assertEquals(emptyList(), root.resolve("projects").listFiles().orEmpty().toList())
    }

    @Test fun `a ledger that could not be read at all is a rejection too`() {
        assertFailsWith<BlueprintRejected> { create { Result.failure(IllegalStateException("parse crash")) } }
        assertFalse(workspace.exists("books"))
    }

    @Test fun `an engine that cannot be reached does not stop the ledger being created`() {
        create { Result.failure(LedgerUnavailable("engine not started")) }
        assertTrue(workspace.exists("books"))
    }

    @Test fun `a project that already exists is refused and left alone`() {
        create()
        assertFailsWith<IllegalArgumentException> { create() }
        assertTrue(workspace.mainFile("books").isFile)
    }

    @Test fun `a blueprint with problems is refused before anything is written`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { workspace.createFromBlueprint("x", blueprint.copy(title = " ")) }
        }
        assertFalse(workspace.exists("x"))
    }

    @Test fun `staging folders old enough are swept and recent ones are not`() {
        val old = root.resolve("projects/.creating-old").apply { mkdirs(); setLastModified(0) }
        val fresh = root.resolve("projects/.creating-new").apply { mkdirs() }
        workspace.sweepStaleStaging()
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
