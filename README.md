<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/banner-dark.svg">
  <img src="docs/assets/banner-light.svg" width="100%" alt="Beanbook: plain-text accounting for Beancount, on your phone. The app mark, two brackets around two equal rules, beside the name.">
</picture>

<br>

<a href="COPYING"><img src="docs/assets/badges/licence.svg" height="28" alt="Licence: GPL-2.0-only"></a>
<img src="docs/assets/badges/android.svg" height="28" alt="Android 10 or later (API 29)">
<img src="docs/assets/badges/kotlin.svg" height="28" alt="Kotlin and Jetpack Compose">
<img src="docs/assets/badges/beancount.svg" height="28" alt="Beancount 3.2.3">
<img src="docs/assets/badges/fava.svg" height="28" alt="Fava 1.30.15">
<img src="docs/assets/badges/python.svg" height="28" alt="CPython 3.13">
<a href="docs/privacy.md"><img src="docs/assets/badges/telemetry.svg" height="28" alt="Telemetry: none"></a>

<a href="https://github.com/pintofi/beanbook/releases/latest"><img src="docs/assets/badges/get-it-on-github.png" height="64" alt="Download the APK from GitHub Releases"></a>

<h3>Your ledger stays a folder of Beancount text files.<br>Beanbook lets you work on it from your phone.</h3>

The real Beancount and Fava run on the device, so the figures you see are the ones<br>
`bean-check` and Fava give you on a desktop. No account, no sign-in, no server run by this project.

[**What it is**](#what-it-is) &nbsp;·&nbsp; [**See it**](#see-it) &nbsp;·&nbsp; [**What it does**](#what-it-does) &nbsp;·&nbsp; [**How a save works**](#how-a-save-is-written) &nbsp;·&nbsp; [**Editions**](#two-editions) &nbsp;·&nbsp; [**Privacy**](#privacy-and-network-use) &nbsp;·&nbsp; [**Build**](#build-and-test) &nbsp;·&nbsp; [**Docs**](#documentation)

</div>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/rule-dark.svg">
  <img src="docs/assets/rule-light.svg" width="100%" alt="">
</picture>

## What it is

Plain-text double-entry accounting on Android, for [Beancount](https://github.com/beancount/beancount)
ledgers. The app embeds CPython and runs the real Beancount and
[Fava](https://github.com/beancount/fava) on the device, so what you see on a phone is what
`bean-check` and Fava produce on a desktop. It can also act as a client for a Fava server you
host yourself.

Beanbook is an independent project. It is built on Beancount and Fava, and is not affiliated
with or endorsed by them.

Your books remain a folder of ordinary Beancount text files. There is no account to create, no
sign-in, and no server run by this project.

<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/ledger-dark.svg">
  <img src="docs/assets/ledger-light.svg" width="100%" alt="A transaction written onto a slip from transactions.beancount: 2026-09-12, Corner grocer, Weekly shop; Expenses:Food:Groceries 71.85 EUR; Assets:Bank:Checking -71.85 EUR. The postings sum to 0.00 EUR and the slip is stamped In balance. Beside it, the save steps in order: lock the project, keep a copy to go back to, note the change, write a temporary file to disk, swap it into place, Beancount reads it back, and only then Saved.">
</picture>
<sub>An invented ledger's transaction, and the order every save follows. The steps are explained <a href="#how-a-save-is-written">below</a>.</sub>
</div>

### Who it is for

People who already keep, or want to keep, books in Beancount and want to work on them from a
phone without converting to some app's private format. If you have never used plain-text
accounting, the [Beancount documentation](https://beancount.github.io/docs/) is the place to
start; this app assumes you know what a directive is.

It is not a general expense tracker, and it will not guess your categories for you.

### What makes it different

Most mobile bookkeeping apps import your data into a database you cannot read. This one edits
the files directly.

<table>
<tr>
<td width="50%" valign="top">

**The real engines, not a copy**<br>
Upstream Beancount and Fava run in-process. The app does not reimplement the parser or the
reports, so the numbers agree with your desktop.

</td>
<td width="50%" valign="top">

**Nothing of yours is thrown away**<br>
Includes, comments, metadata, whitespace outside an edited region, and directives the app
has no editor for all survive editing, export, and backup.

</td>
</tr>
<tr>
<td width="50%" valign="top">

**A form when it helps, the file when it does not**<br>
Guided entry covers common work. When a form is the wrong tool, you edit the file itself,
with highlighting, search, validation, and stale-write detection.

</td>
<td width="50%" valign="top">

**Getting your records out is free**<br>
Import, export, backup, and restore are free in both editions, like everything else. Getting
your records out is not a paid feature.

</td>
</tr>
</table>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/rule-dark.svg">
  <img src="docs/assets/rule-light.svg" width="100%" alt="">
</picture>

## See it

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/tour-dark.svg">
  <img src="docs/assets/tour-light.svg" width="300" alt="A tour of the app, one screen after another: Home with net worth 6563.40 EUR, the Journal, the New entry form with its Will be written as preview, Balances, Reports drawn by Fava, Documents, and More.">
</picture>

<sub>Home, Journal, the new-entry preview, Balances, Reports, Documents and More.</sub>

<br><br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/phones-dark.webp">
  <img src="docs/assets/screenshots/phones-light.webp" width="88%" alt="Three phones. In front, Home: net worth 6563.40 EUR, income 5700.00 EUR, expenses 2486.60 EUR, and where the money went this month. Behind on the left, the Journal with its search, filters and entries. Behind on the right, Reports: Fava's income statement with a monthly chart.">
</picture>

</div>

<details>
<summary><b>More screens</b>: an entry, the entry form, Balances, Documents, Plugins, widgets</summary>
<br>

<table>
<tr>
<td align="center" width="33%">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/entry-dark.webp"><img src="docs/assets/screenshots/entry-light.webp" width="240" alt="An entry: Corner grocer, Weekly shop, 2026-09-12, cleared. Postings Expenses:Food:Groceries 71.85 EUR and Assets:Bank:Checking -71.85 EUR, one receipt PDF with View and Share, and where it is: transactions.beancount line 62. Buttons Clone, Edit, Delete, Close."></picture><br>
<sub><b>An entry</b>: its postings, its receipt, and the file and line it lives on.</sub>
</td>
<td align="center" width="33%">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/form4-dark.webp"><img src="docs/assets/screenshots/form4-light.webp" width="240" alt="The New entry form scrolled to its preview, headed Will be written as, showing the exact Beancount text of the transaction, above a Save entry button."></picture><br>
<sub><b>New entry</b>: the exact text is shown before anything is written.</sub>
</td>
<td align="center" width="33%">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/balances-dark.webp"><img src="docs/assets/screenshots/balances-light.webp" width="240" alt="Balances: Assets 6581.90 EUR with Bank and Cash below it, Liabilities -18.50 EUR, Equity -3350.00 EUR, with a filter field, a Filters button and the Units, At cost and Market value switch."></picture><br>
<sub><b>Balances</b>, in Units, At cost or Market value, as Fava does.</sub>
</td>
</tr>
<tr>
<td align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/journal-dark.webp"><img src="docs/assets/screenshots/journal-light.webp" width="240" alt="The Journal: a search field, Errors, Pending and Flagged filters, and entries newest first with their postings. One entry is marked pending, one says Has a document."></picture><br>
<sub><b>Journal</b>: search, and filters for errors, pending and flagged entries.</sub>
</td>
<td align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/reports-dark.webp"><img src="docs/assets/screenshots/reports-light.webp" width="240" alt="Reports: Fava's income statement inside the app, with time, account and filter fields, chart options and a monthly stacked bar chart."></picture><br>
<sub><b>Reports</b>: Fava itself, dressed in the app's colours.</sub>
</td>
<td align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/documents-dark.webp"><img src="docs/assets/screenshots/documents-light.webp" width="240" alt="Documents, Organize tab: two PDFs. One attached to the 2026-09-12 Corner grocer entry, one filed under Expenses:Home:Utilities. Each has View, Share, Refile, Unlink and Delete file."></picture><br>
<sub><b>Documents</b>: receipts filed under an entry or an account.</sub>
</td>
</tr>
<tr>
<td align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/more2-dark.webp"><img src="docs/assets/screenshots/more2-light.webp" width="240" alt="More: grouped rows for Backup, Mirror, Remote (use a Fava server you host yourself), Share on Wi-Fi, Plugins and Scripts."></picture><br>
<sub><b>More</b>: backup, mirror, remote, sharing, plugins, scripts.</sub>
</td>
<td align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/plugins-dark.webp"><img src="docs/assets/screenshots/plugins-light.webp" width="240" alt="Plugins: a notice that reads A plugin is a program you are trusting. It runs with this app's permissions: it can read and change your whole ledger, and use the network. Install plugins only from a source you trust. Below it, a plugin list with search and All, Beancount and Fava filters."></picture><br>
<sub><b>Plugins</b>, with the warning said plainly (Expert edition shown).</sub>
</td>
<td align="center">
<img src="docs/assets/screenshots/widget-home.webp" width="240" alt="An Android home screen with two Beanbook widgets: Net worth 6563.40 EUR, and Balance listing Checking 6568.65 EUR, Cash 13.25 EUR and Groceries 237.80 EUR, each with a plus button to add an entry."><br>
<sub><b>Widgets</b> for net worth and chosen balances, each with a + to add an entry.</sub>
</td>
</tr>
</table>

</details>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/rule-dark.svg">
  <img src="docs/assets/rule-light.svg" width="100%" alt="">
</picture>

## What it does

<table>
<tr>
<td width="50%" valign="top">

#### Entries and editing
Guided entry for common directives, plus editing, deleting, and duplicating existing entries.
The form shows the exact text it will write before you save. Every included file is directly
editable as text. Nothing is reported as saved until it has been written and read back
successfully, and a write that fails leaves the previous version of the file in place. The last
change can be undone.

</td>
<td width="50%" valign="top">

#### Reports
The full Fava surface: balance sheet, income statement, trial balance, holdings,
commodities, account and payee and tag views, charts, and BQL queries. Home, Balances and the
widgets show figures in Units, At cost or Market value, the way Fava's own switch does.

</td>
</tr>
<tr>
<td valign="top">

#### Documents
Capture receipts with the camera, crop them, assemble PDFs, and file them against entries.
Documents live inside the portable project tree.

</td>
<td valign="top">

#### Moving data
Import a folder tree or an archive, export a copy to a folder you choose, write backup archives,
and optionally mirror to a folder or schedule backups.

</td>
</tr>
<tr>
<td valign="top">

#### Remote mode
A deliberately narrow client for a Fava server you host. It gives you reports and quick-add
while the server is reachable. It does not keep an offline copy, edit existing remote entries,
or attempt two-way sync.

</td>
<td valign="top">

#### Share on Wi-Fi
Open your Reports from another device on the same private network, in a browser. Off until you
turn it on, behind a username and password you set, with a notification for as long as it runs.

</td>
</tr>
<tr>
<td valign="top">

#### Plugins and scripts
Beancount plugins and Fava extensions load with your ledger, and you can run your own Python
scripts against it. A safe mode loads the ledger with no plugins.
[docs/plugin-compatibility.md](docs/plugin-compatibility.md) says which were tested and how.

</td>
<td valign="top">

#### Widgets and appearance
Home-screen widgets for net worth and balances. Light, Dark and AMOLED themes, optional dynamic
colour.

</td>
</tr>
</table>

## How a save is written

Every change, from the entry form, the file editor or a delete, takes the same path. Success is
reported only at the end, after Beancount has read the ledger back.

The one exception is the editor inside Fava's own pages in Reports: Fava saves those itself, with
its own check that the file has not changed underneath it, and the app re-reads the ledger
afterwards. Those saves get no snapshot or Undo.

```mermaid
flowchart TD
    subgraph before["Before anything changes"]
        direction LR
        A["Lock the project"] --> B["Keep a copy<br/>to go back to"] --> C["Note the change<br/>about to be made"]
    end
    subgraph write["The write"]
        direction LR
        D["Write a temporary file<br/>and flush it to disk"] --> E["Swap it into place"]
    end
    before --> write
    write --> F{"Beancount reads<br/>the ledger back"}
    F -- "reads cleanly" --> G(["Saved."])
    F -- "fails" --> H["Put the copy back"]
    H --> I(["Not saved: the previous<br/>ledger stands"])
```

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/rule-dark.svg">
  <img src="docs/assets/rule-light.svg" width="100%" alt="">
</picture>

## Two editions

Play and Expert read the same projects, are both entirely free, and share every feature except
three points that Google Play policy decides. They also update differently:

| | Play | Expert |
|---|:---:|:---:|
| Local Beancount and Fava | Included | Included |
| User plugins and extensions | Pure Python only | Pure Python and compatible native packages |
| Android bridge in user Python | Withheld | Available |
| Optional package downloads | None | User-confirmed |
| App updates | Google Play | Signed package, confirmed by you |

<table>
<tr>
<td width="50%" valign="top">

**Choose Play** if you want store-managed updates and your own plugins do not need native
packages or Android APIs.

</td>
<td width="50%" valign="top">

**Choose Expert** if your plugins need compatible native packages or the Android bridge, you
want packages fetched on demand, and you are comfortable installing a signed build directly.

</td>
</tr>
</table>

The Play restrictions are compliance with Google Play's rules about shipping executable code,
not a security boundary: plugins you supply run as ordinary Python in both editions, with your
ledger in reach. The Play edition tells you the Expert edition exists and what it adds.
[docs/editions.md](docs/editions.md) explains which to pick.

## Privacy and network use

No account, no sign-in, no telemetry, no analytics, no crash reporting, and no ads, in either
edition. Local mode makes no network requests at all.

```mermaid
flowchart LR
    subgraph phone["Your phone"]
        app["Beanbook"] -- "127.0.0.1 only,<br/>a new token each run" --> fava["Fava, inside the app"]
        files[("Your ledger files")] --- app
    end
    app -. "only if you enter its address" .-> server[("Your own Fava server")]
    app -. "Expert only, when you ask" .-> pkg[("Python package index<br/>or GitHub")]
    other["Another device<br/>on your Wi-Fi"] -. "while Share on Wi-Fi is on,<br/>after sign-in" .-> app
```

The app reaches out to the network only in cases you start:

- **Your own Fava server**, if you enter its address in remote mode.
- **A plugin or an optional Python package**, in the Expert edition only, when you ask for one.
  The Play edition never downloads code from anywhere.

**Share on Wi-Fi** is the one way in. It is off until you turn it on, listens only on the phone's
private network address, shows nothing until someone signs in with the username and password you
set, locks out repeated wrong passwords, and keeps a notification up for as long as it runs. The
connection is plain `http://`, so use it only on a network you trust.

If you type an `https://` address, certificate errors are fatal and cannot be overridden in
any build. If you type an `http://` address, that traffic is unencrypted and can be read or
modified on the network path, including any password you set. The app says so on screen before
connecting. Cleartext is never inferred and never used as a fallback after a failed HTTPS
connection; it happens only when you type `http://` yourself.

[docs/privacy.md](docs/privacy.md) has the full position, and
[docs/threat-model.md](docs/threat-model.md) has the reasoning behind it.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/rule-dark.svg">
  <img src="docs/assets/rule-light.svg" width="100%" alt="">
</picture>

## Requirements

- Android 10 (API 29) or later.
- `arm64-v8a` or `x86_64`.

## Getting started

Create a ledger or import an existing project, then work on it. The short version:

1. Open Projects and create a new ledger, or import a folder tree or archive.
2. Import the whole tree rather than a single `.beancount` file, so includes and document
   links stay valid.
3. Fix any parse errors in the raw editor, which reports file, line, and column.
4. Set up an export folder, a backup, or mirroring before you rely on the app.

[docs/getting-started.md](docs/getting-started.md) covers each step, where your files actually
live, and what to do when something goes wrong.

<details>
<summary><b>Where your files live</b></summary>
<br>

Projects live in app-private storage, which is canonical. That is not an aesthetic choice:
Python and Fava need real POSIX paths, and Android's folder picker hands out content URIs.
Modern file managers cannot browse app-private storage, so the app provides explicit routes
out — an in-app editor for every included file, export to a folder you pick, backup archives,
and optional mirroring. Caches, logs and credentials are kept outside the
portable project tree, so an exported copy contains your ledger and nothing else.

</details>

## Good to know

Beanbook is built for plain-text ledgers kept on your own device, and it is honest about where its
edges are today:

- **Version control is yours.** Keep the exported or mirrored folder in Git, or any sync tool you
  like; the app works on the files and does not run Git itself.
- **Remote mode is live.** It shows your own Fava server as it is right now, so it needs a
  connection to that server.
- **Receipts are kept as PDFs.** Reading amounts off them automatically is not part of the app yet.
- **Plugins.** The Expert edition offers a catalogue of tested plugins; the Play edition loads plugin
  files you provide.
- **English.** The interface is in English.
- **Large ledgers.** It is designed and measured around ledgers of about 25,000 transactions; bigger
  ones work, with a longer first load after a change.

[docs/known-limitations.md](docs/known-limitations.md) has the full list.

## Roadmap

What is planned, as a checklist. Each line is an issue; [ROADMAP.md](ROADMAP.md) has the reasoning
and the status of every item.

- [x] **1.0**: local Beancount and Fava on the phone, entry forms, documents and scanning, backup,
      mirror, remote Fava, plugins and extensions, Share on Wi-Fi
- [ ] **1.1 Trust and polish** (now)
  - [ ] Review every file in the repository: 6 of 15 areas done ([#16](https://github.com/pintofi/beanbook/issues/16))
  - [ ] Fava's own editor saves outside the app's write protocol ([#17](https://github.com/pintofi/beanbook/issues/17))
  - [ ] TalkBack pass on a device ([#18](https://github.com/pintofi/beanbook/issues/18))
  - [ ] Instrumentation tests in CI, weekly ([#19](https://github.com/pintofi/beanbook/issues/19))
  - [ ] Screenshot tests, light and dark, 200% text ([#20](https://github.com/pintofi/beanbook/issues/20))
  - [ ] User-facing strings moved into resources ([#21](https://github.com/pintofi/beanbook/issues/21))
  - [ ] Reproducible wheels and APK checked in CI ([#22](https://github.com/pintofi/beanbook/issues/22))
- [ ] **1.2 Capture**: add an entry from shared text, photos and PDFs ([#23](https://github.com/pintofi/beanbook/issues/23)); a Quick Settings tile and shortcut ([#24](https://github.com/pintofi/beanbook/issues/24)); templates and recurring reminders ([#25](https://github.com/pintofi/beanbook/issues/25))
- [ ] **1.3 Reconcile and import**: a reconcile screen ([#26](https://github.com/pintofi/beanbook/issues/26)); bank statements on the phone ([#27](https://github.com/pintofi/beanbook/issues/27)); Syncthing conflict files ([#28](https://github.com/pintofi/beanbook/issues/28))
- [ ] **1.4 Reach**: a send queue for a remote Fava ([#29](https://github.com/pintofi/beanbook/issues/29)); price updates on request ([#30](https://github.com/pintofi/beanbook/issues/30)); translations ([#31](https://github.com/pintofi/beanbook/issues/31))

Not planned: two-way sync and embedded Git, cloud OCR or text AI, accounts, analytics and ads.

## Support Beanbook

Every feature is free in both editions: no trial, no paid tiers, no keys, no ads. If Beanbook is
useful to you, you can help it along:

<p align="center">
  <a href="https://ko-fi.com/pintofi"><img src="https://storage.ko-fi.com/cdn/kofi3.png?v=3" height="64" alt="Support Beanbook on Ko-fi"></a>
</p>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/rule-dark.svg">
  <img src="docs/assets/rule-light.svg" width="100%" alt="">
</picture>

## Build and test

The build needs a little more than a checkout because of the embedded Python runtime: a JDK and
the Android SDK, a host CPython 3.13 for Chaquopy's pip, and the cross-built `beancount` and
`watchfiles` wheels. [CONTRIBUTING.md](CONTRIBUTING.md) walks through it.

```bash
. spikes/env.sh                                       # JDK, SDK and Gradle paths on the build machine
tools/build-wheels.sh --out ~/tools/wheels            # pinned sdists, pinned build backends

./gradlew :core-domain:test :core-data:test :core-engine:test :app:testPlayDebugUnitTest
./gradlew :app:assemblePlayDebug :app:assembleExpertDebug
./gradlew :app:verifyPlayFlavorBoundaries             # the Play edition's build gate
./gradlew detekt                                      # gates CI; run it before pushing
```

<details>
<summary><b>More checks</b>: release builds, lint, Python tests, the golden corpus, instrumentation</summary>
<br>

```bash
./gradlew :app:assemblePlayRelease :app:assembleExpertRelease
./gradlew :app:lintPlayDebug :app:lintExpertDebug

# Python layer: the interpreter must have beancount importable
~/tools/bc-desktop/bin/python3 -m unittest discover -s engine-local/src/test/python

# Golden corpus: generate fixtures, then record or verify against upstream Beancount
~/tools/bc-desktop/bin/python3 tools/corpus/generate.py --out corpus
~/tools/bc-desktop/bin/python3 tools/corpus/differential.py verify

# Instrumentation, on a device or emulator
./gradlew :app:connectedExpertDebugAndroidTest
```

A change is done when it builds in both editions, debug and release; unit and instrumentation
tests pass on API 29 and 36; the golden corpus round-trips byte-clean; and new screens pass the
accessibility checks. [CONTRIBUTING.md](CONTRIBUTING.md) lists the commands and what each one needs.

</details>

## Documentation

| Document | Contents |
|---|---|
| [docs/getting-started.md](docs/getting-started.md) | First project, importing an existing ledger, moving data, troubleshooting |
| [docs/editions.md](docs/editions.md) | Play against Expert, and supporting the project |
| [docs/privacy.md](docs/privacy.md) | What stays on the device and when the network is used |
| [docs/known-limitations.md](docs/known-limitations.md) | What the app cannot do, and why |
| [docs/on-demand-packages.md](docs/on-demand-packages.md) | Fetching extra Python packages, Expert only |
| [docs/plugin-compatibility.md](docs/plugin-compatibility.md) | Which plugins and extensions work, and how that was tested |

## Contributing

Bug reports and patches are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the build,
which needs a little more than a checkout because of the embedded Python runtime, and the
boundaries a change has to keep: the data invariants, the edition split, and the licence.
Security reports go through [SECURITY.md](SECURITY.md). What is planned is in [ROADMAP.md](ROADMAP.md).

## Licence

GPL-2.0-only.

The full text is in [COPYING](COPYING), and the copyright holders are listed in
[AUTHORS](AUTHORS).

Neither edition links any proprietary library for payments: nothing is sold. There is
no additional licence permission: Beanbook is GPL-2.0-only, with nothing added.

Beancount and Fava are independent projects. This app is built on their work and is not
affiliated with or endorsed by them.
