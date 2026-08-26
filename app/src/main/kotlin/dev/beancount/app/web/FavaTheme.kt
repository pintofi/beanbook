package dev.beancount.app.web

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * Makes Fava's reports look like the rest of the app (docs/ux.md §3).
 *
 * Fava themes itself entirely through CSS custom properties and honours an explicit
 * `:root[data-color-scheme=dark|light]`, which is a considerable piece of luck: the whole
 * job is setting one attribute and overriding five variables. **The app re-skins Fava; it
 * never restructures it.** Rewriting its layout from outside would break on every Fava
 * release and is not something a viewer should be doing to a server's own pages.
 *
 * Dark mode needs no colours at all — Fava already has a complete dark palette behind that
 * attribute, and a hand-written one here would be worse and would drift. Only the blue that
 * carries Fava's brand is replaced, so the reports read as part of this app rather than a
 * different one embedded in it.
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
    fun script(scheme: ColorScheme, dark: Boolean): String {
        val css = buildString {
            // `:root[data-color-scheme]`, not plain `:root`. Fava sets its dark values on
            // `:root[data-color-scheme=dark]`, which is a more specific selector than `:root`
            // and wins no matter what order the stylesheets are in — so in dark mode the
            // app's colour was silently ignored and Fava's own blue came through. Matching
            // the specificity and relying on source order (this style is appended last) is
            // what actually applies. Caught by FavaThemeTest reading the computed value back
            // out of a real WebView; nothing about the generated string looked wrong.
            append(":root[data-color-scheme]{")
            append("--header-background:${scheme.primary.css()};")
            append("--header-color:${scheme.onPrimary.css()};")
            append("--button-background:${scheme.primary.css()};")
            append("--button-color:${scheme.onPrimary.css()};")
            append("--link-color:${scheme.primary.css()};")
            append("--sidebar-hover-color:${scheme.primary.css()};")

            // The filter boxes that sit *inside* the header. Overriding the header colour
            // without these left Fava's fixed #3282b3 blue sitting on the app's palette,
            // which looked worse than not theming the header at all.
            //
            // Derived by mixing towards the header's own text colour rather than picked, so
            // they follow the header in both directions: on a dark header the boxes come out
            // lighter, on a light one darker, and dynamic colour cannot produce a pair that
            // does not contrast.
            val placeholder = lerp(scheme.primary, scheme.onPrimary, 0.18f)
            val placeholderText = lerp(scheme.primary, scheme.onPrimary, 0.62f)
            append("--header-placeholder-background:${placeholder.css()};")
            append("--header-placeholder-color:${placeholderText.css()};")
            append("}")
        }

        return """
            (function () {
              var root = document.documentElement;
              root.setAttribute('data-color-scheme', '${if (dark) "dark" else "light"}');
              var style = document.getElementById('$STYLE_ID');
              if (!style) {
                style = document.createElement('style');
                style.id = '$STYLE_ID';
                document.head.appendChild(style);
              }
              style.textContent = ${css.jsString()};
            })();
        """.trimIndent()
    }

    const val STYLE_ID = "bca-theme"

    /** `#rrggbb`, dropping alpha: CSS custom properties here are opaque colours. */
    private fun Color.css(): String = "#%06x".format(toArgb() and 0xFFFFFF)

    /** A JavaScript string literal. Only ever applied to CSS this file built. */
    private fun String.jsString(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'").replace("\n", "") + "'"
}
