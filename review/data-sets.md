# Review log: data-sets (corpus/, fixtures/ and catalog/)

Area `data-sets` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** The ledgers and README files under `corpus/` and `fixtures/` (about 270 KB, all small) were read. The two PDFs and the expected digests were checked by what uses them. `catalog/plugins.json` was parsed and every archive it names was downloaded again and hashed.

| path | checked | finding | evidence |
|---|---|---|---|
| `catalog/plugins.json` | 1 3 5 | K-1, K-2 | all 36 archive hashes re-downloaded and matched; hosts are only files.pythonhosted.org and codeload.github.com over https; no duplicate ids; licence labels normalised; process history removed from the note |
| `corpus/custom-directives/main.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/documents/documents/receipt-2024-01-06.txt` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/documents/documents/statement-2024-01.pdf` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/documents/main.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/expected/custom-directives.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/expected/documents.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/expected/invalid.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/expected/large.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/expected/minimal.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/expected/multi-currency.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/expected/multi-file.json` | 1 | none | `tools/corpus/differential.py verify`: all 6 cases agree with Beancount 3.2.3 and `bean-check` |
| `corpus/invalid/main.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/minimal/main.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/multi-currency/main.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/multi-file/files/2024/quarters/q1.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/multi-file/files/2024/transactions.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/multi-file/files/accounts.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `corpus/multi-file/main.beancount` | 1 3 | none | `tools/corpus/differential.py verify` passes; invented data, no names or real figures |
| `fixtures/sample-ledger/README.md` | 3 5 | none | invented ledger used by unit tests; read: no real names or figures |
| `fixtures/sample-ledger/files/accounts.beancount` | 3 5 | none | invented ledger used by unit tests; read: no real names or figures |
| `fixtures/sample-ledger/files/transactions.beancount` | 3 5 | none | invented ledger used by unit tests; read: no real names or figures |
| `fixtures/sample-ledger/main.beancount` | 3 5 | none | invented ledger used by unit tests; read: no real names or figures |
| `fixtures/site-screenshots/README.md` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/documents/Expenses/Food/Groceries/2026-09-12.corner-grocer.pdf` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/documents/Expenses/Home/Utilities/2026-08-24.electricity-bill.pdf` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/files/accounts.beancount` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/files/commodities.beancount` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/files/documents.beancount` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/files/transactions.beancount` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |
| `fixtures/site-screenshots/household/main.beancount` | 3 5 | none | the README states the ledger and documents are invented; read: no real names, amounts or addresses; the PDFs are generated placeholders |

`tools/corpus/differential.py verify` passes for all six corpus cases. Searched for e-mail addresses, bank and fund names, identity-document and account-number patterns: none in `corpus/` or `fixtures/`. `:core-data:test` and `:app:testPlayDebugUnitTest` pass after the catalogue change.

## Fixed in this pull request

- **K-1 (S3): licence labels in the catalogue used nine spellings** (`GPL2`, `GPLv2`, `GNU General Public License v2 (GPLv2)`, `MIT License`, `GPL-2.0`, ...). The ones that mean the same thing now read `GPL-2.0` or `MIT`. `unverified` and `present, unrecognised` stay as they are: they are honest.
- **K-2 (S3):** the catalogue's `note` carried the history of how an earlier fetcher bug was found, and a reference to a private policy id; both removed. Nothing else in the file changed (every entry was compared before and after).

## Accepted, with the reason

- **Seven catalogue entries are GPL-3.0.** The app does not bundle or distribute them: the user installs them from upstream, they run in the engine process, and the catalogue lists them with their licence. This is a licensing position, recorded in `docs/`; this review did not settle it.
- **The catalogue is regenerated by `tools/plugin-compat/catalogue.py`,** which takes licence strings from PyPI metadata, so the normalised labels will drift back until that script normalises them. Not changed here.
