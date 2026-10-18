# Review log: core-domain

Area `domain` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `core-domain/build.gradle.kts` | 3 5 | D-7 | `:core-domain:test` runs; no other module imports coroutines from here |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/AccountTree.kt` | 1 2 4 5 | none | AccountTreeTest; O(n²) tree build is fine at ledger sizes seen (25k-entry corpus has about 1k accounts) |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/BeancountText.kt` | 1 2 5 | D-3, D-6, I-2 | RefileDocumentTest, DocumentDirectiveTest, RemoveEntryLinesTest, IncludeGraphTest, HighlightTest, DocumentKeyTest; `git grep` showed the plugin-directive helpers had no production caller |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/CapturedPages.kt` | 2 5 | D-6 | CapturedPagesTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/CommodityDetails.kt` | 1 2 5 | D-4 | CommodityDetailsTest (two new cases for thousands separators) |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/CropQuad.kt` | 2 5 | none | CropQuadTest; Float is pixel geometry, not money |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/DirectiveDraft.kt` | 1 2 5 | D-6, I-3 | DirectiveDraftTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/DocumentIndex.kt` | 1 2 5 | D-6, I-2 | DocumentIndexTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/DocumentName.kt` | 1 2 3 5 | D-6 | DocumentNameTest: deterministic names, account and extension validated, no path can leave `documents/` |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/EntryTemplate.kt` | 1 2 5 | I-1 | EntryTemplateTest passes; it does not cover mixed currencies or cost and price, which is the finding |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/HeadlineFigures.kt` | 1 5 | D-6 | HeadlineFiguresTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/HomeInsights.kt` | 1 2 5 | D-6 | HomeInsightsTest; `shareOf` uses Float for a bar fraction only |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/JournalErrors.kt` | 2 5 | none | JournalErrorsTest; `!!` are all behind a non-null filter |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/Ledger.kt` | 1 5 | D-6 | LedgerFormatTest, JournalSearchTest, LinkedDocumentsTest, RecentEntriesTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/LedgerFormat.kt` | 1 2 5 | D-1, I-4 | LedgerFormatTest; HALF_EVEN matches Beancount display rounding (comment cites why) |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/LedgerSummary.kt` | 1 5 | none | LedgerSummaryTest, RootTotalValuationTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/Money.kt` | 1 2 5 | D-1, D-6 | MoneyTest, ToleranceTest (cases checked against `bean-check` 3.2.3); no Double or Float anywhere in the money path |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/PluginPolicy.kt` | 3 5 | none | used through PluginPreflightTest and the Play boundary gate |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/PluginPreflight.kt` | 2 3 4 | D-2 | PluginPreflightTest (three new cases: bounded read, in-limit read, oversized source file) |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/StructuredEdit.kt` | 1 2 5 | D-5 | StructuredEditTest (one new case) |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/TagBalances.kt` | 1 5 | none | TagBalancesTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/TransactionDraft.kt` | 1 2 5 | D-1, D-6, I-1 | TransactionDraftTest, ToleranceTest, CloneEntryTest, PostingLotTest |
| `core-domain/src/main/kotlin/io/github/pintofi/beanbook/core/domain/Valuation.kt` | 1 5 | A-1 | ValuationTest |

`./gradlew :core-domain:test`: 319 tests, 0 failures after the fixes below. `:core-data:test`, `:core-engine:test`, `:engine-remote:test`, `:app:testPlayDebugUnitTest` and `detekt` pass too.

## Fixed in this pull request

- **D-1 (S1): the balance check was not Beancount's.** `TransactionDraft.validate` refused to save an entry whose residual was within Beancount's tolerance, so a valid entry could not be added from the form. It used one tolerance for all currencies, took whole numbers into it (forcing zero), and let the file's `inferred_tolerance_default` replace the inferred value where Beancount takes the larger of the two. Beancount 3.2.3's `infer_tolerances` (`beancount/core/interpolate.py`) was read and ported as `Tolerances`: per currency, from the units written with decimals, whole numbers contribute nothing, the file's default is a floor, and `*` is what a currency with nothing else gets. Evidence: `bean-check` accepts `1000.5 PKR / -1000.45 PKR` under `inferred_tolerance_default PKR:0.01` and `10 USD / -5.005 USD / -4.99 USD`; the old code refused both (confirmed by running the new cases against the old code), the new code accepts them, and `ToleranceTest` covers them with a boundary and a still-refused case. The unused `Balance.toleranceFor` and `isBalancedWithin` were removed.
- **D-2 (S1): `PluginPreflight` could be made to allocate without limit.** An archive entry was read completely with `readBytes()` and measured afterwards, so one entry that expands to gigabytes exhausted memory before the 64 MB ceiling was ever checked. A plain source file and an archive were also read whole to hash them. Entries are now read with `readAtMost` (abandoned as soon as they pass what is left of the budget), files are hashed as a stream, and a source file over the ceiling is refused unread. Tests: an endless stream is given up on within the limit plus one buffer, an in-limit entry is read in full, an oversized source file is refused.
- **D-3 (S2): dead code with latent bugs.** `hasPluginDirective`, `withPluginDirective` and `withoutPluginDirective` had no production caller (only a test and one instrumentation setup). They rewrote line endings with `lines()`/`joinToString("\n")` (a CRLF ledger would have been converted, which invariant 4 forbids) and inserted after the last `plugin` line even when that directive's string continued on following lines (the owner's own ledger has such a plugin). Removed, with their test; the instrumentation test writes its one directive line directly.
- **D-4 (S2):** `CommodityDetails` refused a cost such as `{1,250.00 USD}` that Beancount reads, because the number pattern had no thousands separators and the lot was split on every comma. Fixed with a separator-aware split and two tests. The unused `Read.cost()` was removed.
- **D-5 (S2):** `StructuredEdit.tokens` did not treat `\"` inside a string as an escape, so spacing after an escaped quote was collapsed when comparing layout. Fixed, with a test through the public API.
- **D-6 (S3):** about 60 comments cited internal tracker ids, `SPEC.md`, an internal project guide, a design document and an audit that are not in this repository; removed or reworded to stand alone. Four KDoc blocks sat above the wrong declaration (`Ledger.kt`, `CapturedPages.kt`, `BeancountText.kt`, `DocumentIndex.kt`) and were moved.
- **D-7 (S2):** `kotlinx-coroutines-core` was declared by `core-domain` and imported nowhere. Removed.

## Filed as issues

- **I-1 (S1)** `EntryTemplate.from` keeps one currency for the whole template and drops cost, price, comments and metadata, so a template of a currency exchange or a purchase at cost is saved as a different entry and shown as one that does not balance. Changes what is stored: [#47](https://github.com/pintofi/beanbook/issues/47).
- **I-2 (S2)** `removeDocumentMetadata` and `DocumentIndex` match a document by its bare file name as well as its path, so a different document with the same name in another folder can be unlinked, or shown as attached: [#48](https://github.com/pintofi/beanbook/issues/48).
- **I-3 (S2)** `Balance` and `Price` directives are aligned at a fixed column and ignore the ledger's `currency-column`: [#49](https://github.com/pintofi/beanbook/issues/49).
- **I-4 (S2)** `tolerance_multiplier` and `infer_tolerance_from_cost` are not read from the ledger, so a ledger that changes either gets the default in the form's balance check. Needs the engine to report them: [#50](https://github.com/pintofi/beanbook/issues/50).
- **I-5 (S3)** Public members with no production caller (`accountsMatching`, `payeesMatching`, `currenciesMatching`, `biggestMover`, `isBalanced`, `isCropped`, `isRectangular`, `isValid`) are kept for now; and a detekt rule that bans `Float` and `Double` from the money paths would make invariant 1 mechanical: [#51](https://github.com/pintofi/beanbook/issues/51).

## Accepted, with the reason

- **A-1: rounding modes.** `LedgerFormat.number` rounds half-even, as Beancount's display context does (the code comment gives the source). `HomeInsights.shareOf` rounds half-up and returns a `Float`, but it only sizes a bar. `PriceBook.convert` and cost-per-unit division use `MathContext.DECIMAL64` (16 significant digits, half-even); Beancount works to 28, so the two can differ only beyond the sixteenth digit, far below any ledger's display precision.
- `Float` appears only in `CropQuad` (pixel geometry) and `shareOf`. No money value passes through a binary float.
