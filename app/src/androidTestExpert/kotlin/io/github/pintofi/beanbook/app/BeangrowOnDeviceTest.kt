package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.pintofi.beanbook.app.packages.FetchResult
import io.github.pintofi.beanbook.app.packages.WheelFetcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * beangrow, fetched on demand and computing a real return, on an Android device (D6).
 *
 * This entry said "cannot be built for Android at all", concluded from SciPy having no
 * CPython 3.13 wheel. SciPy is used for **one call** — a one-dimensional root find for IRR —
 * and everything else beangrow needs has an Android build. So the claim was wrong, and this
 * is what replaces it: the packages fetched, the overlay applied, an internal rate of return
 * computed on the device.
 *
 * It downloads about 50 MB, which is why the packages are not in the APK.
 */
@RunWith(AndroidJUnit4::class)
class BeangrowOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "ondemand")

    @Test fun beangrowIsFetchedAndComputesAnInternalRateOfReturn() {
        val fetcher = WheelFetcher(root, allowed = true, allowNative = true, assets = context.assets)

        // A second run costs nothing. That is the point of `installed()`, and until this
        // nothing called it — so the property it exists to provide was never exercised.
        if ("beangrow" in fetcher.installed() && "numpy" in fetcher.installed()) {
            android.util.Log.i(TAG, "already installed; skipping the download")
        } else {
            install(fetcher)
        }

        checkItComputes(fetcher)
    }

    private fun install(fetcher: WheelFetcher) {
        val resolution = fetcher.resolve(listOf("beangrow")).getOrElse {
            throw AssertionError("could not resolve beangrow: $it")
        }
        android.util.Log.i(
            TAG,
            "resolved ${resolution.wheels.size}: " +
                resolution.wheels.joinToString { "${it.name} ${it.version}" } +
                " | unavailable: ${resolution.unavailable}",
        )
        assertTrue(
            "scipy should not be in the closure — the overlay removes the only call to it",
            resolution.wheels.none { it.name == "scipy" },
        )

        val installed = fetcher.install(resolution.wheels)
        assertTrue("install failed: $installed", installed is FetchResult.Installed)
        val megabytes = (installed as FetchResult.Installed).bytes / 1_000_000
        android.util.Log.i(TAG, "installed ${resolution.wheels.size} packages, $megabytes MB")

    }

    private fun checkItComputes(fetcher: WheelFetcher) {
        // The overlay must have replaced beangrow's returns.py, or the import below reaches
        // for SciPy and there is none.
        val returns = File(root, "beangrow/returns.py")
        assertTrue("beangrow/returns.py is missing", returns.isFile)
        assertTrue(
            "the overlay did not apply — this is still upstream's SciPy version",
            returns.readText().contains("def solve_irr"),
        )

        // The companion libraries Chaquopy's native wheels link against. Without this,
        // numpy installs perfectly and fails to import.
        val preloaded = fetcher.preloadNativeLibraries()
        android.util.Log.i(TAG, "preloaded: $preloaded")

        val python = Python.getInstance()
        python.getModule("sys").get("path")!!.callAttr("insert", 0, root.absolutePath)
        python.getModule("importlib").callAttr("invalidate_caches")

        val module = try {
            python.getModule("beangrow.returns")
        } catch (missing: com.chaquo.python.PyException) {
            throw AssertionError("beangrow does not import on device: ${missing.message}")
        }

        // Twelve monthly contributions and a payout — the shape a real portfolio has, and the
        // case checked against SciPy on desktop, where it agreed to 3.2e-13.
        // Built on the Python side. A Kotlin List arrives as a Java proxy that numpy wraps
        // as an object array rather than a float one, and the failure surfaces much later as
        // `unsupported operand type(s) for ** or pow(): 'float' and 'ArrayList'`.
        fun pythonList(values: List<Double>) = python.builtins.callAttr("list").also { list ->
            values.forEach { list.callAttr("append", it) }
        }

        val numpy = python.getModule("numpy")
        val flows = numpy.callAttr("array", pythonList((0 until 12).map { -100.0 } + listOf(1500.0)))
        val years = numpy.callAttr(
            "array",
            pythonList((0 until 12).map { -(12 - it) / 12.0 } + listOf(0.0)),
        )
        val irr = module.callAttr("solve_irr", flows, years, 0.2).toDouble()

        android.util.Log.i(TAG, "irr=$irr")
        assertEquals(
            "the internal rate of return does not match the value SciPy computes for this series",
            0.4914752702,
            irr,
            1e-6,
        )
    }

    private companion object {
        const val TAG = "BeangrowOnDevice"

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
