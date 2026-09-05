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

The Play edition includes **Google Play's billing library**, because Play requires its billing
system for unlocking app features. When you buy a tier, Google handles the payment and tells
the app what you own; the developer never sees your payment details, and no ledger data is
involved. That library brings some of Google's own client code onto the classpath, but it adds
no permissions: the built Play package requests no location and no advertising ID, which is
checked against the merged manifest rather than assumed.

The Expert edition has no billing library at all. Its contribution keys are verified on the
device with a signature check, so there is no licence server and nothing to phone home to.

## What stays on your device

- **Ledger files.** Plain Beancount text in the project you created or imported.
- **Documents and receipt photos**, stored inside the project tree. The app never reads your
  photo library; it sees images you capture in it or attach yourself.
- **Backups**, written where you choose, encrypted with your passphrase if you ask for that.
- **Settings**, including your theme.
- **A contribution key, if you have one.** It contains the email address it was issued to, so
  the app can show you which key is installed. It is checked offline and never transmitted.
- **A password for your own server, if you set one.** Encrypted with a key held in the Android
  Keystore, which the app itself cannot read.

The app sets `allowBackup="false"`, so Android's own cloud backup does not copy app-private
storage off the device.

## When the app uses the network

In local mode the app makes no network requests at all. It works with the network off. The
embedded Fava binds only to `127.0.0.1` on an ephemeral port and requires a token generated per
process, so it is reachable from inside the app and nowhere else.

There are three outbound paths in the whole codebase, and each is one you start:

**Your own Fava server.** If you enter an address for remote mode, the app connects to it and
sends what is needed to show your ledger, including a username and password if your server asks
for them. That destination is a machine you control. There is no default server to fall back
on.

**Optional Python packages, Expert edition only.** When you ask for a package, it is downloaded
from the Python Package Index. The request contains the package name; none of your ledger goes
with it. The Play edition never downloads code from anywhere.

**Buying a tier, Play edition only.** The purchase runs through Google Play. The app asks Play
what you own when it starts and when you open the Contribute screen, which is also how a refund
takes effect and how a purchase follows you to a new device.

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
- **Internet**, used for the three cases above. It is required even in local mode because the
  app runs Fava as a local web server on the device itself.
- **Install packages**, in the Expert edition only, so it can offer you a signed update. Not
  present in the Play build.

## What is deliberately absent

No device fingerprinting. The 30-day trial is counted locally, which means a reinstall starts a
new one. Preventing that would require identifying your device, and that trade was declined.

No advertising identifiers, no analytics or attribution SDKs, and no hidden diagnostic uploads.
Diagnostics stay on the device unless you share them yourself.

The one third-party component that talks to anyone is Google Play's billing library in the
Play edition, and it talks to Google about purchases and nothing else.

## Deleting your data

Uninstalling removes app-private storage, including any stored server password and contribution
key. Your exported copies, backups, and mirrored folders are yours and are left alone, because
they are your records and they live where you put them. There is no account to close.

## Further reading

[threat-model.md](threat-model.md) sets out the assets, the adversaries considered, the
mitigations, and the risks accepted rather than solved.
