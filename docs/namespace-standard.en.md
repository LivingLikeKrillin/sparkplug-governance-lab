# UNS Namespace Governance Standard (draft v0.1) (English translation)

> Status: 🟢 draft. A governance standard synthesizing the PoC experiments (SessionDemo / LateJoiner / Udt / StateStoreForward / StolenSession / JsonBridge) and ADR-0001~0012.
> Purpose: govern topics, identifiers, the data model, access, and state consistently across an enterprise UNS.
> *(Original Korean: [namespace-standard.md](namespace-standard.md))*

## 1. Scope

A Sparkplug B (3.0) UNS on HiveMQ + Ignition (+Cirrus Link). Covers: topic namespace, identifiers, metric/UDT schemas, aliases, access control, state/failover.

## 2. ISA-95 ↔ Sparkplug topic mapping

Sparkplug's fixed 4 levels `spBv1.0/{group}/{msgtype}/{edge}[/{device}]` vs ISA-95's 6 (Enterprise/Site/Area/Line/Cell/Device).

- **Convention:** encode the upper hierarchy into `group_id` with a separator.
  - `group_id` = `"{Enterprise}:{Site}:{Area}"` (e.g. `Acme:Busan:Press`)
  - `edge_node_id` = `"{Line}:{Cell-or-Gateway}"` (e.g. `L1:GW3`)
  - `device_id` = the physical equipment (e.g. `Press01`)
  - Structure below the device goes into the **metric path** (e.g. `Hydraulics/Pump/Pressure`).
- **Trade-off (stated):** group_id encoding is not a clean native hierarchy → **partial wildcards are impossible** (no "subscribe to a whole Area"). Cross-cutting analytical queries are served by the JSON bridge (`uns/`, ADR-0004) or consumer-side indexing instead.
- The separator (`:`) is reserved inside identifiers — forbidden character in §3 naming.
- **OPC UA sources (ADR-0010):** when normalizing an OPC UA browse hierarchy (server-local `ns=` indices) into ISA-95 UNS paths, same-named BrowseNames differing only in namespace collide in the flat metric space → disambiguation required. NodeId→alias follows §6 + the ADR-0010 convention (flattening order `alias = i+1`; multi-server namespacing out of scope).

### 2.1 Kafka egress mapping (extending across the IT boundary, ADR-0009)

When the UNS crosses into IT analytics (Kafka), the same ISA-95 mapping extends into topic names:

- **Kafka topic** = `uns.{Enterprise}.{Site}.{Area}.{Line}.{Cell}` (the `:` separator of group/edge becomes `.`).
- **Message key** = `{Device-or-Cell}/{metricPath}` (metric identity). → **A log-compacted topic becomes a last-known-value store**: Sparkplug RBE's current-state semantics (only changes are transmitted) are preserved by Kafka compaction. A consumer reading from the beginning recovers the latest value of every metric.
- **Contract violations** (type mismatch / unknown metric, checked against the ADR-0007 registry) → **DLQ topic** `uns.dlq` (the main topic stays clean).
- State: NBIRTH establishes initial state; NDEATH emits STALE tombstones (not key deletion — staleness is exposed as a value).

## 3. Identifier naming convention (globally unique) (ADR-0006)

- `group_id` / `edge_node_id` / `device_id` are **globally unique**; a central **registry** issues them and tracks ownership.
- The derived **MQTT client-id is unique too** (= the `edge-{group}-{edge}` convention). A collision causes takeover → NDEATH storms.
- Allowed: `[A-Za-z0-9_-]`, hierarchy separator `:` (group only). Whitespace, MQTT wildcards (`+`, `#`), and `/` are forbidden.
- Operate **flap detection/alerting** on the broker (frequent reconnects).

## 4. Metric naming & data contracts

- Metric path = the semantic hierarchy inside the equipment (`Subsystem/Component/Signal`).
- Each metric: datatype, engineering unit (properties), and range documented as a data contract.
- The bridge's JSON path (ADR-0004) = `uns/{group}/{edge}/{metricPath}` — kept 1:1 with the Sparkplug metric path (no drift).

## 5. UDT (Template) schema governance & versioning (ADR-0005/0007)

- UDT definitions: an **external registry + review** is the source of truth (NBIRTH `_types_/` is wire truth).
- **SemVer enforced (by governance):** member add = minor (backward compatible); remove / type change = major → **split into a new `templateRef`** (protecting v1 consumers).
- Consumers compare the received version's major against their expectation; unknown majors are rejected/alerted. CI gates additive-only changes.
- OPC UA information-model mapping follows the ADR-0010 convention.
- **OPC UA ObjectType → UDT source mapping (ADR-0010, implemented):** flatten the ObjectType (subtype inheritance + `HasInterface` multiple inheritance) to derive the UDT member set (`TypeFlattener`: most-derived override / interface dedup with deterministic conflict fallback / ObjectType-own wins). **Not lossless** — a per-member loss ledger (`LossLedger`) plus side-channels (`ua_statuscode` UInt32 verbatim / `ua_ticks` Int64 original 100 ns) preserve the lossless truth. #607 engUnit ↔ OPC UA EngineeringUnits/EURange; #603 quality ↔ StatusCode severity (lossy projection). Code `src/.../opcua/`, demo `OpcUaUdtBridgeDemo`.

> **Enforcement mechanism (ADR-0007):** the UDT versioning/ownership rules above are enforced not by this document but by the **schema registry gate**: `registry/udt/<ref>/<semver>.json` (source of truth) + `policy.json` (default FORWARD) + the `SchemaGate` CI gate (breaking → non-zero exit). Code/demo: `src/.../schema/`, `SchemaGateDemo.java`.

## 6. Alias assignment policy

- Aliases are **per-edge integers, valid after NBIRTH**. The NodeId/metric-name ↔ alias mapping is kept **stable and unique** per edge in the registry (identical across restarts/rebirths).
- Protecting consumers that missed an NBIRTH: re-acquire the mapping via aware-certs (ADR-0002) or a rebirth.

## 7. Access control / command governance

- Topic ACLs: publish/subscribe permissions at group/edge granularity.
- **NCMD/DCMD (OT writeback)** publish permission is least-privilege + audited — it is the command path into OT equipment, the security core.
- Read permissions on `$sparkplug/certificates/#` (ADR-0002) and `uns/#` (ADR-0004) are ACL subjects too.

**Layered enforcement mechanism (ADR-0011).** The NCMD command name lives in the payload metric, not the topic (Rebirth, Reboot, and Setpoint all share the one topic `spBv1.0/{group}/NCMD/{edge}`), so a broker topic ACL can structurally enforce only **node-level reachability** (who may command this node at all). Per-command / per-value authorization is possible only at the edge, which sees the payload. Therefore **one single policy source** (`registry/command-policy.json`, deny-by-default) is projected onto **two enforcement points plus one CI gate**: ▶ enforcement ① `CommandAuthorizer` (edge layer — command allowlist + value range/type, fail-closed, runtime enforcement) ▶ enforcement ② broker topic ACL (identity → node reachability, enforced by MQTT auth) ◆ artifact `BrokerAclProjector` (produces the broker-ACL representation for ② — principal → PUBLISH on NCMD topics, `*` → MQTT `+`; projection/validation only, no live RBAC) ◆ CI `CommandPolicyGate` (shift-left lint, non-zero exit). Code `src/main/java/dev/krillin/sparkplug/acl/`, live demo `CommandAclDemo`.

## 8. State & failover governance (ADR-0001/0002)

- **Store-and-forward gates on a single system of record (historian)** as the primary host (completeness). Edges bind to a single `primaryHostId`.
- **Current state for arbitrary consumers comes from aware-broker certs (ADR-0002)** — avoiding rebirth storms. This role split is the answer to the n:1 ↔ n:m gap.
- Consumers **subscribe first, then declare STATE online** (avoiding the race where a flush arrives before the subscription completes).

## 9. Observability & drift governance (ADR-0012)

- **Pre-deployment (ADR-0007) ↔ runtime (ADR-0012) loop.** The ADR-0007 `SchemaGate` enforces data contracts fail-closed in CI before deployment. ADR-0012 observes the NBIRTHs/liveness actually flowing at runtime — together they close the "enforced in CI + watched at runtime" loop.
- **DriftMonitor = a passive observer of `spBv1.0/#` (detect-only).** Compares NBIRTH UDT definitions (`_types_/<ref>`, extracted via `TemplateAdapter`) against the `DefinitionStore.latest(ref)` source of truth to detect schema drift: `UNREGISTERED` / `VERSION_DRIFT` plus three member kinds (`UNKNOWN_MEMBER` / `MISSING_MEMBER` / `TYPE_DRIFT`). Raw deviations (not CompatMode semantics).
- **Staleness.** Tracks each node's last-observed time (injected clock) → silence beyond a threshold yields `STALE`. NDEATH excludes a node; rebirth clears death.
- **Governance health snapshot.** Aggregates total nodes, conformant nodes, conformance rate, drift counts per kind, stale count (append-only audit).
- **Detect-only — OT data is never dropped** (no republish/drop/DLQ — the principle that OT is observed, not blocked, at runtime). ADR-0009 (§2.1) is the data-plane DLQ gate; ADR-0012 is the observability layer — separate roles.
- Limits: node-level / NDATA value drift out of scope / single-process PoC / reuses the ADR-0007 registry. Code `src/.../drift/`, demo `DriftMonitorDemo`.

## Appendix — evidence

PoC code: `src/` at the repo root. ADRs: `docs/adr/`.
