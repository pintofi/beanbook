# Review log: resources (strings, drawables, icons, widget layouts, bundled assets)

Area `resources` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `app/src/expert/res/drawable/ic_support_kofi.xml` | 3 5 | none | expert source set only; :verifyPlayFlavorBoundaries keeps it out of play; drawn for this app |
| `app/src/main/assets/licences.json` | 3 5 | none | valid JSON; all 34 locked Python packages are listed with text; regenerated and drift-checked in CI by tools/licences.py --check (not run locally: it needs a built APK) |
| `app/src/main/assets/overlays/README.txt` | 5 | none | matches the two overlays |
| `app/src/main/assets/overlays/beangrow-1.0.1/REMOVES.txt` | 5 | none | OverlayAssetsTest |
| `app/src/main/assets/overlays/beangrow-1.0.1/beangrow/returns.py` | 1 5 | none | every line patches/beangrow-1.0.1-no-scipy.patch adds is present; OverlayAssetsTest, NativeWheelsTest |
| `app/src/main/assets/overlays/fava_portfolio_returns-2.7.0/REMOVES.txt` | 5 | none | OverlayAssetsTest |
| `app/src/main/assets/overlays/fava_portfolio_returns-2.7.0/fava_portfolio_returns/_vendor/beangrow/returns.py` | 1 5 | none | every line patches/fava_portfolio_returns-2.7.0-no-scipy.patch adds is present; OverlayAssetsTest |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | 5 | none | LauncherIconTest |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | 5 | none | LauncherIconTest |
| `app/src/main/res/drawable/widget_paper.xml` | 5 | R-1 | comment id removed; tinted by the renderer |
| `app/src/main/res/drawable/widget_rule.xml` | 5 | R-1 | comment id removed |
| `app/src/main/res/layout/widget_balance_row.xml` | 5 | R-1 | comment id removed; WidgetHostTest |
| `app/src/main/res/layout/widget_balances.xml` | 5 | R-1, R-2 | comment ids removed; WidgetHostTest |
| `app/src/main/res/layout/widget_metric.xml` | 5 | R-1, R-2 | comment ids removed; WidgetHostTest |
| `app/src/main/res/layout/widget_preview_balance.xml` | 5 | R-1, R-2 | comment ids removed; preview text is illustrative |
| `app/src/main/res/layout/widget_preview_networth.xml` | 5 | R-1, R-2 | comment ids removed; preview text is illustrative |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 5 | none | LauncherIconTest |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 5 | none | LauncherIconTest |
| `app/src/main/res/values-night/colors.xml` | 5 | none | values match WidgetPalette and Colour.kt |
| `app/src/main/res/values-night/themes.xml` | 5 | none | startup window is dark in dark mode |
| `app/src/main/res/values/colors.xml` | 5 | none | values match WidgetPalette and Colour.kt |
| `app/src/main/res/values/ic_launcher_background.xml` | 5 | none | LauncherIconTest |
| `app/src/main/res/values/strings.xml` | 5 | R-1, R-2 | comment id removed; three strings |
| `app/src/main/res/values/themes.xml` | 5 | none | startup window colour |
| `app/src/main/res/xml/document_paths.xml` | 2 3 5 | R-1 | FileProvider grants projects/ and the diagnostics cache folder only; the narrower rule is checked in shareDocument (app-core) |
| `app/src/main/res/xml/network_security_config.xml` | 3 5 | none | cleartext allowed so a LAN or VPN Fava can be used; nothing falls back to it; the remote screen warns while typing |
| `app/src/main/res/xml/widget_balance_info.xml` | 3 5 | none | updatePeriodMillis is 0; configure activity checks its widget id (app-core) |
| `app/src/main/res/xml/widget_networth_info.xml` | 3 5 | none | updatePeriodMillis is 0 |

Gradle: `:app:lintPlayDebug :app:lintExpertDebug :app:testPlayDebugUnitTest :app:verifyPlayFlavorBoundaries` pass.

## Fixed in this pull request

- **R-1 (S3):** private bug ids removed from the comments of nine resource files.

## Filed as issues

- **R-2 (S3)** user-facing text is in code and layouts, not in `strings.xml`: [#76](https://github.com/pintofi/beanbook/issues/76)

## Accepted, with the reason

- **Overlay files are whole copies of patched upstream files.** Every line each patch adds is present in its overlay, and `OverlayAssetsTest` checks the set. The solver agrees with SciPy's `fsolve` to 3e-13 (`patches/README.md`).
- **`licences.json` is generated.** It is checked for drift in CI against a built APK, which this review did not rebuild.
