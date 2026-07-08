# Heimdall — Governance Engine Extraction (Design)

**Date:** 2026-07-08
**Status:** Design (approved for spec review)
**Scope:** Extract the three governance obligations from `sparkplug-governance-lab` into a standalone, independently-runnable governance engine, `heimdall`.

---

## 1. Motivation

`sparkplug-governance-lab` today mixes two things:

1. A **Sparkplug B experimentation lab** — codecs, a Kafka sink, drift/liveness monitors, edge-node smoke demos.
2. The **governance implementation** — the three obligations the blog series calls the "three bills":
   - **① 데이터 정의 (data definition)** — UDT/schema backward-compatibility validation (`SchemaGate`, `CompatibilityChecker`, `UdtDefinition`, `SemVer`).
   - **② 명령 인가 (command authorization)** — deny-by-default authorization (`CommandPolicyGate` for CI lint; `CommandAuthorizer` + OPA for runtime).
   - **③ 데이터 리니지 (data lineage / version provenance)** — mint + verify a content-addressed reference (content hash + commit SHA) for definitions (`RecipePublish`, `RecipeManifest`), and stamp it on execution.

The governance implementation is the shippable product; the lab is a demonstrator. They deserve to be separate deployables. The apply-gateway that guards the IT→OT write boundary (`bridge/NcmdOpcUaBridge`, already runnable via `NcmdOpcUaBridgeMain`) is the runtime heart of that product.

**Naming.** The governance project is referred to conceptually as *Bifrost* (the governed IT↔OT bridge — an umbrella concept, not a repo). The concrete, independently-runnable governance engine is **Heimdall** — the gatekeeper that guards the bridge. Verðandi (a separate field-data tool) is explicitly out of scope for this track.

## 2. Goals / Non-Goals

**Goals**

- A standalone public repo `heimdall` (Apache-2.0) that owns all three governance obligations.
- Heimdall runs in **two modes** over one shared rule-model:
  - **CI-lint mode** — schema compatibility (①), policy well-formedness (②), provenance publish (③), as PR-time gate CLIs.
  - **Runtime bridge mode** — a long-running write-boundary daemon: NCMD → authorize (②) → apply to OPC-UA → stamp provenance (③).
- `sparkplug-governance-lab` becomes a thin consumer/demo that **uses** Heimdall via CLI/process, with no code dependency.
- Existing verification gates keep passing, re-homed under Heimdall.
- The cross-repo relationship "Heimdall publishes the ③ reference ← `resequence-twin-lab` independently verifies it" is preserved (publisher changes from the lab to Heimdall).

**Non-Goals**

- Building the standalone-packaging *deployment* concerns beyond a runnable jar/CLI (containerization, vendor-server certificate lockdown, provenance-at-gateway hardening remain design/roadmap).
- Touching Verðandi (koshei) or its transaction/saga concerns.
- Real-plant / real-PLC / certificate-secured OPC-UA validation (stays hypothesis; simulator validation only).
- Rewriting the OPA policy semantics — the existing `command_authz.rego`/`.wasm` move as-is.

## 3. Architecture — one engine, three internal units

The primary decomposition seam is **the moment of governance (authoring vs runtime)**, *not* the three bills. The three bills each have an authoring facet; ② and ③ additionally have a runtime facet. Splitting by bill would scatter the shared rule-model and force three deployables over the same Sparkplug/OPC-UA plumbing. Splitting by moment keeps a single rule-model that both the validator and the enforcer share.

**Invariant:** the code that *validates* a rule (gates) and the code that *enforces* it (bridge) share the same `core` — so a policy that passes CI cannot be interpreted differently at runtime.

Maven multi-module reactor:

### 3.1 `heimdall-core` — the rule-model + pure evaluators

- **Owns the *meaning* of the rules.** No I/O, no OPC-UA, no MQTT, no CI framework.
- Models: `CommandPolicy` (+ `Rule`/`Target`/`Constraint`/`Decision`/`CommandRequest`), `UdtDefinition` (+ `Member`/`Param`/`SemVer`/`Verdict`/`Violation`), `RecipeManifest` and the provenance value types.
- Pure evaluators: `CompatibilityChecker` (① backward-compat), `CommandAuthorizer` + OPA `OpaCommandAuthorizer` (② decision; WASM-in-JVM), content-hash/provenance computation (③).
- Dependencies: Jackson, the OPA WASM runtime. Nothing else.

### 3.2 `heimdall-gates` — the authoring face (CLIs)

- Thin command-line entrypoints over `core`, run in PR pipelines:
  - `SchemaGate` (①) — reject a backward-incompatible UDT change.
  - `PolicyGate` (②-lint) — reject a malformed / non-deny-by-default command policy.
  - `ProvenancePublish` (③) — mint the content-addressed reference (content hash + commit SHA) for a committed definition and materialize the manifest; a companion verify path.
- Depends on `core` only. **No OPC-UA / MQTT dependency** (a build tool, not a service).

### 3.3 `heimdall-bridge` — the runtime face (daemon)

- The long-running write-boundary application (today's `NcmdOpcUaBridge` + `OpcUaApplier` + `NcmdOpcUaBridgeMain`).
- Flow: subscribe to Sparkplug **NCMD** → **authorize** via `core` (deny-by-default) → **apply** to the OPC-UA server → **stamp provenance** (③) on the executed write → emit response/audit.
- Depends on `core` + Eclipse Milo (OPC-UA client) + Eclipse Tahu (Sparkplug).
- Configuration by env/args (broker, OPC-UA endpoint, Sparkplug identity, policy path). **koshei-named legacy defaults are removed** (`OPCUA_URL`, `SPB_GROUP=Koshei:Line1`, `POLICY_PATH=registry/koshei-line1-policy.json` → neutral defaults).

## 4. What moves, what stays

**Moves into `heimdall`** (re-homed into core/gates/bridge):

- `acl/` (CommandPolicy model, CommandAuthorizer, CommandPolicyGate, `acl/opa/*`) and `src/main/resources/opa/*.rego`,`*.wasm`.
- `schema/` governance parts: `UdtDefinition`, `CompatibilityChecker`, `SemVer`, `SchemaGate`, `DefinitionStore`, `RecipeDefinitionStore`, `RecipeManifest`, `RecipePublish`, `Member`, `Param`, `Verdict`, `Violation`, `TemplateAdapter`, `JsonMapperFactory`.
- `bridge/` (NcmdOpcUaBridge, Applier, OpcUaApplier, NcmdResponse, NcmdOpcUaBridgeMain, RogueNcmd).
- Governance demos/examples: `SchemaGateDemo`, `CommandAclDemo`, `OpcUaUdtBridgeDemo`, `InteropEdge`/`InteropHost` — become Heimdall's own examples.
- The OPC-UA type-mapping support in `opcua/` that the bridge/interop path needs (audited during migration; the mapping used by `OpcUaApplier`/interop moves, lab-only browsing stays if truly lab-only).

**Stays in `sparkplug-governance-lab`** (the Sparkplug B lab / demonstrator):

- `spb40/` codec experiments, `kafka/` UNS sink, `drift/` monitors, edge-node/host smokes (`EdgeNode`, `HostApp`, `Smoke`, `MqttSmoke`, `SessionDemo`, etc.).
- Its gate scripts are re-pointed to invoke Heimdall's CLIs/daemon.

Migration audits each `schema/` and `opcua/` file for its true consumer; anything imported only by lab demos stays, anything the governance path needs moves. Shared-but-ambiguous types resolve toward `core`.

## 5. Cross-repo relationships

- **Heimdall ↔ lab:** the lab consumes Heimdall as **CLI/process only** (no cross-repo Maven artifact). Contract = policy/definition files + the gate CLI + the bridge daemon interface. Lab gate scripts (`run-r2-ncmd-gate.sh`, schema/authz gates) invoke Heimdall jars.
- **Heimdall → `resequence-twin-lab`:** Heimdall's `ProvenancePublish` mints the canonical ③ reference; the twin independently reads the published bytes, recomputes the hash, and verifies against the manifest (the existing v3 two-witness relationship; only the *publisher* changes from the lab to Heimdall). Raw-byte hashing is preserved so publisher/consumer agree cross-platform.
- **Out of scope:** Verðandi (koshei) — no dependency in either direction.

## 6. Migration phasing (executed by the plan, not this spec)

The spec defines the **target architecture**; the implementation plan sequences it so each step is independently verifiable:

1. **Stand up `heimdall`** — new repo, `core` + `bridge` + the runtime NCMD gate; get it standalone-green against a simulator OPC-UA. koshei-named defaults removed.
2. **Move the authoring gates** — `SchemaGate` (①), `PolicyGate` (②-lint), `ProvenancePublish` (③) into `heimdall-gates`; port their tests; gates green.
3. **Thin out the lab** — remove the moved packages from `sparkplug-governance-lab`, re-point its gate scripts to Heimdall CLIs/daemon, confirm the lab still builds and its remaining demos run; confirm `resequence-twin-lab`'s ③ verification passes against Heimdall-published references.

## 7. Success criteria (controller-direct verification)

- `heimdall` multi-module build green; unit tests green (ported `CommandAuthorizerTest`, `CommandPolicyGateTest`, `OpaCommandAuthorizerTest`, `SchemaGateTest`, `CompatibilityChecker`/recipe tests, `NcmdOpcUaBridgeTest`, `NcmdBridgePolicyTest`).
- Gates each `[GATE] PASS`:
  - `run-schema-gate` — a backward-incompatible UDT change is rejected; a compatible one passes.
  - `run-command-authz-gate` — deny-by-default: an unlisted command is refused; a policy-permitted one is allowed; operational-context constraints evaluated.
  - `run-provenance-gate` — publish mints a reference; an independent recompute verifies == manifest; a tampered blob 409s.
  - `run-ncmd-runtime-gate` — the bridge authorizes an NCMD, applies to a simulator OPC-UA (confirm-by-read), stamps provenance, and refuses an unauthorized/rogue NCMD.
- `sparkplug-governance-lab` still builds after the governance packages are removed; its remaining demos run.
- `resequence-twin-lab`'s ③ verification passes against a Heimdall-published reference.

## 8. Testing strategy

- **core** — pure unit tests (no I/O): compatibility matrix, authorizer decisions (incl. OPA context cases), hash/provenance determinism over raw bytes.
- **gates** — CLI-level tests: exit-code + rejection on bad input, success on good input; the provenance publish/verify round-trip.
- **bridge** — the existing bridge tests against an embedded/simulator OPC-UA + a rogue-NCMD refusal test; the runtime gate script for end-to-end.
- **cross-repo** — a lab gate that drives Heimdall as a process; the twin's independent-verify test against a Heimdall manifest.

## 9. Risks & mitigations

- **Hidden lab→governance coupling.** Lab demos/tests may import `acl/`/`schema/` classes. *Mitigation:* migration audits imports per file; governance demos move to Heimdall; genuinely lab-only code that imports governance types is rewritten to call the CLI or dropped.
- **CI-lint vs runtime drift** — the very thing we're preventing. *Mitigation:* both faces depend on `core`; no duplicated rule-model.
- **Provenance byte-agreement across the publisher change.** *Mitigation:* preserve raw-binary hashing (`readBytes`/blob bytes/`sha256sum`) exactly; keep the twin's independent recompute test as the cross-repo guard.
- **Scope creep into deployment hardening.** *Mitigation:* Non-Goals fence containerization/vendor-lock/provenance-at-gateway as roadmap.

## 10. Open questions (resolved)

- Repo boundary → **separate public repo** `heimdall` (Apache-2.0).
- Ownership of the three bills → **all three in Heimdall** (governance engine), CI-lint + runtime modes.
- Internal decomposition → **by moment** (core / gates / bridge), not by bill.
- lab consumption → **CLI/process, no code dependency**.
