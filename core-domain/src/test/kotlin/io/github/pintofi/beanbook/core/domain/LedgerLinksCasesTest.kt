package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two rules the import writes again in Kotlin, against what real Python and Beancount say about the
 * same folder tree. The fixture is written by tools/gen-link-cases.py, which CI runs with `--check`.
 */
class LedgerLinksCasesTest {

    private val lines = checkNotNull(javaClass.getResource("/beancount-link-cases.txt")).readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }

    private val tree = lines.filter { it.startsWith("file ") }.map { it.removePrefix("file ") }

    private fun source(extra: Map<String, String> = emptyMap()) =
        MapLinkedSource(tree.associateWith { "x".toByteArray() } + extra.mapValues { it.value.toByteArray() })

    private fun expected(line: String) = line.substringAfter("=> ").split(',').filter { it.isNotEmpty() }

    @Test
    fun `include globs expand as Python's glob does`() {
        val cases = lines.filter { it.startsWith("glob ") }
        assertTrue(cases.size >= 20)
        for (line in cases) {
            val pattern = line.removePrefix("glob ").substringBefore(" =>")

            val got = LinkGlob.expand(source(), "", pattern, 10_000)

            assertEquals(expected(line), got, "glob $pattern")
        }
    }

    @Test
    fun `a documents folder brings the files Beancount's documents plugin finds`() {
        val cases = lines.filter { it.startsWith("documents ") }
        assertTrue(cases.size >= 3)
        for (line in cases) {
            val root = line.removePrefix("documents ").substringBefore(" =>")
            val source = source(mapOf("main.bean" to "option \"documents\" \"$root\"\n"))

            val got = LedgerLinks(source).walk("main.bean").files
                .filter { it.kind == LinkedKind.DOCUMENT }.map { it.path }.sorted()

            assertEquals(expected(line), got, "documents $root")
        }
    }
}
