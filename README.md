# Beanbook

Plain-text double-entry accounting on Android, for [Beancount](https://github.com/beancount/beancount)
ledgers. The app embeds CPython and runs the real Beancount and
[Fava](https://github.com/beancount/fava) on the device, so what you see on a phone is what
`bean-check` and Fava produce on a desktop. It can also act as a client for a Fava server you
host yourself.

Beanbook is an independent project. It is built on Beancount and Fava, and is not affiliated
with or endorsed by them.

Your books remain a folder of ordinary Beancount text files. There is no account to create, no
sign-in, and no server run by this project.

## Status

Implemented, not released. Both editions build and the quality gates pass, but the app has
never been submitted to Google Play. There are no
download links yet, here or anywhere else. When that changes, this section changes with it.

[docs/release-readiness.md](docs/release-readiness.md) tracks where the project stands against
the acceptance criteria in [SPEC.md](SPEC.md).

## Who it is for

People who already keep, or want to keep, books in Beancount and want to work on them from a
phone without converting to some app's private format. If you have never used plain-text
accounting, the [Beancount documentation](https://beancount.github.io/docs/) is the place to
start; this app assumes you know what a directive is.

It is not a general expense tracker, and it will not guess your categories for you.

## What makes it different

Most mobile bookkeeping apps import your data into a database you cannot read. This one edits
the files directly.

- Upstream Beancount and Fava run in-process. The app does not reimplement the parser or the
  reports, so the numbers agree with your desktop.
- Includes, comments, metadata, whitespace outside an edited region, and directives the app
  has no editor for all survive editing, export, and backup.
- Guided entry covers common work. When a form is the wrong tool, you edit the file itself,
  with highlighting, search, validation, and stale-write detection.
- Import, export, backup, and restore are free in both editions, like everything else. Getting your
  records out is not a paid feature.

## Requirements

- Android 10 (API 29) or later. Android 9 does not work: the embedded Python runtime is killed
  by seccomp before the engine starts.
- `arm64-v8a` or `x86_64`. There is no 32-bit build.
- Roughly 48.5 MB to download for a single architecture, or 59.3 MB for the universal package.
  Optional Expert packages add to that, and take noticeably more room unpacked than they do
  to download.

Ships CPython 3.13, Beancount 3.2.3, Fava 1.30.15, beanquery 0.2.0, and beangulp 0.2.0.

## What it does

**Entries and editing.** Guided entry for common directives, plus editing, deleting, and
duplicating existing entries. Every included file is directly editable as text. Nothing is
reported as saved until it has been written and read back successfully, and a write that fails
leaves the previous version of the file in place.

**Reports.** The full Fava surface: balance sheet, income statement, trial balance, holdings,
commodities, account and payee and tag views, charts, and BQL queries.

**Documents.** Capture receipts with the camera, crop them, assemble PDFs, and file them
against entries. Documents live inside the portable project tree.

**Moving data.** Import a folder tree or an archive, export a copy to a folder you choose,
write backup archives, and optionally mirror to a folder or schedule backups.

**Remote mode.** A deliberately narrow client for a Fava server you host. It gives you reports
and quick-add while the server is reachable. It does not keep an offline copy, edit existing
remote entries, or attempt two-way sync.

## Two editions

Play and Expert read the same projects, are both entirely free, and share every
feature except three points that Google Play policy decides:

| | Play | Expert |
|---|---|---|
| User plugins and extensions | Pure Python only | Pure Python and compatible native packages |
| Android bridge in user Python | Withheld | Available |
| Optional package downloads | None | User-confirmed |

Updates differ too: Play through the store, Expert through a signed package you confirm.
The Play edition tells you the Expert edition exists and what it adds.
[docs/editions.md](docs/editions.md) explains which to pick.

## Getting started

Create a ledger or import an existing project, then work on it. The short version:

1. Open Projects and create a new ledger, or import a folder tree or archive.
2. Import the whole tree rather than a single `.beancount` file, so includes and document
   links stay valid.
3. Fix any parse errors in the raw editor, which reports file, line, and column.
4. Set up an export folder, a backup, or mirroring before you rely on the app.

[docs/getting-started.md](docs/getting-started.md) covers each step, where your files actually
live, and what to do when something goes wrong.

## Where your files live

Projects live in app-private storage, which is canonical. That is not an aesthetic choice:
Python and Fava need real POSIX paths, and Android's folder picker hands out content URIs.
Modern file managers cannot browse app-private storage, so the app provides explicit routes
out — an in-app editor for every included file, export to a folder you pick, backup archives,
and optional mirroring. Caches, logs and credentials are kept outside the
portable project tree, so an exported copy contains your ledger and nothing else.

## Privacy and network use

No account, no sign-in, no telemetry, no analytics, no crash reporting, and no ads, in either
edition. Local mode makes no network requests at all.

The app reaches the network in three cases, each of which you start:

- **Your own Fava server**, if you enter its address in remote mode.
- **Optional Python packages** from PyPI, in the Expert edition, when you ask for one.
- **An update check**, in the Expert edition only, when you ask for it.

If you type an `https://` address, certificate errors are fatal and cannot be overridden in
any build. If you type an `http://` address, that traffic is unencrypted and can be read or
modified on the network path, including any password you set. The app says so on screen before
connecting. Cleartext is never inferred and never used as a fallback after a failed HTTPS
connection; it happens only when you type `http://` yourself.

[docs/privacy.md](docs/privacy.md) has the full position, and
[docs/threat-model.md](docs/threat-model.md) has the reasoning behind it.

## Limitations worth knowing before you rely on it

No Git integration. No offline cache in remote mode. No 32-bit build. No OCR yet. No plugin
catalogue: you supply the files. English UI only. The app was designed and measured around
ledgers of roughly 25,000 transactions in about 10 MB; larger ones work, but the first load
after a change costs real time.

[docs/known-limitations.md](docs/known-limitations.md) is the full list, written for someone
deciding whether to depend on this.

## Free, with donations welcome

Every feature is free in both editions: no trial, no paid tiers, no keys, no ads. If Beanbook
is useful to you, the project will accept donations on Ko-fi -- the page is not open yet. The
Expert edition mentions it once, after a week of use; the Play edition does not link to it,
because Google Play's payment rules do not allow that. [docs/support-model.md](docs/support-model.md)
explains the decision.

## Documentation

For users:

| Document | Contents |
|---|---|
| [docs/getting-started.md](docs/getting-started.md) | First project, importing an existing ledger, moving data, troubleshooting |
| [docs/editions.md](docs/editions.md) | Play against Expert, and supporting the project |
| [docs/privacy.md](docs/privacy.md) | What stays on the device and when the network is used |
| [docs/known-limitations.md](docs/known-limitations.md) | What the app cannot do, and why |
| [docs/on-demand-packages.md](docs/on-demand-packages.md) | Fetching extra Python packages, Expert only |
| [docs/plugin-compatibility.md](docs/plugin-compatibility.md) | Which plugins and extensions work, and how that was tested |

For developers:

| Document | Contents |
|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | Building, testing, and the constraints a change must preserve |
| [docs/architecture.md](docs/architecture.md) | Components, trust boundaries, process model, dependency direction |
| [docs/storage-and-backup.md](docs/storage-and-backup.md) | Workspace layout, atomic writes, SAF import and export, archives |
| [docs/threat-model.md](docs/threat-model.md) | Assets, adversaries, mitigations, accepted risk |
| [docs/distribution.md](docs/distribution.md) | Edition matrix, update channels, the Play payment rules |
| [SPEC.md](SPEC.md) | Product scope, non-goals, acceptance criteria |

## Licence

GPL-2.0-only.

Beancount, beanquery, and beangulp are GPL-2.0-*only*, explicitly not "or later". GPLv3 code
cannot be used here. The full text is in [COPYING](COPYING), and the copyright holders are
listed in [AUTHORS](AUTHORS).

Neither edition links any proprietary library for payments: nothing is sold (B81).
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) records the linking permission the project granted
while Play Billing was planned; no shipped build relies on it now.

Beancount and Fava are independent projects. This app is built on their work and is not
affiliated with or endorsed by them.

## Contributing

Bug reports and patches are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the build,
which needs a little more than a checkout because of the embedded Python runtime, and the
boundaries a change has to keep: the data invariants, the edition split, and the licence.
