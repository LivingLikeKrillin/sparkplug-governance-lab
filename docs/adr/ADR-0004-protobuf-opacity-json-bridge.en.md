# ADR-0004 — Protobuf opacity → JSON UNS bridge (dual namespace) (English translation)

- Status: **Accepted** (when non-Sparkplug consumers are needed)
- Date: 2026-06-03
- Evidence: direct experiment (`src/.../JsonBridgeDemo.java` + `SparkplugToJsonBridge.java`). *(Original Korean: [ADR-0004-protobuf-opacity-json-bridge.md](ADR-0004-protobuf-opacity-json-bridge.md))*

## Context

Sparkplug payloads are **binary protobuf** — MQTT Explorer and generic IT tools cannot read them, and raw Sparkplug has no retained current state either (ADR-0002). How do IT/analytics/plain-MQTT consumers use the data?

## Experiment (measured)

A bridge subscribes to `spBv1.0/{group}/#`, decodes, and republishes **retained JSON** to `uns/{group}/{edge}/{metric}`:

- NBIRTH metrics (Temperature/Pump/Running) → republished as JSON.
- NDATA (alias-only; raw = unreadable `50 B protobuf 08 c0 ce 87...`) → the bridge **resolves alias→name** and republishes JSON values.
- A late-joining plain-JSON consumer subscribing to `uns/#` gets **retained current values immediately on connect**.

## Findings

1. **Protobuf is opaque** — consumers that don't speak Sparkplug cannot use it directly.
2. **The bridge must be Sparkplug-stateful** — resolving NDATA aliases requires caching name↔alias from NBIRTH (birth tracking). It is not a dumb republish; if it misses a birth, aliases are unresolvable.
3. **JSON + retained = state-on-connect for arbitrary IT consumers** (extends the aware-cert idea of ADR-0002 into the plain-MQTT world).
4. **The price: two namespaces** (spBv1.0 protobuf + uns JSON) → drift/maintenance cost, and the bridge is a single point of failure.

## Decision (governance)

- When IT/plain consumers or human browsing are needed, publish retained current state to a parallel UNS via a **governed Sparkplug→JSON bridge**.
- Govern the bridge as a **first-class component**: monitor it, re-collect births via rebirth on restart, persist the alias cache.
- **Sparkplug-native consumers use aware-certs (ADR-0002); only non-Sparkplug consumers use the JSON bridge** — the same role-split thinking as ADR-0001.
- Govern the JSON namespace schema **together with UDTs (ADR-0005)** to prevent dual-definition drift.

## Consequences

- Bridge availability/state is operational risk (missing a birth breaks alias resolution).
- The spBv1.0 metric-path → uns path mapping is a namespace decision (namespace standard §4).
- Implementation lesson: the bridge must **separate** its subscribe and publish clients (avoiding the callback-thread publish deadlock — publishing from a Paho callback thread blocks the client's own message loop).

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/`](../../src/main/java/dev/krillin/sparkplug/) — `SparkplugToJsonBridge`, `JsonBridgeDemo`
- Related: ADR-0002 (aware certs), ADR-0005 (UDT governance)
