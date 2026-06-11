# ADR-0002 — Late-joiner state-on-connect: rely on a Sparkplug-Aware broker (English translation)

- Status: **Accepted** (assuming a multi-consumer UNS)
- Date: 2026-06-03
- Evidence: direct A/B experiment (`src/.../LateJoinerExperiment.java`), HiveMQ CE 2026.5. *(Original Korean: [ADR-0002-late-joiner-state-on-connect.md](ADR-0002-late-joiner-state-on-connect.md))*

## Context

Sparkplug clients do **not publish NBIRTH retained.** So while an edge is quiet after its birth, a **late-joining consumer** receives no current state right after subscribing. A UNS has arbitrary consumers (MES, historian, analytics, new dashboards) late-joining all the time — "know the current state immediately on connect" is a governance requirement.

## Experiment

The same code (`LateJoinerExperiment`) against two brokers. Measured: *while the edge is silent after NBIRTH, does a late subscriber to `spBv1.0/<group>/#` + `$sparkplug/certificates/#` receive current state within 3 s?*

- **A (non-aware):** HiveMQ CE with only the Allow-All extension.
- **B (aware):** A + `hivemq-sparkplug-aware-extension:4.33.4` (free OSS, prebuilt release).
  Run: `docker compose -f docker-compose.yml -f docker-compose.aware.yml up -d --force-recreate`

## Findings (measured evidence)

| | Current state on subscribe | Evidence |
|---|---|---|
| **A non-aware** | ❌ NO | received topics = `[]`; NBIRTH arrives only after publishing a rebirth NCMD |
| **B aware** | ✅ YES | immediately receives retained `$sparkplug/certificates/spBv1.0/.../NBIRTH/...` (136 B) |

Broker log (B): `Extension "Sparkplug Aware Extension" version 4.33.4 started successfully`. The aware extension mirrors NBIRTH/NDEATH as retained messages under `$sparkplug/certificates/#`.

## Decision

In a multi-consumer UNS, **guarantee "current state on connect" via a Sparkplug-Aware broker.** Do not assume plain Sparkplug provides it.

- Consumers that need current state subscribe (read-only) to `$sparkplug/certificates/#`.
- **Do not use rebirth (NCMD) as the standard late-join mechanism** — it forces the edge to republish, and per-consumer requests become a **rebirth storm** (cost grows with consumers — ties into ADR-0001). Aware-broker retained certs scale independently of consumer count.

## Consequences

- **The broker becomes a governed component:** "is it aware?" is a requirement. Options: HiveMQ aware extension / HiveMQ Enterprise. (Default Mosquitto etc. are non-aware → unsuitable.) Vendor/configuration governance item.
- **`$sparkplug/certificates/#` is a parallel read-only namespace** consumers must know about → codified in the namespace standard.
- **Security:** read ACL governance for the certificates topics.
- **Limits (follow-up work):** this experiment covers NBIRTH only. NDEATH/late-death, many edges, and certificate expiry/consistency are future experiments.

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/LateJoinerExperiment.java`](../../src/main/java/dev/krillin/sparkplug/LateJoinerExperiment.java)
- Extension: https://github.com/hivemq/hivemq-sparkplug-aware-extension
- Related: ADR-0001 (multi-consumer role split)
