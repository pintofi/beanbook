# Roadmap

Where Beanbook is going. Each block below is a [GitHub milestone](https://github.com/pintofi/beanbook/milestones);
the issues in it are the plan, and this page is the readable summary. Dates are not promised: a
version ships when its work is done and tested.

Beanbook stays what it is: your ledger as plain Beancount text on your phone, with the real
Beancount and Fava running on the device. There is no account, no server run by this project, no
telemetry, no ads, and no network use in local mode. Ideas that need any of those are out of scope.

Status of an item: **proposed** (an idea, not yet taken), **accepted** (it has a design and a
milestone), **in progress**, **shipped in x.y.z**.

Versions follow [semantic versioning](https://semver.org/): a new feature goes in a minor release
(1.1.0, 1.2.0 and so on), and fixes and tidy-ups go in patch releases (1.0.4, 1.0.5). The milestones
are named that way.

## Shipped

- **1.0.4** (2026-11-07): the entry form's flag is Cleared, Pending or any other flag Beancount allows, a backup (.bcaz) can be restored from the first-run setup, the Journal's filters are one choice, templates can be renamed and deleted, the widgets follow the system colours and the Balances widget shows the basis you tap, Mirror settles a file deleted on one side, and a set of fixes to rounding, suggestions and notifications. See the [changelog](CHANGELOG.md).
- **1.0.3** (2026-10-30): the scanner's zoom slider, one Filters button for Home, Journal and Balances, the Units, At cost and Market value choice on the Journal and on the widgets, a notice when the phone's WebView is too old for Reports, and a set of fixes to setup, importing, unlinking documents, large text and widget resizing. See the [changelog](CHANGELOG.md).
- **1.0.2** (2026-10-25): the document scanner reworked (review screen, crop screen with a magnifier, better page detection, flat black-and-white and greyscale styles, A4 or Letter), a guided first run and an Accounts page with account details, encrypted scheduled backups, a trash for deleted documents, a new entry form with standard buttons, and the safety fixes from a second review. See the [changelog](CHANGELOG.md).
- **1.0.1** (2026-10-18): one APK per processor type for F-Droid, a Trash list for deleted projects, receipt-photo cleanup, a safer document Unlink, a more careful balance check, and many other fixes from the repository review. See the [changelog](CHANGELOG.md).
- **1.0.0** (2026-10-15): local Beancount and Fava on the phone, entry forms and editors, documents and scanning, backup and restore, mirror, remote Fava, plugins and Fava extensions (Expert), Share on Wi-Fi. See the [changelog](CHANGELOG.md).

## Now: 1.0.5 Trust and polish, continued

1.0.5 is a patch release: fixes and tidy-ups, with new features in the 1.x.0 releases that follow.
What is left from 1.0.4:

| Item | Status | Why |
|---|---|---|
| A TalkBack pass on a device ([#18](https://github.com/pintofi/beanbook/issues/18)) | accepted, on hold | Automated checks do not cover screen-reader order |
| The instrumentation suite in CI, weekly ([#19](https://github.com/pintofi/beanbook/issues/19)) | in progress: the weekly run exists and is waiting for a green scheduled run | Regressions and flakes should be visible |
| User-facing strings moved into resources ([#21](https://github.com/pintofi/beanbook/issues/21), [#76](https://github.com/pintofi/beanbook/issues/76)) | accepted, with no visible change | Groundwork for translations |
| Hand-passed parameters grouped into state holders ([#67](https://github.com/pintofi/beanbook/issues/67)) | accepted | Every new screen needs a parameter threaded through three files |
| A click-through walkthrough of the app on the site ([#160](https://github.com/pintofi/beanbook/issues/160)) | accepted | Show the app before someone installs it |
| A way to update and go back for plugins and packages ([#343](https://github.com/pintofi/beanbook/issues/343)) | in progress: catalogue plugins first | Nothing in the app updates an installed plugin yet |
| A project can be renamed ([#368](https://github.com/pintofi/beanbook/issues/368)) | accepted | A project imported from a ledger with no title keeps a name made of its folder path |
| Measured speed figures for the README ([#363](https://github.com/pintofi/beanbook/issues/363)) | accepted | A one-off benchmark on a phone and the emulator |
| Reports fail on a ledger whose included file sets display_precision ([#349](https://github.com/pintofi/beanbook/issues/349)) | cause not confirmed | A report from one ledger, not seen again |

## Next: 1.1.0 Capture

Get an entry into the ledger faster, from anywhere on the phone.

| Item | Status | Why |
|---|---|---|
| Add an entry from shared text, photos and PDFs ([#23](https://github.com/pintofi/beanbook/issues/23)) | proposed | A bank SMS or receipt should become a draft in two taps |
| A Quick Settings tile and launcher shortcut for a new entry ([#24](https://github.com/pintofi/beanbook/issues/24)) | proposed | The form is the most used screen |
| Entry templates and recurring reminders ([#25](https://github.com/pintofi/beanbook/issues/25)) | proposed | Rent and subscriptions are the same entry every month |
| Rules that suggest the account for a shared message ([#283](https://github.com/pintofi/beanbook/issues/283)) | proposed | "Zomato" should land in Expenses:Food without choosing it each time |
| Settle an entry: a refund, pay-back or reimbursement linked to the original ([#242](https://github.com/pintofi/beanbook/issues/242)) | proposed | Shows what was really spent when a friend or employer pays you back |

## Later

**1.2.0 Reconcile and import**

| Item | Status | Why |
|---|---|---|
| A reconcile screen ([#26](https://github.com/pintofi/beanbook/issues/26)) | proposed | Check the ledger against the bank statement |
| Import bank statements on the phone ([#27](https://github.com/pintofi/beanbook/issues/27)) | proposed | The importers are already bundled |
| Syncthing conflict files in Mirror ([#28](https://github.com/pintofi/beanbook/issues/28)) | proposed | A common way to sync a ledger folder |

**1.3.0 Reach**

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

Last reviewed: 2026-11-07. The README carries a shorter checklist of the same plan; this page is the detail.
