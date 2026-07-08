# Heimdall — Governance Engine Extraction (Design)

**Date:** 2026-07-08
**Status:** Design (approved for spec review)
**Scope:** Extract the three governance obligations from `sparkplug-governance-lab` into a standalone, independently-runnable governance engine, `heimdall`.

---

## 1. Motivation

`sparkplug-governance-lab` today mixes two things:

1. A **Sparkplug B experimentation lab** — codecs, a Kafka sink, drift/liveness monitors, OPC-UA interop/type-mapping experiments, edge-node smoke demos.
2. The **governance implementation** — the three obligations the blog series calls the "three bills":
   - **① 데이터 정의 (data definition)** — UDT/schema backward-compatibility validation (`SchemaGate`, `CompatibilityChecker`, `UdtDefinition`, `SemVer`).
   - **② 명령 인가 (command authorization)** — deny-by-default authorization (`CommandPolicyGate` for CI lint; `CommandAuthorizer` + OPA for runtime; broker-ACL projection).
   - **③ 데이터 리니지 (data lineage / version provenance)** — mint + verify a content-addressed reference (content hash + commit SHA) for definitions (`RecipePublish`, `RecipeManifest`), and stamp it on execution.

The governance implementation is the shippable product; the lab is a demonstrator. They deserve to be separate deployables. The apply-gateway that guards the IT→OT write boundary (`bridge/NcmdOpcUaBridge`, already runnable via `NcmdOpcUaBridgeMain`) is the runtime heart of that product.

**Naming.** The governance project is referred to conceptually as *Bifrost* (the governed IT↔OT bridge — an umbrella concept, not a repo). The concrete, independently-runnable governance engine is **Heimdall** — the gatekeeper that guards the bridge. Verðandi (a separate field-data tool) is explicitly out of scope for this track.

## 2. Goals / Non-Goals

**Goals**

- A standalone public repo `heimdall` (Apache-2.0) that owns all three governance obligations.
- Heimdall runs in **two modes** over one shared rule-model:
  - **CI-lint mode** — schema compatibility (①), policy well-formedness (②), provenance publish (③), as PR-time gate CLIs.
  - **Runtime bridge mode** — a long-running write-boundary daemon: NCMD → authorize (②) → apply to OPC-UA → stamp provenance (③).
- `sparkplug-governance-lab` becomes a thin consumer/demo. It depends on Heimdall **only through a minimal `heimdall-model` artifact** (the shared pure value types) and otherwise **invokes the gate CLIs / bridge daemon by process** — no dependency on the engine logic (`core`/`gates`/`bridge`). See §5 for why a code-level model dependency is unavoidable and correct.
- Existing verification gates keep passing, re-homed under Heimdall.
- The cross-repo relationship "Heimdall publishes the ③ reference ← `resequence-twin-lab` independently verifies it" is preserved (publisher changes from the lab to Heimdall).

**Non-Goals**

- Building the standalone-packaging *deployment* concerns beyond a runnable jar/CLI (containerization, vendor-server certificate lockdown, provenance-at-gateway hardening remain design/roadmap).
- Touching Verðandi (koshei) or its transaction/saga concerns.
- Real-plant / real-PLC / certificate-secured OPC-UA validation (stays hypothesis; simulator validation only).
- Rewriting the OPA policy semantics — the existing `command_authz.rego`/`.wasm` move as-is.
- Moving the OPC-UA interop/type-mapping experiments (`opcua/`) — they are lab experimentation, not governance (see §4).

## 3. Architecture — one engine, four internal modules

The primary decomposition seam is **the moment of governance (authoring vs runtime)**, *not* the three bills. The three bills each have an authoring facet; ② and ③ additionally have a runtime facet. Splitting by bill would scatter the shared rule-model and force three deployables over the same Sparkplug/OPC-UA plumbing. Splitting by moment keeps a single rule-model that both the validator and the enforcer share.

**Invariant:** the code that *validates* a rule (gates) and the code that *enforces* it (bridge) share the same `core`/`model` — so a policy that passes CI cannot be interpreted differently at runtime.

Maven multi-module reactor, four modules:

### 3.0 `heimdall-model` — the shared rule vocabulary (pure value types + serde)

- The **data types** the rules are expressed in, their Jackson (de)serialization, and the filesystem accessor that loads/stores those types in the definition registry. No evaluation logic, no OPA runtime, no OPC-UA, no MQTT.
- Schema vocabulary: `UdtDefinition`, `Member`, `Param`, `SemVer`, `Verdict`, `Violation`, `CompatMode`, `TemplateAdapter`, `JsonMapperFactory`.
- Command vocabulary: `CommandPolicy` (+ `Rule`/`Target`/`Constraint`/`Decision`/`CommandRequest`), `AclEntry`, `AclMapperFactory`.
- Provenance vocabulary: `RecipeManifest` and the provenance record types.
- Registry accessor: `DefinitionStore` — the ~60-line disk-based JSON registry reader/writer (`latest`/`load`/`promote`/`policyMode`) for `UdtDefinition` over the `registry/` layout. It is the canonical loader of the shared model, and its only dependencies are Jackson + the model types — so it belongs with the vocabulary, not with the evaluators. Both Heimdall (core/gates) and the lab (drift/kafka) read the registry through it.
- Dependencies: Jackson + the JDK. (No OPA/OPC-UA/MQTT.)
- **This is the only Heimdall module the lab depends on** (as a Maven artifact) — because these types (and the registry accessor) are the in-process vocabulary the lab's own Sparkplug/Kafka/drift code speaks (see §5).

### 3.1 `heimdall-core` — the evaluators

- The **behavior** that acts on `model`: the pure evaluators. No CI framework, no daemon.
- ① `CompatibilityChecker`; ③ `RecipeDefinitionStore`, `RecipePublish` (mint), content-hash/provenance computation (reading the registry via `heimdall-model`'s `DefinitionStore`); ② `CommandAuthorizer`, `OpaCommandAuthorizer` (WASM-in-JVM), `BrokerAclProjector`, and the `opa/*.rego`,`*.wasm` resources.
- Dependencies: `heimdall-model` + the OPA WASM runtime. **No OPC-UA / MQTT.**

### 3.2 `heimdall-gates` — the authoring face (CLIs)

- Thin command-line entrypoints over `core`, run in PR pipelines:
  - `SchemaGate` (①) — reject a backward-incompatible UDT change.
  - `PolicyGate` (②-lint; today's `CommandPolicyGate`) — reject a malformed / non-deny-by-default command policy.
  - `ProvenancePublish` (③) — mint the content-addressed reference (content hash + commit SHA) for a committed definition and materialize the manifest; a companion verify path.
- Depends on `core` (+ `model`). **No OPC-UA / MQTT dependency** (a build tool, not a service).

### 3.3 `heimdall-bridge` — the runtime face (daemon)

- The long-running write-boundary application (today's `NcmdOpcUaBridge` + `Applier` + `OpcUaApplier` + `NcmdResponse` + `NcmdOpcUaBridgeMain`, with `RogueNcmd` as a test aid).
- Flow: subscribe to Sparkplug **NCMD** → **authorize** via `core` (deny-by-default) → **apply** to the OPC-UA server → **stamp provenance** (③) on the executed write → emit response/audit.
- Depends on `core` (+ `model`) + Eclipse Milo (OPC-UA client) + Eclipse Tahu (Sparkplug).
- Configuration by env/args (broker, OPC-UA endpoint, Sparkplug identity, policy path). **koshei-named legacy defaults are removed** (`OPCUA_URL`, `SPB_GROUP=Koshei:Line1`, `POLICY_PATH=registry/koshei-line1-policy.json` → neutral defaults).

### 3.4 Examples (in Heimdall, not the lab)

Governance demos move to Heimdall as its own examples: `SchemaGateDemo`, `CommandAclDemo` (uses `BrokerAclProjector`), `GuardedEdgeNode` (a Tahu edge node that enforces command authorization via `CommandAuthorizer`), `InteropEdge`, `InteropHost`.

## 4. What moves, what stays — decided against the real import graph

The move/stay split below is fixed by the actual import graph (verified 2026-07-08), not deferred to migration.

**Moves into `heimdall`:**

| Target module | Classes |
|---|---|
| `heimdall-model` | `schema/`: UdtDefinition, Member, Param, SemVer, Verdict, Violation, CompatMode, TemplateAdapter, JsonMapperFactory, **DefinitionStore** (registry accessor) · `acl/`: CommandPolicy, Rule, Target, Constraint, Decision, CommandRequest, AclEntry, AclMapperFactory · RecipeManifest |
| `heimdall-core` | `schema/`: CompatibilityChecker, RecipeDefinitionStore, RecipePublish · `acl/`: CommandAuthorizer, BrokerAclProjector · `acl/opa/`: OpaCommandAuthorizer, Context, OpaPolicy · resources `opa/*.rego`,`*.wasm` |
| `heimdall-gates` | SchemaGate (①), CommandPolicyGate→PolicyGate (②), RecipePublish CLI→ProvenancePublish (③) |
| `heimdall-bridge` | `bridge/`: NcmdOpcUaBridge, Applier, OpcUaApplier, NcmdResponse, NcmdOpcUaBridgeMain, RogueNcmd |
| Examples | SchemaGateDemo, CommandAclDemo, GuardedEdgeNode, InteropEdge, InteropHost |

Ported tests: `CommandAuthorizerTest`, `CommandPolicyGateTest`, `OpaCommandAuthorizerTest`, `SchemaGateTest`, the compatibility/recipe tests, `NcmdOpcUaBridgeTest`, `NcmdBridgePolicyTest`.

**Stays in `sparkplug-governance-lab`** (the Sparkplug B lab / demonstrator):

- `spb40/` codec experiments, `kafka/` UNS sink, `drift/` monitors, edge-node/host smokes (`EdgeNode`, `HostApp`, `Smoke`, `MqttSmoke`, `SessionDemo`, `PrimaryHost`, `RebirthCmd`, `StateStoreForwardDemo`, `StolenSessionDemo`, `LateJoinerExperiment`, `SparkplugToJsonBridge`, `JsonBridgeDemo`, `UnsToKafkaDemo`, `DriftMonitor`, `DriftMonitorDemo`, `Spb40Demo`, `SfEdgeNode`, `UdtDemo`).
- **The entire `opcua/` package (25 classes + 5 tests) plus `OpcUaUdtBridgeDemo`.** Rationale, corrected by import check: `bridge/OpcUaApplier` and `NcmdOpcUaBridge` import **only** Eclipse Milo — zero `dev.krillin.sparkplug.opcua.*`. The `opcua/` package's *only* main-source consumer is `OpcUaUdtBridgeDemo`. It is OPC-UA↔UDT type-mapping/flattening/loss-ledger interop experimentation, not one of the three governance obligations, and the runtime bridge does not need it. So `opcua/` + its demo stay in the lab as an all-or-nothing unit.
- These staying packages depend only on `heimdall-model`: they import its value types, and `DriftMonitor` (production) / `DriftMonitorDemo` / `UnsToKafkaDemo` read the registry through its `DefinitionStore`. No `heimdall-core`/`gates`/`bridge` dependency is introduced (see §5).

Nothing in the split is left to "audit during migration"; the plan's file list is complete as above.

## 5. Cross-repo relationships

- **Why the lab needs a code dependency on `heimdall-model` (not CLI-only).** The lab's staying packages speak the schema/command value types as in-process Java objects: `kafka/ContractValidator.validateMetric(UdtDefinition, …)`, `kafka/RecordBuilder`, `kafka/SparkplugToKafkaBridge`, `drift/SchemaDriftDetector`, `spb40/` (DefinitionCodec, DefinitionPublisher, SchemaRef, SchemaResolver, ThinCodec, Spb40Edge), and `opcua/` all import `UdtDefinition`/`Member`/`Param`/`SemVer`. A CLI/process boundary cannot pass a `UdtDefinition` *object* across. Duplicating the model in the lab would break the "single rule-model" invariant (§3) and the provenance byte-agreement guard (below). Therefore the lab depends on the minimal `heimdall-model` artifact, and on nothing else in Heimdall.
- **Heimdall ↔ lab coupling, precisely:** lab → `heimdall-model` (Maven artifact: value types + the `DefinitionStore` registry accessor); lab → gate CLIs + bridge daemon (by process, for its gate scripts and runtime demos). No lab dependency on `heimdall-core`/`gates`/`bridge`. (`DriftMonitor`, a staying production monitor, reads the registry via `DefinitionStore`; keeping `DefinitionStore` in `heimdall-model` rather than `heimdall-core` is what preserves the model-only invariant — it is a serde-level registry accessor, not an evaluator.)
- **`heimdall-model` distribution:** published for the lab to consume — `mvn install` to the local repo for development; a pinned version tag / GitHub Packages for CI. Cross-repo model versioning is an accepted, managed cost (the model is small and changes rarely).
- **Heimdall → `resequence-twin-lab`:** Heimdall's `ProvenancePublish` mints the canonical ③ reference; the twin independently reads the published bytes, recomputes the hash, and verifies against the manifest (the existing v3 two-witness relationship; only the *publisher* changes from the lab to Heimdall). Raw-byte hashing (`readBytes`/committed-blob bytes/`sha256sum`) is preserved exactly so publisher and consumer agree cross-platform. The twin's independent-recompute test is the cross-repo guard.
- **Out of scope:** Verðandi (koshei) — no dependency in either direction.

## 6. Migration phasing (executed by the plan, not this spec)

The spec defines the **target architecture**; the implementation plan sequences it so each step is independently verifiable:

1. **Stand up `heimdall`** — new repo, `heimdall-model` + `heimdall-core` + `heimdall-bridge` + the runtime NCMD gate; get it standalone-green against a simulator OPC-UA. koshei-named defaults removed.
2. **Move the authoring gates** — `SchemaGate` (①), `PolicyGate` (②-lint), `ProvenancePublish` (③) into `heimdall-gates`; port their tests; gates green.
3. **Thin out the lab** — remove the moved packages from `sparkplug-governance-lab`; add a `heimdall-model` dependency for the staying packages; re-point the lab gate scripts to Heimdall CLIs/daemon; confirm the lab still builds and its remaining demos run; confirm `resequence-twin-lab`'s ③ verification passes against Heimdall-published references.

## 7. Success criteria (controller-direct verification)

- `heimdall` multi-module build green; unit tests green (the ported tests in §4).
- Gate scripts live **in the `heimdall` repo** (`scripts/`) and each `[GATE] PASS`:
  - `run-schema-gate` — a backward-incompatible UDT change is rejected; a compatible one passes.
  - `run-command-authz-gate` — deny-by-default: an unlisted command is refused; a policy-permitted one is allowed; operational-context constraints evaluated.
  - `run-provenance-gate` — publish mints a reference; an independent recompute verifies == manifest; a tampered blob 409s.
  - `run-ncmd-runtime-gate` — the bridge authorizes an NCMD, applies to a simulator OPC-UA (confirm-by-read), stamps provenance, and refuses an unauthorized/rogue NCMD.
- `sparkplug-governance-lab` still builds after the governance packages are removed (now depending on `heimdall-model`); its remaining demos run. Its re-pointed gate script (`run-r2-ncmd-gate.sh`) drives the Heimdall bridge.
- `resequence-twin-lab`'s ③ verification passes against a Heimdall-published reference.

## 8. Testing strategy

- **model** — serde round-trip tests for the value types (no logic).
- **core** — pure unit tests (no I/O): compatibility matrix, authorizer decisions (incl. OPA context cases), broker-ACL projection, hash/provenance determinism over raw bytes.
- **gates** — CLI-level tests: exit-code + rejection on bad input, success on good input; the provenance publish/verify round-trip.
- **bridge** — the existing bridge tests against an embedded/simulator OPC-UA + a rogue-NCMD refusal test; the runtime gate script for end-to-end.
- **cross-repo** — a lab gate that drives Heimdall as a process; the twin's independent-verify test against a Heimdall manifest.

## 9. Risks & mitigations

- **CI-lint vs runtime drift** — the very thing we're preventing. *Mitigation:* both faces depend on `core`/`model`; no duplicated rule-model.
- **Cross-repo model coupling.** The lab now has a compile dependency on `heimdall-model`. *Mitigation:* keep the model minimal (value types + serde only, no logic); pin a version for CI; the model changes rarely. This is the honest, minimal cost of a shared vocabulary — smaller than the drift risk of duplication.
- **Provenance byte-agreement across the publisher change.** *Mitigation:* preserve raw-binary hashing exactly; keep the twin's independent recompute test as the cross-repo guard.
- **Scope creep into deployment hardening.** *Mitigation:* Non-Goals fence containerization/vendor-lock/provenance-at-gateway as roadmap.

## 10. Open questions (resolved)

- Repo boundary → **separate public repo** `heimdall` (Apache-2.0).
- Ownership of the three bills → **all three in Heimdall** (governance engine), CI-lint + runtime modes.
- Internal decomposition → **by moment**: `model` (shared vocabulary) / `core` (evaluators) / `gates` (CLIs) / `bridge` (daemon) — not by bill.
- lab consumption → **`heimdall-model` Maven artifact (value types + the `DefinitionStore` registry accessor) + gate CLIs / bridge daemon by process.** No dependency on `core`/`gates`/`bridge`. (This refines the earlier "no code dependency" intent: the shared *value types* must be a code dependency because the lab's own Sparkplug/Kafka/drift code passes them as objects; the engine *logic* is not a dependency.)
