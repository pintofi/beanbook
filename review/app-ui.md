# Review log: app-ui (screens, theme, shared components, accessibility semantics)

Area `app-ui` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** The screens that hold decisions or money (`QuickAddScreen`, `DirectiveEntryScreen`, `HomeScreen`, `BalancesScreen`, `LedgerScreens`, `DocumentsScreen`, the backup, remote and sharing screens, the raw editor, the document viewer, the fields and figure helpers, the stores of display preferences) were read line by line. The visual files (theme, motion, shapes, flourishes, scanner and crop screens, and the rest) were read for comments and scanned for file access, network, intents, unchecked numbers and `!!`, with the existing emulator tests as evidence; the `checked` column says which numbers were applied.

| path | checked | finding | evidence |
|---|---|---|---|
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/AppearanceScreen.kt` | 5 | none | AccessibilityTest, SurfaceRolesTest passed on the emulator; read for comments and pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/BackupScreen.kt` | 1 2 5 | U-5 | Restore can no longer be started twice; Argon2BackupTest. Scheduled backups are not encrypted (#68) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/BalancesScreen.kt` | 1 4 5 | none | BalancesTreeTest passed on the emulator; totals follow the rows shown |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/BeanbookMark.kt` | 5 | none | LauncherIconTest; comment only |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ConfirmRemoval.kt` | 5 | U-6 | new file |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/CropPage.kt` | 2 4 | none | CropPageTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/DiagnosticsScreen.kt` | 5 | none | DiagnosticsScreenTest passed; clearing asks first |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/DirectiveEntryScreen.kt` | 1 2 | U-1 | an unreadable amount is now refused (TypedAmountTest) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/DocumentCaptureHandle.kt` | 5 | none | data holder |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/DocumentViewer.kt` | 2 4 | I-8 | DocumentViewerTest passed; closing can wait on a render (#73) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/DocumentsScreen.kt` | 1 2 5 | I-7 | DocumentFlowTest; deleting a file is permanent (#72) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/EntryDetailScreen.kt` | 1 2 | none | deleting asks first and names the entry; JournalErrorMarkingTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/EntryEditDialog.kt` | 1 5 | none | text edit; the write protocol re-parses before it commits |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ExpressiveControls.kt` | 5 | none | ExpressiveControlsTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/Fields.kt` | 1 5 | none | InputFieldsTest passed on the emulator |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/Figures.kt` | 1 4 | U-4 | a figure too wide even at its smallest size ends in an ellipsis; AccessibilityTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/FolderNames.kt` | 1 | none | FolderNamesTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/Folio.kt` | 5 | none | SurfaceRolesTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/FormChrome.kt` | 5 | none | AddFormChromeTest passed |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/HomePreferences.kt` | 1 2 | none | unreadable file falls back to the defaults |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/HomeScreen.kt` | 1 4 | U-4 | HomeCardsTest passed; figures come from HomeInsights and LedgerSummary, now remembered |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/JournalFilters.kt` | 5 | none | JournalErrorMarkingTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/Ledger.kt` | 5 | none | SurfaceRolesTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/LedgerFlourish.kt` | 5 | none | LedgerFlourishTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/LedgerScreens.kt` | 1 2 | U-3 | JournalErrorMarkingTest; selecting a plugin-generated entry no longer removes the entry after it (fix in DocumentActions.kt) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/LicencesScreen.kt` | 5 | none | LicencesTest; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/MirrorScreen.kt` | 2 5 | none | MirrorStatusTest passed; each conflict choice keeps the other version as a `.conflict` copy |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/Motion.kt` | 4 5 | none | MotionTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/PageEditorScreen.kt` | 5 | none | PageEditorTest passed; pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/PageThumbnail.kt` | 4 | none | pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/PluginPresentation.kt` | 1 5 | none | PluginStatusTest, FetchedPackagesTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/PluginsScreen.kt` | 1 2 | U-6 | removing every fetched package now asks first |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/Progress.kt` | 5 | none | comment only |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ProjectScreens.kt` | 1 2 5 | none | deleting a project moves it to the trash and says so |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/QuickAddScreen.kt` | 1 2 | U-1 | TypedAmountTest; EntryFormLayoutTest, TemplateEntryTest, AddFormChromeTest passed on the emulator |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/RawEditorScreen.kt` | 1 2 | none | the writes it asks for go through the editor controller (A-5 in app-core) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/RemoteScreen.kt` | 1 3 | none | the cleartext warning appears while typing; no way past a certificate error |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ReportsPane.kt` | 5 | none | pattern-scanned |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ScannerScreen.kt` | 2 4 | none | ScannerScreenTest passed; pattern-scanned, capture file names now unique |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ScriptRunnerScreen.kt` | 2 5 | U-6 | ScriptRunnerTest; removing a saved script now asks first |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/SharingScreen.kt` | 1 3 | U-7 | the risk is stated before the control; a user name can contain a space |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/StatusLine.kt` | 5 | none | comment only |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/TypedAmount.kt` | 1 | U-1 | TypedAmountTest (new file) |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/ValuationPreference.kt` | 1 | none | one-line file; unreadable value falls back |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Colour.kt` | 5 | none | pixel gates in PixelGateTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Measure.kt` | 5 | none | constants |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Motion.kt` | 5 | none | MotionTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Shapes.kt` | 5 | none | constants |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Spacing.kt` | 5 | none | constants |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Theme.kt` | 5 | none | SurfaceRolesTest |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/ThemeSettings.kt` | 1 2 | none | unreadable file falls back to the defaults |
| `app/src/main/kotlin/io/github/pintofi/beanbook/app/ui/theme/Type.kt` | 5 | none | constants |

Gradle: `:app:testPlayDebugUnitTest :app:compileExpertDebugKotlin detekt :app:lintPlayDebug :app:lintExpertDebug :app:verifyPlayFlavorBoundaries` pass. On the API 36 emulator all 136 tests of the `ui` instrumentation package passed.

## Fixed in this pull request

- **U-1 (S1): an amount that was not a number was dropped without a word.** `QuickAddScreen` turned an unreadable box (`4,50`, `4.5O`, `1e5`) into "no amount", which means "let Beancount work it out", so the figure typed was replaced by whatever balanced. The form now refuses to save and names the box. A new `parseTypedAmount` accepts what Beancount accepts (digits, a point, comma-grouped thousands) and nothing else. The directive screen uses it too.
- **U-3 (S1): deleting a selection could remove an extra entry.** (Not covered by a test: the delete runs inside the activity.) Entries a plugin generates point at the line that caused them, so selecting one selects every entry at that position, and the same lines were removed twice, taking the entry after them. Each position is now removed once (`DocumentActions.deleteEntries`).
- **U-4 (S2):** a figure too wide even at its smallest size was cut with no sign; it now ends in an ellipsis. Home no longer recomputes its summary on every recomposition. Two pictures brought in from the gallery in the same millisecond could be written to one file; capture names are unique.
- **U-5 (S2):** Restore could be started twice by tapping again during a long copy; the pending restore is cleared at once (in `BackupController`, with the screen).
- **U-6 (S2):** removing every fetched package, and removing a saved script (the only copy the app holds), now ask first and say what goes and what stays.
- **U-7 (S3):** a sharing user name could not contain a space, because it was trimmed as it was typed; it is trimmed on save.
- **U-8 (S3):** about 40 private ids and references to documents that are not in the repository removed from comments.

## Filed as issues

- **I-7 (S2)** deleting a document file cannot be undone: [#72](https://github.com/pintofi/beanbook/issues/72).
- **I-8 (S3)** closing the document viewer can wait on a page that is still rendering: [#73](https://github.com/pintofi/beanbook/issues/73).
- Already filed from the previous area and met again here: [#67](https://github.com/pintofi/beanbook/issues/67), [#68](https://github.com/pintofi/beanbook/issues/68).

## Accepted, with the reason

- **No minimum length on a backup passphrase.** The screen says plainly that a forgotten passphrase cannot be recovered, and Argon2id slows guessing; a rule here would be a policy the user did not ask for.
- **The visual files were not read line by line.** They hold no money, file or network logic, and 136 emulator tests exercise them. A later pass for accessibility (TalkBack) is already on the roadmap.
