# Storage, synchronization, and backup

---

## 1. Why the ledger lives in app-private storage

Python and Fava require real POSIX paths (`open()`, `os.walk`, relative `include`
resolution). Android user-visible storage is exposed through SAF as `content://` URIs
with no filesystem path. There is no way to hand Fava a `content://` URI.

Therefore: **the app-private workspace is canonical**, and SAF locations are
import/export/mirror endpoints. This is a deliberate, documented trade-off, not a
limitation we hide.

```
<app-private files>/
├── projects/<project-id>/            ← canonical portable project tree
│     └── documents/<Account>/…       ← receipts, in Beancount's own documents layout
├── snapshots/<project-id>/           ← pre-write snapshots for rollback
├── journal/<project-id>.wal          ← write-ahead journal
├── drafts/<project-id>/              ← unsaved editor buffers
├── templates/<project-id>.templates  ← named entry templates (prefill, not directives)
├── cache/<project-id>/               ← parse cache, widget cache (never backed up)
└── runtime/                          ← Python runtime state (never in a project tree)
```

Runtime caches, indexes, logs, credentials, and generated app state are **outside** the
portable project tree, so an exported project is clean.

### 1.1 What "raw file access" does and does not mean

Two different things get called raw access, and only one of them is possible:

| | Supported? | |
|---|:--:|---|
| **In-app raw editing** of every included file — highlighting, search, completion, validation, stale-write detection | ✅ **Yes, free, permanently** | Core to the plain-text promise |
| **Browsing the canonical files with a file manager or another app** | ❌ **No** | Not a product choice — an Android constraint |

The canonical store must be app-private because Fava and Beancount need real POSIX paths,
and app-private storage is not visible to file managers on modern Android
(`Android/data/<pkg>/` was closed to file managers in Android 11+). There is no
configuration in which the user browses the canonical files *and* Fava runs in-process
against them. Import, export, and mirror are the bridge; on a rooted device or over `adb`
the directory is reachable, but that is not a supported path.

A consequence worth stating plainly: because the raw editor is free, a user can already
copy ledger text out of the app by hand. Gating export would therefore be a weak lock that
mainly inconveniences honest users — which is why manual import, export, backup and
restore are all free. See [distribution.md](distribution.md) §4.4.

---

## 2. Portable project layout

```
<project>/
├── main.beancount
├── files/          accounts, commodities, transactions, budgets, user files
├── scripts/        user scripts (expert)
├── prices/         generated price files
├── plugins/        external plugins (expert)
└── documents/      receipts, invoices, statements
```

New projects are generated in this shape. **Imported projects keep their own shape** —
these filenames are a convention for new users, never a requirement. The app discovers
structure from the root file's `include` graph.

Preserved on every write: user file layout, comments, whitespace outside the edited
region, include structure, and relative paths.

---

## 3. Atomic write protocol

Every mutation, whether from guided entry or the raw editor:

1. **Lock** the project (single-writer, in-process mutex + on-disk lock file).
2. **Snapshot** the affected files into `snapshots/<project>/<txn-id>/`.
3. **Journal** an intent record (`txn-id`, files, hashes before, operation).
4. Write each file to `<name>.tmp`, `fsync`, `rename()` over the original (atomic on the
   same filesystem), `fsync` the directory.
5. **Re-parse** the whole project with Beancount.
6. On success: commit the journal record, drop the lock, *then* show success in the UI.
7. On parse failure or any exception: restore from the snapshot, mark the journal record
   rolled back, and surface the exact Beancount error with file, line, and column.

**Recovery on start:** an uncommitted journal record means the process died mid-write.
If the rename completed and the re-parse succeeds, roll forward and commit; otherwise
roll back to the snapshot. Recovery is reported to the user, never silent.

**Stale-write detection:** the raw editor records the file's content hash at open. If the
hash changed before save (external mirror, another edit, a script), the save is refused
and a conflict UI appears with a diff. No last-write-wins.

**Snapshot retention** powers undo: the last N mutations per project (default 50,
configurable), pruned by count and age, stored outside the project tree.

---

## 4. SAF import

Import sources: a folder tree (`ACTION_OPEN_DOCUMENT_TREE`) or an archive.

Validation applied to **every** entry before anything is written:

| Threat | Check |
|---|---|
| Path traversal | Reject any normalized path escaping the project root |
| Absolute paths | Reject |
| Symlinks | Reject (archives) / not followed (trees) |
| Zip bombs | Enforce total-size, entry-count, and compression-ratio caps |
| Case collisions | Detect `Foo.beancount` vs `foo.beancount` and refuse with a clear report |
| Duplicate names | Refuse |
| Unsafe overwrite | Import always targets a fresh project id; never overwrites in place |
| Include escape | `include` targets resolving outside the project root are reported, not silently rewritten |

After copying, the project is parsed. Errors are shown with file/line/column; the import
still completes so the user can fix them in the app (a ledger with errors is a normal
state in Beancount).

Manual import is free and unlimited, for any number of projects. Onboarding
must never start with a paywall, and a per-count import limit would be both unenforceable
in spirit (the raw editor already accepts pasted text) and impossible to define cleanly.

---

## 5. SAF export

Two forms, both free in every edition:

- **Plain folder export** to a user-chosen SAF tree — ordinary files, readable by desktop
  Beancount. Interoperability first.
- **Archive export** — see §7.

Export writes to a temporary staging name and renames into place per file, and reports
partial failure explicitly (SAF providers do fail).

---

## 6. Auto-mirror

Optional. The private workspace stays canonical; after every successful commit, changed
files are mirrored to the user's SAF folder.

Conflict semantics — the rule is **never silently pick a winner**:

| Internal changed | External changed | Behaviour |
|:--:|:--:|---|
| ✅ | ❌ | Mirror out |
| ❌ | ✅ | Offer to pull in, showing a diff |
| ✅ | ✅ | **Conflict**: both versions preserved (`<file>.conflict-<timestamp>`), a conflict card appears, user chooses per file with a diff view. Nothing is overwritten until they choose. |

External change detection uses `DocumentFile` last-modified plus content hash — modified
time alone is unreliable across providers. Mirroring is skipped while a write transaction
is open. Mirror state (per-file hash of the last known-synced version) lives in the cache
directory, never in the project tree.

---

## 7. Backup archives

Explicit backups, not Android Auto Backup. Auto Backup's 25 MB quota and opaque
scheduling are unsuitable for ledgers with documents; the app **disables Auto Backup for
project data** and only lets it carry trivial preferences.

Archive format (`.bcaz`, a zip with a strict manifest):

```
manifest.json      schema_version, app_version, engine_versions (python/beancount/fava),
                   created_at, project_id, file list with sizes + SHA-256, encryption info
project/…          the portable project tree
```

Excluded by default: caches, indexes, logs, runtime binaries, mirror state, credentials.

**Encryption:** optional, off by default. Plain archives are interoperable and
recoverable without this app — the right default for a plain-text-accounting product.
When enabled: AES-256-GCM with a key derived by Argon2id from the user's passphrase;
salt and parameters recorded in the manifest; the manifest itself stays readable so a
user can identify an archive without the passphrase. The UI warns unmistakably that a
forgotten passphrase means permanently lost data — we cannot recover it.

**Restore is staged, never in-place:**

1. Verify manifest, schema version, and every checksum before touching live data.
2. Extract into a staging project.
3. Parse with Beancount; show a summary and a diff against the current project.
4. Only on explicit confirmation, atomically activate the staged project and retain the
   previous one as a rollback point.

**Never overwrite the only good copy** — activation is a rename, and the prior project is
kept until the user prunes it.

**Scheduled backups** use `WorkManager` with constraints; exact timing is not
guaranteed by Android and the UI says so. Retention is configurable by count and age.
Partial failures are reported per file, never summarised as success.

---

## 8. Documents

Stored under `documents/` inside the project so they travel with it. Filenames are
deterministic and collision-resistant:
`<YYYY-MM-DD>.<slug>.<first-8-of-sha256>.<ext>`.

The app distinguishes, in wording and in confirmation dialogs, **unlinking** a document
(removing the directive/metadata) from **deleting** the underlying file. Imported PDFs
and images are treated as untrusted input: rendered in a sandboxed viewer, never passed
to a component with elevated privileges, and never auto-parsed without consent.
