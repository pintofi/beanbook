package dev.beancount.app

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.web.AllowlistWebViewClient
import dev.beancount.app.web.applyBeancountHardening
import dev.beancount.core.engine.OriginAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Closes Phase 0 risk R3 on a real WebView: the allowlist is tested for what it
 * **refuses**, and every hardening flag is asserted rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class WebViewHardeningTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val allowedOrigin = "http://127.0.0.1:45999"

    private fun onMainThread(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun fakeRequest(
        url: String,
        mainFrame: Boolean = true,
        gesture: Boolean = false,
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    @Test
    fun every_hardening_flag_is_set() {
        onMainThread {
            val webView = WebView(instrumentation.targetContext)
            webView.applyBeancountHardening(debuggingAllowed = false)

            val settings = webView.settings
            assertTrue("Fava renders client-side; JS must be on", settings.javaScriptEnabled)
            assertFalse("file access must be off", settings.allowFileAccess)
            assertFalse("content:// access must be off", settings.allowContentAccess)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertEquals(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                settings.mixedContentMode,
            )
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
            assertFalse(settings.saveFormData)
            webView.destroy()
        }
    }

    @Test
    fun navigation_to_another_origin_is_blocked() {
        val blocked = mutableListOf<String>()
        val client = AllowlistWebViewClient(
            allowlist = OriginAllowlist(setOf(allowedOrigin)),
            onBlocked = { blocked += it },
        )

        onMainThread {
            val webView = WebView(instrumentation.targetContext)

            // Allowed: our own loopback origin.
            assertFalse(
                "the engine's own origin must load",
                client.shouldOverrideUrlLoading(webView, fakeRequest("$allowedOrigin/x/journal/")),
            )

            // Refused: everything else.
            listOf(
                "https://evil.example.com/",
                "http://127.0.0.1:46000/",          // right host, wrong port
                "http://localhost:45999/",           // not literally 127.0.0.1
                "file:///data/data/dev.beancount.app/files/projects/x/main.beancount",
                "content://media/external/images/1",
                "javascript:alert(document.cookie)",
                "intent://evil/#Intent;scheme=http;end",
                "http://127.0.0.1:45999@evil.example.com/",
            ).forEach { url ->
                assertTrue(
                    "must block $url",
                    client.shouldOverrideUrlLoading(webView, fakeRequest(url)),
                )
            }
            webView.destroy()
        }

        assertEquals(8, blocked.size)
    }

    @Test
    fun off_origin_subresources_are_refused_not_passed_through() {
        val client = AllowlistWebViewClient(allowlist = OriginAllowlist(setOf(allowedOrigin)))

        onMainThread {
            val webView = WebView(instrumentation.targetContext)

            // null means "let the WebView fetch it normally", so an allowed subresource
            // returns null and a blocked one must NOT.
            assertNull(
                client.shouldInterceptRequest(
                    webView,
                    fakeRequest("$allowedOrigin/static/app.js", mainFrame = false),
                ),
            )
            val blockedResponse = client.shouldInterceptRequest(
                webView,
                fakeRequest("https://cdn.example.com/tracker.js", mainFrame = false),
            )
            assertNotNull("an off-origin subresource must be intercepted", blockedResponse)
            assertEquals(403, blockedResponse!!.statusCode)
            webView.destroy()
        }
    }

    @Test
    fun a_tapped_external_link_is_handed_to_the_browser_not_loaded_inline() {
        val external = mutableListOf<Uri>()
        val client = AllowlistWebViewClient(
            allowlist = OriginAllowlist(setOf(allowedOrigin)),
            onExternalLink = { external += it },
        )

        onMainThread {
            val webView = WebView(instrumentation.targetContext)
            // A user-initiated main-frame navigation off origin.
            assertTrue(
                client.shouldOverrideUrlLoading(
                    webView,
                    fakeRequest("https://beancount.github.io/docs/", gesture = true),
                ),
            )
            // A script-initiated one must not be treated as user intent.
            assertTrue(
                client.shouldOverrideUrlLoading(
                    webView,
                    fakeRequest("https://tracker.example.com/", gesture = false),
                ),
            )
            webView.destroy()
        }

        assertEquals(1, external.size)
        assertEquals("beancount.github.io", external.single().host)
    }
}
