# ADR-0001 — Primary Host STATE + store-and-forward vs the multi-consumer contradiction (English translation)

- Status: **Accepted**
- Date: 2026-06-03
- Evidence: direct experiment (`src/.../StateStoreForwardDemo.java` + `PrimaryHost`/`SfEdgeNode`), HiveMQ CE. *(Original Korean: [ADR-0001-primary-host-store-and-forward.md](ADR-0001-primary-host-store-and-forward.md))*

## Context

Sparkplug store-and-forward is gated on the STATE of a **single Primary Host** (`spBv1.0/STATE/{hostId}`, retained JSON online/offline). While the host is offline the edge buffers data and flushes it in order on recovery — so the system of record never misses data. But a UNS has many independent consumers (MES, historian, analytics, ERP), an n:m shape — "who is the primary?" becomes a contradiction.

## Experiment (measured, no-loss confirmed)

Scenario: host online → edge publishes LIVE (seq 1, 2) → host goes OFFLINE (graceful STATE) → edge **buffers** seq 3, 4, 5 (not delivered) → host returns → edge **flushes in order** → host receives **all of seq 3, 4, 5** → LIVE resumes at seq 6. (exit 0)

## Findings — two real concurrency/ordering defects (found → fixed)

The experiment itself surfaced two genuine async-state bugs:

1. **Flushing by publishing from the Paho callback thread → deadlock.** Running `client.publish` inside the STATE callback (the comms thread) hung permanently at seq=4. **Fix:** flush on a separate thread; snapshot the buffer under the lock, publish over the network outside the lock.
2. **Consumer announced STATE online before subscribing to data → the flush outran the subscription and the backlog was lost.** Initially only seq=3 arrived. **Fix:** the host subscribes to `spBv1.0/<group>/#` **before** publishing STATE online → 3, 4, 5 all arrive.

## Decision (governance — resolving the multi-consumer contradiction)

- **Designate exactly one system of record** (typically the historian / UNS recording layer) as the primary host, and gate store-and-forward on its availability only. All other consumers (analytics, dashboards, …) are **best-effort live subscribers**, and they recover current state via **Sparkplug-aware broker certificates (ADR-0002)** — not via store-and-forward.
- **Do not try to make every consumer primary** — it is semantically contradictory (an edge cannot satisfy N STATE signals at once).
- The role split: primary-host store-and-forward = *completeness of the recording layer*; aware-broker retained certs = *current state for arbitrary consumers*. This split is the governance answer to the "Sparkplug (n:1) ↔ UNS (n:m)" gap.

## Consequences

- **Primary-host identity is a governance decision.** Edge configuration binds to a single `primaryHostId` → which system is the recording layer must be written down (namespace standard §8).
- Multi-consumer current state depends on ADR-0002 (aware broker).
- **Flush QoS choice** (0 vs 1) is a completeness trade-off — if recording-layer completeness matters, flush at QoS 1 with a persistent buffer.
- Code lessons: never publish from a Paho callback thread (snapshot then separate thread); consumers must "subscribe, then declare online".

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/`](../../src/main/java/dev/krillin/sparkplug/) — `PrimaryHost`, `SfEdgeNode`, `StateStoreForwardDemo`
- Thematically paired with ADR-0002 (late joiner) — both govern how consumers obtain state
