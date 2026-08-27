package dev.beancount.core.domain

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pre-flight scan, and the two Play boundary rules it exists to enforce
 * (CLAUDE.md, flavor boundaries; PLAN.md M11).
 *
 * Note what these tests do **not** claim. A static read of Python is not a sandbox: code can
 * assemble an import from a string at runtime, and this scan will not see it. It is Play
 * policy compliance and an honest summary shown to the user before anything runs. The bridge
 * is actually withheld inside the interpreter, where it cannot be talked around by spelling.
 */
class PluginPreflightTest {

    private val temp = File(System.getProperty("java.io.tmpdir"), "preflight-${System.nanoTime()}")
        .apply { mkdirs() }

    private fun sourceFile(name: String, body: String): File =
        File(temp, name).apply { parentFile.mkdirs(); writeText(body) }

    private fun archive(name: String, entries: Map<String, ByteArray>): File =
        File(temp, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    // ---- what is in the file ------------------------------------------------------------

    @Test
    fun `a plain python plugin is read and allowed in both editions`() {
        val plugin = sourceFile(
            "split_expenses.py",
            """
            from beancount.core import data

            __plugins__ = ['split']

            def split(entries, options_map):
                return entries, []
            """.trimIndent(),
        )

        val scan = PluginPreflight.scan(plugin)

        assertEquals(PluginKind.SOURCE_FILE, scan.kind)
        assertEquals(listOf("split_expenses.py"), scan.pythonModules)
        assertTrue(scan.bridgeImports.isEmpty())
        assertTrue(PlayRules.judge(scan).isAllowed, "a pure-Python plugin must install on Play")
        assertTrue(ExpertRules.judge(scan).isAllowed)
    }

    @Test
    fun `the same bytes always produce the same checksum, and different bytes do not`() {
        val one = sourceFile("a.py", "x = 1\n")
        val two = sourceFile("b.py", "x = 1\n")
        val three = sourceFile("c.py", "x = 2\n")

        assertEquals(PluginPreflight.scan(one).sha256, PluginPreflight.scan(two).sha256)
        assertFalse(PluginPreflight.scan(one).sha256 == PluginPreflight.scan(three).sha256)
    }

    // ---- Play rule D1: no native code --------------------------------------------------

    @Test
    fun `the play edition refuses a wheel containing native code, and says why`() {
        val wheel = archive(
            "fastthing-1.0-cp313-cp313-android_arm64.whl",
            mapOf(
                "fastthing/__init__.py" to "from ._speed import go\n".toByteArray(),
                "fastthing/_speed.cpython-313-aarch64-linux-android.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46),
            ),
        )

        val scan = PluginPreflight.scan(wheel)
        assertEquals(
            listOf("fastthing/_speed.cpython-313-aarch64-linux-android.so"),
            scan.nativeLibraries,
        )

        val verdict = assertIs<PluginVerdict.Rejected>(PlayRules.judge(scan))
        val native = assertIs<PluginRejection.NativeCode>(verdict.reasons.single())
        val message = native.describe()
        assertTrue(message.contains("native code"), message)
        // A dead end with no way forward is a bug report. Name the way forward.
        assertTrue(message.contains("Expert edition"), message)

        // The same wheel is fine in the edition that is allowed to run it.
        assertTrue(ExpertRules.judge(scan).isAllowed)
    }

    @Test
    fun `native code is found wherever it is spelled`() {
        val wheel = archive(
            "mixed.whl",
            mapOf(
                "pkg/__init__.py" to "".toByteArray(),
                "pkg/a.so" to byteArrayOf(1),
                "pkg/b.PYD" to byteArrayOf(1),
                "pkg/c.dylib" to byteArrayOf(1),
                "pkg/notes.txt" to "not code".toByteArray(),
            ),
        )

        assertEquals(3, PluginPreflight.scan(wheel).nativeLibraries.size)
    }

    // ---- Play rule D2: no Java/Android bridge -------------------------------------------

    @Test
    fun `the play edition refuses code that imports the bridge, in each of its spellings`() {
        val spellings = listOf(
            "import java",
            "from java import jclass",
            "from java.lang import String",
            "from com.chaquo.python import Python",
            "import jnius",
            "from jnius import autoclass",
            "mod = __import__('java')",
            """mod = importlib.import_module("android.os")""",
        )

        spellings.forEach { line ->
            val plugin = sourceFile("bridged.py", "$line\n__plugins__ = []\n")
            val scan = PluginPreflight.scan(plugin)

            assertEquals(1, scan.bridgeImports.size, "not seen as a bridge import: $line")
            assertEquals(1, scan.bridgeImports.single().line)

            val verdict = assertIs<PluginVerdict.Rejected>(PlayRules.judge(scan))
            assertTrue(
                verdict.reasons.any { it is PluginRejection.AndroidBridge },
                "play accepted a bridge import: $line",
            )
            assertTrue(ExpertRules.judge(scan).isAllowed, "expert must still allow: $line")
        }
    }

    @Test
    fun `a package that merely looks like the bridge is not mistaken for it`() {
        // javalang and javaobj are real packages on PyPI, and a plugin that parses Java
        // source is not a plugin that calls Android. Rejecting them would be a bug the user
        // could never work around.
        val innocent = sourceFile(
            "parser.py",
            """
            import javalang
            import javaobj.v2 as javaobj
            from javaproperties import loads
            # import java  -- this is a comment, not an import
            androids = ['not a module']
            """.trimIndent(),
        )

        val scan = PluginPreflight.scan(innocent)

        assertTrue(
            scan.bridgeImports.isEmpty(),
            "false positive: ${scan.bridgeImports}",
        )
        assertTrue(PlayRules.judge(scan).isAllowed)
    }

    // ---- archive safety ------------------------------------------------------------------

    @Test
    fun `an archive that would write outside the plugin folder is refused by both editions`() {
        val hostile = archive(
            "evil.zip",
            mapOf(
                "pkg/__init__.py" to "".toByteArray(),
                "../../../../data/data/dev.beancount.app/files/projects/main.beancount" to
                    "2020-01-01 close Assets:Everything\n".toByteArray(),
            ),
        )

        val scan = PluginPreflight.scan(hostile)
        assertEquals(1, scan.unsafePaths.size, "zip-slip entry not caught: ${scan.unsafePaths}")

        listOf(PlayRules, ExpertRules).forEach { policy ->
            val verdict = assertIs<PluginVerdict.Rejected>(policy.judge(scan))
            assertTrue(
                verdict.reasons.any { it is PluginRejection.UnsafePaths },
                "${policy.name} accepted a path escape",
            )
        }
    }

    @Test
    fun `every way of spelling an escape is an escape, and ordinary paths are not`() {
        listOf(
            "../outside.py",
            "pkg/../../outside.py",
            "/etc/passwd",
            "C:/Windows/system32/evil.py",
            "..\\outside.py",
        ).forEach { assertTrue(PluginPreflight.escapesRoot(it.replace('\\', '/')), "missed: $it") }

        listOf(
            "pkg/__init__.py",
            "pkg/sub/../mod.py",
            "./pkg/mod.py",
            "a/b/c/d.py",
        ).forEach { assertFalse(PluginPreflight.escapesRoot(it), "false positive: $it") }
    }

    @Test
    fun `an archive that expands far beyond its size is abandoned rather than read`() {
        val bomb = archive(
            "bomb.zip",
            (1..40).associate { "pad/$it.bin" to ByteArray(2 * 1024 * 1024) },
        )

        val scan = PluginPreflight.scan(bomb)

        assertTrue(scan.truncated, "an 80 MB expansion was read to the end")
        val verdict = assertIs<PluginVerdict.Rejected>(ExpertRules.judge(scan))
        assertTrue(verdict.reasons.any { it is PluginRejection.TooLarge })
    }

    @Test
    fun `something with no python in it is refused rather than installed as an empty plugin`() {
        val nothing = archive("docs.zip", mapOf("README.md" to "# hello".toByteArray()))

        val verdict = assertIs<PluginVerdict.Rejected>(ExpertRules.judge(PluginPreflight.scan(nothing)))

        assertTrue(verdict.reasons.any { it is PluginRejection.NoPythonFound })
    }

    // ---- directories ---------------------------------------------------------------------

    @Test
    fun `a package directory is scanned whole`() {
        val root = File(temp, "mypkg").apply { mkdirs() }
        File(root, "__init__.py").writeText("from . import rules\n")
        File(root, "rules.py").writeText("import java\n")
        File(root, "_fast.so").writeBytes(byteArrayOf(1, 2, 3))

        val scan = PluginPreflight.scan(root)

        assertEquals(listOf("__init__.py", "rules.py"), scan.pythonModules)
        assertEquals(listOf("_fast.so"), scan.nativeLibraries)
        assertEquals("rules.py", scan.bridgeImports.single().file)

        val verdict = assertIs<PluginVerdict.Rejected>(PlayRules.judge(scan))
        assertEquals(2, verdict.reasons.size, "both rules should bite: ${verdict.reasons}")
    }

    /** Deliberately mirrors the shipped policies, so a change to either shows up here. */
    private object PlayRules : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = false
        override val allowsAndroidBridge = false
        override val allowsSelfUpdate = false
        override val allowsPackageDownload = false
        override val name = "play"
    }

    private object ExpertRules : PluginPolicy {
        override val allowsUserCode = true
        override val allowsNativeCode = true
        override val allowsAndroidBridge = true
        override val allowsSelfUpdate = true
        override val allowsPackageDownload = true
        override val name = "expert"
    }
}
