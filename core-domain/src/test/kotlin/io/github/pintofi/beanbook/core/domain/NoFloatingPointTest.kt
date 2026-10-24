package io.github.pintofi.beanbook.core.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Invariant 1: money is never a binary floating-point number. This module's code may not use
 * `Float`, `Double` or their conversions, apart from the files named here with the reason.
 * A new use fails the build and has to be argued for in this list.
 */
class NoFloatingPointTest {

    private val allowed: Map<String, String> = mapOf(
        "CropEditing.kt" to "pixel geometry of the crop editor, not money",
        "ScanTone.kt" to "tone curves and kernel sizes for scanned pages, not money",
        "PageChoice.kt" to "choosing a page outline from pixel geometry, not money",
        "CropQuad.kt" to "pixel coordinates of a page crop, not money",
        "HomeInsights.kt" to "CategorySpend.shareOf: a bar's width on screen, from exact amounts",
    )

    private val floating = Regex("""\b(Float|Double|FloatArray|DoubleArray|toDouble|toFloat)\b""")

    @Test
    fun `no main source uses floating point apart from the listed files`() {
        val source = File("src/main/kotlin")
        val offenders = source.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowed }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.trim()
                    !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") &&
                        floating.containsMatchIn(code.substringBefore("//"))
                }
            }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders, "floating point in money code; see NoFloatingPointTest")
    }

    @Test
    fun `every excused file still uses floating point`() {
        val source = File("src/main/kotlin")
        val stale = allowed.keys.filter { name ->
            source.walkTopDown().none { it.name == name && floating.containsMatchIn(it.readText()) }
        }
        assertEquals(emptyList<String>(), stale, "these no longer need an exception")
    }
}
