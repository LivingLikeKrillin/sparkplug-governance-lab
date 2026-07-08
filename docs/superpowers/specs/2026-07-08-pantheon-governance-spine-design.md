# Pantheon Governance Spine — Design

> Status: DRAFT (brainstormed 2026-07-08, research-grounded). Spans the built `bifrost` repo + two NEW repos (`mimir`, `muninn`). Downstream of the Bifrost extraction (Chunks 1–3, done). Mjölnir carve-out is a separate track (other session) and is NOT touched here.

## Goal

Stand up the **minimal skeleton of the remaining pantheon** (Mímir + Muninn) and prove, with ONE end-to-end integration gate, that the whole **northbound governance spine works together**: a single piece of equipment flows through modeling → governance → feeding, coupled only by the language-neutral data/wire contract (zero shared code). Then — and only then — the portfolio blog is written on top of the real, running system.

Non-goal: full products. This is a skeleton that proves composition. Mjölnir (koshei), the southbound Heimdall command path, and the enterprise-template registry layer compose later.

## Context

The governance product **Bifrost** already exists and is public (`github.com/LivingLikeKrillin/bifrost`, Apache-2.0): `core` (rule model + evaluators) + `heimdall` (runtime write-boundary daemon, ②) + `gates` (CI CLIs: SchemaGate ①, PolicyGate ②-lint, ProvenancePublish ③) + `sim` (embedded Milo OPC-UA server). The lab (`sparkplug-governance-lab`) has been reconciled to a code-independent consumer and holds the northbound **prototypes**: `opcua/` (OPC-UA type-map/flatten) seeds Mímir; `spb40/` (Sparkplug definition publish/resolve + edge) + `kafka/` seed Muninn. `resequence-twin-lab` already verifies Bifrost's ③ provenance by bytes.

Pantheon roles (see `bifrost-heimdall-naming` memory): Bifrost = governance product · Heimdall = write-boundary daemon (②, southbound) · **Mímir = northbound modeler (design-time, governance upstream)** · **Muninn = northbound feeder (runtime, governance downstream)** · Mjölnir = field EAI+Saga (separate).

## The load-bearing decision: TWO orthogonal axes

Governance applies along **two independent axes**, not one. This is the central architectural claim, and it is corroborated by standards (see References):

| Axis | Scope | Producer | What it is | Standard |
|---|---|---|---|---|
| **Equipment** | **per-SITE** | Mímir | What the equipment IS — types/members/ranges (physical, OT-derived) | ISA-95 Part 2 asset hierarchy; OPC-UA type space |
| **Spec** | **per-PRODUCT-DOMAIN** (spans sites) → site binding | MES (in-house) | Product-specific setpoints + inspection config | ISA-88 recipe hierarchy (general → master) |

**Why two axes (research correction):** every federation source (data-mesh, arXiv 2601.09744, HighByte) federates by business/product DOMAIN, not geographic site. Collapsing everything to "site" mis-scopes the spec: a global product line's recipe authority does not sit at any single site. ISA-88 already resolves this — the **general recipe** is equipment-independent, corporate-authored, "complete/unambiguous/consistent across sites" (a contract), while the **master recipe** binds to a specific site's process-cell equipment. So:

- **Equipment model = per-site** (Eisen's instinct, correct — it is derived from that site's physical OT).
- **Spec model = product-domain-scoped** with a per-site equipment-binding step.

The two axes **meet** where the master recipe binds to the site equipment model — this binding IS the `spec ⊨ equipment-model` conformance gate, which is exactly ISA-88's master-recipe-binds-to-process-cell relation.

**Federation invariant (validated):** the contract FORMAT is enterprise-uniform (everyone speaks the same published schema); the governed CONTENT (equipment models per site, specs per product-domain) is local; governance is federated with per-registry authority + adherence to global format rules (data-mesh "federated computational governance"; arXiv 2601.09744 "subsidiarity + extend-not-redefine baseline ontology").

## Canonical model representation: AAS-submodel-aligned (lightweight)

Research verdict: a bespoke JSON `UdtDefinition` risks reinventing governed model structures that **AAS Submodel Templates (IEC 63278, IDTA-registered)** and **OPC-UA companion specs** already provide, and an official **AAS↔OPC-UA mapping companion spec (OPC 30270 / I4AAS)** already bridges them.

Decision — **align, don't reinvent, but stay lightweight:**
- The canonical equipment definition is reshaped to **AAS-submodel vocabulary**: a `Submodel` with `SubmodelElement`/`Property` entries, each carrying a `semanticId` and a value type + (for numeric setpoint-eligible members) an EU-range qualifier.
- It is **projectable to OPC-UA companion-spec type nodes** (the OPC 30270 mapping direction), which is also the shape Mímir *derives from* (the OT type space).
- We do NOT drag the full AAS SDK / AASX packaging / ECLASS-IRDI semanticIds into the skeleton. `semanticId` is a free/local IRI in the skeleton; ECLASS/IRDI alignment is deferred.
- This maps cleanly onto the modeler/feeder split: **AAS is design-time/exchange-oriented (Mímir's canonical model), OPC-UA is runtime/live-data-oriented (Muninn's wire)** — the split the standards themselves draw.

## Spec model: ISA-88 two-layer (general + master)

- **General spec** (product-domain scope, spans sites): equipment-independent product intent, corporate/product-line-authored. Governed by Bifrost for FORMAT (schema).
- **Master spec** (binds to a site's process cell): the general spec bound to a specific site equipment model's members, with concrete setpoints. Governed by Bifrost for **conformance to the site equipment model** + provenance.
- Control recipe (per-batch runtime) is OUT of scope (deferred).

## Conformance semantics: structural + range

`gates spec <masterSpec> <equipmentModelRef>` checks:
1. **Structural** — every setpoint key maps to an existing member/Property of the referenced equipment model; value types match.
2. **Range** — each setpoint value is within the member's model-declared EU range.

**Single-source-of-truth property (the strong architecture story):** the EU range is declared once on the equipment model and travels: `model → spec gate (design-time reject out-of-range) → Heimdall ② (runtime authz bound)`. Design-time and runtime enforcement derive from the same governed source. (Full B2MML / AAS capability-skill matching is deferred.)

## Muninn's runtime governance (governance downstream)

Muninn is NOT a dumb pipe — it is an **egress governance enforcement point**:
1. **Provenance-verify** the governed equipment definition it consumes (recompute contentSha256 vs the published manifest — same data-contract mechanism `resequence-twin-lab` already proved). Refuses to publish an ungoverned/tampered model.
2. **NBIRTH** publishes that exact governed definition as the Sparkplug B edge-node birth template.
3. **Egress-validate** every NDATA sample against the governed definition (member names/types); non-conformant samples are dropped/flagged, never emitted to the UNS.

This closes the invariant loop: Mímir proposes → Bifrost governs → **only governed-conformant data crosses to the UNS**.

## UNS substrate: Sparkplug B edge node → MQTT

Muninn publishes the equipment's live data as a Sparkplug B **edge node** (NBIRTH = governed definition, NDATA = values) to an MQTT broker = the UNS. Chosen because it is the canonical UNS pattern, shares the broker/Sparkplug substrate with Heimdall (one coherent story), and the NBIRTH template is the natural place to carry+enforce the governed model. Seeds: lab `spb40/`. (Kafka UNS deferred.)

## The single coherent equipment example

One piece of equipment threads the entire spine: a **"Line1 Mixer"** type with members `Rpm:Double [0..3000]`, `Temp:Double [0..450]`, `Running:Boolean`, `Secret:Double` (deny-by-default target). Mímir models its type; MES specs its setpoints; Muninn feeds its live data; Heimdall guards its writes; the twin monitors its drift. Same equipment everywhere = the whole runs together.

## Components & build scope

| Component | State | This track |
|---|---|---|
| **Bifrost** | built + public | ADD: first-class spec model (general/master, ISA-88 vocab) + `gates spec` (structural+range conformance) + reshape equipment definition to AAS-submodel-aligned form; hierarchical-identity + scope-carrying-manifest seams |
| **Mímir** | NEW repo (`mimir`, Java, seeded from lab `opcua/`) | browse the sim's OPC-UA Mixer type → derive AAS-aligned equipment definition → submit via `gates schema` (per-site registry) |
| **Muninn** | NEW repo (`muninn`, Java, seeded from lab `spb40/`) | consume governed definition → provenance-verify → Sparkplug NBIRTH + egress-validated NDATA → MQTT UNS |
| **MES** | in-house, represented minimally | general + master spec fixtures (Git-committed) + `gates spec`/`gates provenance` calls in the gate — NOT a real MES build |
| **sim** | extend | expose an OPC-UA **type node** (Mixer type) for Mímir to browse + instance value nodes for Muninn to read |
| Heimdall, twin | built | compose later (not in this proof) |

**Zero shared code** across repos — Mímir/Muninn/MES integrate with Bifrost only via the published data/wire contract (JSON schema + Sparkplug/OPC-UA wire + gate CLIs + published-manifest bytes), each modeling the format in its own types.

## Enterprise-ready seams (so the enterprise layer is additive, not a rewrite)

The enterprise-template layer is deferred, but the skeleton must not preclude it. The enterprise layer is fundamentally "the same conformance relation, one level up," so we build these seams now:
1. **Hierarchical identity** — equipment ref `enterprise:site:type@version`, spec ref `enterprise:productDomain:...` + site-binding; `enterprise` defaults to a constant now, but the slot exists.
2. **Reference-parameterized conformance** — gates already take the registry/model ref as an argument; keep that (so a future `site-model ⊨ enterprise-template` is the same gate pointed at a different reference).
3. **Reserved `conformsTo`/extends pointer** on the equipment definition (a site model may later declare its enterprise-template parent) — distinct from the type's own id.
4. **Scope-carrying manifest** — the published manifest self-describes `site` (now) and reserves `enterprise` — consumers know which scope an artifact came from by bytes.

## Acceptance — the end-to-end integration gate

ONE gate script proves the spine composes (run FOREGROUND; controller-verified, not a subagent claim):
1. Mímir browses the sim's Mixer type → AAS-aligned definition → `gates schema` admits it into the per-site registry.
2. MES general + master specs (fixtures, `Rpm=1500`, `Temp=200`) → `gates spec` master⊨equipment (structural+range) → `gates provenance` publishes.
3. Muninn consumes the governed definition → provenance-verifies → NBIRTH (governed definition) + egress-validated NDATA → MQTT UNS.
4. Assertions (all observed, unweakened):
   - a compatibility-breaking equipment change → `gates schema` REJECT (①);
   - an out-of-range master spec (`Rpm=9999`) → `gates spec` REJECT (range);
   - a tampered published spec → provenance verify REJECT (③);
   - a non-conformant NDATA sample (extra member / wrong type) → Muninn drops it, never reaches the UNS;
   - the NBIRTH definition bytes equal the governed registry bytes.
5. `[GATE] PASS`.

## Deferred (YAGNI, explicit)

ISA-88 control recipe / full 4-level hierarchy · full AAS SDK/AASX + ECLASS/IRDI semanticIds · enterprise-template registry layer · southbound Heimdall composition into this gate · Kafka UNS · Confluent-grade contract features (data-quality rules, encryption, migration rules) · Mjölnir (separate track).

## Open questions / spec-draft defaults (confirm in review)

- semanticId source: free/local IRI in the skeleton; ECLASS/IRDI later.
- Version/binding lifecycle: master spec pins the equipment definition's specific version (defRef); an equipment version bump requires re-conformance of bound specs.
- Repo names `mimir` / `muninn` provisional; language Java (matches lab seeds); design spec kept in this private location (bifrost is public).
- Should `gates spec` conformance later adopt B2MML / ISA-95 Part 4 process segments / AAS capability-skill rather than the bespoke structural+range check?
- Should the equipment definition become a strict projection of an AAS Submodel Template (IDTA-registered) once the skeleton proves out?

## References (research-verified 2026-07-08, 24/25 claims confirmed)

- ISA-88 recipe hierarchy (general/site/master/control), general recipe as cross-site contract, master-binds-process-cell: ceur-ws.org/Vol-1333/fomi2014_4.pdf; Brandl "Enterprise Recipe Management"; en.wikipedia.org/wiki/ISA-88.
- AAS Submodel Templates as governed registry-of-record (IEC 63278, IDTA numbers): industrialdigitaltwin.org/en/content-hub/submodels; github.com/admin-shell-io/submodel-templates.
- AAS↔OPC-UA mapping companion spec OPC 30270 / I4AAS v1.00 (2021): reference.opcfoundation.org/I4AAS/.
- Federated computational governance (local semantics / global format): martinfowler.com/articles/data-mesh-principles.html; arXiv 2601.09744 (Alagappan 2026-01-10, subsidiarity + baseline ontology — single-author preprint, evidences convergence not proof).
- Central-authored/site-consumed federation + template mechanism: AWS/HighByte industrial-data-fabric reference architecture; Ignition UDT inheritance docs.
- Data contracts / compatibility scoping: Confluent data-contracts (compatibilityGroup) — note it EXCEEDS the three gates.
- Caveat carried forward: "domain = site" is an interpretive mapping, not a standards mandate; cross-site product domains need the product-domain axis (why the spec axis is product-domain-scoped, not site-scoped).
