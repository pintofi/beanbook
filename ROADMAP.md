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

- **1.0.0** (2026-10-15): local Beancount and Fava on the phone, entry forms and editors, documents and scanning, backup and restore, mirror, remote Fava, plugins and Fava extensions (Expert), Share on Wi-Fi. See the [changelog](CHANGELOG.md).

## Now: 1.1 Trust and polish

Make the app sturdier before it grows.

| Item | Status | Why |
|---|---|---|
| A careful review of every file in the repository ([#16](https://github.com/pintofi/beanbook/issues/16)) | in progress: 4 of 15 areas done (`core-domain`, `core-data`, `engine-local`, `clients`: [logs](review/)) | A known-good baseline to build on |
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

## Not planned

Two-way sync and embedded Git (a sync engine under another name; Mirror plus Syncthing, MGit or
Termux already cover it), cloud OCR or text AI (they send your data off the phone), accounts,
analytics, ads, and another full redesign before the screenshot tests exist.

## How to propose something

Open a [feature request](https://github.com/pintofi/beanbook/issues/new?template=feature_request.yml).
An idea moves to **accepted** when it has a short design note and a milestone, and into **Now**
when a milestone takes it. A bug is not a roadmap item: use the
[bug form](https://github.com/pintofi/beanbook/issues/new?template=bug_report.yml). Security
problems go through [SECURITY.md](SECURITY.md).

Last reviewed: 2026-10-16. The README carries a shorter checklist of the same plan; this page is the detail.
