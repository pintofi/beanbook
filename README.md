# Beancount & Fava for Android

Plain-text double-entry accounting on Android, running the real
[Beancount](https://github.com/beancount/beancount) and
[Fava](https://github.com/beancount/fava) on-device — or connecting to your own
self-hosted Fava.

**Status: specification phase.** No application code exists yet. Feasibility spikes come
next; the architecture is not approved until they report back.

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
| [CLAUDE.md](CLAUDE.md) | Durable engineering rules (proposed) |

## License

**GPL-2.0-only.** Beancount, Beanquery, and Beangulp are GPL-2.0-*only* (explicitly not
"or later"), and this application embeds them in-process, so the combined work carries the
same terms. GPLv3 code cannot be used here.

Paid contribution keys unlock convenience features. Because the source is GPL, anyone can
build the app without them — this is a contribution model, not DRM, and the app says so.
