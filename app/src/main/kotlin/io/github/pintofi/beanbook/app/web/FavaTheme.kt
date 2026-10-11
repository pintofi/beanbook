package io.github.pintofi.beanbook.app.web

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.pintofi.beanbook.app.ui.theme.LedgerColours

/**
 * Makes Fava's reports look like the rest of the app (docs/ux.md §3, design-direction §10).
 *
 * **Re-skinned, never restructured.** Fava themes itself through CSS custom properties and
 * honours an explicit `:root[data-color-scheme=dark|light]`. Almost everything here sets one of
 * *its* variables -- paper, ink, borders, tables, sidebar, header, the diff pair -- so a Fava
 * release that renames one simply stops taking that colour and renders its own. It fails to
 * their design, never to a broken page.
 *
 * **The header is the app's band, not a filled accent.** It used to be painted with the theme's
 * primary, which on a wallpaper-coloured phone was a salmon bar across a dark app (device audit
 * 2026-10-10). Now it is the same tint as the app's own top bar, with ink text, and the accent
 * is kept for links, buttons and the logo -- the way the app itself uses it.
 *
 * **The few rules that are not variables** are scoped to selectors Fava's own stylesheet uses
 * (`header`, `aside`, `.aside-buttons`, `article table`), and only change spacing, radius,
 * wrapping and scrolling -- never what is shown:
 * - phone width: tables scroll inside their own box instead of pushing the page sideways, text
 *   cells wrap (the Errors message column was off screen), and the sidebar buttons become 48dp
 *   rounded targets;
 * - type: the device's own sans and monospace, as in the rest of the app;
 * - the sidebar closes itself after one of its links is followed (it stayed open over the page).
 *
 * The script runs inside a `try`: a failure leaves Fava exactly as it would be with no theme.
 */
object FavaTheme {

    /**
     * The script that applies the theme.
     *
     * `evaluateJavascript` is the app running its own script *in* the page. It is not a
     * bridge: nothing here exposes an app object to page JavaScript, there is no
     * `addJavascriptInterface` anywhere in this file or its caller, and the rule that forbids
     * one is about the other direction (CLAUDE.md, security rules).
     *
     * Every value interpolated below is a `#rrggbb` string this file generates from a
     * [ColorScheme]. None of it comes from a ledger, a filename, or anything a user typed, so
     * there is nothing here that could close the string and start executing.
     */
    fun script(scheme: ColorScheme, dark: Boolean, ledger: LedgerColours): String {
        // Every value comes from the scheme in use, so AMOLED's black paper and raised blacks
        // reach Fava without a case of their own.
        val css = buildString {
            // `:root[data-color-scheme]`, not plain `:root`. Fava sets its dark values on
            // `:root[data-color-scheme=dark]`, which is a more specific selector than `:root`
            // and wins no matter what order the stylesheets are in -- so in dark mode the
            // app's colour was silently ignored. Matching the specificity and relying on source
            // order (this style is appended last) is what applies. FavaThemeTest reads the
            // computed values back out of a real WebView.
            append(":root[data-color-scheme]{")
            variable("background", scheme.background)
            variable("background-darker", scheme.surfaceContainerLow)
            variable("background-darkest", scheme.surfaceContainer)
            variable("background-button", scheme.surfaceContainerHigh)
            variable("border-lighter", scheme.outlineVariant)
            variable("border", scheme.outlineVariant)
            variable("border-darker", scheme.outline)
            variable("text-color", scheme.onSurface)
            variable("text-color-lighter", scheme.onSurface)
            variable("text-color-lightest", scheme.onSurfaceVariant)
            variable("heading-color", scheme.onSurface)
            variable("code-background", scheme.surfaceContainerLow)
            variable("link-color", scheme.primary)
            variable("link-hover-color", scheme.onSurfaceVariant)
            variable("button-background", scheme.primary)
            variable("button-color", scheme.onPrimary)
            variable("button-muted-background", scheme.surfaceContainerHigh)
            variable("button-muted-color", scheme.onSurface)

            // The header as the app's band: the same tint as the top bar, ink on it.
            variable("header-background", scheme.surfaceContainerLow)
            variable("header-color", scheme.onSurface)
            // The filter boxes that sit inside the header, a step up from the band so they
            // read as fields; left alone they were Fava's fixed #3282b3 blue.
            variable("header-placeholder-background", scheme.surfaceContainerHigh)
            variable("header-placeholder-color", scheme.onSurfaceVariant)

            variable("sidebar-background", scheme.surfaceContainerLow)
            variable("sidebar-color", scheme.onSurfaceVariant)
            variable("sidebar-hover-color", scheme.primary)
            variable("sidebar-border", scheme.outlineVariant)
            variable("summary-background", scheme.surfaceContainerLow)
            variable("summary-background-darker", scheme.surfaceContainer)
            variable("table-header-text", scheme.onSurfaceVariant)
            variable("table-header-background", scheme.surfaceContainer)
            variable("table-border", scheme.outlineVariant)
            variable("table-background-even", scheme.surfaceContainerLow)
            variable("treetable-expander", scheme.primary)
            variable("mobile-button-text", scheme.onSurface)
            variable("placeholder-color", scheme.onSurfaceVariant)
            variable("placeholder-background", if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest)

            // The gain/loss *text* colour, so a difference keeps its colour across the line
            // between the app's own screens and Fava's (design-direction §4): green up, carmine
            // down, never red and green alone -- the sign carries the meaning.
            //
            // `--diff-green`/`--diff-red` rather than `--diff-positive`/`--diff-negative`: Fava
            // defines the latter in terms of the former and swaps them for people who prefer the
            // opposite convention, so overriding the source pair keeps that preference working.
            //
            // **This does not reach the charts.** Fava colours them in its JavaScript with a D3
            // scale and no CSS variable behind it; recolouring them would mean reaching into
            // its scripts, which is the coupling this file exists not to have.
            variable("diff-green", ledger.positive)
            variable("diff-red", ledger.negative)

            // The device's own faces, as in the app (Type.kt): Fava bundles Fira, and a report
            // set in another typeface read as another app.
            append("--font-family:system-ui,sans-serif;")
            append("--font-family-monospaced:monospace;")
            append("}")

            // The journal defines its row tint on `.journal` itself.
            append(":root[data-color-scheme] .journal{")
            variable("journal-postings", scheme.surfaceContainerLow)
            variable("journal-hover-highlight", scheme.surfaceContainer)
            append("}")

            // The band meets the page with the app's hairline, not a shadow.
            append("header{box-shadow:none;border-bottom:1px solid ${scheme.outlineVariant.css()};}")
            // Fava's mark, recoloured to sit on the band: it is drawn in fixed white and pale
            // blue, which on a light band was invisible.
            append("header>svg path:first-child{fill:${scheme.onSurface.css()};}")
            append("header>svg path:last-child{fill:${scheme.primary.css()};}")
            // Controls at the app's control radius.
            append("button:not(.unset):not(.round),.button,input,select,textarea{border-radius:10px;}")

            // Phone width: Fava's own breakpoint.
            append("@media (width<=767px){")
            append("header{padding-left:64px;}")
            append(".aside-buttons{padding:4px 0 0 6px;gap:6px;}")
            append(
                ".aside-buttons>*{width:48px;height:48px;border-radius:12px;border:none;" +
                    "display:flex;align-items:center;justify-content:center;" +
                    "background-color:${scheme.surfaceContainerHigh.css()};color:${scheme.onSurface.css()};}",
            )
            append("aside{box-shadow:4px 0 16px ${scheme.scrim.css()}40;}")
            append("article table{display:block;max-width:100%;overflow-x:auto;}")
            append("article td:not(.num){white-space:normal;word-break:break-word;}")
            append("article pre{white-space:pre-wrap;word-break:break-word;}")
            append("}")
        }

        return """
            (function () {
              try {
                var root = document.documentElement;
                root.setAttribute('data-color-scheme', '${if (dark) "dark" else "light"}');
                var style = document.getElementById('$STYLE_ID');
                if (!style) {
                  style = document.createElement('style');
                  style.id = '$STYLE_ID';
                  document.head.appendChild(style);
                }
                style.textContent = ${css.jsString()};
                if (!window.$ASIDE_FLAG) {
                  window.$ASIDE_FLAG = true;
                  document.addEventListener('click', function (event) {
                    var link = event.target && event.target.closest ? event.target.closest('aside a') : null;
                    if (!link) return;
                    var toggle = document.querySelector('.aside-buttons.active > button');
                    if (toggle) setTimeout(function () { toggle.click(); }, 0);
                  }, true);
                }
              } catch (ignored) {}
            })();
        """.trimIndent()
    }

    const val STYLE_ID = "bca-theme"

    /** Set once per page, so the sidebar listener is added once however often the theme runs. */
    private const val ASIDE_FLAG = "__bcaAsideCloses"

    /**
     * Whether Fava is working on a page right now: its header mark carries `loading` while a
     * report is being fetched (Fava's `HeaderIcon`). Read by the app with `evaluateJavascript`,
     * whose result comes back to the app's own callback -- the page is handed nothing.
     */
    const val BUSY_PROBE =
        "(function(){try{var s=document.querySelector('header svg');" +
            "return !!(s&&s.classList.contains('loading'));}catch(e){return false;}})()"

    private fun StringBuilder.variable(name: String, colour: Color) {
        append("--").append(name).append(':').append(colour.css()).append(';')
    }

    /** `#rrggbb`, dropping alpha: CSS custom properties here are opaque colours. */
    private fun Color.css(): String = "#%06x".format(toArgb() and 0xFFFFFF)

    /** A JavaScript string literal. Only ever applied to CSS this file built. */
    private fun String.jsString(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'").replace("\n", "") + "'"
}
