# Bifrost — Governance Product (Design)

**Date:** 2026-07-08
**Status:** Design (approved for spec review — full revision after design dialogue)
**Scope:** Extract the governance implementation out of `sparkplug-governance-lab` into a standalone public product, `bifrost`, whose contract with every consumer is a language-neutral **data/wire format** — never shared code.

> Supersedes the earlier `2026-07-08-heimdall-governance-engine-design.md` draft, which modeled Heimdall as the whole engine with a shared `heimdall-model` Maven artifact. That coupling was rejected: it forces every consumer to be a JVM, contradicting the independence goal.

---

## 1. Motivation & naming

`sparkplug-governance-lab` today entangles several unrelated concerns. This design separates the **governance product** out and names the surrounding pieces so their boundaries are explicit. The naming follows the Norse world-tree Yggdrasil:

| Name | Role | This spec |
|---|---|---|
| **Bifrost** | The governance **product** (repo): governs definitions/policies and guards the IT→OT write boundary. The rainbow bridge between realms. | **YES — this spec** |
| **Heimdall** | A module *inside* Bifrost: the runtime write-boundary **daemon** (the gatekeeper who guards the bridge). Southbound governed writes. | YES (a module) |
| **Mímir** | The northbound **modeler** (design-time): reads OT type spaces → *proposes* canonical UDT definitions to Bifrost's ① gate. Keeper of the well of wisdom (= the definitions/knowledge). A separate product. | No — separate track |
| **Muninn** | The northbound **feeder** (runtime): consumes *governed* definitions → streams live OT instance data → the UNS. Odin's raven "memory" — observes the world and brings knowledge back to the centre (OT→SSoT, direction matches). A separate product. | No — separate track |
| **Mjölnir** | A **field EAI/ETL platform**: a Node-RED replacement for gluing heterogeneous sources, with Saga (idempotency + compensation) added. A separate product. | No — separate track |

Bifrost governs; Heimdall (inside it) enforces at the write boundary; Mímir *proposes* canonical definitions (upstream of governance); Muninn *feeds* the UNS with data conforming to the governed definitions (downstream); Mjölnir glues field integrations with durable saga. **Distinct applications**, each consuming the others only through published data/wire contracts.

**Canonical-model ownership (three layers, so Bifrost's boundary is unambiguous):**
- **Authority / ownership → Bifrost.** "Owning the canonical model" means deciding what a valid definition *is*, versioning it, and admitting it to the registry-of-record — which is exactly what ① does. Bifrost is the single authority; it publishes the format spec (§4).
- **Production → Mímir (or a human).** Proposes definitions (bottom-up from OT, or top-down). Upstream of governance; not the owner.
- **Representation → each consumer.** The lab, Muninn, `resequence-twin-lab` each hold their own local types conforming to the published format. Code-ownership (each has its own types) ≠ model-authority (Bifrost, single).
- **Invariant:** production and feeding are separated by governance — a proposed definition is not authoritative (and Muninn must not feed data against it) until Bifrost's ① gate admits it. No path bypasses Bifrost.

The three governance obligations (the blog's "three bills") that constitute Bifrost:

- **① 데이터 정의** — UDT/schema backward-compatibility validation.
- **② 명령 인가** — deny-by-default command authorization (OPA), incl. broker-ACL projection.
- **③ 데이터 리니지** — mint + verify a content-addressed reference (content hash + commit SHA) for definitions and stamp it on execution.

## 2. Goals / Non-Goals

**Goals**

- A standalone public repo `bifrost` (Apache-2.0) owning the three governance obligations.
- Internally three *kinds* of artifact (see §3): a `core` **library**, the `heimdall` **daemon** (independent app), and `gates` **CI tooling** (CLIs).
- **Every external consumer interacts with Bifrost through a language-neutral data/wire contract only — never shared code.** Consumers (the lab, `resequence-twin-lab`, a future Mímir, anything in Go/Node/Java) model Bifrost's published formats with their own types. See §4.
- The definition/policy **format specifications** are first-class published deliverables, and Bifrost's own ① gate governs their evolution (the anti-drift mechanism).
- Existing verification behavior is preserved, re-homed under Bifrost as gate scripts.
- The cross-repo provenance relationship "Bifrost publishes the ③ reference ← `resequence-twin-lab` independently verifies it (raw bytes → recompute hash)" is preserved.

**Non-Goals**

- The northbound feeder (**Mímir**) and the field EAI/ETL platform (**Mjölnir**) — separate tracks.
- Deep redesign of `sparkplug-governance-lab` (its future as a Mímir prototype / experimentation ground) — a follow-up. This spec only reconciles it enough to keep it building.
- Deployment hardening beyond a runnable jar/CLI (containerization, vendor-server cert lockdown, provenance-at-gateway) — roadmap.
- Real-plant / real-PLC / certificate-secured OPC-UA validation — hypothesis; simulator only.
- Rewriting OPA policy semantics — `command_authz.rego`/`.wasm` move as-is.
- Any shared Java artifact for consumers (explicitly rejected — see §4).

## 3. Architecture — one repo, three kinds of artifact

`sparkplug-governance-lab` bundled a long-running service, a set of CI validators, and a shared rule library under one roof. They are **different kinds of thing** with different lifecycles; Bifrost separates them as modules while keeping them in one repo so the daemon and the CLIs share one rule library (both JVM — internal sharing is fine and does not leak outward).

**Invariant:** the code that *validates* a rule (gates) and the code that *enforces* it (Heimdall) share `core` — so a policy that passes CI cannot be interpreted differently at runtime.

### 3.1 `core` — the rule library (not an app, not a tool)

- The **rule model** (value types + serde) and the **evaluators** over it. Long-running nothing; invoked-as-a-CLI nothing. A library the daemon and the gates both call.
- Rule model: `UdtDefinition` (+ `Member`/`Param`/`SemVer`/`Verdict`/`Violation`/`CompatMode`/`TemplateAdapter`/`JsonMapperFactory`), the `DefinitionStore` registry accessor, `CommandPolicy` (+ `Rule`/`Target`/`Constraint`/`Decision`/`CommandRequest`/`AclEntry`/`AclMapperFactory`), `RecipeManifest`.
- Evaluators: ① `CompatibilityChecker`; ② `CommandAuthorizer`, `OpaCommandAuthorizer` (WASM-in-JVM), `BrokerAclProjector`; ③ `RecipeDefinitionStore`, `RecipePublish` (mint), content-hash/provenance computation.
- Also owns the **published format specifications** (§4) as resources: the definition & policy JSON schemas, the manifest + hashing spec.
- Dependencies: Jackson + the OPA WASM runtime. No OPC-UA, no MQTT.

### 3.2 `heimdall` — the runtime daemon (the independent application)

- The long-running write-boundary service: subscribe to Sparkplug **NCMD** → **authorize** via `core` (deny-by-default) → **apply** to the OPC-UA server → **stamp provenance** (③) → emit response/audit.
- Built from today's `bridge/` (`NcmdOpcUaBridge`, `Applier`, `OpcUaApplier`, `NcmdResponse`, `NcmdOpcUaBridgeMain`; `RogueNcmd` as a test aid).
- Config by env/args (broker, OPC-UA endpoint, Sparkplug identity, policy path). **koshei-named legacy defaults removed** (`OPCUA_URL`, `SPB_GROUP=Koshei:Line1`, `POLICY_PATH=registry/koshei-line1-policy.json` → neutral defaults).
- Dependencies: `core` + Eclipse Milo (OPC-UA) + Eclipse Tahu (Sparkplug).
- **This is what "Heimdall = an independent application" means** — a deployable daemon. Consumers reach it over the Sparkplug/OPC-UA wire, never as a library.

### 3.3 `gates` — the CI tooling (CLIs, not a service)

- Short-lived command-line validators/publisher, invoked by PR pipelines and exiting:
  - `SchemaGate` (①) — reject a backward-incompatible UDT change.
  - `PolicyGate` (②-lint; today's `CommandPolicyGate`) — reject a malformed / non-deny-by-default policy.
  - `ProvenancePublish` (③) — mint the content-addressed reference for a committed definition + a verify path.
- Depends on `core`. No OPC-UA / MQTT. Consumed by *invocation as a process* (a CI step runs the CLI over files), not as a library.

### 3.4 Examples (in Bifrost)

Governance demos become Bifrost's examples: `SchemaGateDemo`, `CommandAclDemo` (uses `BrokerAclProjector`), `GuardedEdgeNode` (an edge node enforcing authz via `CommandAuthorizer`), `InteropEdge`, `InteropHost`.

## 4. The published data contract — the only thing shared, and it is not code

Bifrost is an independent application, so **its contract with every consumer is a language-neutral data/wire format**, published explicitly. A consumer in any language (Go, Node, Java) models these formats with its own types; consumer-side types are not "duplication," they are what a consumer *is*.

Published contract deliverables (owned by `core`, versioned, governed by the ① gate):

- **Definition format** — the JSON schema of a `UdtDefinition` and the `registry/udt/<ref>/<version>.json` layout.
- **Policy format** — the JSON schema of a command policy (deny-by-default structure, rules/targets/constraints).
- **Provenance format** — the published ③ manifest layout **plus the hashing algorithm specification** (raw-byte sha256 over the committed blob), so any language can recompute and verify independently.
- **Wire behavior** — the Sparkplug B NCMD contract Heimdall enforces and the OPC-UA apply/confirm-by-read behavior (standard protocols; documented usage).

**Anti-drift = the product's own function.** Because consumers model these formats independently, format evolution could drift — and the mechanism that prevents it is exactly Bifrost's ① schema-compatibility gate plus the versioned published schema. Governing the contract's evolution *is* the value proposition.

**No shared Java artifact for consumers.** There is deliberately no `bifrost-model` published for consumers to depend on. `core` is internal to the Bifrost repo (shared only by `heimdall` and `gates`, both in-repo). `resequence-twin-lab` already consumes ③ this way (reads published bytes, recomputes the hash) — the correct pattern, now applied to all consumers.

## 5. What moves to Bifrost / what stays in the lab

Grounded in the real import graph (verified 2026-07-08).

**Moves into `bifrost`** — the governance *logic* and its rule model:

| Target | Classes |
|---|---|
| `core` | `schema/`: UdtDefinition, Member, Param, SemVer, Verdict, Violation, CompatMode, TemplateAdapter, JsonMapperFactory, DefinitionStore, CompatibilityChecker, RecipeDefinitionStore, RecipePublish, RecipeManifest · `acl/`: CommandPolicy(+Rule/Target/Constraint/Decision/CommandRequest), AclEntry, AclMapperFactory, CommandAuthorizer, BrokerAclProjector · `acl/opa/`: OpaCommandAuthorizer, Context, OpaPolicy · resources: `opa/*.rego`,`*.wasm`, the published format schemas |
| `heimdall` | `bridge/`: NcmdOpcUaBridge, Applier, OpcUaApplier, NcmdResponse, NcmdOpcUaBridgeMain, RogueNcmd |
| `gates` | SchemaGate (①), CommandPolicyGate→PolicyGate (②), RecipePublish CLI→ProvenancePublish (③) |
| examples | SchemaGateDemo, CommandAclDemo, GuardedEdgeNode, InteropEdge, InteropHost |

Ported tests: `CommandAuthorizerTest`, `CommandPolicyGateTest`, `OpaCommandAuthorizerTest`, `SchemaGateTest`, the compatibility/recipe tests, `NcmdOpcUaBridgeTest`, `NcmdBridgePolicyTest`.

> **Reading the table correctly:** the `schema/` **value types + `DefinitionStore`** in the `core` row are *copied* (bifrost gets its own canonical copy), **not deleted from the lab** — the lab retains its own copy as a consumer (§5 prose, §7.3). Only the **governance logic** (evaluators `CompatibilityChecker`/`RecipeDefinitionStore`/`RecipePublish`, all `acl/`, all `bridge/`) and the five moving demos are *removed* from the lab.

**Stays in `sparkplug-governance-lab`** — the Sparkplug B experiments + the Mímir (northbound feeder) prototype:

- **Mímir prototype:** the whole `opcua/` package (25 classes — OPC-UA browse / type-map / flatten / loss-ledger) + the whole `spb40/` package (codec, `DefinitionPublisher`, `SchemaResolver`, …) + edge nodes (`EdgeNode`, `SfEdgeNode`) + `OpcUaUdtBridgeDemo`. This is OT→canonical→UNS feeding — a separate role (§1), left as a prototype here.
- `kafka/` UNS sink, `drift/` monitors, and the Sparkplug smokes (`HostApp`, `Smoke`, `MqttSmoke`, `SessionDemo`, `PrimaryHost`, `RebirthCmd`, `StateStoreForwardDemo`, `StolenSessionDemo`, `LateJoinerExperiment`, `SparkplugToJsonBridge`, `JsonBridgeDemo`, `UnsToKafkaDemo`, `DriftMonitor`, `DriftMonitorDemo`, `Spb40Demo`, `UdtDemo`).

**The lab keeps its own value types.** The staying code (`spb40/`, `drift/`, `kafka/`, `opcua/`) speaks `UdtDefinition`/`Member`/`SemVer`/`CompatMode`/… and reads the registry via `DefinitionStore`. Per §4, the lab is a *consumer* and owns its own copy of these value types + registry reader — modeling the same published definition format, with **no dependency on `bifrost`**. Bifrost owns its own canonical copies in `core`; the two conform to the published schema. This is not accidental duplication — it is the polyglot-consumer contract (a Go/Node consumer would likewise define its own). The lab loses only the governance *logic* (evaluators, gates, authz, daemon), which its staying code never imported — only the moving demos did.

## 6. Consumer relationships (all via the data contract)

- **`sparkplug-governance-lab`:** owns its value types (its representation of the definition format); reads/writes the same `registry/` JSON layout; invokes Bifrost's gate CLIs and runs the Heimdall daemon as processes for its gate scripts / runtime demos. No code dependency on Bifrost.
- **`resequence-twin-lab`:** consumes the ③ published manifest — reads the bytes, recomputes the raw-byte hash, verifies against the manifest. Publisher changes to Bifrost; the mechanism is unchanged. This is the reference example of the §4 pattern.
- **Mímir (future):** proposes canonical UDT definitions derived from OT type spaces to Bifrost's ① gate (upstream producer). Data contract only.
- **Muninn (future):** consumes Bifrost's governed definitions to shape live OT data into conforming canonical instances and feed the UNS (downstream feeder). Data contract only. Separated from Mímir by Bifrost's governance (no bypass).
- **Mjölnir:** unrelated to this track.

## 7. Migration phasing (executed by the plan)

The spec defines the target; the plan sequences it so each step verifies independently:

1. **Stand up `bifrost`** — new repo; `core` (rule model + evaluators + published format schemas) + `heimdall` (daemon) + the runtime NCMD gate; standalone-green against a simulator OPC-UA; koshei-named defaults removed.
2. **Move the gates** — `SchemaGate` (①), `PolicyGate` (②), `ProvenancePublish` (③) into `gates`; port tests; gate scripts green.
3. **Reconcile the lab** — remove the moved governance *logic* + demos from `sparkplug-governance-lab`; keep its value types + `DefinitionStore` as lab-owned consumer types; re-point its gate scripts (`run-r2-ncmd-gate.sh`) to invoke the Bifrost Heimdall daemon / gate CLIs by process; confirm the lab still builds and its demos run; confirm `resequence-twin-lab`'s ③ verification passes against a Bifrost-published manifest.

## 8. Success criteria (controller-direct verification)

- `bifrost` multi-module build green; unit tests green (the ported tests in §5).
- Gate scripts live in the `bifrost` repo `scripts/`, each `[GATE] PASS`:
  - `run-schema-gate` — incompatible UDT change rejected; compatible passes.
  - `run-command-authz-gate` — deny-by-default: unlisted command refused; permitted allowed; operational-context constraints evaluated.
  - `run-provenance-gate` — publish mints a reference; an **independent** recompute verifies == manifest; a tampered blob 409s.
  - `run-ncmd-runtime-gate` — Heimdall authorizes an NCMD, applies to a simulator OPC-UA (confirm-by-read), stamps provenance, refuses an unauthorized/rogue NCMD.
- The published format schemas are present and the ① gate validates a change against them.
- `sparkplug-governance-lab` still builds (its own value types; no Bifrost code dependency); its remaining demos run; its re-pointed gate script drives the Heimdall daemon.
- `resequence-twin-lab`'s ③ verification passes against a Bifrost-published manifest.

## 9. Testing strategy

- **core** — pure unit tests: compatibility matrix, authorizer decisions (incl. OPA context), broker-ACL projection, hash/provenance determinism over raw bytes, format-schema conformance.
- **heimdall** — bridge tests against an embedded/simulator OPC-UA + a rogue-NCMD refusal; the runtime gate script end-to-end.
- **gates** — CLI tests: exit-code + rejection on bad input, success on good input; provenance publish/verify round-trip.
- **cross-repo** — a lab gate that drives the Heimdall daemon as a process; the twin's independent-verify test against a Bifrost manifest.

## 10. Risks & mitigations

- **Consumer/format drift** (each consumer models the format independently). *Mitigation:* the published, versioned format schema + Bifrost's ① compatibility gate — i.e. the product's own function. Consumers verify against the published schema.
- **CI-lint vs runtime drift** *(internal).* *Mitigation:* `heimdall` and `gates` share `core`; one rule library.
- **Provenance byte-agreement across the publisher change.** *Mitigation:* preserve raw-binary hashing exactly; the twin's independent recompute test is the guard.
- **Lab value-type divergence.** The lab's copy could diverge from the published format. *Mitigation:* the lab conforms to the published schema; a lab-side conformance check can consume the published schema. Divergence is caught the same way any consumer's is.
- **Scope creep** into Mímir / Mjölnir / deployment hardening. *Mitigation:* Non-Goals fence them.

## 11. Resolved decisions

- Repo → **separate public repo `bifrost`** (Apache-2.0); the governance product.
- "Heimdall" → the **runtime daemon module only** (the independent app), not the whole engine.
- Internal structure → three kinds: `core` (library) / `heimdall` (daemon) / `gates` (CLIs).
- Consumer contract → **language-neutral data/wire format only; no shared code artifact** (rejected the `bifrost-model` Maven dependency — it would force JVM consumers and betray independence). Consumers own their types; the format spec is the published contract; the ① gate governs its evolution.
- Northbound OT→UNS feeder (**Mímir**), field EAI/ETL (**Mjölnir**) → separate products/tracks, out of scope.
- Lab → keeps its own consumer value types; loses only governance logic; consumes Bifrost by process + data contract.
