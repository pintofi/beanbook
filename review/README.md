# Baseline review

Every file in this repository is read once, carefully, before the project grows. This folder is
the record. The plan and progress live in [issue #16](https://github.com/pintofi/beanbook/issues/16)
and its sub-issues, one per area.

## How it works

1. `tools/review-files.py` assigns every tracked file to exactly one area. `tools/review-files.py
   status` shows how many files each area's log covers.
2. An area is worked in one pull request. The pull request adds `review/<area>.md`: a log with
   one row per file.
3. The area is done when `tools/review-files.py check <area>` reports every file logged, and the
   sub-issue's findings are all fixed or have their own issue.

Order, because the cost of a miss falls in this order: `domain`, `data`, `python` (the writer),
`clients`, `app-core`, `app-ui`, `resources`, `scanner`, `tests`, `tools`, `build-ci`,
`data-sets`, `docs`, `site`, `repo-files`. Documentation is late on purpose: it is checked against
code that has already been reviewed.

## What is checked in each file

1. **Invariants.** Decimal only for money. Nothing reports success before a durable write and a
   successful re-parse. Unknown directives, metadata, comments and whitespace survive. A retried
   write never duplicates an entry. Destructive actions are named and undoable.
2. **Failure paths.** Exceptions, process death, empty or odd ledgers, time zones, rotation,
   cancellation.
3. **Boundaries.** The Play and Expert split, the security rules (no new listener, no trust-all
   TLS, WebView hardening), licences (GPL-2.0-only, no GPLv3 code).
4. **Cost.** Main-thread work, leaks, recomposition, needless allocation.
5. **Truth.** Names, comments and documentation match what the code does. A comment that points
   at a document that is not in this repository is replaced with a self-contained explanation.

## Severity

- **S0:** data loss or corruption, or a security rule broken. Stops the review. Fixed first, in
  its own pull request.
- **S1:** wrong output, a crash, or a broken invariant without data loss.
- **S2:** maintainability or performance.
- **S3:** wording or naming.

## Fix or file

Fixed in the area's pull request: S2 and S3 changes of about 30 lines or fewer that do not touch
the write path, and any S1 that gets a regression test. Filed as an issue instead: every S0,
anything that changes behaviour or a storage format, and anything that crosses modules.

## The log

`review/<area>.md` is a table. One row per file:

```
| path | checked | finding | evidence |
|---|---|---|---|
| `core-domain/src/main/kotlin/.../Money.kt` | 1 2 5 | none | MoneyTest, `./gradlew :core-domain:test` |
```

- `checked` lists the numbers above that were actually applied.
- `finding` is `none` or a finding id (`D-3`) described under the table with severity, file and
  line, and what was done.
- `evidence` names the test or command that backs the row. **"none" is accepted only with
  evidence.** A file that has no test says so here.
- The first column is the file path in backticks, which is what the script reads.

## Rules for whoever reviews

- A documented decision (a comment or document giving the reason) is quoted before it is changed,
  and a guard is never removed because it looks redundant.
- One area per pull request. Behaviour changes are separate from cleanup.
- Every finding cites a file and a line, checked with `git grep`.
