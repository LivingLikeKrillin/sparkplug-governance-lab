# site ⊨ enterprise conformance — prescriptive model governance, standard-agnostic via ports & adapters

**Date:** 2026-07-09
**Track:** Yggdrasil governance portfolio — T1 of the governance-spine completion roadmap (T1 site⊨enterprise → T3 deployment → T4 lineage → T5 identity).
**Status:** design — research-grounded (deep-research R2), approved to proceed to plan.

## 1. Purpose & thesis

Today Bifrost governs equipment models by **change-control** (SemVer compatibility) + provenance, and enforces `recipe ⊨ equipment` at design-time and runtime. But a domain expert's fair critique holds: **bottom-up OPC-derivation (Mímir) alone is reflective *discovery*, not *prescriptive governance*** — there is no independent standard the model is held to. Deep-research (R2) confirms: prescriptive governance genuinely requires a **top-down standard to conform against** (this is what CFIHOS/ISO-15926/PI-AF/Ignition-UDT-inheritance do in the real world).

This track adds the first **prescriptive model governance**: a site's equipment `UdtDefinition` must **conform to an enterprise TEMPLATE** (`site model ⊨ enterprise template`), activating the reserved `UdtDefinition.conformsTo` pointer.

**Architectural thesis (the load-bearing card claim):** Bifrost governs the **invariant**, not the **representation**. The conformance logic + the versioned record + enforcement are defined against a **stable internal canonical model**; external standards are **pluggable driven-adapters**. Because authoritative standards *churn* over decades (a company adopts CFIHOS → migrates → M&A brings another → a new plant ships a vendor/commercial-tool shape), a governance system hard-wired to one standard's schema = rewrite-on-every-change = institutional fragility. **Ports & adapters + dependency inversion** make each future standard an *additive adapter*, core untouched. And it lets Bifrost **integrate with — not replace — the trusted commercial/standard stack** (Ignition, HiveMQ, OIDC, OPC-UA, Sparkplug): you keep your Ignition; Bifrost governs the models that flow through it.

## 2. The real problem this addresses (why it isn't a toy)

- **Prescriptive gap:** bottom-up derivation catalogs "what exists"; it cannot answer "does this site meet our corporate standard?" (R2: a real, named governance activity — CFIHOS conformance portal/validators; PI AF standard-data-model governance).
- **Standard churn (future-inevitable):** the authoritative standard's *shape* changes over a plant's life. Coupling governance to one shape is the fragility this design refuses.
- **Trusted-stack integration:** enterprises have invested in commercial platforms (Ignition) and will not rip-and-replace. A governance layer that ingests **Ignition's own UDT model** (via an adapter) and governs it — Ignition unchanged — is adoptable, not academic.

## 3. Architecture — ports & adapters (dependency inversion)

All in bifrost (`core`/`gates`; mirrors the existing `SpecGate`/`SpecConformanceChecker`→`ConformanceEvaluator` patterns). No new repo. No runtime; design-time gate.

```
              ┌──────────── CORE (invariant, depends on NOTHING external) ────────────┐
              │ TemplateConformanceChecker.check(siteDef, enterpriseTemplate)          │
              │   → ConformanceVerdict   (reuses core.conformance.ConformanceVerdict)   │
              │ operates ONLY on the internal canonical model: UdtDefinition            │
              └───────────────────────────────▲───────────────────────────────────────┘
                                               │ PORT = UdtDefinition (the stable internal model)
                    ┌──────────────────────────┼──────────────────────────┐
        native UdtDefinition            IgnitionUdtAdapter          CfihosTemplateAdapter
        (Bifrost's own shape)           (commercial: Ignition UDT   (industry RDL:
                                         export JSON → UdtDefinition) CFIHOS-flavored → UdtDefinition)
                    ▲                           ▲                          ▲
             native template             Ignition UDT export         CFIHOS-shaped template
```

- **Dependency direction is inward only.** The core imports NO adapter and NO external schema. Adapters depend on the core's `UdtDefinition`, never the reverse. Adding a standard = adding an adapter; the checker/record/gate are untouched.
- **The internal canonical model (`UdtDefinition`) is the anti-corruption boundary** — the same discipline the whole portfolio already uses (mimir/muninn model the published format in their own types).

### 3.1 `core.conformance.TemplateConformanceChecker` (the invariant core)
Pure function: `ConformanceVerdict check(UdtDefinition siteDef, UdtDefinition enterpriseTemplate)`. Semantics — **subtyping/Liskov: a site may specialize/tighten/extend, but not violate:**
- **member superset** — every template member must be present in the site model (site MAY add extra members = extension). Rule id `template.member.missing`.
- **type match** — site member type == template member type. `template.type.mismatch`.
- **semanticId match** — site member semanticId == template member semanticId (the corporate-dictionary IRI). `template.semanticId.mismatch`.
- **range envelope** — site member range ⊆ template member range (site `low ≥` template `low` AND site `high ≤` template `high`; site may tighten, not exceed). `template.range.exceeds-envelope`. (A null site range against a bounded template range = violation "unbounded exceeds envelope"; a null template range = no envelope constraint.)

Reuses `core.schema.{UdtDefinition,Member,Range}` and `core.conformance.{ConformanceVerdict, Violation}` (Violation ids in a distinct `template.*` namespace). Violations accumulate (report all).

### 3.2 The port + adapters
- **Port** = `UdtDefinition` (existing). The enterprise template, whatever its source shape, is represented internally as a `UdtDefinition` (all members required; ranges are envelopes).
- **`IgnitionUdtAdapter`** (primary, commercial): maps an **Ignition UDT export JSON** → `UdtDefinition`. Ignition exports UDTs as tag JSON (tags with `dataType`, `engUnit`/`engLow`/`engHigh`, nested members). Mapping: tags→members, Ignition `dataType`→our `type`, `engLow`/`engHigh`→`range`, a documented convention for `semanticId` (Ignition tags carry no corporate IRI natively → synthesize from a documented convention OR a custom UDT property). **The fixture should be a REAL export from Ignition Maker Edition** (authentic) or, if unavailable, hand-authored to Ignition's documented UDT-export schema (noted honestly).
- **`CfihosTemplateAdapter`** (secondary, for pluggability-vividness — DROPPABLE): maps a CFIHOS-flavored equipment-class JSON (`properties[]` with IRI `propertyId`, `datatype:"REAL"`, `minValue`/`maxValue`, `requirement:"M"/"O"`) → `UdtDefinition`.

### 3.3 Honest impedance handling (architect maturity, not a lossless pretense)
Adapters make **explicit, documented decisions** where external semantics don't map cleanly:
- Ignition tag has no corporate IRI → semanticId is synthesized from a documented convention (or read from a designated custom UDT property); stated, not hidden.
- CFIHOS `requirement:"O"` (optional) → Bifrost's minimal template has all-required semantics → optional members are **dropped with a logged decision** (optional-member support is a reserved additive extension, §7).
- CFIHOS `uom`, Ignition `engUnit` → not yet modeled internally → carried as a documented drop (units = future additive extension).

## 4. `gates template` — the prescriptive gate
New `GatesCli` leg + `TemplateGate` mirroring `SpecGate`: `gates template <registryDir> <siteDefFile>` — loads the site `UdtDefinition`, reads its `conformsTo` (`ref@version`), loads the enterprise template from `registryDir` (`udt/<ref>/<version>.json`) via `DefinitionStore`, runs `TemplateConformanceChecker`, prints `[GATE] … PASS/FAIL + violations`, exit `0` conform / `1` violations / `2` error. A site model is thus **admitted only if it conforms to the enterprise standard it claims** — prescriptive, not change-control.

## 5. Killer proof — `run-template-conformance-gate.sh`

Automotive multi-plant example. Enterprise template `WeldController-corp@1.0.0`: `WeldCurrent`(Double, sem `corp:weld/current`, [0,15]), `WeldTime`([0,600]), `ElectrodeForce`([0,8]) — all required.

- **P1 native ACCEPT:** site `Ulsan-Weld@1.0.0` conformsTo corp, tightened+extended (WeldCurrent[0,12], +WeldVoltage) → `gates template` exit 0.
- **P2 native REJECTs (3 legs, exit 1 each):** exceeds-envelope (WeldCurrent[0,20]) · member-missing (drop WeldTime) · semanticId-mismatch (`ulsan:current`).
- **P3 ⭐ adapter equivalence (the platform proof):** the SAME enterprise standard expressed as an **Ignition UDT export** → `IgnitionUdtAdapter` → `UdtDefinition` that is **semantically identical** to the native template; run the SAME `gates template` → **identical verdicts to P1/P2**. Repeat with the **CFIHOS-flavored** shape → same. Assert: `adapt(external) ≡ native` (equal `UdtDefinition`) AND identical conformance verdicts AND the checker/gate code is **byte-identical across all three sources**. → "standards are pluggable; the governance core is standard-agnostic; a new standard is an additive adapter, core untouched."

## 6. Scope

**IN:** `TemplateConformanceChecker` (+ `template.*` rule ids) · `gates template` leg (`TemplateGate`) · `IgnitionUdtAdapter` (Ignition UDT export → UdtDefinition) · `CfihosTemplateAdapter` (droppable second adapter) · fixtures (native template, conforming + 3 violating sites, Ignition-UDT export, CFIHOS-shaped) · the killer gate.

**DEFERRED (reserved additive seams — the growth roadmap):** optional/recommended members + cardinality + units in the template model · a distinct richer `EnterpriseTemplate` type (vs reusing UdtDefinition) · top-down template AUTHORING tooling ("Eitri") · running Ignition as the full OT data-plane · external-RDL live ingestion (real CFIHOS/ISO-15926 parsers) · runtime (this is design-time only).

## 7. Honest limitations (card-honest, not overclaimed)

- **The template's authoring is out of scope** — templates are hand-authored / exported governed fixtures; "how enterprise architects author standards at scale" is the deferred authoring layer (Eitri/Kotlin builder). We prove the *conformance + adapter* mechanism, not authoring ergonomics.
- **Adapters are illustrative mappings, not production parsers** — `IgnitionUdtAdapter` targets Ignition's UDT-export shape (ideally a real Maker export); `CfihosTemplateAdapter` targets a CFIHOS-*flavored* JSON, not the full CFIHOS RDL. They prove the ports-&-adapters SHAPE + honest impedance decisions, not full standard coverage.
- **semanticId conformance = string-equality against the template**, not validation against an external corporate dictionary/RDL — "template-consistency," not full dictionary validation.
- **Minimal template semantics** (all-required members, ranges-as-envelopes); optional/recommended/units/cardinality are reserved additive extensions.
- **Bottom-up is retained, not replaced** — Mímir still derives site models (right for brownfield); this track *completes* it by governing the derived model against the top-down standard. When the site model is Mímir-derived, `site-model ⊨ enterprise` effectively asks "does the as-built reality conform to the corporate standard?" — genuinely prescriptive (can reject).
- Design-time only; no runtime enforcement of template conformance (the runtime boundary is Heimdall ②, which governs recipe/range, not model-vs-template).

## 8. Verification (controller-direct — the #1 rule)

- `mvn install` green at bifrost root (core + gates incl. `TemplateConformanceChecker`, both adapters, `TemplateGate` tests).
- `run-template-conformance-gate.sh` → `[GATE] PASS` with P1 accept, P2 three rejects, **P3 adapter-equivalence (Ignition-UDT & CFIHOS adapt to a UdtDefinition equal to native, identical verdicts, checker unchanged)** — run BY the controller.
- No-regression: `run-spec-gate.sh`, `run-ncmd-runtime-gate.sh`, `run-yggdrasil-spine-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-composable-conformance-gate.sh` all still `[GATE] PASS`.

All local until Eisen OKs a push (bifrost/lab already public; feature branch stays local until then).
