# ADR-0012 — Runtime drift detection (detect-only) / post-deployment observability (English translation)

- Status: **Accepted**
- Date: 2026-06-11
- Relations: the **runtime counterpart** of ADR-0007 (the pre-deployment gate). Code: `src/.../drift/`, shell `DriftMonitor`, demo `DriftMonitorDemo.java`. *(Original Korean: [ADR-0012-runtime-drift-detection.md](ADR-0012-runtime-drift-detection.md))*

## Context

ADR-0007 enforces UDT data contracts **pre-deployment, shift-left** (CI, fail-closed). But after deployment, nothing observes whether the NBIRTHs actually flowing at runtime match the registered contracts (source of truth), whether nodes have quietly died (staleness), or what the namespace-wide conformance rate is. Two governance principles drive this: *make governance observable — what isn't seen isn't followed*, and *in OT, observe rather than block at runtime*.

## Decision

1. **Detect-only runtime observation.** A **passive subscription** to `spBv1.0/#` — no republish, no drop, no DLQ. **OT data is never dropped.** Alerts, audit records, and health metrics only.
2. **Schema drift = observed NBIRTH definition vs registry `latest(ref)` (source of truth).** `TemplateAdapter` extracts the NBIRTH `_types_/<ref>` Template into an observed `UdtDefinition` for comparison → 5 schema-drift kinds: `UNREGISTERED` (not in the registry) / `VERSION_DRIFT` (version mismatch) / `UNKNOWN_MEMBER` (extra in observed) / `MISSING_MEMBER` (registered member absent) / `TYPE_DRIFT` (common member, different type). (The `DriftKind` enum has a sixth value, `STALE`, covering the time dimension below.) These are raw deviations, not CompatMode semantics.
3. **Staleness = the time dimension.** `LivenessTracker` tracks each node's last-observed time (injected clock = deterministic); silence beyond a threshold → `STALE`. NDEATH excludes a node; rebirth (markSeen) clears death.
4. **Governance health snapshot.** `GovernanceHealth` aggregates total nodes, conformant nodes, conformance rate (distinct nodes), drift counts per kind, and stale node count.

## Consequences

- Governance becomes *observable* at runtime — the ADR-0007 pre-deployment gate plus this post-deployment observation **closes the policy loop** (enforced in CI + watched at runtime).
- **Boundary vs the ADR-0009 ContractValidator:** ADR-0009 is a data-plane gate at the Kafka egress (violation → DLQ, for routing, fixed contract passed by the caller). This is an **observability layer** — it compares against `DefinitionStore.latest` as source of truth, adds VERSION_DRIFT / UNREGISTERED / STALE (time), and emits global health metrics + audit, detect-only and passive (never blocking).
- Limits (honesty): **detect-only** (no blocking/DLQ/OT drops) · **node-level** (group/edge, not device-level) · **per-metric NDATA value drift out of scope** (observed schema comes from NBIRTH only; NDATA only marks liveness) · **single-process PoC** (not distributed/HA; the audit/liveness collections are **unsynchronized** — the Paho callback thread mutates while `report()` reads from the caller thread; safe for the sequential demo, concurrent collection out of scope) · reuses the ADR-0007 registry (not an independent source of truth). The shell is not unit-tested — verified by the live HiveMQ demo.

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/drift/`](../../src/main/java/dev/krillin/sparkplug/drift/)
- Shell/demo: [`DriftMonitor.java`](../../src/main/java/dev/krillin/sparkplug/DriftMonitor.java), [`DriftMonitorDemo.java`](../../src/main/java/dev/krillin/sparkplug/DriftMonitorDemo.java)
- Prior: ADR-0007 (schema registry gate), ADR-0009 (Kafka egress DLQ gate)
