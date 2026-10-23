# Roadmap

Where Beanbook is going. Each block below is a [GitHub milestone](https://github.com/pintofi/beanbook/milestones);
the issues in it are the plan, and this page is the readable summary. Dates are not promised: a
version ships when its work is done and tested.

Beanbook stays what it is: your ledger as plain Beancount text on your phone, with the real
Beancount and Fava running on the device. There is no account, no server run by this project, no
telemetry, no ads, and no network use in local mode. Ideas that need any of those are out of scope.

Status of an item: **proposed** (an idea, not yet taken), **accepted** (it has a design and a
milestone), **in progress**, **shipped in x.y.z**.

## Shipped

- **1.0.1** (2026-10-18): one APK per processor type for F-Droid, a Trash list for deleted projects, receipt-photo cleanup, a safer document Unlink, a more careful balance check, and many other fixes from the repository review. See the [changelog](CHANGELOG.md).
- **1.0.0** (2026-10-15): local Beancount and Fava on the phone, entry forms and editors, documents and scanning, backup and restore, mirror, remote Fava, plugins and Fava extensions (Expert), Share on Wi-Fi. See the [changelog](CHANGELOG.md).

## Now: 1.1 Trust and polish

Make the app sturdier before it grows.

| Item | Status | Why |
|---|---|---|
| A careful review of every file in the repository ([#16](https://github.com/pintofi/beanbook/issues/16)) | done: 15 of 15 areas (`core-domain`, `core-data`, `engine-local`, `clients`, `app-core`, `app-ui`, `resources`, `scanner`, `tests`, `tools`, `build-ci`, `data-sets`, `docs`, `site`, `repo-files`: [logs](review/)) | A known-good baseline to build on |
| A guided first run and account management: setup steps, a ledger Beancount reads before it is kept, and an Accounts page ([#127](https://github.com/pintofi/beanbook/issues/127) to [#133](https://github.com/pintofi/beanbook/issues/133)) | merged ([#174](https://github.com/pintofi/beanbook/pull/174), [#140](https://github.com/pintofi/beanbook/pull/140)), ships in 1.1 | A new ledger should start with the person's own currency and accounts, and an account should be something you can open in the app |
| Fixes from daily use: one filter for Home, Journal and Balances, per-widget choices, signed amounts, document pages, editing entries that carry comments, buttons that look pressable ([#116](https://github.com/pintofi/beanbook/issues/116) to [#126](https://github.com/pintofi/beanbook/issues/126), [#134](https://github.com/pintofi/beanbook/issues/134) to [#136](https://github.com/pintofi/beanbook/issues/136)) | merged ([#138](https://github.com/pintofi/beanbook/pull/138), [#141](https://github.com/pintofi/beanbook/pull/141)), ships in 1.1 | Found by using the app on a phone |
| Safety fixes from a second look: a remote retry that could send an entry twice ([#156](https://github.com/pintofi/beanbook/issues/156)), unbounded reads of a backup ([#157](https://github.com/pintofi/beanbook/issues/157)), a PDF page that crashed the viewer ([#158](https://github.com/pintofi/beanbook/issues/158)), an entry waiting for a remote retry lost when the app closed ([#65](https://github.com/pintofi/beanbook/issues/65)), a backup written from files that changed under it ([#53](https://github.com/pintofi/beanbook/issues/53)), a damaged plugin list read as empty ([#58](https://github.com/pintofi/beanbook/issues/58)), a stale saved answer after a receipt changed ([#61](https://github.com/pintofi/beanbook/issues/61)), an imported ledger left in two copies ([#55](https://github.com/pintofi/beanbook/issues/55)), templates that lost currencies and lots ([#47](https://github.com/pintofi/beanbook/issues/47)) | merged, ships in 1.1 | Each could lose or duplicate something without saying so |
| The document scanner on OpenCV 5 ([#176](https://github.com/pintofi/beanbook/issues/176)) | merged, ships in 1.1 | Keeping the one native library current. It adds about 4.5 MB to each APK |
| The document scanner, reworked on standard components: auto-capture that shoots a page once and shows a countdown ([#179](https://github.com/pintofi/beanbook/issues/179)), a review screen with a thumbnail strip, retake, add page and apply to all ([#180](https://github.com/pintofi/beanbook/issues/180)), better styles with an automatic default ([#181](https://github.com/pintofi/beanbook/issues/181)), a crop screen with a magnifier and edge handles ([#182](https://github.com/pintofi/beanbook/issues/182)), page detection with more than one pass ([#183](https://github.com/pintofi/beanbook/issues/183)), and an A4 or Letter option ([#184](https://github.com/pintofi/beanbook/issues/184)) | accepted, starting with the auto-capture fix | It works, but it feels custom and inconsistent, and the filters flatten receipts to grey |
| A smaller OpenCV build for the scanner, measured before it is decided ([#185](https://github.com/pintofi/beanbook/issues/185)) | proposed | The library is 13.7 MB compressed per APK and the scanner uses a small part of it, but building it ourselves has a running cost |
| The rest of the 1.1 bugs and tidy-ups: ledger currency column and tolerance options ([#49](https://github.com/pintofi/beanbook/issues/49), [#50](https://github.com/pintofi/beanbook/issues/50)), cent differences from Fava ([#60](https://github.com/pintofi/beanbook/issues/60)), encrypted backups held in memory ([#54](https://github.com/pintofi/beanbook/issues/54)), scheduled backups not encrypted ([#68](https://github.com/pintofi/beanbook/issues/68)), a document delete that cannot be undone ([#72](https://github.com/pintofi/beanbook/issues/72)), closing the viewer mid-render ([#73](https://github.com/pintofi/beanbook/issues/73)), plugin-rewritten narrations ([#70](https://github.com/pintofi/beanbook/issues/70)), and others in the milestone | accepted | Small, but each one is a place the app can surprise someone |
| Fava's own editor saves outside the app's write protocol ([#17](https://github.com/pintofi/beanbook/issues/17)) | accepted | Every change should get a snapshot, a re-parse and Undo |
| A TalkBack pass on a device ([#18](https://github.com/pintofi/beanbook/issues/18)) | accepted | Automated checks do not cover screen-reader order |
| The instrumentation suite in CI, weekly ([#19](https://github.com/pintofi/beanbook/issues/19)) | accepted | Regressions and flakes should be visible |
| Screenshot tests, light and dark, at 200% text ([#20](https://github.com/pintofi/beanbook/issues/20)) | accepted | So the look can change without surprise |
| User-facing strings moved into resources ([#21](https://github.com/pintofi/beanbook/issues/21)) | accepted | Groundwork for translations |
| Reproducible wheels and APK checked in CI ([#22](https://github.com/pintofi/beanbook/issues/22)) | accepted | F-Droid verifies the build byte for byte |

## Next: 1.2 Capture

Get an entry into the ledger faster, from anywhere on the phone.

| Item | Status | Why |
|---|---|---|
| Add an entry from shared text, photos and PDFs ([#23](https://github.com/pintofi/beanbook/issues/23)) | proposed | A bank SMS or receipt should become a draft in two taps |
| A Quick Settings tile and launcher shortcut for a new entry ([#24](https://github.com/pintofi/beanbook/issues/24)) | proposed | The form is the most used screen |
| Entry templates and recurring reminders ([#25](https://github.com/pintofi/beanbook/issues/25)) | proposed | Rent and subscriptions are the same entry every month |

## Later

**1.3 Reconcile and import**

| Item | Status | Why |
|---|---|---|
| A reconcile screen ([#26](https://github.com/pintofi/beanbook/issues/26)) | proposed | Check the ledger against the bank statement |
| Import bank statements on the phone ([#27](https://github.com/pintofi/beanbook/issues/27)) | proposed | The importers are already bundled |
| Syncthing conflict files in Mirror ([#28](https://github.com/pintofi/beanbook/issues/28)) | proposed | A common way to sync a ledger folder |

**1.4 Reach**

| Item | Status | Why |
|---|---|---|
| A send queue for a remote Fava ([#29](https://github.com/pintofi/beanbook/issues/29)) | proposed | Add entries when the server is not reachable |
| Price updates on request, Expert edition ([#30](https://github.com/pintofi/beanbook/issues/30)) | proposed | Opt-in, never automatic |
| Translations ([#31](https://github.com/pintofi/beanbook/issues/31)) | proposed | Beancount has a large non-English community |
| Git sync for a project: pull and push, never an automatic merge ([#154](https://github.com/pintofi/beanbook/issues/154)) | proposed | A ledger kept in Git should not need another app. Opt-in, and a conflict stops and asks |

## Not planned

A sync that merges conflicting edits for you (it can lose money without saying so; Git sync, if it
comes, stops and shows both versions), cloud OCR or text AI (they send your data off the phone),
accounts, analytics, ads, and another full redesign before the screenshot tests exist.

## How to propose something

Open a [feature request](https://github.com/pintofi/beanbook/issues/new?template=feature_request.yml).
An idea moves to **accepted** when it has a short design note and a milestone, and into **Now**
when a milestone takes it. A bug is not a roadmap item: use the
[bug form](https://github.com/pintofi/beanbook/issues/new?template=bug_report.yml). Security
problems go through [SECURITY.md](SECURITY.md).

Last reviewed: 2026-10-23. The README carries a shorter checklist of the same plan; this page is the detail.
