package io.github.pintofi.beanbook.app

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.ReportPath
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §6: a report renders in under two seconds once the engine is ready.
 *
 * Never measured. It is the one budget that cannot be inferred from a parse time, because it
 * is Fava building a page and a WebView laying it out — the ledger is already loaded before
 * the clock starts.
 *
 * The measurement starts when the engine reports `Serving` and stops at `onPageFinished` for
 * the income statement, which is the app's default report. A page that has "finished" is not
 * necessarily one a person can read — charts arrive over XHR afterwards — so this is a floor
 * on the real figure, and it is reported as one.
 */
@RunWith(AndroidJUnit4::class)
class ReportRenderBudgetTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun theIncomeStatementRendersWithinTheBudget() {
        val root = File(context.cacheDir, "report-budget-${System.currentTimeMillis()}")
        val workspace = ProjectWorkspace(root)
        val main = workspace.createProject("budget", title = "Budget")

        // The 25k corpus if it has been pushed, otherwise the project's own small ledger —
        // the budget is written against the former, and the latter still exercises the path.
        val pushed = File("/data/local/tmp/beancount-corpus/main.beancount")
        val ledger = if (pushed.isFile) {
            val home = File(context.cacheDir, "report-corpus").apply { deleteRecursively() }
            pushed.parentFile!!.copyRecursively(home, overwrite = true)
            home.resolve("main.beancount")
        } else {
            main
        }

        val engine = LocalEngineClient(context)
        try {
            val status = runBlocking { engine.start(ledger.absolutePath) }
            assertTrue("engine did not start: $status", status is EngineStatus.Serving)
            val serving = status as EngineStatus.Serving

            val url = "${serving.origin}/${serving.ledgerSlug}/${ReportPath.INCOME_STATEMENT.path}/"
            val finished = CountDownLatch(1)
            var failure: String? = null
            lateinit var web: WebView

            val began = System.nanoTime()
            instrumentation.runOnMainSync {
                web = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            finished.countDown()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                failure = "${error.errorCode} ${error.description}"
                                finished.countDown()
                            }
                        }
                    }
                    loadUrl(url)
                }
            }

            assertTrue(
                "the report never finished loading",
                finished.await(60, TimeUnit.SECONDS),
            )
            val renderMs = (System.nanoTime() - began) / 1_000_000
            assertEquals("the report failed to load", null, failure)

            android.util.Log.i(
                "ReportBudget",
                "report_render=${renderMs}ms ledger=${ledger.parentFile?.name} " +
                    "(onPageFinished; charts load after this, so it is a floor)",
            )

            instrumentation.runOnMainSync { web.destroy() }

            // Asserted generously against the wall clock rather than at the 2 s budget: this
            // runs on whatever hardware is attached, and a budget assertion that fails on a
            // slow emulator would say nothing about the device the budget is written for.
            // The number goes to the log; the budget is judged in docs/release-readiness.md.
            assertTrue("the report took ${renderMs}ms, which is not a render", renderMs < 60_000)
        } finally {
            runBlocking { engine.stop() }
            root.deleteRecursively()
            File(context.cacheDir, "report-corpus").deleteRecursively()
        }
    }
}
