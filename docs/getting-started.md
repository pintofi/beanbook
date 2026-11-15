# Getting started with Beanbook

This covers setting up a project, moving an existing ledger in, getting copies back out, and
what to do when something goes wrong. It assumes you already know Beancount syntax.

The app is not released yet, so there is nothing to download at the moment. Everything below
describes how it behaves once you have a build.

## Your first project

The first time you open the app, it asks three short things: the currency and the day your books
begin, which accounts you want, and a few preferences (opening balances, appearance, a folder to
keep a copy in). **Create with defaults** skips the questions. After the app writes the ledger it
has Beancount read it, so what you start with is known to be valid.

Later, open **Projects** to create another ledger, select your Beancount file to import one, or restore a backup.

A new project is a main file plus files for commodities, accounts, transactions and prices.
Nothing about that layout is required, and an imported project keeps whatever structure it
already had.

**More, then Accounts** opens and closes accounts. In the entry form, an account the ledger has
not opened has an *Open* button beside the message about it.

## Importing an existing ledger

Choose **Select your Beancount file** and pick your ledger's main file. Its name does not matter
(`main.beancount`, `hmw.bean`, or just `hmw`); it only has to be text. The app follows what that file
links to (its `include` lines, `document` links and `option "documents"` folder, and the like) and
brings exactly those files, with their names and folders as they are. It shows you the list of files
first, with **Import** and **Cancel**; nothing is copied until you tap Import, and nothing else in your
folder is read, listed or changed. Your main file keeps its own name and place.

Android gives the app access to the one file you picked. If that file links to others, it asks you to
allow the folder that holds it, for this import only. If you would rather not, the file can come in on
its own, and the links it could not follow are listed. A ledger directly in the `Download` folder can
only come in on its own, because Android does not let an app be given that folder; move it into a
subfolder first.

Before writing anything, the import refuses path traversal, absolute paths, symlinks, case
collisions, and duplicate names, and a project over the size limit. It always creates a new project
rather than overwriting one you already have.

An import finishes even when Beancount reports errors, so you can fix them in the raw editor
with the file, line, and column in front of you. A ledger that will not parse is reported, not
quietly reinterpreted.

## Where your files live

Projects live in app-private storage, and that copy is the canonical one. The reason is
mechanical rather than philosophical: Python and Fava need real POSIX paths, and Android's
folder picker gives out content URIs that they cannot use.

Modern Android file managers cannot browse app-private storage, so the app gives you explicit
ways to reach your data:

- **The raw editor.** Every included file is directly editable inside the app, with
  highlighting, search, and validation. This is free, permanently.
- **Export a copy** to any folder you choose through Android's folder picker.
- **Backup archives**, with optional passphrase encryption.
- **Auto-mirror** to a folder, which keeps a copy in step as you work.

Caches, logs, credentials, and generated state are stored outside the
portable project tree. An exported copy contains your ledger and its documents, and nothing
belonging to the app.

## Getting data out

**Export a copy** writes the project into a folder you pick. Includes and document links stay
valid, so the exported tree opens in desktop Beancount unchanged.

**Backup archives** are a single file with a manifest and per-file checksums. Encryption is
optional; when you turn it on, the archive uses AES-256-GCM with a key derived from your
passphrase using Argon2id. Nobody can recover the contents if you lose the passphrase.

**Auto-mirror** keeps a chosen folder in step after each successful write. It never
picks a winner silently. If a file changed both in the app and in the folder, both are kept,
you get a conflict card, and you decide per file with a diff in front of you.

**Scheduled backups** run through Android's `WorkManager`. Android does not guarantee
exact timing for background work, and the app says so rather than pretending otherwise. Retention is
configurable by count and age, and a partial failure is reported per file instead of being
rounded up to success.

Import, export, backup, and restore are free in both editions, like everything else.

## Restoring

Restore is staged, never in place. The app verifies the manifest and every checksum first,
extracts into a staging project, parses it, and shows you a summary and a diff against what you
have now. Only when you confirm does it activate the restored copy, and the previous project is
kept as a rollback point until you remove it.

## Remote mode

Remote mode points the app at a Fava server you host. It is a client, not a sync engine: you
get reports and quick-add while the server is reachable, and that is the whole of it. There is
no offline copy, no editing of existing remote entries, and no two-way sync.

Enter the address yourself. If it starts with `https://`, certificate errors end the
connection, in every build, with no override. If it starts with `http://`, the connection is
unencrypted and anything on the network path can read or change it, including your password;
the app warns you on screen before you connect. Cleartext is used only when you type it, and
never as a fallback after HTTPS fails.

## When something goes wrong

**A ledger will not load.** The error names the file, line, and column. Open it in the raw
editor and fix it there.

**A save was refused.** The file changed since the editor opened it. The app refuses
last-write-wins and shows you a conflict view instead of discarding one of the versions.

**A plugin breaks startup.** Turn on **safe mode**, which disables all third-party code and
opens the ledger read-only. If the engine already failed to start, **recovery mode** is still
reachable and is the way back in.

**An Expert package will not install.** Not every dependency has a build that runs on Android.
The resolver tells you what it found, and what it could not, before anything is downloaded. See
[on-demand-packages.md](on-demand-packages.md).

## Large ledgers

The app was designed and measured around ledgers of roughly 25,000 transactions in about
10 MB. Bigger ones work: a ledger of just over 100,000 entries loads correctly.

What it costs is time on the first load after a change, which is tens of seconds at that size.
After that, opening the app is quick, because the figures the native screens need are cached
and checked against the contents of your files. Fava reports and BQL queries still do a full
load each time, so they stay slow on a large ledger.

## Further reading

- [known-limitations.md](known-limitations.md): what the app does not do
- [editions.md](editions.md): Play against Expert, and supporting the project
- [privacy.md](privacy.md): what stays on the device
- [storage-and-backup.md](storage-and-backup.md): the storage model in technical detail
