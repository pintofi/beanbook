package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageRecordsTest {

    private val base: File = Files.createTempDirectory("records").toFile()
    private val root = File(base, "ondemand")
    private val records = PackageRecords(root, File(base, "ondemand-records.json"))

    @AfterTest fun cleanUp() = base.deleteRecursively().let { }

    private fun dist(name: String, version: String, tag: String, vararg requires: String) {
        File(root, "$name-$version.dist-info").apply { mkdirs() }.let { dir ->
            File(dir, "WHEEL").writeText("Wheel-Version: 1.0\nTag: $tag\n")
            File(dir, "METADATA").writeText(
                "Metadata-Version: 2.1\nName: $name\n" + requires.joinToString("") { "Requires-Dist: $it\n" },
            )
        }
    }

    @Test
    fun `an install from before records is read from the packages' own folders`() {
        dist("numpy", "2.1.3", "cp313-cp313-android_24_arm64_v8a", "chaquopy-openblas (>=0.3)")
        dist("chaquopy_openblas", "0.3.27", "py3-none-any")
        dist("pandas", "2.2.3", "cp313-cp313-android_24_arm64_v8a", "numpy>=1.26", "pytz")
        dist("pytz", "2024.2", "py3-none-any")
        dist("PyYAML", "6.0.2", "py3-none-any")

        val found = records.current().associateBy { it.name }

        assertEquals("2.1.3", found["numpy"]?.version)
        assertEquals("chaquopy", found["numpy"]?.source)
        assertEquals("pypi", found["pytz"]?.source)
        assertEquals("not-recorded", found["numpy"]?.verified)
        // Asked for: nothing else needs it. Not asked for: needed by another, or a companion library.
        assertTrue(found.getValue("pandas").asked)
        assertTrue(found.getValue("pyyaml").asked)
        assertFalse(found.getValue("numpy").asked)
        assertFalse(found.getValue("pytz").asked)
        assertFalse(found.getValue("chaquopy-openblas").asked)
    }

    @Test
    fun `written records are used where they match what is installed`() {
        dist("pytz", "2024.2", "py3-none-any")
        records.write(
            listOf(PackageRecord("pytz", "2024.2", "pypi", "a".repeat(64), "published-digest", asked = true)),
        )

        val found = records.current().single()

        assertEquals("published-digest", found.verified)
        assertTrue(found.asked)
    }

    @Test
    fun `a record for another version, or for a package no longer there, is not believed`() {
        dist("pytz", "2025.1", "py3-none-any")
        records.write(
            listOf(
                PackageRecord("pytz", "2024.2", "pypi", "a".repeat(64), "published-digest", asked = false),
                PackageRecord("gone", "1.0", "pypi", "", "published-digest", asked = true),
            ),
        )

        val found = records.current()

        assertEquals(listOf("pytz"), found.map { it.name })
        assertEquals("2025.1", found.single().version)
        assertEquals("not-recorded", found.single().verified)
    }

    @Test
    fun `a damaged list falls back to the folder`() {
        dist("pytz", "2024.2", "py3-none-any")
        File(base, "ondemand-records.json").writeText("{ not json")

        assertEquals(listOf("pytz"), records.current().map { it.name })
    }
}
