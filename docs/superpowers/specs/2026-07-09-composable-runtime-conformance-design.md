# Composable Runtime Conformance — Bifrost as the IAM-like control plane for the OT governance boundary

**Date:** 2026-07-09
**Track:** Yggdrasil governance portfolio — feature + narrative strengthening (follow-on to the full-loop track, which is complete and public).
**Status:** design — research-grounded, approved to proceed to plan.

## 1. Purpose & narrative

Today Bifrost governs the STRUCTURE of equipment models and the setpoints of recipes, and enforces command authorization ("who may write what") at the OT write boundary. But the CONFORMANCE rules — is this value admissible for this equipment, given the governed model and the active recipe — live in two disconnected places: a design-time checker (`SpecConformanceChecker`, reads the equipment `member.range()`) and a runtime authorizer (`CommandAuthorizer`, reads a hand-authored `min`/`max` in `policy.json`). Two sources for the same fact.

**This track promotes conformance into a single governed, composable, declarative rule contract — evaluated at BOTH design-time (`gates spec`) AND the runtime write boundary (Heimdall) — managed over its lifecycle through Bifrost's existing control plane (registry-of-record + gates + provenance + SemVer).** The positioning: **Bifrost is the IAM for the OT governance boundary** — equipment models, recipes, AND conformance policies are all declarative, versioned, provenance'd config, evaluated consistently at design-time and runtime.

The running example moves to **automotive resistance spot welding** (body-shop, Eisen's domain) — see §3.

## 2. Research grounding (deep-research, 2026-07-09; 21/25 claims confirmed, 4 refuted)

A requirements-level YAGNI check (fan-out web research, adversarial verification) validated the core premises and sharpened the honest scope:

- **Cross-parameter / conditional constraints are a real, named practice** — APC/MPC "operating envelope" (multivariable constraint control), SIS cause-and-effect matrices, NAMUR MTP/POL cross-module interlocking. The feature is not inventing a fake problem. ([Control Engineering — APC limits](https://www.controleng.com/understanding-apc-limits-and-targets/), Honeywell US10908562B2, [NAMUR/ZVEI POL](https://www.zvei.org/fileadmin/user_upload/Verband/Fachverbaende/Automation/Messtechnik___Prozessautomatisierung/POL_NAMUR-ZVEI/NAMUR_ZVEI_Positionpaper_Process_Orchestration.pdf))
- **Design-time↔runtime limit duplication is real** — control-critical/setpoint limits maintained in PLC (source of truth) + SCADA copies; unsynchronized limits drifting across systems is a recognized concern. ([PLC-vs-SCADA limits](https://industrialmonitordirect.com/blogs/knowledgebase/plc-vs-scada-alarm-processing-architecture-best-practices))
- **The envelope-vs-recipe distinction is standardized** — ISA-88 distinguishes recipe limits (master recipe) from equipment CAPABILITY limits (equipment modules) from batch setpoints (control recipe); NAMUR MTP/POL separates a design-time engineering phase (interlocks/logic/parameters/versioning) from a runtime phase. Ground the strictness dial in ISA-88, NOT APC (the APC-envelope-vs-setpoint claim was **refuted 0-3**). ([SG Systems — ISA-88](https://sgsystemsglobal.com/glossary/isa-88-phases-equipment-modules/))
- **Governed recipes/limits already cross the enterprise→control boundary** — ISA-88 general→site→master→control recipe push-down; ERP→MES→batch; "validated digital recipes flow to the shop floor where MES enforces the precise parameters." Matches the data-mesh federated framing. ([Cybertrol](https://www.cybertrol.com/batch-control-recipe-management-systems-process-control-automation))
- **A declarative, model-derived conformance layer is a shipping pattern** — HighByte Intelligence Hub Pipelines validate payloads against governed model definitions (data types + required attributes) with conditional branching, no per-field code. ([HighByte Pipelines](https://www.highbyte.com/intelligence-hub/pipelines))

**Honesty guardrails (hard constraints baked into the design):**
1. **NOT a safety system.** Per IEC 61511/ISA-84, safety-critical interlocks/trips MUST remain in the SIS/PLC as independent protection layers and can NEVER be credited to an IT-side governance layer (BPCS-tier risk-reduction capped at ~RRF 10; an IPL sharing common software/network is disqualified; SIS→IT is read-only). Bifrost/Heimdall is **enterprise-contract/audit enforcement**, a governance gate, not an IPL. ([SIS-TECH/Summers SCAI](https://sis-tech.com/wp-content/uploads/2013/09/PSP-published-SCAI.pdf), [Primatech S84](https://www.primatech.com/images/docs/faq_s84_standard_for_safety_instrumented_systems.pdf))
2. **Quality, not safety, is the example's domain.** The cross-parameter constraint is a product-QUALITY / process-control-plan concern (IATF 16949 territory), NOT a machine-safety interlock. Weld-cell personnel/machine safety (guarding, e-stop, arc) stays in the independent safety-PLC.
3. **"Single governed source" is our governance goal, not an ISA-88 mandate** — the claim that ISA-88 prescribes a single source of truth was **refuted 0-3**. The duplication is real; unifying it is a reasonable improvement for NON-safety limits, stated as our design position.
4. **Cut the no-code DSL.** A user-facing self-serve rule-authoring surface for non-engineers has a documented failure mode (non-engineers stay blocked on engineering). Rules stay declarative-config-as-data, engineer/admin-owned, managed through the control plane. ([Justin D'Souza — Rules Engines & the False Promise of No-Code](https://justindsouza.substack.com/p/rules-engines-dsls-and-the-false)) This is distinct from — and does not contradict — an AWS-IAM-like *governed declarative policy* layer, which the research endorses as the SUCCESS pattern (narrowly scoped, engineer-owned).
5. **White space, stated modestly.** No surviving evidence showed a real MES enforcing a governed cross-parameter recipe limit at an OT write boundary as an authz gate. Present this as "a governance-layer take on a real problem," not "everyone does this."
6. **Do not over-cite APC.** APC is a continuous closed-loop optimizer, not a boolean rule; it proves cross-parameter envelopes are real, not that a policy-as-code IT layer is the vehicle.

## 3. Running example — automotive resistance spot welding (body shop)

Resistance spot welding is the primary joining process in automotive body assembly, and its quality hinges on the **weld lobe** (a.k.a. weldability lobe / process window): the region of **weld current × weld time × electrode force** that yields an acceptable nugget. Outside it → undersized nugget (no weld) or **expulsion/spatter** (off-spec). Electrode force is a coupled variable: too low a clamping force cannot contain a high current → expulsion. ([AHSS Guidelines — RSW](https://ahssinsights.org/joining/resistance-welding-processes/resistance-spot-welding/), [Springer — variable electrode force & weldability lobe](https://link.springer.com/article/10.1007/s40194-020-01001-2))

- **Equipment type `WeldControllerType`** (ns=2), members: `WeldCurrent` (Double, kA, range [0,12]), `WeldTime` (Double, ms, range [0,500]), `ElectrodeForce` (Double, kN, range [0,6]). Instance `BodyShop/Weld1`.
- **Envelope rule** (per-member, derived from `member.range()`): each member within its equipment range.
- **Cross-member quality rule** (the weld lobe, composition proof): `ElectrodeForce < 3.0 kN ⇒ WeldCurrent ≤ 8 kA` (low clamping force + high current → expulsion → off-spec nugget).
- **Recipe** (per body panel/joint): the approved weld schedule, e.g. `WeldSchedule-BPillar` = WeldCurrent 9 kA, WeldTime 200 ms, ElectrodeForce 4.0 kN.

This governs **weld quality/traceability** (an enterprise-contract concern); the weld cell's personnel/machine safety is independent and out of scope (guardrail #1/#2).

The existing chemical `Mixer` model is retained for the spine/full-loop tracks (already public); adding `WeldController` here is **additive** and strengthens the narrative: the SAME governance machinery governs a process-industry mixer AND an automotive weld cell — domain-agnostic governance.

## 4. Architecture

All in bifrost (`core`/`gates`/`heimdall`/`sim` — heimdall and gates already depend on core; no new repo, no shared-code violation since these are one product).

```
        ┌─────────── governed canonical MODEL ───────────┐   ┌──── governed CONFORMANCE POLICY (IAM-like) ────┐
        │ UdtDefinition(Member: name, type, range)        │   │ crossConstraints[]  (weld-lobe, small closed     │
        │  = "what the equipment IS" (envelope source)    │   │   algebra) · strictnessDial(envelope|recipe)     │
        └───────────────────────┬─────────────────────────┘   │ · nodeBindings[] (opcNode → member)              │
                                │  references (conformsTo)     │  = "what is ALLOWED", references model + recipe  │
                                │◀─────────────────────────────┤  managed via registry+gates+provenance+SemVer    │
                                ▼                               └───────────────────────┬──────────────────────────┘
                   ┌───────────────────────── core.conformance.ConformanceEvaluator ────┴──────────┐
                   │ (model, policy, activeRecipe?, candidate {member,value[,siblings]}) → Verdict  │
                   │ derives rules (type + envelope + cross-member + recipe), precedence, exact why │
                   └───────────────────────────┬──────────────────────────────┬────────────────────┘
                          design-time (gates spec)                 runtime (Heimdall ② after ① authz)
```

### 4.1 `core.conformance.ConformanceEvaluator` (small closed rule algebra)
Pure function. Inputs: the governed equipment `UdtDefinition`, an OPTIONAL governed `ConformancePolicy` (may be null — see graceful degrade below), the active recipe (for recipe-mode), and the candidate value(s). Derives and composes a small **closed** set of rule types:
- **structural** — the targeted member exists in the model (preserves `SpecConformanceChecker`'s `spec.member.unknown`; an unknown member short-circuits the value rules and is a null-safe guard, not an NPE).
- **type** — value type matches `member.type`.
- **envelope** — value within `member.range()` (derived from the model; skipped when the member declares no range).
- **cross-member** — the policy's conditional constraints (e.g. `ElectrodeForce<3.0 ⇒ WeldCurrent≤8`); evaluated over the candidate + sibling member values. **Requires a ConformancePolicy; absent → no cross-member rules.**
- **recipe** (recipe-mode only) — value equals the active recipe's setpoint for the member, within the policy's `recipeTolerance` (§4.2).

Returns a **rich verdict** (`ConformanceVerdict(boolean ok, List<Violation> violations)`, each `Violation` carrying the rule id + exact reason). **Precedence** (memory constraint): authz-deny short-circuits before conformance (handled by the Heimdall caller); within conformance, all rules evaluate and violations **accumulate** (a value can fail envelope AND cross-member AND recipe — report all). The algebra is small and closed (a handful of rule types + a verdict model), NOT Turing-complete; adding a rule type is a bounded, engineer-owned change.

**Graceful degrade (no-regression seam):** with a null/absent `ConformancePolicy`, the evaluator runs structural+type+envelope only (exactly today's `SpecConformanceChecker` behavior). The existing spec-gate fixtures (`scripts/fixtures/gates/spec/`) have a model but no policy, so they exercise this path — accept/reject is preserved and `run-spec-gate.sh` (asserts exit 0/1 only) does not regress.

`SpecConformanceChecker` is **subsumed and deleted** — its callers move to the evaluator. **Committed verdict path:** replace `SpecVerdict` with `ConformanceVerdict` and update `SpecGate` to map it to its exit code (0 conformant / 1 violations / 2 error). `SpecGate` prints only rule ids and checks exit codes, so the rename does not regress `run-spec-gate.sh`.

### 4.2 `ConformancePolicy` — the governed declarative config artifact (IAM analog)
A NEW first-class governed artifact (declarative JSON, published `conformance-policy.schema.json`): `crossConstraints[]` (conditional bound rules), `strictnessDial` (`envelope` | `recipe`, with `activeRecipe: ref@ver` and `recipeTolerance` — a fraction or absolute band — when `recipe`), `nodeBindings[]` (`opcNodeId → {equipmentRef@ver, member}`). References the equipment model (and recipe). The `recipeTolerance` lives HERE (in the policy, the governed strictness config), not in the recipe/manifest. **Managed over its lifecycle through Bifrost's EXISTING control plane** — schema-gated, provenance-published, SemVer'd — exactly like recipes: author a new version → gate validates → provenance publishes → consumers pin/consume. This is the "IAM-like management layer"; the abstraction is the declarative policy schema + the control plane, not an authoring syntax. (An optional Kotlin type-safe builder that emits this JSON is a later authoring-ergonomics convenience — NOT built here.)

### 4.3 Wiring the two boundaries

**Design-time (`gates spec`)** — the rewired `SpecGate` loads the equipment model and, if present, the bound `ConformancePolicy`, and evaluates a master recipe via the evaluator (structural + type + envelope + cross-member; recipe-mode is not applicable at authoring — the recipe IS the artifact). Admits only if it conforms. With no policy (existing Mixer fixtures) it degrades to structural+type+envelope (§4.1).

**Runtime (Heimdall)** — new startup + per-command wiring (this is real, enumerated work, not one line):
- **Startup (NEW registry-loading):** `NcmdOpcUaBridgeMain` today loads only `policy.json`. Add loading of a governed `UdtDefinition` + `ConformancePolicy` (version-pinned) from Heimdall's registry dir into the bridge, alongside the existing `CommandPolicy`.
- **Per NCMD:** ① authz (`CommandAuthorizer` on `policy.json`: target/command/principal, deny-by-default) unchanged = WHO/WHAT. ② NEW conformance (only if ① allows — precedence/short-circuit): resolve the command's OPC node → governed member via the policy's `nodeBindings`, run the evaluator (envelope from the model, cross-member from the policy, recipe if the dial is `recipe`), DENY with the exact conformance reason on any violation, else APPLY. (Secret-style deny-targets remain authz-deny-by-default = WHO, not conformance.)

**`policy.json` migration (the load-bearing no-regression detail):** the range must move OUT of `policy.json` WITHOUT breaking the `constraint==null` "trigger-only" semantics that the `ApplyRecipe` rule depends on. **Mechanism:** change the numeric-write rules (`rpm`, `temp`) from `constraint{type,min,max}` to `constraint{type}` (drop `min`/`max`, KEEP `type`). `CommandAuthorizer` already treats a non-null constraint with null `min`/`max` as "type-check then allow" (it skips the null bounds), so authz still enforces WHO + type but no longer range — and the `ApplyRecipe` `constraint==null` trigger-only rule is untouched. **No `CommandAuthorizer` code change.** The range is now enforced only by ② conformance, from the governed model.

**ncmd-gate no-regression (concrete):** the ncmd gate exercises the **Mixer** node `ns=2;s=Recipe/Rpm`. For its `T3 (Rpm=9999 → above-max DENY)` to keep passing FROM THE MODEL, Heimdall's runtime registry must contain a **Mixer** `UdtDefinition` (`Line1-Mixer`, `Rpm` range [0,3000]) + a **Mixer** `ConformancePolicy` binding `ns=2;s=Recipe/Rpm → Line1-Mixer@ver.Rpm`. Then: `T1 (Rpm=1500)` → authz type-ok + conformance in-range → APPLY; `T2 (Recipe/Secret)` → authz deny-by-default (unchanged); `T3 (Rpm=9999)` → authz allows (type-ok, no range) → conformance DENY. The conformance DENY reason string MUST contain `above-max` so the gate's existing grep still matches (or update the gate assertion in the same commit).

## 5. Killer proof — `run-composable-conformance-gate.sh`

```
[baseline] govern WeldControllerType (WeldCurrent∈[0,12], force-coupled weld-lobe) + policy + recipe BPillar(9kA/200ms/4.0kN)

(COMPOSITION — individual envelopes pass, the composed constraint denies)
  ElectrodeForce=2.5kN, command WeldCurrent=9kA:
    ① authz PASS  ② type PASS  ② envelope 9∈[0,12] PASS  ② weld-lobe 2.5<3.0⇒≤8; 9>8 DENY
    ⇒ runtime DENY "weld-lobe: at 2.5 kN, current capped 8 kA (expulsion → off-spec nugget)"
  Same (WeldCurrent=9, ElectrodeForce=2.5) master recipe at DESIGN time → gates spec REJECT (same evaluator)

(SINGLE-SOURCE DUAL-EVAL — change the model/policy once, both boundaries flip)
  tighten weld-lobe threshold 3.0→3.5 kN in the governed policy (one versioned change, policy.json untouched)
    → gates spec verdict flips AND Heimdall runtime verdict flips — from one governed source, no code change

(STRICTNESS DIAL — recipe-mode adds the recipe rule to the composed set, grounded in ISA-88)
  dial=envelope: WeldCurrent=7kA (in envelope, force=4.0) → APPLY
  dial=recipe (governed change), activeRecipe=BPillar(9kA): WeldCurrent=7kA → DENY-recipe "deviates from approved schedule 9kA"; 9kA → APPLY

(LIFECYCLE / IAM-like management)
  publish policy v1 → v2 (new cross-constraint) via gates+provenance; consumers pin the version; provenance-verify the policy bytes
```

Assertions: (C1) composition denies a value all individual envelopes admit; (C2) design-time and runtime agree via the one evaluator; (C3) one governed change flips both boundaries; (C4) the dial adds recipe-conformance; (C5) the policy is a versioned, provenance-verified governed artifact (tamper → reject). All controller-run.

## 6. Scope boundary

**IN:** `ConformanceEvaluator` (structural+type+envelope+cross-member+recipe, rich verdict, graceful-degrade) · `ConformancePolicy` governed artifact + `conformance-policy.schema.json` + schema-gate + provenance + SemVer · derivation of envelope from the model · `gates spec` rewired to the evaluator (`SpecConformanceChecker`/`SpecVerdict` subsumed) · `WeldControllerType`+instance+recipe in sim · **Heimdall runtime registry-loading** (load `UdtDefinition`+`ConformancePolicy` at startup) · **Heimdall ② conformance wiring** (after ① authz) · **`policy.json` migration** (numeric rules → `constraint{type}`, range removed) · **the Mixer `Recipe/Rpm` model+binding** required for ncmd no-regression · the killer gate.

**Phasing (additive-first, so no gate ever sees a broken window — a NAMED plan constraint):**
- **Phase A (design-time, additive, low-regression):** evaluator + `ConformancePolicy` artifact/schema/gate/provenance + envelope-derivation + `SpecGate` rewired + sim `WeldControllerType`. Nothing existing at runtime is touched; `run-spec-gate.sh` stays green via graceful-degrade.
- **Phase B (runtime, the hazard):** Heimdall registry-loading + ② conformance + the Weld conformance path proven by the killer gate FIRST (new domain, no existing gate touches it). THEN, in the SAME commit: add the Mixer model+`ConformancePolicy`+binding to Heimdall's registry AND drop `min`/`max` from `policy.json` AND re-verify `run-ncmd-runtime-gate.sh` — so the range is never gone while the model is unwired.

**DEFERRED / CUT (reserved seams):** no-code self-serve authoring UX (**cut**, guardrail #4) · optional Kotlin type-safe builder emitting the policy JSON (deferred authoring convenience) · enterprise-template layer (this track activates `conformsTo`, easing it) · additional rule types (units/quality/rate-of-change/inter-parameter beyond the one weld-lobe form) · full ISA-88 control-recipe layer · external text grammar.

## 7. Honest limitations (Ep5 material — do not oversell)

- **Enterprise-contract/audit governance, NOT a safety system.** Never an IEC 61511 IPL; the SIS/PLC remains the independent safety authority; the example governs weld *quality*, not weld-cell *machine safety* (guardrails #1/#2).
- **"Single governed source" is our design position, not an ISA-88 mandate** (the standards-mandate claim was refuted); it applies to non-safety limits.
- **Envelope is dual-evaluated; recipe is runtime-only** (at design time the recipe is the artifact being authored, so it is the reference, not a checked candidate).
- **The rule algebra is small and closed** (type/envelope/cross-member/recipe) — proves the SHAPE (IAM-like governed declarative conformance), not a general OPA/CEL engine; extended by adding engineer-owned rule types, never a user-facing DSL.
- **Runtime conformance evaluates inside Heimdall (a bifrost module)** — design-time gates and runtime heimdall legitimately share `core`; this is not yet a separate zero-shared-code app consuming a rule library.
- **This exact pattern (governed cross-parameter recipe limit enforced as an OT-write authz gate) was not found in the wild** — presented as a governance-layer approach to a real problem, plausibly white space.
- **The weld-lobe threshold is illustrative** (a simplified `force ⇒ current` bound), not a calibrated welding engineering model.

## 8. Verification (controller-direct — the #1 rule)

- `mvn install` green at bifrost root (core/gates/heimdall/sim incl. new evaluator + policy + WeldController tests).
- `run-composable-conformance-gate.sh` → `[GATE] PASS` with C1–C5 observed, run BY the controller.
- No-regression: `run-yggdrasil-spine-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-ncmd-runtime-gate.sh`, `run-spec-gate.sh` all still `[GATE] PASS` (SpecConformanceChecker subsumption + `policy.json` range removal must not regress the ncmd/spec gates — a NAMED acceptance criterion).

All repos remain local until Eisen OKs a push (bifrost/lab already public; feature branch stays local until then).
