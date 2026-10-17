# Review log: clients (core-engine and engine-remote)

Area `clients` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `core-engine/build.gradle.kts` | 3 5 | none | pure Kotlin module; builds in `:core-engine:test` |
| `core-engine/src/main/kotlin/io/github/pintofi/beanbook/core/engine/EngineClient.kt` | 1 5 | C-6 | comments pointing at private finding ids rewritten; interface unchanged |
| `core-engine/src/main/kotlin/io/github/pintofi/beanbook/core/engine/OriginAllowlist.kt` | 3 5 | C-6 | OriginAllowlistTest (refusals: userinfo, scheme, port, downgrade); comment cleaned |
| `engine-remote/build.gradle.kts` | 3 5 | C-6 | no HTTP library, no TLS override; comment no longer cites a design section that does not say so |
| `engine-remote/src/main/kotlin/io/github/pintofi/beanbook/engine/remote/FavaClient.kt` | 1 2 5 | C-2, C-6 | IdempotencyTest, LostResponseTest, ReviewFixesTest (slug behind a base path, hostile slug) |
| `engine-remote/src/main/kotlin/io/github/pintofi/beanbook/engine/remote/PlatformTransport.kt` | 2 3 5 | C-5, C-6 | CertificateIsFatalTest (real TLS handshake); streamed body kept; response now bounded to 4 MiB |
| `engine-remote/src/main/kotlin/io/github/pintofi/beanbook/engine/remote/RemoteAddress.kt` | 2 3 | C-1 | RemoteAddressTest, ReviewFixesTest (capital host, IPv6 default port, encoded path) |
| `engine-remote/src/main/kotlin/io/github/pintofi/beanbook/engine/remote/RemoteEngineClient.kt` | 2 5 | C-3, C-4, C-6 | RemoteEngineClientTest, ReviewFixesTest (default report URL, reconnect, base path) |

Gradle: `:core-engine:test :engine-remote:test` pass; 7 of the tests in `ReviewFixesTest` fail on the old code and pass now. The full gate is listed in the pull request.

## Fixed in this pull request

- **C-1 (S1): a server address typed with capitals blocked every page.** `RemoteAddress` kept the host as typed while `OriginAllowlist` compares lower-case hosts, so `https://Fava.Example.com` connected and then showed nothing. The host is lower-cased. Two neighbours fixed with it: an IPv6 literal such as `[::1]` was taken to already have a port, so it never got the default one, and the decoded path turned `%20` into a space that is not a URL (the raw path is kept).
- **C-2 (S1): the ledger slug was wrong behind a base path.** For `https://host/fava` Fava redirects to `/fava/<slug>/...` and the client took `fava` as the slug. The base path is now removed first. The slug comes from the network and is spliced into every later URL, so one that is not a plain path segment (spaces, `..`) now means "not a Fava server" instead of an uncaught `URISyntaxException`.
- **C-3 (S2):** `urlFor(LEDGER_DEFAULT)` produced `/<slug>//`; it now produces `/<slug>/`.
- **C-4 (S2):** `start()` left the previous connection's slug in place after a failed reconnect, left the status at `Starting` if cancelled, and let an unexpected exception escape as a crash. The slug is cleared first, cancellation sets `Stopped`, and anything else becomes a `Failed` status.
- **C-5 (S2):** a response body was read without a limit. It is now capped at 4 MiB; every answer the app needs is far smaller.
- **C-6 (S3):** comments citing documents and ids that are not in this repository (a private spec, feasibility notes, milestone notes, "Phase 0" ids) removed or made self-contained.

## Filed as issues

- **I-1 (S3)** `docs/architecture.md` still draws OkHttp in its diagram although the module uses the platform stack: https://github.com/pintofi/beanbook/issues/63. Left for the `docs` area.

## Accepted, with the reason

- **Cleartext `http` is accepted for LAN and VPN hosts.** It is shown wherever the address is shown and is never a fallback from `https`; a certificate error is fatal and has no override (`CertificateIsFatalTest` uses a real handshake).
- **`ROWS` in `isPresent` is a regular expression over JSON, not a parser.** An unreadable answer is `UNKNOWN`, which blocks the send, so a misparse costs a retry and never a duplicate. Covered by `IdempotencyTest`.
- **The allowlist uses `java.net.URI`, which is stricter than the WebView's parser.** Anything it cannot parse (for example a backslash in the authority) is refused.
