# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- A zoom control in the scanner: the zoom is said in the middle of a small dial above the shutter. Press it and hold your finger to the left and the zoom keeps going up, faster the further over; hold it to the right and it keeps coming down; bring the finger back, or lift it, and it stops. The number changes in place with a small tick for each step, and pinching ticks too. It is the camera's real zoom. The white corner marks that showed before a page was found are gone. ([#272], [#280])
- The Journal has the Units, At cost and Market value choice that Home and Balances have, the same one for the whole app, and shows its amounts on it; where an amount differs from the units, the units stay beneath it. ([#274])
- Home and Journal have the same Filters button as Balances: a sheet for the period, the search, and tags or links. The period now narrows all three screens. ([#275])

### Changed
- The notification shown while Beanbook installs a package or finishes a save no longer repeats itself with a second line under its title. ([#259])
- Adding a project from the Projects page goes through the same setup as a first run, with Back to my projects. ([#264])
- Units, At cost and Market value are on the widgets themselves: tap one to change that widget's basis, instead of choosing it in the widget's settings. The row is left out on a widget too short to hold it, and the net worth widget no longer has a settings screen. The preview shown when you pick a widget shows the row. ([#277])

### Fixed
- The decimal places box in the new-ledger setup takes numbers only, shows the number keyboard, and can be cleared and retyped; before, it ignored some keys. ([#261])
- The plus/minus button on an amount keeps the cursor on the character it was on: after the 0 of 1230 stays after the 0 of -1230. ([#261])
- Every password and passphrase box now asks the keyboard not to suggest or remember what is typed; four of the eight did not. ([#261])
- Deleting the project that is open no longer sends you to the first-run screen when you have other projects: the most recently changed one opens. Deleting a project that is not open leaves the open one alone. The first-run screen is for a new user, a new ledger, and deleting the only project. ([#263])
- Opening a folder no longer fails with "project already exists" when an unfinished import left a folder of that name behind, or a second one was started while the first was running. ([#266])
- In the new-ledger setup, an account you added can be removed again, the page says a custom account starts with its type, and the buttons read Next instead of naming the next page. ([#265])
- Adding and removing documents no longer makes files/documents.beancount grow by a blank line each time: removing a document's line takes the blank line that followed it too. Lines already added are left as they are. ([#268])
- A running script's output now appears as it prints, like a terminal, instead of all at once when the script has finished. ([#270])

## [1.0.2] - 2026-10-25

### Added
- Two more scanner controls. Crop to the page automatically can be turned off (the button next to automatic shooting), so a shot is kept whole, and Photo size lists the 4:3 picture sizes your phone's camera reports, largest first, with its megapixels and pixels; the default is the camera's largest ordinary picture, as before. ([#180])
- Scanned pages are flattened properly. The old Document style left a shadowed half of a page grey; the lighting is now estimated and divided out per colour, so the paper goes white and the ink dark in the shadow too, while a stamp or a highlighter keeps its colour. Greyscale is flattened and darkened for faded thermal print, and a new Black and white style gives black ink on white paper. The camera offers Automatic, Colour, Document, Greyscale and Black and white, with Automatic chosen: it gives a page a colour scan when it has real colour and a grey one when it does not (a lamp's yellow is not colour). The review says "Chosen automatically", and cropping such a page chooses again until you choose a style yourself. ([#181])
- A page size for scanned documents. In the page review's menu, Page size lets you lay every page on A4 or Letter paper, fitted and centred with a 5 mm margin, instead of the shape it was photographed. The review then shows each page on its sheet, so you see the margins before filing. As scanned stays the default and makes the same files as before. ([#184])
- Retake and Add page in the scanner's review. The camera's tick now opens the review of the batch instead of leaving the scanner; from there Retake takes a new photograph of the page you are looking at (it keeps the page's style and place, and Back returns to the page with nothing changed), and Add page goes back to the camera with the batch kept. Finish with the page count leaves the scanner as the tick used to. ([#180])
- The page review is a new screen that follows the app's theme, in light and dark. It has a strip of thumbnails you can tap to jump or hold and drag to reorder, a style chip for every style with Use for all pages, and Finish with the page count. Removing a page, and Use for all pages, show an Undo bar. Removing the last page leaves the screen open with Undo instead of closing it, and the title moves under the bar at large text sizes instead of being cut off. ([#180])
- The scanner finds more pages. A white receipt on a light table, or a white page on a beige table of the same brightness, used to leave the outline empty and the corners to be placed by hand. Detection now tries several ways of telling the page from the table, picks the outline they agree on, and still returns what the old single pass found when nothing else qualifies. On 60 drawn scenes the old pass found the page in 37 and the new one in all 60; on a real photo it is only as good as the photo, so tell us about pages it misses. ([#183])
- The crop screen is easier to place by touch. A round magnifier shows the held handle larger above your finger, the middle of each long side has a handle that moves the whole side, handles take a 48 dp touch target, and a press that lands on no handle moves nothing. A corner stops where the page would fold, so a crop that cannot be straightened cannot be made. The screen can turn the picture, undo the last move, and find the page again. Each handle has actions for TalkBack and arrow keys for a keyboard. ([#182])
- Scheduled backups can be encrypted. A switch on the Backup screen asks for a passphrase, which the phone keeps wrapped by its Keystore so backups can be made while it is locked. It protects the copies in the backup folder, not someone who can use your unlocked phone. If the passphrase can no longer be read, a scheduled run writes nothing and says so instead of writing a plain backup. ([#68])
- An account can carry details. A new account takes an optional list of name and value pairs, such as the bank or the last digits of its number, written as metadata under its `open` line. Tapping an account on the Accounts page lets you change, add or remove them later; only the lines that changed are rewritten, and it can be undone. ([#208], [#210])
- Amount fields have a plus/minus button, because the number keyboard has no minus sign. ([#138], fixes [#117])
- One search and tag filter is shared by Home, Journal and Balances, shown as chips that can be cleared. ([#138], fixes [#119])
- Each widget has its own basis (units, at cost or market value), and a widget can show one commodity of an account. ([#138], fixes [#120])
- First run is a guided setup: the basics (currency, start date and fine-tuning), the accounts you want, and a few preferences, then a ledger that Beancount has already read. Create with defaults skips the questions. ([#140], [#139], fixes [#127], [#128])
- The first-run page can connect to a Fava server you host, and an import from first run ends with a summary and the choice of adding an entry or going to Home. ([#140], fixes [#129])
- More, then Accounts, opens an account and closes an empty one, and lists closed accounts apart. ([#140], fixes [#130])
- An Open button beside the message when an entry names an account the ledger has not opened. The entry you typed stays as it is. ([#140], fixes [#131])

### Changed
- Lists of things to tick (the accounts in first-run setup, and the cards on Home) share one row. The tick box now starts where the text of every other row starts, not 13 dp further in, and the label follows closely instead of a wide gap away. Headings, notes and status lines above and below the cards now start at that same line, so the box, the names and the headings sit on one edge on every page. ([#229])
- The small add buttons on the entry form (Tag, Link, Detail, Attach file, Scan document, Add posting, Commodity details) are the same outlined button as everywhere else, in the standard size and padding and with room between them, instead of the compact old style. ([#230])
- Opening and closing an account is done on the Accounts page only. The Directives tab of the entry form no longer has Open and Close. Closing an account now asks for the date. ([#208])
- The sharing sign-in password is now stored with 600,000 rounds of PBKDF2 instead of 150,000, the figure OWASP recommends. Saving the password takes a few seconds on a phone and runs in the background; a password saved earlier keeps working. ([#56])
- First-run setup uses the same cards as the rest of the app: Add a ledger, and each of the three setup steps, are a heading and a card for each group, with choices as rows and switches as switch rows. It works as before. ([#197])
- Every button is now either filled (a page's main action) or outlined (everything else), in Material's own size. The outlined ones are no longer taller and tighter than the filled ones beside them. ([#192])
- The Accounts page uses the same cards as the other pages under More: a heading and a card for each type of account, the form to open an account in a card, and closed accounts in a card. ([#189])
- The document scanner uses OpenCV 5.0.0.1 (was 4.12.0). Five geometry functions it calls moved to a new `Geometry` class; page detection and straightening are unchanged, and the scanner's tests pass on a device. The native library is larger, so each APK grows by about 4.5 MB. ([#176])
- Edit opens entries that have comments, metadata on a posting or several currencies in the entry form instead of the text editor. ([#138], fixes [#124])
- Editing and deleting read an entry's text from the file instead of loading the ledger again. ([#138], fixes [#125])
- Every field explanation sits under its field, in one style. ([#138], fixes [#126])
- The Documents Add page resets after filing and keeps one status line, and the Home card for an unlinked document opens Organize. ([#138], fixes [#121], [#122])
- The entry preview keeps long lines whole and scrolls sideways. ([#138], fixes [#123])
- Balances hides a closed account that holds nothing, and the entry form no longer offers closed accounts. ([#140], fixes [#132])
- Actions that looked like plain text (Clear, View, Share, Edit, Delete, Cancel, Fine-tune and the actions in dialogs) are outlined buttons, and a dialog's main action is filled. ([#141], fixes [#134])
- The setup checklist has rounder checkboxes with room before each label, its currency field takes the whole line, and the notification button reads Granted once permission is given. ([#141], fixes [#135])

### Fixed
- Opening a project folder no longer fails on Android 10 to 13. Reading the folder used a call that only newer Android versions have. ([#249])
- Saving an entry with a scanned document no longer needs a second Save. The first time, the app asks to add one `include` line to your main file; pressing Add the line now carries on and saves the entry. ([#234])
- Deleted documents can be found again. The Organize tab on Documents lists what was deleted in the last 30 days, with a button to put each one back or delete it for good, and Empty the trash. ([#235])
- The date picker no longer stutters. Its pencil button, which switched to a second text box and resized the dialog each time, is gone; the date field it opens from is already a text box. ([#236])
- The scanner's light goes out when you open a page for review, instead of staying lit behind it. ([#180])
- Black and white no longer turns every edge into a staircase of whole pixels: edges are smooth, with grey only where ink meets paper. ([#181])
- Document no longer leaves yellow patches on the paper. The brightness of the light is measured once, on the grey page, the colour of the light is measured over the paper and averaged wide, and single-pixel colour speckle is smoothed away, so sensor noise in the blue channel cannot tint the paper. ([#181])
- The scanner no longer takes a page by itself while a page is open for editing behind it. Sampling pauses while the editor is open, and the shutter has to see the page settle again once you are back at the camera. ([#180])
- Coming back to the app no longer reads every file in the mirror folder again. A file is read only when its size or modified time changed since the last pass, so keeping documents in the mirror folder stays cheap. A provider that reports no time is still read each time. ([#66])
- Encrypted backups no longer hold the whole project in memory, so a project with hundreds of megabytes of scanned receipts can be backed up with a passphrase instead of crashing the app. The backup is sealed in small chunks as it is written and opened the same way. Backups made earlier still open. A backup made by this version cannot be opened by an older version of the app. ([#54])
- Delete file on the Documents screen can be undone. The file is moved aside for 30 days instead of removed, an Undo bar appears, and Undo puts it back where it was and relinks the entry. A deleted document is no longer part of the project, so it is not in a backup, a mirror or an export. ([#72])
- Closing the document viewer while a large page is still drawing no longer freezes the screen until the page finishes. The viewer lets go of the file as soon as nothing is drawing. ([#73])
- Totals no longer differ from Fava by a cent. The engine used to round each account's value before the app added them up; it now reports exact values and the total is rounded once, to the places the ledger shows the currency in, as Beancount and Fava do. A currency the ledger never writes, used only as the operating currency, shows two places. ([#60])
- The entry form's balance check follows a ledger's `tolerance_multiplier` and `infer_tolerance_from_cost` options. A ledger that sets either no longer gets the default tolerance in the form, so an entry Beancount accepts is not refused, and one it refuses is not accepted. ([#50])
- Balance and price directives are written at the ledger's own currency column (Fava's `currency-column`), in line with its transactions, instead of always at column 61. The preview on the form shows the same text. ([#49])
- The scanner no longer shoots the same page again and again while it stays in view. Auto-capture waits for the page to hold still for about a second, shows a ring filling on the page while it does, and shoots again only once the page has been taken away or replaced. A shot taken with the shutter button counts too. ([#179])
- Delete and Edit no longer refuse an entry because a plugin rewrote its narration or payee in memory. The entry at the line is checked by its date and the postings its own text writes, so it is still refused if the file has changed to some other entry. ([#70])
- Remove buttons on form rows line up with their field. ([#138], fixes [#116])
- A suggestion that matches the typed text exactly stays in the list. ([#138], fixes [#118])
- An entry sent to a remote Fava that may not have arrived is now kept on the phone until the server confirms it or you discard it, so closing or losing the app no longer loses it or its key. After a restart, Try again asks the server whether it arrived before sending anything. ([#65])
- A saved entry template keeps each posting's own commodity, lot, price and details, so a currency exchange or a purchase at cost is applied as it was saved and no longer shows an imbalance you did not create. Templates saved before this still load. ([#47])
- A damaged plugin list (`plugins/installed.json`) is no longer read as "no plugins". Installing, turning on or removing a plugin is refused with a message instead of writing the list back with every other plugin forgotten, and a copy of the damaged file is kept beside it. ([#58])
- A backup can no longer be written from files that changed while it was being made. Each file is checked against the backup's list as it is copied, and a backup that sees a file change starts again; if a file keeps changing it stops with a message and writes nothing, instead of leaving an archive that fails to restore. ([#53])
- Adding, renaming or deleting a receipt in a ledger's documents folder is no longer hidden behind the saved answer from the last open. The saved answer is now checked against what is in the document folders as well as the ledger's files and plugins. ([#61])
- Importing a project whose main ledger is not called `main.beancount` no longer leaves two copies of it. A main ledger at the top of the project is renamed to `main.beancount`; one inside a folder, or one another file includes, stays where it is and `main.beancount` includes it. The import message says which. ([#55])
- Retrying an entry on a remote Fava no longer sends it again when Fava could not be asked to re-read its ledger first. The retry fails and tells you, instead of storing the entry twice. ([#156])
- Checking or opening a backup no longer reads whatever size the file claims. A manifest over 8 MiB, an encrypted project over 256 MiB or more than 100,000 files is refused with a message before anything is read into memory, so a small crafted backup cannot use up the phone's memory. ([#157])
- A PDF page with an absurd size no longer crashes the document viewer. The viewer draws a page at a size that fits in memory, and says so when a page is too large or oddly shaped to show. ([#158])
- The app no longer stops at start when the file that remembers the open project is empty, and that choice is now written to disk safely. ([#140], fixes [#133])
- When Beancount refuses a new ledger, the message says what is wrong and which step to change, instead of quoting a file and line you cannot open. ([#141], fixes [#136])

## [1.0.1] - 2026-10-18

### Added
- Deleted projects are listed in a Trash section on the Projects screen, with their size, and can be put back or deleted for good, one at a time or all together, after a confirmation that names what goes. Until now they stayed on the phone with no way to remove or restore them. ([#94], fixes [#57])

### Changed
- The bundled Python runtime is 3.13.16 (Chaquopy 17.1.0). ([#98])
- Gradle dependencies are checked against recorded checksums, so a changed artifact fails the build. ([#95], fixes [#82])
- Each processor type (arm64-v8a, x86_64) has its own APK with its own version code, so F-Droid and the phone choose the matching one. The universal APK stays on the release page. ([#90], [#97])

### Fixed
- The entry form no longer refuses an entry Beancount accepts: the balance check now follows Beancount's own per-currency tolerance rule, including `inferred_tolerance_default`. ([#52])
- The project picker no longer fails after restoring a backup. ([#59])
- A save that is interrupted by the screen closing is finished or put back, never left undecided; after a crash, a raw edit you typed is kept even if the engine is down at the next start. ([#59])
- Undo after restoring a backup can no longer put the old project's files back over the restored ones, and Undo stays in order if the device clock is reset. ([#59])
- A snapshot taken before a write is flushed to storage before the ledger is replaced, and a damaged one is never restored over an intact ledger. ([#59])
- Files you named `key` are included in backups and the mirror. ([#59])
- Typed lots accept thousands separators, as Beancount does. ([#52])
- Editing a ledger with Windows (CRLF) line endings keeps its line endings instead of mixing them. ([#62])
- A cached answer is no longer reused after a plugin changes or the app is updated. ([#62])
- A failed engine start no longer makes every later read wait out its timeout. ([#62])
- Displayed amounts round half to even, as Beancount and Fava do. ([#62])
- A remote server address typed with capital letters no longer shows blank pages, and a server under a path prefix such as `/fava` is found correctly. ([#64])
- Deleting or editing an entry checks that the file still holds the entry you were looking at, instead of acting on a line number from an older list. ([#71])
- Text typed in the file editor while a save is running is no longer lost. ([#71])
- The report view recovers when Android closes it to free memory, instead of taking the app down. ([#71])
- A failed backup says what it left behind and removes the unfinished file; copies of backups, imports and script output no longer stay in the app's cache. ([#71])
- Saving a remote password recovers from an unusable Keystore key and reports a failure instead of crashing. ([#71])
- The entry form refuses an amount it cannot read (such as `4,50` or `4.5O`) instead of leaving it out and letting Beancount fill in a figure. ([#74])
- Deleting a selection that includes an entry generated by a plugin no longer removes the entry after it. ([#74])
- The document scanner frees its image memory as it goes instead of waiting for the garbage collector. ([#78])
- Restore cannot be started twice; removing fetched packages or a saved script asks first; a sharing user name can contain a space. ([#74])
- Receipt photos and attached copies are deleted from the app's cache once the document is filed, instead of staying until Android clears the cache. ([#93], fixes [#69])
- Restoring a deleted project whose name has been reused brings it back under the next free name instead of refusing, and the Trash is shown on the Add a ledger page when there are no projects, so deleting the only project can be undone. ([#103], fixes [#101], [#102])
- Unlinking a document no longer removes the link of a different document that has the same file name; a bare name in an entry is matched only when it is unique, and Unlink says so when it is not. ([#92], fixes [#48])

### Security
- Checking a backup is bounded in memory, and a backup archive can no longer ask the app to spend gigabytes deriving its key. ([#59])
- Diagnostics redact `Authorization: Bearer` tokens, cookies and whole passphrases, and no longer risk a crash when logged from several threads. ([#59])
- A ledger cannot list another project's documents by naming a folder with a shared prefix. ([#59])
- A remote server that answers oddly (a hostile ledger name, an endless reply) ends in an error message instead of a crash or a stall. ([#64])
- A link in a report opens in the browser only after you confirm it, and only if it is a web address. ([#71])
- A plugin picked from a file manager cannot be named so that it lands outside the install folder. ([#71])

## [1.0.0] - 2026-10-15

### Added
- Scripts show their output live, can be cancelled, and no longer stop after 30 seconds.
- The Mirror page lists the last ten files and decisions.
- A README with animated artwork, screenshots, a social preview and store images.
- A Support row in About in the Expert edition, linking to Ko-fi.
- Units, At cost and Market value on Home, Balances and the widgets, matching Fava's switch.
- Journal filters for errors, pending and flagged entries.
- Add forms follow the ledger's plugins (`commodity_attr`, `leafonly`, `check_commodity`).
- Undo for a saved entry, a delete and a raw-editor save.
- Per-plugin load status and an extension-failure count.
- The Bound Ledger redesign on Material 3 Expressive, built for 200% text, landscape and tablets.

### Fixed
- Pages shared on Wi-Fi are read-only: a signed-in browser can no longer save through Fava's editor.
- Reports no longer flashes the browser error page when the local server is a moment late; the loading cover stays and the load is retried.
- The SciPy-free overlay for `fava_portfolio_returns` was missing from every build (Android drops asset folders starting with an underscore); it ships now.
- Entries are written followed by a blank line, and deleting one removes it, as Fava does.
- Saving an entry with a scanned page no longer crashes the app.
- Deleting an entry also removes the blank line that set it apart, so adding and deleting returns the file to how it was.
- Plugins installed before their Android overlay existed get it applied at start (the SciPy-free `fava_portfolio_returns`).
- Searching the Journal no longer crashes when two entries share a position.
- Native companion libraries are fetched for the device's own architecture.
- The mirror is per project, and Reports follow the open project.
- Filing a scan finishes by itself once the include line is added.
- A refused save names the errors it would add; accounts the ledger has not opened are refused.
- Widgets keep each project's figures.

[Unreleased]: https://github.com/pintofi/beanbook/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/pintofi/beanbook/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/pintofi/beanbook/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/pintofi/beanbook/releases/tag/v1.0.0
[#47]: https://github.com/pintofi/beanbook/issues/47
[#48]: https://github.com/pintofi/beanbook/issues/48
[#49]: https://github.com/pintofi/beanbook/issues/49
[#50]: https://github.com/pintofi/beanbook/issues/50
[#52]: https://github.com/pintofi/beanbook/issues/52
[#53]: https://github.com/pintofi/beanbook/issues/53
[#54]: https://github.com/pintofi/beanbook/issues/54
[#55]: https://github.com/pintofi/beanbook/issues/55
[#56]: https://github.com/pintofi/beanbook/issues/56
[#57]: https://github.com/pintofi/beanbook/issues/57
[#58]: https://github.com/pintofi/beanbook/issues/58
[#59]: https://github.com/pintofi/beanbook/issues/59
[#60]: https://github.com/pintofi/beanbook/issues/60
[#61]: https://github.com/pintofi/beanbook/issues/61
[#62]: https://github.com/pintofi/beanbook/issues/62
[#64]: https://github.com/pintofi/beanbook/issues/64
[#65]: https://github.com/pintofi/beanbook/issues/65
[#66]: https://github.com/pintofi/beanbook/issues/66
[#68]: https://github.com/pintofi/beanbook/issues/68
[#69]: https://github.com/pintofi/beanbook/issues/69
[#70]: https://github.com/pintofi/beanbook/issues/70
[#71]: https://github.com/pintofi/beanbook/issues/71
[#72]: https://github.com/pintofi/beanbook/issues/72
[#73]: https://github.com/pintofi/beanbook/issues/73
[#74]: https://github.com/pintofi/beanbook/issues/74
[#78]: https://github.com/pintofi/beanbook/issues/78
[#82]: https://github.com/pintofi/beanbook/issues/82
[#90]: https://github.com/pintofi/beanbook/issues/90
[#92]: https://github.com/pintofi/beanbook/issues/92
[#93]: https://github.com/pintofi/beanbook/issues/93
[#94]: https://github.com/pintofi/beanbook/issues/94
[#95]: https://github.com/pintofi/beanbook/issues/95
[#97]: https://github.com/pintofi/beanbook/issues/97
[#98]: https://github.com/pintofi/beanbook/issues/98
[#101]: https://github.com/pintofi/beanbook/issues/101
[#102]: https://github.com/pintofi/beanbook/issues/102
[#103]: https://github.com/pintofi/beanbook/issues/103
[#116]: https://github.com/pintofi/beanbook/issues/116
[#117]: https://github.com/pintofi/beanbook/issues/117
[#118]: https://github.com/pintofi/beanbook/issues/118
[#119]: https://github.com/pintofi/beanbook/issues/119
[#120]: https://github.com/pintofi/beanbook/issues/120
[#121]: https://github.com/pintofi/beanbook/issues/121
[#122]: https://github.com/pintofi/beanbook/issues/122
[#123]: https://github.com/pintofi/beanbook/issues/123
[#124]: https://github.com/pintofi/beanbook/issues/124
[#125]: https://github.com/pintofi/beanbook/issues/125
[#126]: https://github.com/pintofi/beanbook/issues/126
[#127]: https://github.com/pintofi/beanbook/issues/127
[#128]: https://github.com/pintofi/beanbook/issues/128
[#129]: https://github.com/pintofi/beanbook/issues/129
[#130]: https://github.com/pintofi/beanbook/issues/130
[#131]: https://github.com/pintofi/beanbook/issues/131
[#132]: https://github.com/pintofi/beanbook/issues/132
[#133]: https://github.com/pintofi/beanbook/issues/133
[#134]: https://github.com/pintofi/beanbook/issues/134
[#135]: https://github.com/pintofi/beanbook/issues/135
[#136]: https://github.com/pintofi/beanbook/issues/136
[#138]: https://github.com/pintofi/beanbook/issues/138
[#139]: https://github.com/pintofi/beanbook/issues/139
[#140]: https://github.com/pintofi/beanbook/issues/140
[#141]: https://github.com/pintofi/beanbook/issues/141
[#156]: https://github.com/pintofi/beanbook/issues/156
[#157]: https://github.com/pintofi/beanbook/issues/157
[#158]: https://github.com/pintofi/beanbook/issues/158
[#176]: https://github.com/pintofi/beanbook/issues/176
[#179]: https://github.com/pintofi/beanbook/issues/179
[#180]: https://github.com/pintofi/beanbook/issues/180
[#181]: https://github.com/pintofi/beanbook/issues/181
[#182]: https://github.com/pintofi/beanbook/issues/182
[#183]: https://github.com/pintofi/beanbook/issues/183
[#184]: https://github.com/pintofi/beanbook/issues/184
[#189]: https://github.com/pintofi/beanbook/issues/189
[#192]: https://github.com/pintofi/beanbook/issues/192
[#197]: https://github.com/pintofi/beanbook/issues/197
[#208]: https://github.com/pintofi/beanbook/issues/208
[#210]: https://github.com/pintofi/beanbook/issues/210
[#229]: https://github.com/pintofi/beanbook/issues/229
[#230]: https://github.com/pintofi/beanbook/issues/230
[#234]: https://github.com/pintofi/beanbook/issues/234
[#235]: https://github.com/pintofi/beanbook/issues/235
[#236]: https://github.com/pintofi/beanbook/issues/236
[#249]: https://github.com/pintofi/beanbook/issues/249
[#259]: https://github.com/pintofi/beanbook/issues/259
[#261]: https://github.com/pintofi/beanbook/issues/261
[#263]: https://github.com/pintofi/beanbook/issues/263
[#264]: https://github.com/pintofi/beanbook/issues/264
[#265]: https://github.com/pintofi/beanbook/issues/265
[#266]: https://github.com/pintofi/beanbook/issues/266
[#268]: https://github.com/pintofi/beanbook/issues/268
[#270]: https://github.com/pintofi/beanbook/issues/270
[#272]: https://github.com/pintofi/beanbook/issues/272
[#274]: https://github.com/pintofi/beanbook/issues/274
[#275]: https://github.com/pintofi/beanbook/issues/275
[#277]: https://github.com/pintofi/beanbook/issues/277
[#280]: https://github.com/pintofi/beanbook/issues/280
