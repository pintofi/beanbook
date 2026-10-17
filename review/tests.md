# Review log: tests (JVM, Python and instrumentation)

Area `tests` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** About 24,000 lines in 175 files. They were scanned rather than read line by line: for tests that cannot fail (every `@Test` body was checked for an assertion; none lacks one), for skipped or ignored tests, fixed sleeps, absolute paths, real names and figures, and private references in comments. The `evidence` column says whether each file ran in this review. Tests for code that other areas changed were already run with those areas.

| path | checked | finding | evidence |
|---|---|---|---|
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/AddActionReachableTest.kt` | 1 5 | none | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/Argon2BackupTest.kt` | 1 5 | none | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/AtomicWriteTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/BackstackTest.kt` | 1 5 | none | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/CatalogManifestTest.kt` | 1 5 | none | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/CatalogPluginTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ColdStartTimingTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/DiagnosticsTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/DocumentFlowTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/EngineLifecycleTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/EngineRestartTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/EngineSurvivesTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/FavaEditorRenderTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/FavaExtensionTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/FavaThemeTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ImportRoundTripTest.kt` | 1 5 | none | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/InstallPermissionTest.kt` | 1 5 | none | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/LedgerCorpusTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/LedgerReaderTest.kt` | 1 5 | none | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/LedgerReloadTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/LicencesTest.kt` | 1 5 | none | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/LotAndPriceTest.kt` | 1 5 | T-1 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/NativeWheelsTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/OverlayAssetsTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/PackagesStateTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/PhotoOrientationTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/PluginBridgeTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/PluginCompatibilityTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/RemoteCredentialsTest.kt` | 1 5 | none | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ReportRenderBudgetTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/SafExportTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/SavedScriptsTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ScriptRunnerTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ScriptWithMissingModuleTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/SnapshotCacheTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/WebViewAuthTest.kt` | 1 5 | none | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/WebViewHardeningTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/WidgetAccountChoiceTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/WidgetEngineIsolationTest.kt` | 1 5 | T-2 | passed on the API 36 emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/WidgetHostTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ZakatExtensionTest.kt` | 1 5 | T-2 | compiles (`:app:compilePlayDebugAndroidTestKotlin`); not run in this review |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/AccessibilityTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/AddFormChromeTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/BalancesTreeTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/CropPageTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/DiagnosticsScreenTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/DocumentViewerTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/EditionNoticesTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/EntryFormLayoutTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/ExpressiveControlsTest.kt` | 1 5 | none | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/HomeCardsTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/InputFieldsTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/JournalErrorMarkingTest.kt` | 1 5 | T-1 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/LauncherIconTest.kt` | 1 5 | none | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/LedgerFlourishTest.kt` | 1 5 | T-1 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/MirrorStatusTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/MotionTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/PageEditorTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/PixelGate.kt` | 1 5 | T-2 | helper used by the `ui` tests, which passed on the emulator |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/PixelGateTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/ScannerScreenTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/SurfaceRolesTest.kt` | 1 5 | none | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/TemplateEntryTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/WidgetConfigureScreenTest.kt` | 1 5 | T-2 | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTest/kotlin/io/github/pintofi/beanbook/app/ui/WindowInsetsTest.kt` | 1 5 | none | passed on the API 36 emulator (all 136 `ui` tests ran) |
| `app/src/androidTestExpert/kotlin/io/github/pintofi/beanbook/app/AndroidWheelTagTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/androidTestExpert/kotlin/io/github/pintofi/beanbook/app/BeangrowOnDeviceTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/androidTestExpert/kotlin/io/github/pintofi/beanbook/app/CatalogueDownloadTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/androidTestExpert/kotlin/io/github/pintofi/beanbook/app/PartialInstallTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/androidTestExpert/kotlin/io/github/pintofi/beanbook/app/WheelFetcherTest.kt` | 1 5 | none | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/debug/AndroidManifest.xml` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/debug/kotlin/io/github/pintofi/beanbook/app/PickerActivity.kt` | 1 5 | none | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/LedgerHealthTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/ReviewFixesTest.kt` | 1 5 | none | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/mirror/MirrorHistoryTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/mirror/MirrorSettingsTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/ui/FetchedPackagesTest.kt` | 1 5 | none | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/ui/FolderNamesTest.kt` | 1 5 | T-1 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/ui/PluginStatusTest.kt` | 1 5 | T-2 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/ui/TypedAmountTest.kt` | 1 5 | T-1 | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/ui/theme/ExpressiveThemeTest.kt` | 1 5 | none | passes in `:app:testPlayDebugUnitTest:test` |
| `app/src/test/kotlin/io/github/pintofi/beanbook/app/widget/WidgetFigureTest.kt` | 1 5 | T-1 | passes in `:app:testPlayDebugUnitTest:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ArchiveEncryptionTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/BackupArchiveTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/CatalogueInstallerTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ChangedPluginTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/DiagnosticLogTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/DocumentLocatorTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/DocumentRootsTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/DocumentStoreTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/DraftStoreTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/HostileJsonTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ImportExportTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ImportOverrunTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/JpegPdfTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/JsonTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/MirrorPlanTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/MultiCurrencyWidgetTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/NewErrorsTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/PluginSafeModeTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/PluginStoreTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ProjectRegistryTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ProjectWorkspaceTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ProjectWriterBytesTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ProjectWriterTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/RestorePlanTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ReviewHardeningTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/SharingPasswordTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/SupportPromptTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/TemplateStoreTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/ValidatorUnavailableTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/WidgetCacheTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/WidgetLinkTest.kt` | 1 5 | T-2 | passes in `:core-data:test` |
| `core-data/src/test/kotlin/io/github/pintofi/beanbook/core/data/WriteProtocolHardeningTest.kt` | 1 5 | none | passes in `:core-data:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/AccountNamesTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/AccountTreeTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/CapturedPagesTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/CloneEntryTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/CommodityDetailsTest.kt` | 1 5 | T-1 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/CropQuadTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/DirectiveDraftTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/DocumentAttachmentTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/DocumentDirectiveTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/DocumentIndexTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/DocumentKeyTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/DocumentNameTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/EntryTemplateTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/HeadlineFiguresTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/HighlightTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/HomeInsightsTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/IncludeGraphTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/JournalErrorsTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/JournalSearchTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/LedgerFormatTest.kt` | 1 5 | T-1 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/LedgerSummaryTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/LinkedDocumentsTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/MoneyTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/PluginPreflightTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/PluginVocabularyTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/PostingLotTest.kt` | 1 5 | T-1 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/PostingMetadataTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/RecentEntriesTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/RefileDocumentTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/RemoveEntryLinesTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/RootTotalValuationTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/StructuredEditTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/TagBalancesTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/ToleranceTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/TransactionDraftTest.kt` | 1 5 | none | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/ValuationTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-domain/src/test/kotlin/io/github/pintofi/beanbook/core/domain/VocabularyGapsTest.kt` | 1 5 | T-2 | passes in `:core-domain:test` |
| `core-engine/src/test/kotlin/io/github/pintofi/beanbook/core/engine/OriginAllowlistTest.kt` | 1 5 | none | passes in `:core-engine:test` |
| `engine-local/src/test/python/test_delete_lines.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_document_directives.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_engine_gate.py` | 1 5 | none | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_entry_metadata.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_fava_options.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_ledger_date_range.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_ledger_display.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_ledger_lots.py` | 1 5 | T-1 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_ledger_valuation.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_plugins.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_review_fixes.py` | 1 5 | none | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_script_live_output.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_sharing.py` | 1 5 | T-2 | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-local/src/test/python/test_writer_line_endings.py` | 1 5 | none | `python -m unittest discover -s engine-local/src/test/python`: 153 tests pass |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/CertificateIsFatalTest.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/IdempotencyTest.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/LostResponseTest.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/PlainTransportForTests.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/RemoteAddressTest.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/RemoteEngineClientTest.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/ReviewFixesTest.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `engine-remote/src/test/kotlin/io/github/pintofi/beanbook/engine/remote/SelfSigned.kt` | 1 5 | none | passes in `:engine-remote:test` |
| `scanner/src/androidTest/kotlin/io/github/pintofi/beanbook/scanner/PageDetectorTest.kt` | 1 5 | none | passed on the API 36 emulator |

Gradle: `:core-domain:test :core-data:test :core-engine:test :engine-remote:test :app:testPlayDebugUnitTest`, compiling the expert flavour and every instrumentation source set, detekt, lint for both editions and `:app:verifyPlayFlavorBoundaries` pass. Instrumentation tests are not part of CI; the ones that ran here are named in the table.

## Fixed in this pull request

- **T-1 (S3): example names and figures replaced with invented ones.** Fund and bank names, a first name in a folder path and exact balances appeared in tests and code comments (21 files). Examples should be obviously made up (`Example-Index-Fund`, `EIF`, `Sam`, round figures). Figures that Beancount derives from them were recomputed, by hand first and then against the engine, so the expected values in `test_ledger_lots.py`, `PostingLotTest` and `LotAndPriceTest` changed with them.
- **T-2 (S3):** about 100 private bug and milestone ids, and references to documents that are not in the repository, removed from test comments. A comment in `PostingLotTest` that said a residual of 0.48 PKR was tolerated was wrong (the entry balanced exactly); it now says so.

## Filed as issues

- **T-3 (S3)** the large-ledger budget test fails on a fresh checkout instead of skipping: [#79](https://github.com/pintofi/beanbook/issues/79).

## Accepted, with the reason

- **Fixed sleeps in four tests** (500 ms twice in `DiagnosticsTest`, 8 s in `FavaEditorRenderTest`, 1.1 s in `ProjectRegistryTest` to cross a one-second timestamp). Each waits for something with no event to wait on; they have not been seen to flake. Replacing them is a change to what is being tested.
- **Several budget tests skip when their data is absent** (`ColdStartTimingTest`, `ReportRenderBudgetTest`, the catalogue tests). The skip is stated in the test and the data is large; whether to generate it in CI is the weekly emulator run, an open decision.
