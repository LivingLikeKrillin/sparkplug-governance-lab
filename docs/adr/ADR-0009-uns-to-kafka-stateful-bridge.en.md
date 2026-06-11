# ADR-0009 — Stateful UNS→Kafka governance bridge (English translation)

- Status: **Accepted (PoC)**
- Date: 2026-06-10
- Relations: reuses the ADR-0007 registry as a runtime conformance gate; direct successor of `SparkplugToJsonBridge` (stateful alias resolution, ADR-0004); extends the namespace standard §2 (ISA-95 mapping) across the IT boundary. Code `src/.../kafka/`, demo `UnsToKafkaDemo.java`. *(Original Korean: [ADR-0009-uns-to-kafka-stateful-bridge.md](ADR-0009-uns-to-kafka-stateful-bridge.md))*

## Context

An enterprise UNS has to cross from the OT boundary (Sparkplug/MQTT, report-by-exception, session state machine) into the IT analytics/streaming boundary (Kafka). Sparkplug is stateful (aliases are declared only in NBIRTH; NDATA is alias-only RBE) while Kafka is stateless append-only — a naive bridge cannot resolve aliases and leaks only partial state.

## Decision

1. **A stateful bridge (the backbone).** `UnsStateStore` reconstructs per-edge alias→name mappings plus last-known values. Even though NDATA (RBE) carries only what changed, the last-known store holds the complete current state. Without state there are no correct records.
2. **RBE ↔ Kafka log-compaction isomorphism.** With the message **key = metric identity** (`<cell>/<metric>`), a compacted topic *is* a last-known-value store → an IT consumer reading from the beginning recovers the latest value of every metric. OT state semantics translated into an IT streaming primitive.
3. **Contract validation → DLQ (second governance dimension).** The ADR-0007 registry (`DefinitionStore`) is reused for runtime conformance. Type mismatches / unknown metrics route to a DLQ (the main topic stays clean). Missing-member checks apply at birth only (consistent with RBE).
4. **ISA-95 → topics (third governance dimension).** Namespace standard §2 (`group=Ent:Site:Area`, `edge=Line:Cell`) → Kafka topic `uns.Ent.Site.Area.Line.Cell`.
5. **Carrying the state machine.** NBIRTH establishes state; NDEATH emits STALE tombstones (not drops — consumers see staleness explicitly).

## Honesty (important)

Production UNS→Kafka is typically Confluent + Avro + Schema Registry; this PoC is self-contained with JSON + the file registry of ADR-0007. The compaction demo runs single-node KRaft / single partition. **No seq reordering / out-of-order NDATA handling** (MQTT ordering assumed). At-least-once (exactly-once out of scope). Stated in the demo banner and here.

## Consequences

- Governance: alias resolution, last-known state, contracts, and the namespace are carried first-class across the IT boundary; violations are quarantined in the DLQ.
- A textbook application of async state-consistency patterns (latest-wins / correlation); the same integration shape as HiveMQ↔Kafka pipelines (e.g. Ignition 8.3 Event Streams).
- Limits: node-level focus (device mapping supported, demo is node-level), single contract mapping, no seq reordering, at-least-once. Producer sends are async fire-and-forget; failures are logged in the async callback (producer retries/idempotence at defaults — delivery guarantee stays at-least-once).

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/kafka/`](../../src/main/java/dev/krillin/sparkplug/kafka/)
- Demo: [`src/main/java/dev/krillin/sparkplug/UnsToKafkaDemo.java`](../../src/main/java/dev/krillin/sparkplug/UnsToKafkaDemo.java)
- Prior: ADR-0007 (registry), ADR-0004 (JSON bridge), [namespace-standard §2](../namespace-standard.en.md)
