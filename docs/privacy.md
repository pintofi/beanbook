# Privacy

The short version: your ledger is a folder of text files on your device, and Beanbook
never sends it anywhere.

This describes the app's behaviour. The privacy policy published with a release will say the
same things; if the two ever disagree, the code is the authority and this file is the bug.

## What the project collects

Nothing. There is no account system, no server operated by this project, and no database
anywhere but your device. That is not a promise about careful handling of data received; it is
a statement that none is received.

There is no analytics, telemetry, crash reporting, advertising, attribution, or tracking SDK in
either edition.

Nothing is sold in either edition, so neither contains a billing library, and there is no
licence server or key check to phone home to. If you choose to donate on Ko-fi, that happens in
your browser, on Ko-fi, and the app learns nothing about it.

## What stays on your device

- **Ledger files.** Plain Beancount text in the project you created or imported.
- **Documents and receipt photos**, stored inside the project tree. The app never reads your
  photo library; it sees images you capture in it or choose yourself in the system picker.
- **Backups**, written where you choose, encrypted with your passphrase if you ask for that.
- **Settings**, including your theme.
- **One date**: when the app was first opened, so the single mention of donations comes after
  a week (Expert edition), and whether you have answered it.
- **A password for your own server, if you set one.** Encrypted with a key held in the Android
  Keystore, which the app itself cannot read.

The app sets `allowBackup="false"`, so Android's own cloud backup does not copy app-private
storage off the device.

## When the app uses the network

In local mode the app makes no network requests at all. It works with the network off. The
embedded Fava binds only to `127.0.0.1` on an ephemeral port and requires a token generated per
process, so it is reachable from inside the app and nowhere else, unless you turn on Share on
Wi-Fi (below).

There are three outbound paths in the whole codebase, and each is one you start:

**Your own Fava server.** If you enter an address for remote mode, the app connects to it and
sends what is needed to show your ledger, including a username and password if your server asks
for them. That destination is a machine you control. There is no default server to fall back
on.

**Optional Python packages and plugins, Expert edition only.** When you ask for a package or a
catalogue plugin, it is downloaded from the Python Package Index (`pypi.org` and
`files.pythonhosted.org`), from Chaquopy's index of Android builds (`chaquo.com`), or from
GitHub (`codeload.github.com`). The request contains the package name; none of your ledger goes
with it. Each download is checked against a recorded hash before it is unpacked. The Play
edition never downloads code from anywhere.

**Share on Wi-Fi (incoming connections, off by default).** If you turn this on in More → Share on
Wi-Fi, other devices on the same private Wi-Fi network can open your Reports in a browser. It does
not start until you start it. It listens only on the phone's private network address, never a
public one. It shows nothing until someone signs in with the username and password you set, it
locks out repeated wrong passwords, and a notification is shown for as long as it is on. It stops
when you tap Stop sharing, or from the notification. The connection is plain `http://`, which is
not encrypted: anyone watching that network could read the pages while it is on, so use it only
on a network you trust. Your password is kept only as a salted hash. Nothing about sharing is
sent to the developer or to anyone else, and sharing makes no outgoing request.

Sharing a document to another app is a fourth transfer, but it happens only when you tap Share
and choose the recipient. Android grants that app read access to the one file.

**Updates are not one of those paths.** Neither edition checks for one, and neither can install
a package: no build requests `REQUEST_INSTALL_PACKAGES`. Play delivers updates for the Play
edition; the Expert edition is installed from a file, so a new version arrives the way the
first one did, and Android refuses any file not signed with the key that signed the app you
have.

## `https` and `http`

If the address you enter starts with `https://`, certificate verification is strict. A
certificate error ends the connection, in every build, with no override anywhere in the app.

If the address starts with `http://`, the connection is unencrypted. Anything on the network
path can read and modify it, including your ledger contents and your password. The app tells
you this on screen while you are entering the address.

Cleartext exists because people host Fava on a home network or behind a VPN, where a
certificate for `192.168.1.5` is not a reasonable thing to ask for. It is never inferred, and
never used as a fallback after HTTPS fails. It happens when you type `http://` yourself and at
no other time.

## Permissions

- **Camera**, only for photographing receipts, and only when you open the camera in the app.
- **Storage**, through Android's folder picker, limited to folders you choose.
- **Internet**, used for the cases above. It is required even in local mode because the app
  runs Fava as a local web server on the device itself.
- **Notifications and foreground service**, to show that a save, an install or sharing is
  still going after you leave the app. **Wake lock**, held with a timeout while that work
  finishes. **Network state**, to find the Wi-Fi address when you share Reports.
- **Run at start-up**, added by the scheduler library so scheduled backups survive a restart.
  Nothing else uses it.

Neither edition asks to install packages.

## What is deliberately absent

No device fingerprinting, and nothing that would need it: there is no trial to protect.

No advertising identifiers, no analytics or attribution SDKs, and no hidden diagnostic uploads.
Diagnostics stay on the device unless you share them yourself.

No third-party component in the app talks to anyone.

## Deleting your data

Uninstalling removes app-private storage, including any stored server password. Your exported copies, backups, and mirrored folders are yours and are left alone, because
they are your records and they live where you put them. There is no account to close.

## Further reading

[threat-model.md](threat-model.md) sets out the assets, the adversaries considered, the
mitigations, and the risks accepted rather than solved.
