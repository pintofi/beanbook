# Editions

Two builds exist, Play and Expert. They read the same projects, are both free, and share every
feature except where Google Play's policies decide otherwise. A project created in one
opens unchanged in the other, and in desktop Beancount.

## Play against Expert

| | Play | Expert |
|---|---|---|
| Local Beancount and Fava | Included | Included |
| User plugins and extensions | Pure Python only | Pure Python and compatible native packages |
| Android bridge in user Python | Withheld | Available |
| Optional package downloads | None | User-confirmed |
| App updates | Google Play | Signed package, confirmed by you |

Everything not in that table is shared: reports, editing, documents, backups, remote mode,
plugins and scripts.

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

## Supporting the project

Both editions are free, with every feature and nothing to unlock: no trial, no tiers, no keys.
If Beanbook is useful to you and you would like to help it along, the project accepts
donations on Ko-fi. The Expert edition links to it from About; the Play
edition does not link to it, because Google Play's rules on payments do not allow it
([distribution.md 4.2](distribution.md)). The page is
[ko-fi.com/pintofi](https://ko-fi.com/pintofi).
