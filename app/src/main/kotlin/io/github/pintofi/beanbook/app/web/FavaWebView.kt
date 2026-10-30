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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import io.github.pintofi.beanbook.core.engine.OriginAllowlist
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

private const val TAG = "FavaWebView"

/**
 * Blocks every navigation and subresource that is not on an allowed origin.
 *
 * This is a security control, so it is a named class with injectable callbacks rather
 * than an anonymous object: it has to be testable.
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
    /** Every address the page moves to, including Fava's in-page navigation. */
    private val onUrlChanged: (String) -> Unit = {},
    /** A document load began; [onPageLoaded] says when it ended (the native progress rule). */
    private val onPageBegan: (String) -> Unit = {},
    /** The page's renderer process died or was killed; the view is no longer usable. */
    private val onRendererGone: () -> Unit = {},
    /**
     * How many times a main-frame load that failed is tried again, after a short wait, before
     * it is reported. The local server can be a moment behind the WebView, or on a new
     * port after Android revived the app, and the first load then fails; Android shows its own
     * error page and *still* calls `onPageFinished`, which counted as "loaded" and took the
     * loading cover away, leaving that page on screen.
     */
    private val retries: Int = 0,
) : WebViewClient() {

    private var attemptsLeft = retries
    private var failedUrl: String? = null
    private var failedMessage: String? = null

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        onPageBegan(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Fava moves between reports without loading a new document, so onPageFinished does
        // not see it; the history update does.
        onUrlChanged(url)
    }

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

    /**
     * The renderer process behind the page is gone: Android reclaimed its memory, or it crashed.
     *
     * Not handling this takes the whole app down with it, and the system does this to a
     * WebView in the background more often than to almost anything else. Returning true says
     * the app has dealt with it, and obliges it to stop using this view: [onRendererGone]
     * drops it and builds another.
     */
    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        Log.w(TAG, "the report renderer ended (crashed=${detail.didCrash()})")
        onRendererGone()
        return true
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        // Not reported yet: onPageFinished decides, after a retry or two, so the person sees
        // the loading cover and not a browser error page.
        failedUrl = request.url.toString()
        failedMessage = "The server could not be reached (${error.description})."
    }

    private fun describe(error: SslError): String = when (error.primaryError) {
        SslError.SSL_UNTRUSTED -> "the certificate was not issued by an authority this device trusts"
        SslError.SSL_EXPIRED -> "the certificate has expired"
        SslError.SSL_IDMISMATCH -> "the certificate is for a different server name"
        SslError.SSL_NOTYETVALID -> "the certificate is not valid yet"
        SslError.SSL_DATE_INVALID -> "the certificate's dates are not valid"
        else -> "the certificate was rejected"
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: android.webkit.WebResourceResponse,
    ) {
        // Only for the local server, which answers 401 until the session cookie is in place
        // and 5xx while Fava is still starting: those pages are plain text, not a report.
        if (retries == 0 || !request.isForMainFrame) return
        val status = errorResponse.statusCode
        if (status == 401 || status == 403 || status >= 500) {
            failedUrl = request.url.toString()
            failedMessage = "Reports answered with an error ($status)."
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        val failed = failedUrl
        if (failed != null) {
            // This "page" is Android's error page, not a report: it is neither themed nor
            // counted as loaded, so the native cover stays up while the load is tried again.
            val message = failedMessage.orEmpty()
            failedUrl = null
            failedMessage = null
            if (attemptsLeft > 0) {
                attemptsLeft -= 1
                val wait = RETRY_WAIT_MS * (retries - attemptsLeft)
                view.postDelayed({ view.loadUrl(failed) }, wait)
            } else {
                attemptsLeft = retries
                onLoadProblem(message)
            }
            return
        }
        attemptsLeft = retries
        // Applied per page rather than once: Fava serves a fresh document for each report,
        // so a style injected into one page is gone by the next.
        //
        // The page counts as loaded, and the native cover goes, only after the style has run
        // *and* the WebView has drawn a frame that includes it. Calling onPageLoaded
        // straight after the asynchronous evaluateJavascript uncovered Fava's own layout and
        // colours for a moment before the app's were applied.
        val script = themeScript()
        if (script == null) {
            onPageLoaded(url)
            return
        }
        view.evaluateJavascript(script) {
            view.postVisualStateCallback(
                url.hashCode().toLong(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        onPageLoaded(url)
                    }
                },
            )
        }
    }

    private companion object {
        const val RETRY_WAIT_MS = 600L
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
        // Fava renders every report client-side, so JavaScript is not optional.
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
        // document could — and the page renders a ledger, so it is not a place to be generous.
        // The browser still sends the cookie; only script is shut out.
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

/**
 * A report WebView that outlives the Reports tab.
 *
 * Leaving Reports used to dispose of the WebView, so every visit started Fava again from its
 * first page -- slow on a large ledger, and it forgot the report the person was reading.
 * Held by the app's composition, it is only moved from one parent to the next, and it loads
 * nothing unless the address the app asks for changes.
 */
class RetainedWebView {
    internal var view: WebView? = null
    internal var requested: String? = null
    internal var loaded: Boolean = false

    /** Where the page is now, including in-page moves. */
    var currentUrl by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set

    /**
     * The page the app's own request landed on -- for the ledger's index, the report the
     * file's `default-page` Fava option names -- which Back returns to before leaving Reports.
     */
    var landing by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set

    /** Whether the page is somewhere other than where it landed. */
    val awayFromLanding: Boolean
        get() {
            val here = currentUrl?.trimEnd('/') ?: return false
            val home = landing?.trimEnd('/') ?: return false
            return here != home
        }

    /** Loads [url] in the retained page, if there is one. */
    fun load(url: String) {
        view?.loadUrl(url)
    }
}

/** The report page kept for the app's Reports tab; provided by the app's composition. */
val LocalReportsPage = androidx.compose.runtime.staticCompositionLocalOf<RetainedWebView?> { null }

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
    /** Keeps the page across leaving and returning; null for a page of its own. */
    retained: RetainedWebView? = null,
) {
    // Whether the page has ever finished loading, so the native shell can hold something in
    // front of a blank WebView. A report takes a moment to render and Fava paints white
    // until it does, which read as the app having lost the screen. Nothing is injected into
    // the page and nothing about the WebView's security changes: this is a native cover over
    // a native slot, removed when Fava says it is ready.
    var loaded by androidx.compose.runtime.saveable.rememberSaveable(url) {
        androidx.compose.runtime.mutableStateOf(retained?.loaded == true && retained.requested == url)
    }
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

    // Whether Fava is working on something right now: a document load,
    // from onPageStarted to onPageFinished, or one of Fava's own in-page navigations, which load
    // no document and are read from its header spinner. A 30 s report used to show no native
    // sign of life at all (device audit 2026-10-10).
    var documentLoading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var favaBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val webRef = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<WebView?>(null) }
    // Bumped when the page's renderer dies, which discards the WebView and builds a new one.
    val rebuilds = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val onLoaded = androidx.compose.runtime.rememberUpdatedState { at: String ->
        documentLoading = false
        loaded = true
        retained?.let {
            if (!it.loaded || it.landing == null) it.landing = at
            it.loaded = true
            it.currentUrl = at
        }
    }.value

    androidx.compose.foundation.layout.Box(modifier) {
    androidx.compose.runtime.key(rebuilds.intValue) {
    AndroidView(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        factory = { context ->
            // A FrameLayout around the WebView, not the WebView on its own. `AndroidView`
            // measures its view once with an unconstrained height, and a WebView measured
            // that way tells Chromium its viewport height is zero -- so `100vh` resolves to
            // **0** and stays there. Reports laid out in ordinary flow were unaffected and
            // looked right; Fava's editor sizes itself entirely in viewport units, so it
            // collapsed into nothing and the page came up blank with the whole document
            // present in the DOM. The wrapper absorbs that measure pass and the WebView is
            // laid out MATCH_PARENT inside it, which is what makes `vh` mean something
            // (`ViewportUnitsTest`).
            android.widget.FrameLayout(context).apply {
                val kept = retained?.view
                (kept?.parent as? android.view.ViewGroup)?.removeView(kept)
                val web = (kept ?: WebView(context).apply { applyBeancountHardening(debuggingAllowed) }).apply {
                    retained?.view = this
                    webRef.value = this
                    // A page that renders nothing says nothing without this: the report is
                    // Fava's own JavaScript, and when it throws the only record is the
                    // browser console. Debug builds only, and it reads messages rather than
                    // exposing anything to the page -- a WebChromeClient is not a bridge.
                    if (debuggingAllowed) {
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(
                                message: android.webkit.ConsoleMessage,
                            ): Boolean {
                                Log.i(
                                    TAG,
                                    "console ${message.messageLevel()}: ${message.message()} " +
                                        "(${message.sourceId()}:${message.lineNumber()})",
                                )
                                return true
                            }
                        }
                    }
                    webViewClient = AllowlistWebViewClient(
                        allowlist = allowlist,
                        onBlocked = onBlocked,
                        onExternalLink = onExternalLink,
                        httpAuth = httpAuth,
                        onLoadProblem = onLoadProblem,
                        onPageLoaded = { onLoaded(it) },
                        themeScript = { currentTheme.value },
                        onUrlChanged = { retained?.currentUrl = it },
                        onPageBegan = { documentLoading = true },
                        onRendererGone = {
                            // A dead WebView cannot be used again. It leaves the retained slot
                            // and the screen, and a new one is built in its place and loads
                            // the same address.
                            webRef.value = null
                            retained?.view = null
                            retained?.requested = null
                            retained?.loaded = false
                            loaded = false
                            (parent as? android.view.ViewGroup)?.removeView(this)
                            destroy()
                            rebuilds.intValue += 1
                            // A server the user hosts has no retry of its own to fall back on.
                            if (retained == null) {
                                onLoadProblem(
                                    "The report view was closed by Android, usually to free memory. " +
                                        "Open Reports again.",
                                )
                            }
                        },
                        // The local server is retried; a server the user hosts is reported at once.
                        retries = if (retained != null) 4 else 0,
                    )
                }
                addView(
                    web,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { host ->
            val view = host.getChildAt(0) as? WebView ?: return@AndroidView
            // A retained page loads only when the app asks for a different address, never
            // because the person moved to another report inside it.
            val reload = if (retained != null) retained.requested != url else view.url != url
            if (reload) {
                retained?.requested = url
                retained?.loaded = false
                retained?.landing = null
                view.loadUrl(url)
            } else {
                // Same page, new palette: re-skin what is already on screen instead of
                // waiting for the user to navigate somewhere.
                view.evaluateJavascript(currentTheme.value, null)
            }
        },
    )
    }

    // The report fading up over the shell's own loading rule, rather than a white page
    // becoming a report between two frames. An instant swap with animations off (design-
    // direction §9): the audit found this fade running regardless.
    val motion = io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled.current
    androidx.compose.animation.AnimatedVisibility(
        visible = !loaded,
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        enter = androidx.compose.animation.fadeIn(
            if (motion) {
                io.github.pintofi.beanbook.app.ui.theme.MotionRole.fade()
            } else {
                androidx.compose.animation.core.snap()
            },
        ),
        exit = androidx.compose.animation.fadeOut(
            if (motion) {
                io.github.pintofi.beanbook.app.ui.theme.MotionRole.replace()
            } else {
                androidx.compose.animation.core.snap()
            },
        ),
        label = "report",
    ) {
        ReportLoading()
    }

    // Fava's own state, read while Reports is on screen. evaluateJavascript hands the result to
    // the app's callback; the page is given nothing and can call nothing (no JS bridge).
    androidx.compose.runtime.LaunchedEffect(webRef.value) {
        val web = webRef.value ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(BUSY_POLL_MS)
            web.evaluateJavascript(FavaTheme.BUSY_PROBE) { answer -> favaBusy = answer == "true" }
        }
    }
    if (loaded) ReportWorking(busy = documentLoading || favaBusy)
    }
}

/**
 * What stands in front of a report that has not arrived.
 *
 * The app's own paper and its own rule, filling: the same mark the startup screen draws,
 * because this is the same kind of wait. It covers the WebView rather than replacing it, so
 * Fava is loading underneath the whole time and nothing is torn down when it finishes.
 */
@Composable
private fun ReportLoading() {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        // Still when animations are off (LedgerProgressRule).
        io.github.pintofi.beanbook.app.ui.LedgerProgressRule()
        androidx.compose.material3.Text(
            "Drawing the report…",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How often Reports asks Fava whether it is busy: often enough to feel immediate, cheap. */
private const val BUSY_POLL_MS = 400L

/** How long Fava may work before the progress rule appears: a quick page shows no flicker. */
private const val BUSY_SHOW_AFTER_MS = 250L

/** How long it may work before the words appear as well. */
private const val BUSY_WORDS_AFTER_MS = 3_000L

/**
 * The native sign that a report is on its way, over a page that is already showing
 *: the ledger's rule filling across the top as soon as a wait is
 * noticeable, and after a few seconds a line saying so, announced politely. Both go the moment
 * Fava is done.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ReportWorking(busy: Boolean) {
    var showRule by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showWords by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(busy) {
        if (busy) {
            kotlinx.coroutines.delay(BUSY_SHOW_AFTER_MS)
            showRule = true
            kotlinx.coroutines.delay(BUSY_WORDS_AFTER_MS - BUSY_SHOW_AFTER_MS)
            showWords = true
        } else {
            showRule = false
            showWords = false
        }
    }
    OldWebViewNotice(Modifier.align(androidx.compose.ui.Alignment.TopCenter))
    if (showRule) {
        io.github.pintofi.beanbook.app.ui.LedgerProgressRule(
            modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter),
        )
    }
    io.github.pintofi.beanbook.app.ui.LedgerReveal(
        visible = showWords,
        label = "reportWorking",
        modifier = androidx.compose.ui.Modifier
            .align(androidx.compose.ui.Alignment.BottomCenter)
            .padding(bottom = 24.dp),
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            color = androidx.compose.material3.MaterialTheme.colorScheme.inverseSurface,
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
            modifier = androidx.compose.ui.Modifier.semantics {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                io.github.pintofi.beanbook.app.ui.LedgerLoadingIndicator(
                    size = 20.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface,
                )
                androidx.compose.material3.Text(
                    "Drawing the report…",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
