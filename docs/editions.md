# Editions and contribution keys

Two builds exist, Play and Expert. They read the same projects, use the same tiers, and share
every feature except where Google Play's policies decide otherwise. A project created in one
opens unchanged in the other, and in desktop Beancount.

How you contribute differs, because Google Play requires its own billing system for
unlocking app functionality: the Play edition sells the tiers through Google Play, and the
Expert edition takes a signed key you paste in. Neither links out to a payment page, and
neither contains a third-party payment SDK.

## Play against Expert

| | Play | Expert |
|---|---|---|
| Local Beancount and Fava | Included | Included |
| User plugins and extensions | Pure Python only | Pure Python and compatible native packages |
| Android bridge in user Python | Withheld | Available |
| Optional package downloads | None | User-confirmed |
| App updates | Google Play | Signed package, confirmed by you |

Everything not in that table is shared: reports, editing, documents, backups, remote mode, and
the contribution tiers.

**Choose Play** if you want store-managed updates and your own plugins do not need native
packages or Android APIs.

**Choose Expert** if your plugins need compatible native packages or the Android bridge, you
want packages fetched on demand, and you are comfortable installing a signed build directly.

### What the Play restrictions are, and are not

They are compliance with Google Play's rules about shipping executable code, not a security
boundary. Plugins you supply run as ordinary Python in both editions, with your ledger in
reach. The Play build declines to load native code and withholds the Android bridge because
the store's policy requires it, and the app does not pretend that this sandboxes anything.

[plugin-compatibility.md](plugin-compatibility.md) records which plugins and extensions were
tested and how. [on-demand-packages.md](on-demand-packages.md) covers the Expert package
fetcher, including what it costs in storage.

## The trial

Every fresh install begins a 30-day trial with all three tiers unlocked. It needs no account,
no card, and no network call. The app records the install date locally and counts down.

When the trial ends, only the paid features stop. Your ledger is untouched and every file stays
where it was. Adding, editing, deleting, and duplicating entries keep working, as do the
journal, the account balances list, raw file editing, and all manual import, export, backup,
and restore.

Winding the device clock backwards does not extend the trial: the app remembers the furthest
date it has seen. Winding it forwards ends the trial early, which is your own doing. A
reinstall starts a new trial, and there is no device fingerprinting to prevent that. Adding
some would contradict the privacy position, so it was left out deliberately.

## Tiers

Each tier includes the ones before it.

| Tier | Price (USD) | Adds |
|---|---|---|
| Free | — | Every directive, add, edit, delete, duplicate; the journal; account balances; raw editing of every file; multiple projects; all manual import, export, backup, and restore; safe mode; diagnostics |
| T1 Insight | 9.99 | The full Fava report surface: balance sheet, income statement, trial balance, holdings, commodities, account, payee, tag and link views, charts, and BQL queries |
| T2 Workflow | 14.99 | External documents, camera capture and PDF assembly, home-screen widgets, auto-mirror to a folder, scheduled backups |
| T3 Power | 19.99 | Remote mode, statement importers, scripts, and Beancount plugins and Fava extensions |

The organising idea is that free is fully functional but manual, while paid is automated and
analytical. A free user can do every accounting operation and move data in and out as often as
they like. It simply takes more taps.

Data out is never behind a paywall, and neither is data in or recovery. Two reasons. The free
tier includes the raw editor, so anyone determined can copy their ledger out by hand, which
makes a gate on export a weak lock that mostly annoys honest users. And putting a paywall at
the moment someone is deciding whether the app is worth anything is a bad trade for everyone.

## How a key works

A key is a short block of signed text. You paste it into the app once, under **More →
Contribute → Enter key**.

- It is verified on your device with an Ed25519 signature check. There is no licence server, no
  activation, and no check-in.
- It carries the tier, the email address it was issued to, and an order reference. The app
  shows the tier so you can tell which key is installed; the address is only there so a lost
  key can be reissued.
- If your trial is still running, the app uses whichever gives you more, so buying during a
  trial never downgrades you.
- Upgrading between tiers means a replacement key for the difference, issued by hand.

Because the source is GPL-2.0-only, anyone can build the app without a key. That is understood
and intended: this is a contribution model rather than a lock, and the app says so where it
asks.

**Nothing is on sale yet.** The Play products are not published and no key has been issued.
This page describes how the tiers work, not where to buy one.
