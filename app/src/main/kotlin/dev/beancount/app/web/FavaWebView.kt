package dev.beancount.app.web

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.beancount.core.engine.OriginAllowlist

private const val TAG = "FavaWebView"

/**
 * Blocks every navigation and subresource that is not on an allowed origin.
 *
 * This is a security control, so it is a named class with injectable callbacks rather
 * than an anonymous object: it has to be testable (Phase 0 risk R3).
 */
class AllowlistWebViewClient(
    private val allowlist: OriginAllowlist,
    private val onBlocked: (String) -> Unit = {},
    private val onPageLoaded: (String) -> Unit = {},
    private val onExternalLink: (Uri) -> Unit = {},
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (allowlist.isAllowed(url)) return false

        Log.i(TAG, "navigation blocked: ${request.url.scheme}://${request.url.host}")
        onBlocked(url)
        // A user tapping a genuine external link should still be able to follow it, but
        // only through the browser, after an explicit confirmation the caller shows.
        if (request.hasGesture() && request.isForMainFrame) onExternalLink(request.url)
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (allowlist.isAllowed(url)) return null

        Log.i(TAG, "subresource blocked: ${request.url.scheme}://${request.url.host}")
        onBlocked(url)
        // An empty response, not null: null would let the request proceed.
        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageLoaded(url)
    }
}

/**
 * Applies every WebView hardening rule from docs/architecture.md §4 and
 * docs/threat-model.md §3.4. Kept separate from the composable so instrumentation tests
 * can assert the settings directly.
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyBeancountHardening(debuggingAllowed: Boolean = false) {
    settings.apply {
        // Fava renders every report client-side, so JavaScript is not optional
        // (Phase 0 finding C10).
        javaScriptEnabled = true
        domStorageEnabled = true

        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false

        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        saveFormData = false
        cacheMode = WebSettings.LOAD_DEFAULT
    }
    // Deliberately no addJavascriptInterface: the Fava WebView gets no bridge into the
    // app, in either edition.
    WebView.setWebContentsDebuggingEnabled(debuggingAllowed)

    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
}

/**
 * Hands the per-process token to the WebView as a cookie scoped to the loopback origin.
 * The token never appears in a URL, so it cannot leak through history, logs, or a
 * `Referer` header.
 */
fun installSessionToken(origin: String, token: String) {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setCookie(origin, "bca_token=$token; Path=/; SameSite=Strict")
        flush()
    }
}

fun clearSessionToken(origin: String) {
    CookieManager.getInstance().apply {
        setCookie(origin, "bca_token=; Path=/; Max-Age=0")
        flush()
    }
}

@Composable
fun FavaWebView(
    url: String,
    allowlist: OriginAllowlist,
    modifier: Modifier = Modifier,
    debuggingAllowed: Boolean = false,
    onBlocked: (String) -> Unit = {},
    onExternalLink: (Uri) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                applyBeancountHardening(debuggingAllowed)
                webViewClient = AllowlistWebViewClient(
                    allowlist = allowlist,
                    onBlocked = onBlocked,
                    onExternalLink = onExternalLink,
                )
            }
        },
        update = { view -> if (view.url != url) view.loadUrl(url) },
    )

    DisposableEffect(url) {
        onDispose { /* the WebView is released with the composition */ }
    }
}
