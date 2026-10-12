# Changelog

All notable changes to Beanbook. The format follows [Keep a Changelog](https://keepachangelog.com/);
the project has not been released yet, so everything is under *Unreleased*.

## [Unreleased]

### Added
- Units, At cost and Market value on Home, Balances and the widgets, matching Fava's switch.
- Journal filters for errors, pending and flagged entries.
- Add forms follow the ledger's plugins (`commodity_attr`, `leafonly`, `check_commodity`).
- Undo for a saved entry, a delete and a raw-editor save.
- Per-plugin load status and an extension-failure count.
- The Bound Ledger redesign on Material 3 Expressive, built for 200% text, landscape and tablets.

### Fixed
- Saving an entry with a scanned page no longer crashes the app.
- Deleting an entry also removes the blank line that set it apart, so adding and deleting returns the file to how it was.
- Plugins installed before their Android overlay existed get it applied at start (the SciPy-free `fava_portfolio_returns`).
- Searching the Journal no longer crashes when two entries share a position.
- Native companion libraries are fetched for the device's own architecture.
- The mirror is per project, and Reports follow the open project.
- Filing a scan finishes by itself once the include line is added.
- A refused save names the errors it would add; accounts the ledger has not opened are refused.
- Widgets keep each project's figures.
