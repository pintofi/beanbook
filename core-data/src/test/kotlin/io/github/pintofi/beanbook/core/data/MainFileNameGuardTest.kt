package io.github.pintofi.beanbook.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The main file's name is spelled in one place. A project may keep the name and place of the file it was
 * imported from (see [ProjectWorkspace.mainFile]), so code that wrote `main.beancount` itself would be
 * right for some projects and wrong for others, quietly. Everything asks [ProjectWorkspace] instead.
 */
class MainFileNameGuardTest {

    /**
     * Spelled there on purpose. ImportExport and the import messages in ProjectsState describe the importer's
     * rename to main.beancount, which goes when it stops renaming (#369). NewLedgerSetup describes a new
     * ledger, which the blueprint always writes as main.beancount.
     */
    private val allowed = setOf(
        "ProjectWorkspace.kt", "LedgerBlueprint.kt", "ImportExport.kt", "ProjectsState.kt", "NewLedgerSetup.kt",
    )

    private val spelled = Regex("""main\.beancount|\bMAIN_FILE\b""")

    @Test
    fun `no main source outside the workspace spells the main file's name`() {
        val modules = listOf(
            "core-domain", "core-data", "core-engine", "engine-local", "engine-remote", "app", "scanner",
        )
        val mainSets = listOf("/src/main/", "/src/expert/", "/src/play/")
        val offenders = modules.map { File("../$it/src") }.filter { it.isDirectory }.flatMap { module ->
            module.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
                .filter { file -> mainSets.any { it in file.invariantSeparatorsPath } }
                .filter { file ->
                    file.readLines().any { line ->
                        val code = line.trim()
                        !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") &&
                            spelled.containsMatchIn(code.substringBefore(" //"))
                    }
                }
                .map { it.invariantSeparatorsPath }
        }
        assertEquals(emptyList<String>(), offenders, "ask ProjectWorkspace.mainFile instead; see MainFileNameGuardTest")
    }
}
