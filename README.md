# Beancount & Fava for Android

Plain-text double-entry accounting on Android, running the real
[Beancount](https://github.com/beancount/beancount) and
[Fava](https://github.com/beancount/fava) on-device — or connecting to your own
self-hosted Fava.

**Status: implemented, not released.** Both editions build and the quality gates run; the
app has never been submitted to Google Play and no contribution key has been sold. What is
proven, what is deferred and what is still unverified are tracked in
[docs/pending-verification.md](docs/pending-verification.md), and
[docs/release-readiness.md](docs/release-readiness.md) says where the project stands against
the acceptance criteria in SPEC.md.

Minimum Android version is **10 (API 29)**: on Android 9 (API 28) the embedded Python runtime is killed by
seccomp before the engine starts.

## Documents

| Document | Contents |
|---|---|
| [SPEC.md](SPEC.md) | Product scope, non-goals, journeys, gating, acceptance criteria |
| [PLAN.md](PLAN.md) | Phased roadmap, test strategy, rollback points, MVP |
| [docs/architecture.md](docs/architecture.md) | Components, trust boundaries, lifecycle, dependency direction |
| [docs/distribution.md](docs/distribution.md) | Play vs Expert matrix, update channels, monetization, key issuance |
| [docs/storage-and-backup.md](docs/storage-and-backup.md) | Workspace, SAF mirroring, atomic writes, archives, restore |
| [docs/threat-model.md](docs/threat-model.md) | Assets, adversaries, mitigations, accepted residual risks |
| [docs/plugin-compatibility.md](docs/plugin-compatibility.md) | Compatibility definition, matrix, harness, limitations |
| [docs/ux.md](docs/ux.md) | Information architecture, native-vs-web ownership, accessibility, widgets |
| [docs/feasibility/plan.md](docs/feasibility/plan.md) | Phase 0 spikes, pass/fail criteria, commands |
| [docs/known-limitations.md](docs/known-limitations.md) | What the app cannot do, and why |
| [docs/on-demand-packages.md](docs/on-demand-packages.md) | Fetching extra Python packages (Expert edition) |
| [docs/release-readiness.md](docs/release-readiness.md) | Standing against SPEC.md acceptance criteria |
| [docs/pending-verification.md](docs/pending-verification.md) | Every unfinished item, in one register |
| [CLAUDE.md](CLAUDE.md) | Durable engineering rules |

## License

**GPL-2.0-only.** Beancount, Beanquery, and Beangulp are GPL-2.0-*only* (explicitly not
"or later"), and this application embeds them in-process, so the combined work carries the
same terms. GPLv3 code cannot be used here.

Paid contribution keys unlock convenience features. Because the source is GPL, anyone can
build the app without them — this is a contribution model, not DRM, and the app says so.
