package io.github.pintofi.beanbook.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.web.FavaWebView
import io.github.pintofi.beanbook.app.web.installSessionToken
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.OriginAllowlist
import io.github.pintofi.beanbook.core.engine.ReportPath
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

/**
 * Fava's editor renders, and `100vh` means something inside the app's WebView (B47).
 *
 * The editor came up blank while its whole document was present in the DOM, which is why
 * this measures **layout** rather than content: `AndroidView` measures its view once with an
 * unconstrained height, and a WebView measured that way tells Chromium the viewport is zero
 * high. Every viewport unit on the page then resolves to 0. Reports laid out in ordinary flow
 * were unaffected, so nothing else in the app noticed; Fava's editor is sized entirely in
 * `vh` and collapsed to nothing.
 *
 * A content assertion would have passed throughout the bug. This one fails.
 */
@RunWith(AndroidJUnit4::class)
class FavaEditorRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun theEditorIsGivenARealViewportAndDrawsItsText() {
        val root = File(context.cacheDir, "editor-render-${System.currentTimeMillis()}")
        val workspace = ProjectWorkspace(root)
        val main = workspace.createProject("editor", title = "Editor")
        val engine = LocalEngineClient(context)
        try {
            val status = runBlocking { engine.start(main.absolutePath) }
            assertTrue("engine did not start: $status", status is EngineStatus.Serving)
            val serving = status as EngineStatus.Serving
            // The engine refuses anything without the per-process token, exactly as it
            // refuses another app on the device.
            installSessionToken(serving.origin, engine.sessionToken!!)

            compose.setContent {
                FavaWebView(
                    url = "${serving.origin}/${serving.ledgerSlug}/${ReportPath.EDITOR.path}/",
                    allowlist = OriginAllowlist(setOf(serving.origin)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            compose.waitForIdle()
            Thread.sleep(8_000)   // Fava mounts the report after the document is done

            val web = findWebView(compose.activity.window.decorView)
            assertTrue("no WebView was created", web != null)

            val viewport = evaluate(
                web!!,
                "(function(){var d=document.createElement('div');" +
                    "d.style.cssText='height:100vh;position:absolute;visibility:hidden';" +
                    "document.documentElement.appendChild(d);" +
                    "var h=d.getBoundingClientRect().height;d.remove();" +
                    "return h + '/' + window.innerHeight})()",
            ).trim('"')
            val (vh, innerHeight) = viewport.split("/").map { it.toDouble() }
            assertTrue(
                "100vh resolved to $vh in a $innerHeight-high viewport, so every viewport " +
                    "unit on the page is zero and the editor draws nothing",
                vh > innerHeight / 2,
            )

            val editorHeight = evaluate(
                web,
                "String((document.querySelector('.cm-editor')||{getBoundingClientRect:" +
                    "function(){return{height:0}}}).getBoundingClientRect().height)",
            ).trim('"').toDouble()
            assertTrue("the editor is $editorHeight high, which is not a page", editorHeight > 200)
        } finally {
            runBlocking { engine.stop() }
            root.deleteRecursively()
        }
    }

    private fun findWebView(view: View): WebView? = when {
        view is WebView -> view
        view is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) { value ->
                result = value.orEmpty()
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }
}
