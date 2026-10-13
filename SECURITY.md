# Security policy

## Reporting a vulnerability

Please report it privately, by email to **pintofinan@gmail.com**, with the version (More →
About), the edition (Play or Expert), and the steps to reproduce. Do not open a public issue for
a vulnerability.

You can expect an acknowledgement within a week. This is a volunteer project: fixes are made as
soon as they can be verified, and a reporter is credited unless they ask not to be.

## What is in scope

The rules the app holds itself to are in
[docs/threat-model.md](docs/threat-model.md): loopback-only Fava with a per-process token, the
single opt-in LAN listener (Share on Wi-Fi) behind a sign-in, a hardened WebView, fatal
certificate errors for `https://` servers, no telemetry, and the data-safety invariants for
writes. A break of any of them is a vulnerability.

User Python (plugins and scripts) is unsandboxed in both editions and says so on screen; running
code you chose to run is not a vulnerability.

## Supported versions

Only the latest release.
