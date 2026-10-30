package io.github.pintofi.beanbook.core.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whatever names an archive gives its files, the import plan only accepts paths that land inside the
 * project folder.
 *
 * The example tests in [ImportExportTest] pin the usual traversal spellings. This one builds names
 * from the awkward pieces (dots, empty parts, both slashes, drive letters, device names, very long and
 * non-ASCII names) and checks the plan against what the file system would actually do with
 * each accepted path, not against a second copy of the rule. The seeds are fixed, so a failure names
 * its seed and repeats.
 */
class ImportPathPropertyTest {

    private val root: File = Files.createTempDirectory("import-paths").toFile()
    private val workspace = ProjectWorkspace(root.resolve("workspace"))
    private val project = root.resolve("project").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Listed(private val names: List<String>) : ImportSource {
        override fun entries() = names.map { SourceEntry(path = it, size = 1, sourcePath = it) }
        override fun open(entry: SourceEntry): InputStream = ByteArrayInputStream(ByteArray(1))
    }

    private val pieces = listOf(
        "..", ".", "", "a", "files", "main.beancount", "CON", "nul.txt", "C:", "%2e%2e", " ..", ".. ",
        "..\\", "a\\..", "é", "a".repeat(300), "~", "-rf", "documents",
    )
    private val prefixes = listOf("", "", "", "/", "//", "C:\\", "C:/", "\\\\server\\share\\", "./", "../")

    private fun name(rnd: Random): String {
        val separator = if (rnd.nextBoolean()) "/" else "\\"
        val body = List(rnd.nextInt(1, 7)) { pieces[rnd.nextInt(pieces.size)] }.joinToString(separator)
        return prefixes[rnd.nextInt(prefixes.size)] + body
    }

    private fun assertInside(path: String, where: String) {
        val inside = project.canonicalPath + File.separator
        val resolved = runCatching { File(project, path).canonicalFile.path + File.separator }
            .getOrElse { error("$where: the file system cannot resolve it: ${it.message}") }
        assertTrue(resolved.startsWith(inside), "$where: resolves outside the project: $resolved")
    }

    @Test
    fun `every accepted path stays inside the project folder`() {
        var accepted = 0
        var refused = 0
        for (seed in 0 until SEEDS) {
            val rnd = Random(seed)
            val names = List(rnd.nextInt(1, 6)) { name(rnd) }.distinct()
            val plan = ProjectImporter(workspace).plan(Listed(names))
            for (file in plan.files) {
                accepted++
                val where = "seed $seed, from $names, accepted \"${file.path}\""
                // A leading slash is not asserted against: stripping a "./" wrapper can leave one, and it still
                // resolves inside the project, which is the property that matters and is checked below.
                assertFalse(file.path.contains('\\'), "$where: a backslash is left in it")
                // The path it will be written to, which is also the path that was judged.
                assertInside(file.path, where)
            }
            refused += names.size - plan.files.size
        }
        assertTrue(accepted > 500, "the generator should have produced plenty of accepted paths, made $accepted")
        assertTrue(refused > 500, "the generator should have produced plenty of refused paths, made $refused")
    }

    @Test
    fun `planning never throws and accounts for every name`() {
        for (seed in 0 until SEEDS) {
            val rnd = Random(seed + SEEDS)
            val names = List(rnd.nextInt(1, 8)) { name(rnd) }.distinct()
            val plan = ProjectImporter(workspace).plan(Listed(names))
            // Each name ends up planned or reported, once: none is dropped without a word.
            assertEquals(
                names.size,
                plan.files.size + plan.problems.size,
                "seed ${seed + SEEDS}: $names -> planned ${plan.files.map { it.path }}, reported ${plan.problems}",
            )
        }
    }

    /**
     * A NUL in a name is not refused by the plan: the file system rejects it later, in the import's own
     * second check, which stops the import and removes what it had staged. The user is told the import
     * failed, in general terms. This pins that nothing is left behind and nothing is written.
     */
    @Test
    fun `a name holding a NUL byte stops the import and leaves nothing behind`() {
        val source = Listed(listOf("main.beancount", "documents/a\u0000b.pdf"))
        val outcome = runCatching { ProjectImporter(workspace).import(source, "nul-test") }

        assertTrue(
            outcome.isFailure || outcome.getOrNull() is ImportResult.Rejected,
            "a name with a NUL must not be imported: ${outcome.getOrNull()}",
        )
        assertFalse(workspace.projectDir("nul-test").exists(), "no project may be created")
        assertFalse(File(workspace.projectsDir, "nul-test.importing").exists(), "no staging folder may remain")
    }

    /**
     * An entry like `a/../main.beancount` is `main.beancount`. It used to be planned as written, so when
     * every entry shared the first folder and it was stripped as a wrapper, `../main.beancount` was left,
     * pointing above the project. The path is collapsed first, so none of that can happen.
     */
    @Test
    fun `a name that goes up and down is planned under its collapsed path`() {
        val plan = ProjectImporter(workspace).plan(Listed(listOf("a/../main.beancount")))
        assertEquals(emptyList(), plan.problems)
        assertEquals(listOf("main.beancount"), plan.files.map { it.path })
    }

    @Test
    fun `importing it puts the file in the project and nothing above it`() {
        val outcome = ProjectImporter(workspace).import(Listed(listOf("a/../main.beancount")), "wrapper-test")

        assertTrue(outcome is ImportResult.Imported, "got $outcome")
        assertTrue(File(workspace.projectDir("wrapper-test"), "main.beancount").isFile)
        assertFalse(File(workspace.projectsDir, "main.beancount").exists(), "nothing may land beside the project")
        assertFalse(File(workspace.projectsDir.parentFile, "main.beancount").exists(), "nothing may land above it")
        assertFalse(File(workspace.projectsDir, "wrapper-test.importing").exists(), "no staging folder may remain")
    }

    @Test
    fun `a name that cancels to the project folder itself is refused`() {
        val plan = ProjectImporter(workspace).plan(Listed(listOf("a/..", "main.beancount")))
        assertEquals(listOf("main.beancount"), plan.files.map { it.path })
        assertTrue(plan.problems.any { it is ImportProblem.PathTraversal }, "got ${plan.problems}")
    }

    @Test
    fun `two spellings of one file are a duplicate and not a silent overwrite`() {
        val plan = ProjectImporter(workspace).plan(Listed(listOf("main.beancount", "x/../main.beancount")))
        assertEquals(1, plan.files.size)
        assertTrue(plan.problems.any { it is ImportProblem.DuplicateEntry }, "got ${plan.problems}")
    }

    private companion object {
        const val SEEDS = 1500
    }
}
