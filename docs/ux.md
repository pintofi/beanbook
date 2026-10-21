# UX specification

Design inspiration for information architecture and fast entry is taken at a
**conceptual** level from My Expenses (GPL). No assets, layouts, strings, or code are
copied.

---

## 1. Native vs. web ownership

The rule: **native owns entry, navigation, files, and trust decisions; Fava owns
analysis.**

| Surface | Owner | Why |
|---|---|---|
| Onboarding, mode setup | Native | Needs platform pickers and permission flows |
| Project picker, project health | Native | Multi-project state is an app concern |
| Guided entry, quick-add | Native | Fast, offline, accessible, keyboard-aware |
| Raw source editor | Native | Needs stale-write detection, drafts, atomic save |
| Journal (basic) | Native | Must work before the engine is warm |
| Reports, balance sheet, income statement, holdings, commodities | Fava (WebView) | Highest fidelity, zero divergence risk |
| Charts and graphs | Fava (WebView) | D3 already implemented upstream |
| BQL query page | Fava (WebView) | Upstream editor and result rendering |
| Fava extension pages | Fava (WebView, expert) | By definition upstream-owned |
| Documents, camera, PDF | Native | Platform APIs |
| Backup, restore, import/export, sync conflicts | Native | Destructive actions need native confirmation UX |
| Plugin management, execution logs | Native | Trust decisions must never be rendered by web content |
| Settings, diagnostics, safe mode | Native | Trust and platform integration |

Every trust decision (permissions, plugin enablement, purchases, destructive
confirmations) is rendered natively. Web content never asks for trust.

---

## 2. Information architecture

Bottom navigation (phone) / navigation rail (tablet):

1. **Journal**: the default surface. Chronological entries, search, filter, quick-add FAB.
2. **Balances**: account tree with balances (free), linking to Fava reports.
3. **Reports**: the Fava WebView host, with balance sheet, income statement, trial balance,
   holdings, commodities, queries, charts, extension pages.
4. **Files**: the project's file tree with the raw editor.
5. **More**: documents, backup, import/export, plugins, settings, diagnostics.

Project switching lives in the top app bar, showing project name and health (parse status,
last write, mirror state). A ledger with parse errors is a normal state and is shown as an
informative banner with a "Show errors" action, and never a blocking error screen.

---

## 3. Visual system

One system across native and web: Material 3 with dynamic color where available and a
consistent brand fallback; matching type scale, spacing, elevation, and semantic colors.
A generated CSS theme is injected into the Fava WebView so its surfaces adopt the same
palette, radii, and dark-mode state. The app never fights Fava's layout; it re-skins,
does not restructure.

Dark theme is first-class and follows the system setting with a manual override.

---

## 4. Entry workflows

**Quick-add** (one screen, no scrolling on a phone): date, payee with completion,
narration, amount, from/to account. Sensible defaults from recent usage; opening the
account picker is one tap.

**Full entry**: multi-posting, costs `{}`, prices `@`/`@@`, totals, metadata, tags,
links, flags. Postings show a running imbalance indicator; the app never silently
balances a transaction; it shows the residual and offers to add a posting.

**All directive types** have editors: `open`, `close`, `commodity`, `price`, `balance`,
`pad`, `event`, `note`, `document`, `query`, `custom`. Unknown/custom directives are
editable as raw text and are never rewritten.

**Completion** is derived from the ledger itself (accounts, payees, narrations,
commodities, tags, links), not from a parallel database that could drift.

**Templates and recent values** prefill fields but never alter accounting semantics
(no auto-rounding, no currency substitution, no implicit account creation without the
`open` directive being written).

**Before writing**, guided entry shows the exact Beancount text that will be inserted,
with the target file and line. For destructive or broad changes this preview is
mandatory, not optional.

**Undo** restores a durable snapshot, with a clear description of what will be reverted.

---

## 5. Raw editor

Syntax highlighting, search, account/payee completion, formatting (aligned amounts,
opt-in), inline error markers with line/column from Beancount itself, large-file
handling, and drafts persisted on `onStop`.

Save is refused when the on-disk hash changed since open, with a diff-based conflict UI.
Save is also refused when the result does not parse; the error appears at the exact
location, and the buffer is kept.

---

## 6. Accessibility (release-blocking)

- TalkBack semantics on every interactive element; amounts announced with currency and
  sign in words (e.g. "minus 42 dollars 30 cents"), not just a symbol.
- **No color-only financial indicators**: negative/positive amounts always carry a sign
  and, where meaningful, an icon or label.
- 200% font scaling without truncation or overlap; layouts reflow rather than clip.
- Minimum 48dp touch targets; visible focus indicators; full keyboard/D-pad navigation
  (hardware keyboards on tablets and Chromebooks are a real use case for entry).
- Contrast meets WCAG AA in both themes, including inside the Fava WebView theme.
- Content descriptions on charts, plus a data-table fallback for every chart.
- Localization-ready from day one: no concatenated strings, plurals via resources, RTL
  layouts, locale-aware dates and number formatting; **Beancount file content is
  always written in Beancount's own canonical format regardless of locale.**

---

## 7. Responsive layout

| Width | Layout |
|---|---|
| Compact (< 600dp) | Bottom nav, single pane, FAB quick-add |
| Medium (600–840dp) | Navigation rail, list-detail where useful |
| Expanded (> 840dp) | Rail + two-pane (list + detail / editor + preview); Fava WebView gets the wider pane |

Rotation and window resizing never lose editor state (drafts persist).

---

## 8. Widgets

Three widgets: **account balance**, **net worth / selected metric**, **quick add**.

An income/expense-for-a-period widget was built and removed: two figures in one small
box read as neither, and the same numbers belong on the home surface where there is room to
label them. `WidgetSnapshot` still carries them for that reason.

The balance widget names the account it is showing. Which account is a deterministic default
assets first, then alphabetically, because choosing it per widget needs a configuration
activity that does not exist yet.

Behaviour rules:

- Read exclusively from the compact native cache written after a successful parse.
  **A widget never starts the Fava server.**
- Show a last-updated timestamp, and a distinct stale or error state, and never a plausible
  but outdated number presented as current.
- Amounts can be redacted (configurable, and automatically on the lock screen where the
  platform indicates it).
- Deep links carry the project id and target screen and are validated before use.
- Refresh respects Android background limits and update throttling; updates are
  opportunistic, triggered after app-side writes rather than on a timer.

---

## 9. Onboarding

No account, no network. The first run is a welcome page, a page of ways in, and three steps.

**Welcome.** What the app is, in four plain facts and one line about privacy: everything runs on
your device, nothing is uploaded or tracked, files stay plain text and exportable at any time.
A setup left half done offers to pick up where it was.

**Ways in.** Name the ledger and either **Set it up** (the three steps) or **Create with
defaults** (the device's currency, a short list of everyday accounts, no extra checks).
**Choose folder** and **Choose .zip** import a ledger that already exists. The Trash is listed
here when there are no projects, so deleting the only project is never a one-way door.

**Step 1, basics.** Name, main currency (suggested from the device's region), other currencies,
the day the books begin (the first of the month by default). *Fine-tune* holds decimal places per
currency, the booking method, the financial year end, the page Reports open on, which other
currencies reports also total in, and five optional checks, all off. Each check is a Beancount
plugin; each switch has its explanation under it, in the same style as every other field.

**Step 2, accounts.** A checklist of common accounts. One switch makes every account use the main
currency (so Beancount refuses a stray currency); with it off, each account takes its own list.
`Equity:Opening-Balances` is always kept. At least one Assets account is required. A custom
account is typed under a root, tidied to a valid name as you type, and refused with the reason
when it is not valid.

**Step 3, get set.** Optional opening balances (written as a `pad` and a `balance` on the day
before the books begin, so the figure is checked and can be corrected by editing one line),
appearance, the notification permission, and a folder for a mirror copy.

**Finish.** The ledger is written into a staging folder, read by Beancount, and only then put in
place; a failure leaves nothing behind and offers to try again or create with defaults. Success
offers *Add your first entry* or *Go to Home*.

The answers are saved to a small file in app storage after every change, so rotation and a killed
process lose nothing. The file is deleted when the ledger exists, since it can hold opening
balances.

Connecting to a Fava server you host yourself is under More, Remote, not part of first run.

### Accounts

More, then Accounts, lists open accounts by tree with their balance and any currency restriction,
lists closed accounts under their own heading, and opens a new account. An empty account can be
closed, after a confirmation. Both write one line to `files/accounts.beancount` (or the main
file when the ledger does not read that one).

In the entry form, an account the ledger has not opened shows an *Open <account>* button beside
the problem; it opens the account on the entry's date without leaving the form or losing what was
typed.

---

## 10. Support, not entitlement

Nothing is locked, so there is no Contribute screen, no lock, no trial card and no countdown.
The Expert edition shows one notice on Home after a week of use -- "Beanbook is free", with
*Support on Ko-fi* and *No thanks* -- and never again once either is pressed. The Play edition
shows no donation notice or link (Play Payments policy); it describes the Expert edition at the
top of More. Never a modal interruption mid-task, and never a dark pattern.
