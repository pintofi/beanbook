package io.github.pintofi.beanbook.app.web

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import io.github.pintofi.beanbook.core.engine.OriginAllowlist

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
    private val httpAuth: (() -> HttpAuth?)? = null,
    private val onLoadProblem: (String) -> Unit = {},
    // A provider, not a value: the WebViewClient is created once by AndroidView's factory,
    // so a captured string would freeze the palette at whatever it was then and switching to
    // dark would not re-skin the reports until the WebView was rebuilt.
    private val themeScript: () -> String? = { null },
) : WebViewClient() {

    /** Credentials for a server that sits behind a reverse proxy's login. */
    class HttpAuth(val username: String, val password: CharArray) {
        // A credential that prints itself is a credential in a bug report.
        override fun toString(): String = "HttpAuth(username=<redacted>, password=<redacted>)"
    }

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

    /**
     * Answers the reverse proxy's Basic-auth challenge, and only that proxy's.
     *
     * Fava has no login of its own, so a challenge can only come from something in front of
     * it. The check is the same allowlist the rest of this class uses: a redirect to another
     * host that asks for a password must get nothing, because handing credentials to
     * whoever asks is how they end up somewhere else.
     */
    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String?,
    ) {
        val credentials = httpAuth?.invoke()
        if (credentials == null || !allowlist.allowsHost(host)) {
            Log.i(TAG, "auth challenge not answered for an unexpected host")
            handler.cancel()
            return
        }
        handler.proceed(credentials.username, String(credentials.password))
    }

    /**
     * Refuses a certificate that does not check out, and says so.
     *
     * The default `WebViewClient` already cancels, which is the safe half. The missing half
     * was telling anyone: a cancelled load leaves a blank white page and no explanation, so
     * a server with an expired certificate looked identical to a server with no reports.
     * There is deliberately no `proceed()` anywhere in this file.
     */
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        handler.cancel()
        onLoadProblem(
            "The server's certificate could not be verified (${describe(error)}), so " +
                "nothing was loaded. There is no way past this: a certificate that does " +
                "not check out can mean something is between you and your server.",
        )
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        onLoadProblem("The server could not be reached (${error.description}).")
    }

    private fun describe(error: SslError): String = when (error.primaryError) {
        SslError.SSL_UNTRUSTED -> "the certificate was not issued by an authority this device trusts"
        SslError.SSL_EXPIRED -> "the certificate has expired"
        SslError.SSL_IDMISMATCH -> "the certificate is for a different server name"
        SslError.SSL_NOTYETVALID -> "the certificate is not valid yet"
        SslError.SSL_DATE_INVALID -> "the certificate's dates are not valid"
        else -> "the certificate was rejected"
    }

    override fun onPageFinished(view: WebView, url: String) {
        // Applied per page rather than once: Fava serves a fresh document for each report,
        // so a style injected into one page is gone by the next.
        themeScript()?.let { view.evaluateJavascript(it, null) }
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
        // A no-op since API 26, so at minSdk 29 this changes nothing. Kept and
        // suppressed rather than deleted: WebViewHardeningTest asserts the posture,
        // and the line is where a reader looks to see that form data was considered.
        @Suppress("DEPRECATION")
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
        // HttpOnly: the token authenticates requests to the loopback engine, and nothing on
        // the page has any reason to read it. Without the flag, script running in the Fava
        // document could — and the page renders a ledger, so it is not a place to be generous
        // (R9). The browser still sends the cookie; only script is shut out.
        setCookie(origin, "bca_token=$token; Path=/; SameSite=Strict; HttpOnly")
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
    httpAuth: (() -> AllowlistWebViewClient.HttpAuth?)? = null,
    onLoadProblem: (String) -> Unit = {},
) {
    // Recomputed when the palette changes, so switching theme re-skins the reports on their
    // next load without restarting anything.
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    // The same green and amber the journal draws movements in, so a figure keeps its colour
    // across the boundary between the app's own screens and Fava's.
    val ledger = io.github.pintofi.beanbook.app.ui.theme.BeancountTheme.ledger
    val themeScript = androidx.compose.runtime.remember(scheme, dark, ledger) {
        FavaTheme.script(scheme, dark, ledger)
    }
    val currentTheme = androidx.compose.runtime.rememberUpdatedState(themeScript)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                applyBeancountHardening(debuggingAllowed)
                webViewClient = AllowlistWebViewClient(
                    allowlist = allowlist,
                    onBlocked = onBlocked,
                    onExternalLink = onExternalLink,
                    httpAuth = httpAuth,
                    onLoadProblem = onLoadProblem,
                    themeScript = { currentTheme.value },
                )
            }
        },
        update = { view ->
            if (view.url != url) {
                view.loadUrl(url)
            } else {
                // Same page, new palette: re-skin what is already on screen instead of
                // waiting for the user to navigate somewhere.
                view.evaluateJavascript(currentTheme.value, null)
            }
        },
    )

    DisposableEffect(url) {
        onDispose { /* the WebView is released with the composition */ }
    }
}
