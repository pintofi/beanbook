package dev.beancount.app

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.ui.theme.DarkColoursForTest
import dev.beancount.app.ui.theme.LightColoursForTest
import dev.beancount.app.web.AllowlistWebViewClient
import dev.beancount.app.web.FavaTheme
import dev.beancount.app.web.applyBeancountHardening
import dev.beancount.core.engine.OriginAllowlist
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Fava re-skin, applied to a real page in a real WebView.
 *
 * Asserting the generated script *looks* right would prove nothing: what matters is whether
 * the browser ends up computing the app's colour for a variable Fava reads. So this serves a
 * page that declares Fava's own custom properties, injects, and reads the computed value
 * back out.
 */
@RunWith(AndroidJUnit4::class)
class FavaThemeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var server: ServerSocket? = null

    @After fun stop() {
        runCatching { server?.close() }
    }

    /** A stand-in for a Fava page: same variable names, same dark-mode attribute. */
    private val page = """
        <!doctype html><html><head><style>
          :root { --header-background: #0065a3; --link-color: #0065a3; }
          :root[data-color-scheme=dark] { --header-background: #004f80; }
        </style></head><body><div id="x">report</div></body></html>
    """.trimIndent()

    private fun serve(): String {
        val socket = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    // A WebView may close the connection as soon as it has what it needs —
                    // for a favicon it never asked for, say — and the write then fails with
                    // a broken pipe. That is the client's prerogative, not a test failure.
                    runCatching {
                        client.use {
                            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                            var line = reader.readLine()
                            while (!line.isNullOrBlank()) line = reader.readLine()
                            val body = page.toByteArray()
                            it.getOutputStream().write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            it.getOutputStream().write(body)
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}"
    }

    /** Loads the page with the theme applied and reads one computed value back. */
    private fun computed(dark: Boolean, property: String): String {
        val origin = serve()
        val loaded = CountDownLatch(1)
        val answer = AtomicReference("")
        var webView: WebView? = null

        val scheme = if (dark) DarkColoursForTest else LightColoursForTest
        val script = FavaTheme.script(scheme, dark)

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                applyBeancountHardening()
                webViewClient = AllowlistWebViewClient(
                    allowlist = OriginAllowlist(setOf(origin)),
                    onPageLoaded = { loaded.countDown() },
                    themeScript = { script },
                )
                loadUrl("$origin/")
            }
        }
        assertTrue("the page never loaded", loaded.await(20, TimeUnit.SECONDS))

        val read = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView?.evaluateJavascript(
                "getComputedStyle(document.documentElement)" +
                    ".getPropertyValue('$property').trim()",
            ) { value ->
                answer.set(value.trim('"'))
                read.countDown()
            }
        }
        assertTrue("the value was never read back", read.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { webView?.destroy() }
        return answer.get()
    }

    @Test fun favaAdoptsTheAppsColourForItsOwnHeaderVariable() {
        val header = computed(dark = false, property = "--header-background")

        // Fava's own blue is #0065a3. The app's light primary is #006a6a.
        assertEquals("#006a6a", header)
    }

    /**
     * The case that caught a real bug: Fava's dark values sit on
     * `:root[data-color-scheme=dark]`, which outranks a plain `:root` override whatever the
     * source order. In dark mode the app's colour was being ignored entirely.
     */
    @Test fun theDarkPaletteIsUsedWhenTheAppIsDark() {
        val header = computed(dark = true, property = "--header-background")

        // The app's dark primary, not Fava's #004f80 and not the light #006a6a — which
        // together prove both that the override applied and that it picked the right scheme.
        assertEquals("#80d5d4", header)
    }

    @Test fun theColourSchemeAttributeIsSetSoFavaUsesItsOwnDarkPalette() {
        val origin = serve()
        val loaded = CountDownLatch(1)
        val answer = AtomicReference("")
        var webView: WebView? = null
        val script = FavaTheme.script(DarkColoursForTest, dark = true)

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                applyBeancountHardening()
                webViewClient = AllowlistWebViewClient(
                    allowlist = OriginAllowlist(setOf(origin)),
                    onPageLoaded = { loaded.countDown() },
                    themeScript = { script },
                )
                loadUrl("$origin/")
            }
        }
        assertTrue(loaded.await(20, TimeUnit.SECONDS))

        val read = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView?.evaluateJavascript(
                "document.documentElement.getAttribute('data-color-scheme')",
            ) { value -> answer.set(value.trim('"')); read.countDown() }
        }
        assertTrue(read.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { webView?.destroy() }

        // This attribute is what makes Fava use the complete dark palette it already ships.
        // Hand-writing one here would be worse and would drift from theirs.
        assertEquals("dark", answer.get())
    }

    @Test fun theBoxesInsideTheHeaderFollowTheHeaderRatherThanKeepingFavasBlue() {
        // Overriding the header without these left Fava's fixed #3282b3 filter boxes sitting
        // on the app's palette. The derived value must be neither of the two extremes and
        // must differ between light and dark.
        val light = computed(dark = false, property = "--header-placeholder-background")
        val dark = computed(dark = true, property = "--header-placeholder-background")

        assertTrue("still Fava's blue: $light", light != "#3282b3")
        assertTrue("still Fava's blue: $dark", dark != "#3282b3")
        assertTrue("light and dark produced the same box: $light", light != dark)
    }

    @Test fun theScriptCarriesNoValueThatCouldCloseItsOwnStringLiteral() {
        val script = FavaTheme.script(LightColoursForTest, dark = false)

        // Everything interpolated is a #rrggbb this app generated. If a colour ever arrived
        // from somewhere else, this is the assertion that would notice.
        // To end of line, not to the first ";" — the CSS is full of semicolons, and cutting
        // at one of those was measuring a fragment rather than the literal.
        val cssLiteral = script.substringAfter("style.textContent = ").substringBefore("\n")
        assertEquals(
            "unescaped quote in the injected CSS: $cssLiteral",
            2,
            cssLiteral.count { it == '\'' },
        )
        assertTrue(script.contains("#006a6a"))
    }
}
