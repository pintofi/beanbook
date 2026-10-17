# Review log: scanner (document scanning)

Area `scanner` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `scanner/build.gradle.kts` | 3 5 | S-2 | OpenCV is confined to this module (`implementation`, not `api`); arm64-v8a and x86_64 only; comment no longer cites private ids |
| `scanner/src/main/kotlin/io/github/pintofi/beanbook/scanner/PageDetector.kt` | 1 2 4 | S-1, S-2 | PageDetectorTest (5 tests on the emulator); native matrices released; detection returns null rather than a guess |
| `scanner/src/main/kotlin/io/github/pintofi/beanbook/scanner/PageWarp.kt` | 1 2 4 | S-1, S-2 | PageDetectorTest (straightening, document style); intermediates released on every path |
| `scanner/src/main/kotlin/io/github/pintofi/beanbook/scanner/Scanner.kt` | 2 3 4 | S-1 | a failed OpenCV load falls back to the photograph; input matrices released after use |

Gradle: `:scanner:lintDebug detekt :app:lintPlayDebug :app:lintExpertDebug :app:testPlayDebugUnitTest :app:verifyPlayFlavorBoundaries` pass. On the API 36 emulator the 5 scanner tests and 12 app tests that use it (`ScannerScreenTest`, `CropPageTest`, `DocumentFlowTest`) passed.

## Fixed in this pull request

- **S-1 (S2): native image memory was left to the garbage collector.** OpenCV's `Mat` frees its native buffer in `finalize()`. The collector sees a few bytes of Java object, not the megabytes behind it, so a stream of temporary matrices (the live preview runs detection several times a second) lets native memory climb between collections. Every matrix made by detection, warping and the style pass is now released, on failure paths too, and `Scanner` releases the matrices it converts from bitmaps. I did not measure the memory before and after; the existing tests show behaviour is unchanged.
- **S-2 (S3):** private ids and references to documents that are not in the repository removed from comments.

## Accepted, with the reason

- **Corner ordering by sum and difference** can pick the same point twice for a badly degenerate quadrilateral. The convexity test rejects those before ordering, and detection is advisory: a wrong or missing outline leaves the user cropping by hand.
- **OpenCV is Apache-2.0 in a GPL-2.0-only app.** The module comment records the reasoning (the GPL code lives in the engine process; this joins AndroidX and CameraX in the app process). It is a licensing position, not something this review could settle; the licence texts are shipped.
