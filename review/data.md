# Review log: core-data

Area `data` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `core-data/build.gradle.kts` | 3 5 | none | `:core-data:test` runs; the one runtime dependency (coroutines) is used by `ProjectWriter` |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/ArchiveEncryption.kt` | 1 2 3 | D-7 | ArchiveEncryptionTest; ReviewHardeningTest (bounds on what a manifest may ask the key derivation to spend). AES-256-GCM with a fresh salt and nonce per archive, key zeroed after use |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/BackupArchive.kt` | 1 2 3 4 | D-7, D-11, I-1, I-2 | BackupArchiveTest, ReviewHardeningTest (bounded streamed verification, a file called `key` is backed up) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/CatalogueInstaller.kt` | 2 3 5 | D-15 | CatalogueInstallerTest: archive hash checked before anything is read, members confined to the install folder |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/DiagnosticLog.kt` | 1 2 3 | D-9 | DiagnosticLogTest, ReviewHardeningTest (eight threads at once; bearer, cookie and multi-word passphrase redaction) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/DocumentLocator.kt` | 2 3 | I-2 (domain) | DocumentLocatorTest; every candidate is held to the project tree |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/DocumentRoots.kt` | 2 3 | none | DocumentRootsTest; compares on whole path segments |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/DocumentStore.kt` | 1 2 3 | D-8, D-12 | DocumentStoreTest, ReviewHardeningTest (a ledger cannot list a sibling project by a shared prefix; deleting does not tidy outside `documents/`; stored file whole) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/DraftStore.kt` | 2 3 | D-14 | DraftStoreTest, ReviewHardeningTest (a draft path cannot leave the drafts folder) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/ImportExport.kt` | 1 2 3 | D-10, I-3 | ImportExportTest, ImportOverrunTest, ReviewHardeningTest (a linked folder is a link; too many entries refused up front; a taken `main.beancount` is refused) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/JpegPdf.kt` | 2 5 | none | JpegPdfTest; deterministic bytes, which is what keeps a retried scan from filing a second copy |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/Json.kt` | 2 3 | none | JsonTest, HostileJsonTest; no `Double` is used for money: the numbers are sizes and times, and no amount passes through this type |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/MirrorPlan.kt` | 1 2 | none | MirrorPlanTest enumerates every combination of changed here, changed there and last synced |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/PluginCatalogue.kt` | 2 3 | none | parsed in `CatalogueInstallerTest` and the catalogue tests; an entry without an `https` archive and a SHA-256 has no install |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/PluginSafeMode.kt` | 2 3 5 | D-15 | PluginSafeModeTest, ChangedPluginTest |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/PluginStore.kt` | 1 2 3 | I-6 | PluginStoreTest, ChangedPluginTest; plugins install disabled, and a changed file is held back until installed again |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/ProjectRegistry.kt` | 1 2 5 | D-1, D-14, I-5 | ProjectRegistryTest, ProjectWorkspaceTest (the picker no longer fails on what a restore leaves behind; titles are read as Beancount writes them) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/ProjectWorkspace.kt` | 1 2 3 | D-1, D-13 | ProjectWorkspaceTest (ids; titles with quotes, backslashes and line breaks; a commodity that is not one; interrupted creation) |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/ProjectWriter.kt` | 1 2 3 | D-2, D-5, D-15 | ProjectWriterTest, ValidatorUnavailableTest, WriteProtocolHardeningTest (cancelled mid-check, recovery with the engine down). The new cancellation and recovery cases fail on the old code |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/RestorePlan.kt` | 1 2 | D-6, D-15 | RestorePlanTest (Undo after a restore is refused); BackupRetention keeps the newest regardless of age |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/SharingPassword.kt` | 1 3 | I-4 | SharingPasswordTest pins the format the engine checks; 150,000 iterations kept until measured |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/SupportPrompt.kt` | 2 5 | none | SupportPromptTest; used by the edition notice |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/TemplateStore.kt` | 1 2 5 | D-14 | TemplateStoreTest, ReviewHardeningTest; amounts are stored as the text typed and read as `BigDecimal`, never as a float |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/WidgetCache.kt` | 1 2 5 | D-14 | WidgetCacheTest, MultiCurrencyWidgetTest, ReviewHardeningTest |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/WidgetLink.kt` | 3 | none | WidgetLinkTest: a project id from an intent is honoured only if it exists |
| `core-data/src/main/kotlin/io/github/pintofi/beanbook/core/data/WriteJournal.kt` | 1 2 3 | D-3, D-4 | ProjectWriterTest, WriteProtocolHardeningTest (a clock that goes backwards; a damaged or missing saved copy is not restored over an intact ledger) |

`./gradlew :core-data:test`: 283 tests, 0 failures (256 before this review). `:core-domain:test`, `:core-engine:test`, `:engine-remote:test`, `:app:testPlayDebugUnitTest` and `detekt` pass too.

## Fixed in this pull request

- **D-1 (S1): the project picker failed after a restore.** `listProjects` returned every folder in `projects/` that held a ledger, including `<id>.previous-<time>` (a restore keeps its rollback point for good), `<id>.restoring`, `<id>.importing` and `.creating-<id>`. `ProjectRegistry.summaries` then asked for a summary of a name that is not a project id and threw. Now only valid project ids are listed. The old code fails the new test.
- **D-2 (S1): the write protocol was not safe against cancellation.** Everything after the intent is recorded (the rename, the re-parse, the commit or the rollback) ran in the caller's coroutine. A caller cancelled during the re-parse (the screen closed, the activity recreated while the engine was checking) left the ledger changed, the record undecided and no Undo, until the next start. It now runs to completion whatever the caller does, and so does the part of Undo after the snapshot is put back. Two new tests cancel during the re-parse and fail on the old code.
- **D-3 (S1): the snapshot and the journal were not flushed to storage.** The protocol says the snapshot and the journal intent exist before the ledger is replaced, but only the ledger's own temporary file was synced. After a power loss the saved copy could be empty while the ledger rename had survived, turning a rollback into data loss. `Durable` flushes the snapshot, the journal files, the state file and their folders, and a restore verifies the saved copy against the checksum recorded before the write: a damaged or missing copy now raises instead of replacing an intact ledger or silently doing nothing. This cannot be tested by simulating a power loss here; the evidence is the code, plus tests for the damaged and missing copy.
- **D-4 (S1): a clock that goes backwards reordered Undo.** Transaction ids sort by wall-clock time, and the undo stack, recovery and pruning trust that. After a manual change or a battery reset, new writes sorted before old ones and Undo offered (and restored) the wrong snapshot. An id is now never earlier than the latest that exists. New test.
- **D-5 (S1): recovery threw away a typed raw edit when the engine was down.** The normal path keeps a raw edit and reports it unchecked when the engine is unavailable; recovery after a crash judged the same case differently and restored the snapshot. It now mirrors the normal path (a generated write is still given up). New tests, one failing on the old code.
- **D-6 (S1): Undo after a restore overwrote the restored files.** A restore replaces the project but kept its journal, so Undo restored the last pre-restore snapshot over the restored ledger. The restore now clears the replaced project's history. New test.
- **D-7 (S1): checking or restoring a backup could be made to allocate without limit.** Every entry was read whole into memory, and the key-derivation cost an archive asked for (memory, passes) was taken from its unauthenticated manifest as it stood. Entries are now streamed and bounded by what the manifest says, and the manifest's cipher, derivation, salt, nonce and cost are held to sane ceilings.
- **D-8 (S1): a ledger could list another project's documents.** `DocumentStore.list` compared canonical paths by plain prefix, so for a project `proj`, a folder inside `proj2` counted as inside it. It compares whole path segments, as everything else does.
- **D-9 (S1): the log could crash its caller and still leaked secrets.** The timestamp formatter was a shared `SimpleDateFormat`, which is not thread-safe and ran outside the guard that swallows errors. `Authorization: Bearer <token>` was redacted to `<redacted> <token>`, a cookie header only up to its first space, and a passphrase only its first word. Fixed, with a test from eight threads and a test per leak.
- **D-10 (S2):** `DirectoryImportSource` followed a link to a folder and listed what it pointed at as ordinary files; it now reports the link, which is refused. An archive with far too many entries is refused without iterating them. An import whose root is not `main.beancount` while another file is, used to fail with a bare exception and now says why.
- **D-11 (S2):** backups and the mirror silently left out any file called `key`, `entitlement` or `mirror-state` anywhere in a project, for a key that no longer exists. Only `mirror-state`, which is real runtime state, remains.
- **D-12 (S2):** a stored document is flushed before it is named, and deleting one only tidies empty folders under `documents/`, not a folder the person made.
- **D-13 (S2):** a project title or commodity was written into the starter ledger unescaped (a quote or line break broke the file); they are now quoted, kept to one line and validated, and a project is built beside its final place and moved in, so an interrupted creation neither shows up nor blocks the id.
- **D-14 (S2/S3):** a draft's path is held inside the drafts folder; a carriage return in a template field no longer splits its record; widget files for `a_b` and `ab` no longer collide; a title with a trailing comment or an escaped quote is read as Beancount reads it.
- **D-15 (S3):** comments pointing at documents that are not in this repository removed; stacked and dangling doc comments fixed; a dead variable in `BackupRetention` removed.

## Filed as issues

- **I-1 (S1)** a backup can be written from files that changed while it was made, and then fails verification at restore: [#53](https://github.com/pintofi/beanbook/issues/53).
- **I-2 (S2)** encrypted backups hold the whole project in memory several times: [#54](https://github.com/pintofi/beanbook/issues/54).
- **I-3 (S2)** importing a project whose root is not `main.beancount` leaves two copies of it: [#55](https://github.com/pintofi/beanbook/issues/55).
- **I-4 (S3)** hash the sharing password off the main thread, then raise the iteration count: [#56](https://github.com/pintofi/beanbook/issues/56).
- **I-5 (S3)** the project trash is never emptied: [#57](https://github.com/pintofi/beanbook/issues/57).
- **I-6 (S2)** a damaged plugin index loses the other plugins' state on the next install: [#58](https://github.com/pintofi/beanbook/issues/58).

## Accepted, with the reason

- **The backup manifest is unauthenticated on purpose.** It is readable by design so an archive can be identified without a passphrase; the checksums inside the encrypted payload are what is verified. Its key-derivation parameters are now bounded, which is the one thing an attacker could abuse.
- **`Json.Num` is a `Double`.** It carries sizes and timestamps in the manifest, widget cache and plugin index. No amount passes through it, and amounts in the widget cache are already formatted strings. [#51](https://github.com/pintofi/beanbook/issues/51) adds a build rule so that stays true.
- **The mirror deletes the external copy of a file that is gone here and unchanged there** without asking. That is the design (the mirror follows the app), a conflict is raised whenever the other side changed, and a restore keeps the previous project aside. To be looked at again with the `app` areas, where the confirmation is.
- **`PluginStore.install` removes the old copy before moving the new one in.** A rename inside one folder failing is not a realistic failure; not changed.
