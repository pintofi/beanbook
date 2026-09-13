package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The documents folder a ledger names is there before Beancount looks for it (B79). */
class DocumentRootsTest {

    private val root: File = Files.createTempDirectory("roots").toFile()

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    /** The common ledger the owner supplied, copied as it is, with no documents folder. */
    private fun commonLedger(): File {
        val fixture = File("../fixtures/common-ledger")
        assertTrue(fixture.isDirectory, "fixtures/common-ledger is missing from the repository")
        val project = File(root, "common")
        fixture.copyRecursively(project)
        File(project, "documents").deleteRecursively()
        return File(project, "main.beancount")
    }

    @Test
    fun `the common ledger gets the documents folder its option names`() {
        val main = commonLedger()

        val created = DocumentRoots.ensure(main)

        assertEquals(listOf("documents"), created)
        assertTrue(File(main.parentFile, "documents").isDirectory)
    }

    @Test
    fun `an existing folder is left alone and nothing is reported`() {
        val main = commonLedger()
        val folder = File(main.parentFile, "documents").apply { mkdirs() }
        File(folder, "receipt.pdf").writeText("kept")

        assertEquals(emptyList(), DocumentRoots.ensure(main))
        assertEquals("kept", File(folder, "receipt.pdf").readText())
    }

    @Test
    fun `no ledger file changes`() {
        val main = commonLedger()
        val before = main.parentFile.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(main.parentFile).path to it.readBytes().toList() }

        DocumentRoots.ensure(main)

        val after = main.parentFile.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(main.parentFile).path to it.readBytes().toList() }
        assertEquals(before, after)
    }

    @Test
    fun `a root outside the project is never created`() {
        val project = File(root, "p").apply { mkdirs() }
        val main = File(project, "main.beancount")
        main.writeText("option \"documents\" \"../elsewhere\"\n")

        assertEquals(emptyList(), DocumentRoots.ensure(main))
        assertFalse(File(root, "elsewhere").exists())
    }

    @Test
    fun `a commented-out option asks for nothing`() {
        val project = File(root, "p").apply { mkdirs() }
        val main = File(project, "main.beancount")
        main.writeText("; option \"documents\" \"receipts\"\n")

        assertEquals(emptyList(), DocumentRoots.ensure(main))
        assertFalse(File(project, "receipts").exists())
    }

    @Test
    fun `several roots, nested ones included`() {
        val project = File(root, "p").apply { mkdirs() }
        val main = File(project, "main.beancount")
        main.writeText("option \"documents\" \"docs/a\"\noption \"documents\" \"b\"\n")

        assertEquals(listOf("docs/a", "b"), DocumentRoots.ensure(main))
    }
}
