# Review log: app-core (engine and services, sharing, widgets, WebView hardening, manifest, editions)

Area `app-core` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `app/src/expert/kotlin/io/github/pintofi/beanbook/app/PluginPolicyProvider.kt` | 3 | none | expert policy: user code, native code, bridge and downloads allowed |
| `app/src/expert/kotlin/io/github/pintofi/beanbook/app/about/EditionFacts.kt` | 3 5 | A-11 | expert only (verifyPlayFlavorBoundaries keeps the support link out of play); private id removed |
| `app/src/expert/kotlin/io/github/pintofi/beanbook/app/packages/ArchiveFetcher.kt` | 2 3 | A-6 | two pinned hosts, https only, 50 MB cap; redirects no longer followed (compiled in expert; not covered by a test) |
| `app/src/expert/kotlin/io/github/pintofi/beanbook/app/packages/WheelFetcher.kt` | 2 3 5 | A-6, A-11 | digest checked before unpacking, zip-slip checked, staged merge with marker; names validated and downloads capped (compiled in expert; ReviewFixesTest covers the name rule) |
| `app/src/main/AndroidManifest.xml` | 2 3 5 | A-11 | one exported launcher activity and one exported widget-configure activity (its id is checked against our provider); the service, receivers and provider are not exported; configure label corrected |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/AppChrome.kt` | 1 5 | A-11 | LedgerHealthTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/Backstack.kt` | 5 | none | BackstackTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/BeancountApplication.kt` | 2 5 | A-11 | wiring only; EngineLifecycleTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/DocumentActions.kt` | 1 2 5 | A-2, A-11, I-6 | ReviewFixesTest (sliceMatches); stillThere asks the engine again before an edit is computed |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ExternalLinks.kt` | 1 3 | A-1 | ReviewFixesTest (new file) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/LedgerApp.kt` | 4 5 | A-11, I-3 | AddActionReachableTest; about 150 parameters (#67) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/LedgerChrome.kt` | 5 | A-11 | BackstackTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/MainActivity.kt` | 1 2 3 5 | A-1, A-11, I-3 | ReviewFixesTest; external links now confirmed; widget extras checked against known projects; debug-only test hooks gated by BuildConfig.DEBUG |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/SaveActions.kt` | 1 2 | none | every write goes through ProjectWriter; DocumentFlowTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/SurfaceContent.kt` | 2 5 | A-10, A-11, I-3 | session cookie set once per origin and token; stale comment corrected |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/UndoBar.kt` | 5 | A-11 | BackstackTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/about/AboutSection.kt` | 5 | A-11 | links open the browser on tap only; comment ids removed |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/about/EditionNotices.kt` | 3 5 | none | play only; opens a documentation page on tap |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/about/SupportLink.kt` | 5 | none | data class |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/about/SupportLinks.kt` | 5 | none | constants and a data class |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/backup/Argon2idKeyDerivation.kt` | 1 2 | none | Argon2BackupTest passed on the emulator; passphrase bytes wiped in finally |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/backup/BackupController.kt` | 1 2 | A-7 | a failed backup now removes or names the partial file; the cache copy of a restore archive is deleted once staged; Argon2BackupTest passed on the emulator |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/backup/ScheduledBackup.kt` | 1 2 | A-7, I-4 | an unfinished file is deleted when a run fails; scheduled backups are not encrypted (#68) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/diagnostics/Diagnostics.kt` | 1 2 | none | DiagnosticsTest; the report is built only when the user asks and redacted by DiagnosticLog |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/documents/DocumentCapture.kt` | 1 2 | I-5 | DocumentFlowTest passed on the emulator; captures stay in cache after filing (#69) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/documents/PdfAssembler.kt` | 2 4 | none | PhotoOrientationTest, DocumentFlowTest passed on the emulator |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/documents/Photos.kt` | 2 | none | PhotoOrientationTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/EditorController.kt` | 1 2 | A-5 | text typed while a save runs is kept (no test: needs the write path and a clock; read against the code) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/EngineManager.kt` | 2 3 | A-11 | EngineLifecycleTest, EngineRestartTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/EngineRestart.kt` | 5 | none | EngineRestartTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/LedgerState.kt` | 2 5 | A-11 | LedgerReloadTest passed on the emulator; stale watcher comment corrected |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/LedgerWatcher.kt` | 2 4 | none | started on resume and stopped on pause; coalesced |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/ProjectsState.kt` | 1 2 | A-7, A-11 | the cache copy of an imported archive is deleted when the import ends; ImportRoundTripTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/RecoveryOnStart.kt` | 1 2 | none | runs before a project is shown |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/engine/TemplatesState.kt` | 2 | none | small file read off the main thread |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/mirror/MirrorController.kt` | 1 2 4 | A-9, I-2 | MirrorSettingsTest, MirrorHistoryTest; hashing is streamed; a pass reads every file on every resume (#66) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/mirror/MirrorHistory.kt` | 5 | none | MirrorHistoryTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/packages/PackagesState.kt` | 1 2 3 | none | PackagesStateTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/packages/Wheel.kt` | 1 5 | A-6 | ReviewFixesTest (package names) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/plugins/CatalogueOverlay.kt` | 2 3 | none | OverlayAssetsTest; target path checked to stay inside the plugin folder |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/plugins/CatalogueState.kt` | 2 3 | A-10 | CatalogPluginTest; a second install tap while one runs is ignored |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/plugins/PluginsState.kt` | 1 2 3 | A-4 | ReviewFixesTest (display names); a provider's file name can no longer place the copy outside the scratch folder |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/plugins/SavedScripts.kt` | 2 3 | none | SavedScriptsTest passed on the emulator; names sanitised, removal confined to the folder |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/plugins/ScriptRunnerState.kt` | 2 3 | A-7 | ScriptRunnerTest passed on the emulator; the engine's live output file is deleted after a run |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/remote/KeystoreSecret.kt` | 1 2 | A-8 | RemoteCredentialsTest passed on the emulator; an unusable Keystore key is replaced instead of blocking every save |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/remote/RemoteState.kt` | 1 2 | A-8, I-1 | a failed password store ends in a message, not a crash; a pending retry is lost when the app is killed (#65) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/sharing/Sharing.kt` | 2 3 | A-10 | the share binds to the Wi-Fi address only, behind a sign-in, with a notification and a Stop button; name kept to one line |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/storage/FileNames.kt` | 3 | A-4 | ReviewFixesTest (new file) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/storage/SafBridge.kt` | 1 2 | none | SafExportTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/undo/UndoController.kt` | 1 | none | offers only a committed write; undoIfLatest refuses if anything newer was saved |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/web/FavaTheme.kt` | 3 5 | A-11 | FavaThemeTest passed on the emulator; the script is built only from colour values |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/web/FavaWebView.kt` | 3 4 | A-3, A-11 | WebViewHardeningTest, WebViewAuthTest, FavaEditorRenderTest passed on the emulator; the renderer-gone path is not exercised |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/widget/BalanceWidgetConfigureActivity.kt` | 3 | A-11 | WidgetAccountChoiceTest; the widget id is checked against our provider |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/widget/BeancountWidgets.kt` | 2 3 | none | WidgetEngineIsolationTest passed on the emulator; no engine, no network |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/widget/WidgetPalette.kt` | 5 | none | reads the app's theme file only |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/widget/WidgetRenderer.kt` | 1 2 5 | A-11 | WidgetFigureTest; stale and failing ledgers say so; lock-screen widgets hide amounts |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/widget/WidgetRowsService.kt` | 2 3 | A-10 | WidgetEngineIsolationTest; a row asked for while the list is reread no longer indexes out of range |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/work/KeepWorking.kt` | 2 4 | none | foreground service with a 10-minute wake lock, released with the last job |
| `app/src/play/kotlin/io/github/pintofi/beanbook/app/PluginPolicyProvider.kt` | 3 | none | play policy: nothing downloaded, no native code, no bridge; :verifyPlayFlavorBoundaries |
| `app/src/play/kotlin/io/github/pintofi/beanbook/app/about/EditionFacts.kt` | 3 5 | none | no donation link in play; :verifyPlayFlavorBoundaries |
| `app/src/play/kotlin/io/github/pintofi/beanbook/app/packages/ArchiveFetcher.kt` | 3 | none | refuses; :verifyPlayFlavorBoundaries |
| `app/src/play/kotlin/io/github/pintofi/beanbook/app/packages/WheelFetcher.kt` | 3 | none | refuses every call; :verifyPlayFlavorBoundaries |

Gradle: `:core-domain:test :core-data:test :core-engine:test :engine-remote:test :app:testPlayDebugUnitTest detekt :app:verifyPlayFlavorBoundaries` pass, and the expert flavour compiles (`:app:compileExpertDebugKotlin`). On the API 36 emulator 54 instrumentation tests passed, covering the Keystore secret, WebView hardening and sign-in, scripts, widgets, documents, ledger reload, the Fava editor render, Argon2 backups and themes. Seven new JVM tests are in `ReviewFixesTest`.

## Fixed in this pull request

- **A-1 (S1): links out of a report opened the browser with no confirmation and no check of the scheme.** The threat model promises a confirmation. Any link a page offered (`intent:`, `file:`, `content:`) went straight to `ACTION_VIEW`. A link now opens only if it is `http` or `https` with no user name in it, and only after a dialog showing the whole address.
- **A-2 (S1): deleting or editing an entry used a line number from a list that could be out of date.** The write protocol checks that the file is unchanged at write time, but the SHA it compared was read after the entry had been located, so an edit form left open while a sync changed the file could replace the lines of a different entry. Delete, multi-delete and the edit form now check that the entry found still has the listed date, payee and narration. Saving an edit asks the engine again for the entry and compares its hash, with the file's SHA taken before that check.
- **A-3 (S1): the app had no handler for the report view's renderer dying.** Android ends that process under memory pressure, and an unhandled loss takes the app down. It now drops the dead view and builds another. I could not trigger a renderer death on the emulator, so this path is read against the framework contract and not exercised.
- **A-4 (S1): a plugin's file name came from the document provider unchecked.** A provider supplying `../../x` as the display name placed the copy outside the scratch folder. Only the last path segment is used; empty, `.`, `..` and control-character names fall back to a default.
- **A-5 (S1): text typed in the raw editor while a save was running was lost.** After the write the editor put back the text from when Save was tapped and cleared the draft. It now keeps the current text, and saves a draft if it differs.
- **A-6 (S2):** the catalogue archive fetch followed redirects off its two pinned hosts (it no longer does); package names, which end up in index URLs, are validated; one downloaded wheel is capped at 250 MB.
- **A-7 (S2):** a failed backup said "Nothing was written" after emptying the destination; it now removes the unfinished file or says it may remain. A failed scheduled run deletes its unfinished file. Cache copies of a restore archive, an imported archive, each retention check and a script's live output are deleted when done: they are whole copies of someone's finances.
- **A-8 (S2):** a Keystore key that became unusable blocked saving a remote password for ever; it is replaced. A failure to store the password crashed the app; it now ends with a message. The password is no longer turned into a `String`.
- **A-9 (S2):** the mirror read each file whole into memory to hash it; it streams. A refused conflict copy is logged.
- **A-10 (S2):** a second tap on a catalogue install while one runs is ignored; the multi-account widget cannot index past its list; the session cookie is set once per origin and token instead of on every recomposition; a sharing user name cannot hold a line break.
- **A-11 (S3):** about 270 private bug and milestone ids in comments removed; comments pointing at documents that are not in the repository rewritten; stale comments about a missing file watcher corrected; stacked KDoc blocks reattached to the right functions; the widget configure label matches its screen.

## Filed as issues

- **I-1 (S2)** a remote entry waiting for a retry is lost when the app is killed: [#65](https://github.com/pintofi/beanbook/issues/65).
- **I-2 (S2)** every return to the app re-reads every file in the mirror folder: [#66](https://github.com/pintofi/beanbook/issues/66).
- **I-3 (S2)** about 150 hand-passed parameters across three files: [#67](https://github.com/pintofi/beanbook/issues/67).
- **I-4 (S2)** scheduled backups are not encrypted: [#68](https://github.com/pintofi/beanbook/issues/68).
- **I-5 (S3)** receipt photos stay in the cache after filing: [#69](https://github.com/pintofi/beanbook/issues/69).
- **I-6 (S3)** delete and edit refuse an entry whose narration a plugin rewrites: [#70](https://github.com/pintofi/beanbook/issues/70).

## Accepted, with the reason

- **Debug-only test hooks** (`m4_test`, `m11_test`, the stall helpers) are compiled into release builds and gated by `BuildConfig.DEBUG`. They are what the process-death tests drive, and the gate is a constant the compiler folds.
- **Chaquopy's index publishes no digests**, so a wheel from it is trusted on first fetch and then pinned for that install (the code says so). Expert edition only, and only for a package the user asked for.
- **Creating `files/documents.beancount`** with a one-line comment bypasses the write journal. The include line that makes it part of the ledger goes through the protocol and is shown to the user first.
- **Export deletes a same-named file in the chosen folder before writing it.** The folder is one the user picked for an export, and the report counts what was written.
