package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC.md Q8, answered on a device: **can the Java/Android bridge be withheld from user
 * plugin code reliably enough to be a build-verifiable property?**
 *
 * The desktop tests cannot answer it. There is no Chaquopy there, so `import java` fails
 * whether or not anything is guarding it — a test that passes because the thing it is
 * blocking does not exist proves nothing at all. Here the bridge is genuinely present, and
 * the first test below establishes that before any of the others mean anything.
 *
 * This runs in both flavors and asserts against the flavor's own policy, so the play build
 * and the expert build are each checked for the behaviour they are supposed to have.
 */
@RunWith(AndroidJUnit4::class)
class PluginBridgeTest {

    private val policy = currentPluginPolicy()
    private val python: Python get() = Python.getInstance()
    private val plugins: PyObject get() = python.getModule("plugins")

    private fun probe(allowBridge: Boolean): Map<String, String> =
        plugins.callAttr("probe_bridge", allowBridge).asMap()
            .entries.associate { (k, v) -> k.toString() to v.toString() }

    // ---- the control: the bridge really is there ----------------------------------------

    @Test fun theBridgeIsGenuinelyAvailableWhenNothingIsWithholdingIt() {
        // Without this every other assertion here is worthless: "blocked" and "was never
        // available" look identical from the outside.
        val available = probe(allowBridge = true)

        assertEquals(
            "Chaquopy's java module is not importable at all, so nothing below is a test " +
                "of withholding it: $available",
            "imported",
            available["java"],
        )
    }

    // ---- the boundary ---------------------------------------------------------------------

    @Test fun theGuardWithholdsTheBridgeWhateverItIsCalled() {
        val guarded = probe(allowBridge = false)

        assertTrue(
            "java was still importable with the guard installed: ${guarded["java"]}",
            guarded["java"]!!.startsWith("blocked"),
        )
        // The names a denylist would miss. Chaquopy resolves any Java package, so these are
        // the ones that matter: they are refused because its importer is detached, not
        // because anyone listed them.
        listOf("com.chaquo.python", "androidx.core").forEach { name ->
            assertFalse(
                "$name was importable with the guard installed: ${guarded[name]}",
                guarded[name] == "imported",
            )
        }
    }

    @Test fun theBridgeWorksAgainOnceTheGuardIsGone() {
        // A guard that never lets go would break the app's own use of Chaquopy.
        probe(allowBridge = false)

        assertEquals("imported", probe(allowBridge = true)["java"])
    }

    // ---- through the real loading path ----------------------------------------------------

    @Test fun aPluginThatImportsTheBridgeFailsUnderThePlayPolicyAndWorksUnderTheExpertOne() {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "bridge-plugin-${System.nanoTime()}",
        ).apply { mkdirs() }
        // The statement form on purpose. `importlib.import_module("java")` was already
        // refused by an earlier version of the guard that this form walked straight past,
        // because Chaquopy hooks `builtins.__import__` rather than `sys.meta_path`.
        File(dir, "reaches_out.py").writeText(
            """
            from java.lang import System
            LOADED = System.currentTimeMillis() > 0
            """.trimIndent(),
        )

        val underPlay = load(dir, allowBridge = false)
        assertEquals(
            "the plugin was expected to fail under the play policy; runs=${underPlay["runs"]}",
            1,
            underPlay["failed"].toString().toInt(),
        )
        assertTrue(
            "expected a bridge refusal, got ${underPlay["runs"]}",
            underPlay["bridge_refusals"].toString().contains("java"),
        )

        val underExpert = load(dir, allowBridge = true)
        assertEquals(
            "the same plugin must work where the bridge is allowed: ${underExpert["runs"]}",
            1,
            underExpert["loaded"].toString().toInt(),
        )

        dir.deleteRecursively()
    }

    @Test fun theFlavorEnforcesItsOwnPolicy() {
        // The two editions differ here and only here; whichever one this APK is, it must
        // behave the way its policy says.
        val asShipped = probe(allowBridge = policy.allowsAndroidBridge)

        if (policy.allowsAndroidBridge) {
            assertEquals("expert must reach the bridge", "imported", asShipped["java"])
        } else {
            assertTrue(
                "play must not reach the bridge: ${asShipped["java"]}",
                asShipped["java"]!!.startsWith("blocked"),
            )
        }
    }

    private fun load(dir: File, allowBridge: Boolean): Map<String, PyObject> =
        plugins.callAttr("load_plugins", dir.absolutePath, "reaches_out", allowBridge)
            .asMap().entries.associate { (k, v) -> k.toString() to v }

    companion object {
        @JvmStatic
        @BeforeClass
        fun startPython() {
            if (!Python.isStarted()) {
                Python.start(
                    AndroidPlatform(
                        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                    ),
                )
            }
        }
    }
}
