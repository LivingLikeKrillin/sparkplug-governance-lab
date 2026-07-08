# Yggdrasil Governance Spine Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the northbound governance spine composes end-to-end — a single "Line1 Mixer" flows Mímir(model)→Bifrost(govern)→Muninn(feed UNS), coupled only by the published data/wire contract (zero shared code).

**Architecture:** Two orthogonal axes — equipment model per-SITE (Mímir, AAS-submodel-aligned), spec model per-PRODUCT-DOMAIN (MES, ISA-88 general/master), meeting at a `master ⊨ equipment` structural+range conformance gate. Muninn is an egress governance enforcement point (provenance-verify → Sparkplug NBIRTH governed definition → egress-validated NDATA → MQTT UNS). Cross-repo integration is by built-jar subprocess; the end-to-end gate lives in `bifrost/scripts/`.

**Tech Stack:** Java 17, Maven, Eclipse Milo 1.0.0 (OPC-UA), Eclipse Tahu (Sparkplug B), Jackson 2.17, HiveMQ CE (MQTT), JUnit 5. Repos are sibling checkouts under `Labs/[iiot]/`: `bifrost/` (built+public), NEW `mimir/`, NEW `muninn/`.

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-08-yggdrasil-governance-spine-design.md`.

**Progressive elaboration:** Chunk 1 is fully TDD-detailed (immediately executable, no cross-repo dependency). Chunks 2–5 are task-level outlines here; each is expanded to full bite-sized TDD detail (and plan-reviewed) JUST BEFORE it is executed, because the new-repo shapes (3–5) depend on the realized shape of chunks 1–2. Execute one chunk, controller-verify its done-bit, Eisen-gate, then elaborate + execute the next — exactly as the Bifrost extraction was run.

**Do NOT push any repo without Eisen's explicit OK. bifrost is public — its changes stay on a feature branch, local, until OK.**

---

## File Structure (decomposition locked)

**Chunk 1 — bifrost (feature branch `feat/yggdrasil-spine`):**
- Modify `core/src/main/java/dev/krillin/bifrost/core/schema/Member.java` — add `semanticId`, `range`.
- Create `core/.../schema/Range.java` — `record Range(double low, double high)`.
- Modify `core/.../schema/UdtDefinition.java` — add reserved nullable `conformsTo`.
- Modify `core/src/main/resources/schema/definition.schema.json` — SPLIT shared `namedType` → `member`(+semanticId/range) & `param`(unchanged); add `conformsTo`.
- Create `core/.../schema/{GeneralSpec, MasterSpec, Setpoint, SetpointIntent}.java` — spec model records.
- Create `core/.../schema/{SpecConformanceChecker, SpecVerdict}.java` — structural+range evaluator + its own result type (do NOT reuse `Verdict`).
- Create `core/src/main/resources/schema/spec.schema.json` — published general/master spec contract.
- Modify `core/.../schema/RecipeDefinitionStore.java` — parameterize `publish(kind, …)`; update its 6 test call sites.
- Modify `core/.../schema/RecipePublish.java` — accept `--kind` flag, default `recipe-setpoints`. (`ProvenancePublish.java` needs NO change — raw pass-through.)
- Create `gates/src/main/java/dev/krillin/bifrost/gates/SpecGate.java` — `gates spec <registryDir> <masterSpecFile>`.
- Modify `gates/.../gates/GatesCli.java` — add `case "spec"`.
- Create `scripts/run-spec-gate.sh` — accept/reject gate.
- Tests: `core/src/test/.../schema/{SpecConformanceCheckerTest, SpecFormatConformanceTest, MemberAasShapeTest}.java`, `gates/src/test/.../gates/SpecGateTest.java`; UPDATE `FormatSpecConformanceTest` (reshaped member) + `gates` tests `{SchemaGateTest, GatesCliTest}` (~24 record call sites) + `RecipeDefinitionStoreTest` (kind param).

**Chunk 2 — bifrost sim:** `sim/.../EmbeddedMiloSim.java` gains a Mixer **ObjectType** (type node) + a Mixer instance; Mímir browses the type, Muninn reads the instance.

**Chunk 3 — NEW repo `mimir`:** `mimir/` Maven repo (own `dev.krillin.mimir` package, its OWN copy of the value types — zero shared code). Reads sim OPC-UA Mixer type → derives AAS-aligned `UdtDefinition` JSON → invokes `bifrost-gates.jar schema`. Seeded by lab `opcua/`.

**Chunk 4 — NEW repo `muninn`:** `muninn/` Maven repo. Consumes governed definition (bytes) → provenance-verify (recompute sha vs manifest) → Sparkplug B edge node NBIRTH(=governed def) + egress-validated NDATA → MQTT. Seeded by lab `spb40/`.

**Chunk 5 — MES fixtures + integration gate:** master/general spec fixtures + `bifrost/scripts/run-yggdrasil-spine-gate.sh` orchestrating sim+broker+gates+mimir+muninn, asserting the 5 spine assertions.

---

## Chunk 1: Bifrost core additions (spec model + `gates spec` + AAS-aligned reshape)

> Work in `bifrost/` on a NEW branch `feat/yggdrasil-spine` (off `main`). No cross-repo dependency; fully unit-testable. Done-bit: `mvn -q install` green + `run-spec-gate.sh [GATE] PASS`.

### Task 1.0: Branch

- [ ] **Step 1:** `cd bifrost && git checkout -b feat/yggdrasil-spine` (off `main`). Confirm clean.

### Task 1.1: Reshape `Member` to AAS-aligned (name, type, semanticId, range) + `Range` + `UdtDefinition.conformsTo`

**Files:** Create `core/.../schema/Range.java`; Modify `Member.java`, `UdtDefinition.java`, `definition.schema.json`; extend `core/src/test/.../schema/FormatSpecConformanceTest.java`.

- [ ] **Step 1: Write the failing test** — in a new `core/.../schema/MemberAasShapeTest.java`:
```java
package dev.krillin.bifrost.core.schema;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class MemberAasShapeTest {
  @Test void memberCarriesSemanticIdAndRange() {
    Member m = new Member("Rpm", "Double", "urn:bifrost:sem:Mixer/Rpm", new Range(0, 3000));
    assertEquals("urn:bifrost:sem:Mixer/Rpm", m.semanticId());
    assertEquals(3000.0, m.range().high());
  }
  @Test void nonNumericMemberHasNullRange() {
    Member m = new Member("Running", "Boolean", "urn:bifrost:sem:Mixer/Running", null);
    assertNull(m.range());
  }
}
```
- [ ] **Step 2: Run → FAIL** (`Range` missing; `Member` 4-arg ctor missing): `mvn -q -pl core test -Dtest=MemberAasShapeTest`.
- [ ] **Step 3: Implement** — `Range.java`: `public record Range(double low, double high) {}`. `Member.java`: `public record Member(String name, String type, String semanticId, Range range) {}` (keep the existing javadoc; note member ≈ AAS `Property`). `UdtDefinition.java`: add a trailing nullable field → `public record UdtDefinition(String templateRef, SemVer version, List<Member> members, List<Param> params, String conformsTo) {}` (reserved enterprise-template pointer, null this track; note ≈ AAS `Submodel`).
- [ ] **Step 4: Fix compile fallout — REPO-WIDE, not just `core`.** Reshaping the records breaks call sites in BOTH modules (`gates` depends on `core`; the Chunk-1 done-bit is `mvn install` at the whole bifrost root). Grep repo-wide: `grep -rn "new Member(" core/src gates/src` and `grep -rn "new UdtDefinition(" core/src gates/src`. Expect ~27 `new Member(` + ~24 `new UdtDefinition(`, of which **~24 live in `gates/src/test/.../{SchemaGateTest,GatesCliTest}.java`** — these MUST be updated too (`new Member(n,t,null,null)`, `new UdtDefinition(...,null)`), else the `gates` module won't compile. **Verify `CompatibilityChecker` is untouched semantically** — it reads only `.name()`/`.type()`; confirm it compiles + its tests pass unchanged.
- [ ] **Step 5: Update `definition.schema.json` — SPLIT the shared `namedType`.** Today `members` and `params` both `$ref` a single `#/definitions/namedType`. Adding a required `semanticId` to that shared node would break `Param` (unchanged, no semanticId) and fail the existing `FormatSpecConformanceTest` (it serializes a non-empty `params` with a `Param`). So: create `#/definitions/member` = `{name, type, semanticId(required), range(object{low,high}|null)}` and `#/definitions/param` = the OLD `{name, type}` shape; point `members` → `member`, `params` → `param`. Add `"conformsTo": {"type":["string","null"]}` to the top object; keep `additionalProperties:false`. Update descriptions to note the AAS-vocabulary intent (member ≈ AAS `Property`, definition ≈ AAS `Submodel`); optionally fix the stale `templateRef` description (it is the definition's own registry-key id, not a "parent template").
- [ ] **Step 6: Run** — `mvn -q -pl core test` → `MemberAasShapeTest` passes AND the extended `FormatSpecConformanceTest` (update its sample `UdtDefinition`/`Member` construction to the new shape and re-assert it validates against the updated `definition.schema.json`) passes AND `CompatibilityCheckerTest` unchanged-green.
- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(core): AAS-aligned Member (semanticId+range) + Range + UdtDefinition.conformsTo; definition.schema.json updated"`.

### Task 1.2: Spec model records + `spec.schema.json` (general/master)

**Files:** Create `GeneralSpec.java`, `MasterSpec.java`, `Setpoint.java`, `spec.schema.json`; test `SpecFormatConformanceTest.java`.

- [ ] **Step 1: Write the failing test** — `SpecFormatConformanceTest`: build a `MasterSpec` (see spec doc example), serialize with `JsonMapperFactory.create()`, assert it validates against `spec.schema.json` (networknt validator, already a core test dep); a negative (missing `equipmentRef`) fails. Same for `GeneralSpec`.
- [ ] **Step 2: Run → FAIL** (records + schema absent).
- [ ] **Step 3: Implement records** —
```java
public record Setpoint(String member, String type, double value) {}   // master: member = equipment member name
public record MasterSpec(String specRef, String version, String site,
                         String equipmentRef, String equipmentVersion, List<Setpoint> setpoints) {}
public record GeneralSpec(String specRef, String version, String productDomain,
                          List<SetpointIntent> setpointIntents) {}      // SetpointIntent(key,type,value) — equipment-independent
```
(Add `SetpointIntent(String key, String type, double value)`.) Author `spec.schema.json` (draft-07) describing both, matching the spec doc's example shapes.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(core): spec model (general/master, ISA-88 two-layer) + published spec.schema.json`.

### Task 1.3: `SpecConformanceChecker` — structural + range (core evaluator)

**Files:** Create `SpecConformanceChecker.java`, `SpecVerdict.java`; test `SpecConformanceCheckerTest.java`. (Reuse the existing `Violation` record; do NOT reuse `Verdict` — it is `record Verdict(CompatMode mode, boolean compatible, List<Violation>)` and `CompatMode` is a schema-compat-direction concept with no meaning for spec conformance. Use a dedicated result type.)

- [ ] **Step 1: Write the failing test** — `SpecConformanceCheckerTest`: given a `UdtDefinition` (Mixer: Rpm Double [0,3000], Temp Double [0,450], Running Boolean) and a `MasterSpec` with setpoints {Rpm=1500, Temp=200}: `check(def, spec)` → `SpecVerdict.conformant()==true`, no violations. Negatives: setpoint member "Ghost" not in def → structural violation (conformant false); Rpm type "Int32" ≠ def "Double" → type violation; Rpm=9999 > 3000 → range violation; setpoint on a member with null range but a numeric value is allowed (no range constraint).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** — `SpecVerdict.java`: `public record SpecVerdict(boolean conformant, List<Violation> violations) {}`. `SpecConformanceChecker.check(UdtDefinition def, MasterSpec spec)` returns a `SpecVerdict`: for each setpoint, find the member by name (structural, else add `[spec.member.unknown]`); type-match (`[spec.type.mismatch]`); if `member.range() != null`, assert `low ≤ value ≤ high` (`[spec.range.below-min]`/`[spec.range.above-max]`). `conformant = violations.isEmpty()`.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(core): SpecConformanceChecker (structural+range: master spec ⊨ equipment model)`.

### Task 1.4: `SpecGate` CLI + wire into `GatesCli`

**Files:** Create `gates/.../gates/SpecGate.java`; Modify `GatesCli.java`; test `gates/.../gates/SpecGateTest.java`.

- [ ] **Step 1: Write the failing test** — `SpecGateTest` (mirror `SchemaGateTest`, `@TempDir`): seed a registry `udt/Line1-Mixer/1.0.0.json` (Mixer def) + write a master-spec JSON; `SpecGate.run({registryDir, masterSpecFile})` → 0 conformant; out-of-range spec → 1; unknown-member spec → 1; bad args → 2; a master spec whose `equipmentRef@equipmentVersion` isn't in the registry → 2 (error). Add a `GatesCliTest` case: `GatesCli.run({"spec", registryDir, masterSpecFile})` → 0.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement `SpecGate`** — `run(args)`: `if (args.length < 2) usage→2`. Parse `MasterSpec` from `args[1]` via `JsonMapperFactory`. Load the pinned equipment def from `Path.of(args[0]).resolve("udt").resolve(spec.equipmentRef()).resolve(spec.equipmentVersion()+".json")` (NOT `latest()` — pinned version by path convention); if absent → err "equipment <ref>@<ver> not in registry" → 2. `SpecVerdict v = new SpecConformanceChecker().check(def, spec)`; print the violations + return 0 (`v.conformant()`) / 1 (violations). Wire `case "spec": return SpecGate.run(rest);` into `GatesCli` (usage string → `<schema|spec|policy|provenance>`).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(gates): SpecGate — gates spec <registryDir> <masterSpecFile> (structural+range) + GatesCli wire`.

### Task 1.5: `kind` parameterization for master-spec publish

**Files:** Modify `RecipeDefinitionStore.java`, `RecipePublish.java`; update tests `RecipeDefinitionStoreTest.java` (6 direct `store.publish(...)` call sites — will fail to compile once a leading `kind` param is added), `RecipePublishTest.java`/`ProvenancePublishTest.java`. NOTE: `ProvenancePublish.java` needs NO change — its `publish` subcommand already does `return RecipePublish.run(rest)` (raw pass-through), so a `--kind` flag flows through automatically; do not add parsing logic there.

- [ ] **Step 1: Write the failing test** — extend `RecipePublishTest`/`ProvenancePublishTest`: publishing with an explicit `--kind master-spec` yields a manifest whose `kind == "master-spec"`; default (no flag) stays `"recipe-setpoints"` (back-compat).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** — `RecipeDefinitionStore.publish(String kind, String ref, String version, byte[] bytes, String defRef, String sha, String sourcePath, long at)` (add leading `kind` param; `new RecipeManifest("recipe-setpoints", …)` → `new RecipeManifest(kind, …)`). Update the 6 `RecipeDefinitionStoreTest` call sites + any core call site to pass `"recipe-setpoints"` explicitly (default). `RecipePublish.run`: accept an optional trailing `--kind <k>` flag (default `recipe-setpoints`); pass it to `publish`. Keep all existing call sites working (default kind). (Wart flagged in the spec: `RecipeDefinitionStore.publish` also hardcodes the on-disk filename `recipe-setpoints.yaml` regardless of `kind`; master-spec bytes therefore land in a file so named — acceptable for the skeleton; leave a one-line code comment noting it.)
- [ ] **Step 4: Run → PASS** (incl. existing recipe/provenance tests unchanged with default kind).
- [ ] **Step 5: Commit** — `feat(core,gates): thread kind through publish (master-spec vs recipe-setpoints)`.

### Task 1.6: `run-spec-gate.sh` (Chunk-1 acceptance)

**Files:** Create `scripts/run-spec-gate.sh`; fixtures under `scripts/fixtures/gates/spec/`.

- [ ] **Step 1:** Fixtures: a registry `udt/Line1-Mixer/1.0.0.json` (Mixer def with ranges), a conformant master spec (Rpm=1500,Temp=200), an out-of-range master spec (Rpm=9999). Script (mirror `run-schema-gate.sh`): builds `core,gates` if jar missing; `gates spec <registry> <conformant>` → exit 0 (accept), `gates spec <registry> <out-of-range>` → exit 1 (reject); print `[GATE] PASS run-spec-gate.sh`. Use `cygpath -m` for jar/file paths.
- [ ] **Step 2: Run** — `bash scripts/run-spec-gate.sh` → `[GATE] PASS`.
- [ ] **Step 3: Commit** — `test(gate): run-spec-gate — accept conformant + reject out-of-range master spec [GATE PASS]`.

> **CHUNK 1 DONE-BIT (controller-direct):** `mvn -q install` green at bifrost root; `run-spec-gate.sh` `[GATE] PASS`; `run-schema-gate.sh`/`run-provenance-gate.sh` still `[GATE] PASS` (no regression). Controller runs these, not a subagent claim.

---

## Chunk 2: sim Mixer OPC-UA type node + instance (ELABORATED — ready to execute)

> **Repo:** `bifrost/sim`, branch `feat/yggdrasil-spine` (Chunk 1 already committed). **Goal:** the sim exposes a Mixer **ObjectType** (a type node Mímir browses in Chunk 3) + a Mixer **instance** (values Muninn samples in Chunk 4), with EU-range properties on the numeric members so Mímir can derive `range.low/high`. No cross-repo dependency; unit/integration-testable in-process.
>
> **CHUNK 2 DONE-BIT (controller-direct):** `mvn -q install` green at bifrost root — this runs the new `MixerTypeNodeTest` integration test (a real Milo **client** connects to the in-process sim, browses `ns=2;s=MixerType`, sees members {Rpm,Temp,Running,Secret} with correct DataTypes, decodes Rpm's EURange=[0,3000] & Temp's=[0,450], and reads the instance `ns=2;s=Line1/Mixer1.Rpm` value). AND `run-ncmd-runtime-gate.sh` still `[GATE] PASS` (no regression: the writable `Recipe/Rpm|Temp` nodes + the `Recipe/Secret` deny-target stay untouched). The **browse-read integration test IS the "sim smoke"** the outline named; a redundant standalone browse shell-gate is deferred — Chunk 3's `mimir` (seeded by lab `opcua/OpcUaBrowser`) is the real standalone browse client, so building a throwaway one now would churn. Controller runs the build + gate, never a subagent's PASS claim.

### Design decisions (LOCKED — every Milo 1.0.0 API below was javap-verified against the resolved `milo-{sdk-server,sdk-core,stack-core}-1.0.0.jar`; do NOT re-guess from 0.6.x memory)

- **Members** (the canonical "Line1 Mixer" from the spec): `Rpm` Double EURange **[0,3000]** · `Temp` Double EURange **[0,450]** · `Running` Boolean (no range) · `Secret` Double (no range — the deny-target member, mirroring `Recipe/Secret` in the ncmd gate). Only the two numeric process members carry an EURange.
- **Type node:** `ns=2;s=MixerType` — a `UaObjectTypeNode`, browseName `MixerType`, `isAbstract=false`, linked **inverse-HasSubtype → `BaseObjectType`** (so it is a proper ObjectType a browser can walk up, exactly as the lab's `OpcUaBrowser.addTypeChain` walks inverse `HasSubtype` and stops at `BaseObjectType`).
- **Type member vars:** `ns=2;s=MixerType.<Name>`, `typeDefinition=BaseDataVariableType`, dataType per member, wired onto the type via `mixerType.addComponent(memberVar)` (adds the forward `HasComponent` the client reads).
- **EURange property:** a `HasProperty` child variable `ns=2;s=MixerType.<Name>.EURange`, browseName `EURange`, `dataType=Identifiers.Range`, `typeDefinition=Identifiers.PropertyType`, value = a `Range(low,high)` **wrapped in an `ExtensionObject`**, wired via `numericMemberVar.addProperty(euRangeVar)`. The lab `OpcUaBrowser.readEngInfo` matches the property **by browseName "EURange"** (namespace-index-agnostic) and decodes the `Range` ExtensionObject — so `newQualifiedName("EURange")` (ns=2) is fine; if a strict ns=0 match is ever required use `new QualifiedName(0,"EURange")`.
- **Instance:** `ns=2;s=Line1/Mixer1` — a `UaObjectNode` with `typeDefinition = MixerType`'s NodeId, linked under `ObjectsFolder` via `Organizes` (browsable from Objects), carrying member instance vars `ns=2;s=Line1/Mixer1.<Name>` seeded **Rpm=1535.0, Temp=200.0, Running=true, Secret=42.0** (readable; Muninn only reads). Reuse the existing `makeDoubleNode` shape for the numeric instance vars; add a Boolean one for Running.
- **javap-verified API surface (use verbatim):**
  - `new UaObjectTypeNode.UaObjectTypeNodeBuilder(getNodeContext()).setNodeId(newNodeId("MixerType")).setBrowseName(newQualifiedName("MixerType")).setDisplayName(LocalizedText.english("MixerType")).setIsAbstract(false).buildAndAdd()`
  - `mixerType.addComponent(UaNode)` (public) · `mixerType.addSubtype(UaObjectTypeNode)` · `UaNode.addReference(org.eclipse.milo.opcua.sdk.core.Reference)` (public). **⚠ `UaNode.addProperty(UaVariableNode)` is PACKAGE-PRIVATE → NOT callable from `dev.krillin.bifrost.sim`; wire HasProperty with `addReference` instead (see `attachEuRange`).** `addComponent` IS public — the asymmetry is a trap.
  - `new org.eclipse.milo.opcua.sdk.core.Reference(NodeId source, NodeId refType, ExpandedNodeId target, boolean forward)` (there is also a `Reference(NodeId,NodeId,ExpandedNodeId,Reference.Direction)` overload)
  - `new org.eclipse.milo.opcua.stack.core.types.structured.Range(Double low, Double high)` (boxed Doubles)
  - `ExtensionObject.encode(EncodingContext ctx, UaStructuredType struct)` where `ctx = getNodeContext().getServer().getStaticEncodingContext()`
  - `new UaObjectNode.UaObjectNodeBuilder(getNodeContext()).setNodeId(...).setBrowseName(...).setDisplayName(...).setTypeDefinition(mixerType.getNodeId()).buildAndAdd()`
  - standard NodeIds via `org.eclipse.milo.opcua.stack.core.Identifiers.{Range, PropertyType, ObjectsFolder, BaseObjectType, BaseDataVariableType, Double, Boolean, HasSubtype, HasComponent, HasProperty, Organizes}` (inherited from `NodeIds0`) · `NodeId.expanded()` → `ExpandedNodeId`
  - **`EURange` is NOT a NodeId constant** — it is only a browseName string; do not look for `Identifiers.EURange`.
- **Known risk + mitigation (call it out to the reviewer):** whether `buildAndAdd()` + `addComponent`/`addProperty` make the refs visible to the client **browse service** is exactly what the RED→GREEN browse test proves. If the browse returns members but the client cannot see them, add the inverse references explicitly (`memberVar.addReference(new Reference(memberId, Identifiers.HasComponent, mixerTypeId.expanded(), /*forward*/ false))`). The lab browser walks **forward** HasComponent from the type and **forward** HasProperty from the member, so the forward refs added by the `addComponent`/`addProperty` helpers are what matter; the test is the arbiter.

### Task 2.1: `milo-sdk-client` test dependency + failing browse test (RED)

**Files:** modify `sim/pom.xml`; create `sim/src/test/java/dev/krillin/bifrost/sim/MixerTypeNodeTest.java`.

- [ ] **Step 1:** Add `milo-sdk-client` at **test scope** to `sim/pom.xml` (version is managed by the parent — no explicit `<version>`). This is the only new dependency; the sim ships as a server, the client is test-only.
- [ ] **Step 2: Write the failing test** — `MixerTypeNodeTest.browseMixerTypeMembersAndRanges()` (same package → can use package-private `EmbeddedMiloSim`/`BIND_PORT`):
  - `try (EmbeddedMiloSim sim = new EmbeddedMiloSim().start()) { OpcUaClient c = OpcUaClient.create("opc.tcp://localhost:" + EmbeddedMiloSim.BIND_PORT); c.connect(); … c.disconnect(); }`.
  - Browse members: `c.browse(new BrowseDescription(NodeId.parse("ns=2;s=MixerType"), BrowseDirection.Forward, Identifiers.HasComponent, false, Unsigned.uint(0xFF), Unsigned.uint(0x3F)))`; collect `ReferenceDescription.getBrowseName().getName()` → assert set `== {Rpm,Temp,Running,Secret}`.
  - Per member, read its `DataType` attribute (mirror `OpcUaBrowser.readAttribute(c, memberNodeId, AttributeId.DataType)`; `memberNodeId = ref.getNodeId().toNodeId(c.getNamespaceTable()).orElseThrow()`) → assert Rpm/Temp/Secret DataType NodeId numeric id `== 11` (Double), Running `== 1` (Boolean).
  - **Reach EURange by BROWSING (not `NodeId.parse`)** so the test exercises the exact HasProperty wiring `mimir` depends on (a direct `NodeId.parse` of the EURange node would pass even if the HasProperty ref were missing — B1's trap — and Chunk 3 browses forward HasProperty by browseName per `OpcUaBrowser.readEngInfo`): browse `MixerType.Rpm` forward `HasProperty`, assert a child with browseName `"EURange"` is returned, resolve its NodeId, then read + decode THAT node: `ExtensionObject eo = (ExtensionObject) dv.getValue().getValue(); Range r = (Range) eo.decode(c.getDynamicEncodingContext());` → assert `r.getLow()==0.0 && r.getHigh()==3000.0`. Same for `Temp` → [0,450].
- [ ] **Step 3: Run → FAIL** — `mvn -q -pl sim test -Dtest=MixerTypeNodeTest` (MixerType node absent → browse empty / read throws). **Kill orphan sim JVMs first** if a prior run leaked: `jps -lm | grep -i sim` → `taskkill //F //PID <pid>` (in-process test binds the fixed port 48400).

### Task 2.2: Build `MixerType` ObjectType + members + EURange (GREEN)

**Files:** modify `sim/src/main/java/dev/krillin/bifrost/sim/EmbeddedMiloSim.java` (the `SimNamespace` inner class only).

- [ ] **Step 1: Implement** — in `createNodes()`, after the existing `Recipe/*` nodes, call a new `createMixerType()`:
```java
private void createMixerType() {
    UaObjectTypeNode mixerType = new UaObjectTypeNode.UaObjectTypeNodeBuilder(getNodeContext())
            .setNodeId(newNodeId("MixerType"))
            .setBrowseName(newQualifiedName("MixerType"))
            .setDisplayName(LocalizedText.english("MixerType"))
            .setIsAbstract(false)
            .buildAndAdd();
    // MixerType is-subtype-of BaseObjectType (inverse HasSubtype); the lab browser walks this up.
    mixerType.addReference(new Reference(mixerType.getNodeId(), Identifiers.HasSubtype,
            Identifiers.BaseObjectType.expanded(), false));

    UaVariableNode rpm  = typeMember("MixerType.Rpm",  "Rpm",  Identifiers.Double,  0.0);
    UaVariableNode temp = typeMember("MixerType.Temp", "Temp", Identifiers.Double,  0.0);
    UaVariableNode run  = typeMember("MixerType.Running","Running", Identifiers.Boolean, false);
    UaVariableNode sec  = typeMember("MixerType.Secret","Secret", Identifiers.Double,  0.0);
    mixerType.addComponent(rpm); mixerType.addComponent(temp);
    mixerType.addComponent(run); mixerType.addComponent(sec);
    attachEuRange(rpm,  "MixerType.Rpm.EURange",  0.0, 3000.0);
    attachEuRange(temp, "MixerType.Temp.EURange", 0.0, 450.0);
}

private UaVariableNode typeMember(String id, String name, NodeId dataType, Object initial) {
    return new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
            .setNodeId(newNodeId(id)).setBrowseName(newQualifiedName(name))
            .setDisplayName(LocalizedText.english(name))
            .setDataType(dataType).setTypeDefinition(Identifiers.BaseDataVariableType)
            .setAccessLevel(Unsigned.ubyte(1)).setUserAccessLevel(Unsigned.ubyte(1))
            .setValue(new DataValue(new Variant(initial))).buildAndAdd();
}

private void attachEuRange(UaVariableNode member, String id, double low, double high) {
    ExtensionObject eu = ExtensionObject.encode(
            getNodeContext().getServer().getStaticEncodingContext(), new Range(low, high));
    UaVariableNode euRange = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
            .setNodeId(newNodeId(id)).setBrowseName(newQualifiedName("EURange"))
            .setDisplayName(LocalizedText.english("EURange"))
            .setDataType(Identifiers.Range).setTypeDefinition(Identifiers.PropertyType)
            .setValue(new DataValue(new Variant(eu))).buildAndAdd();
    // NB: UaNode.addProperty(...) is package-private (uncallable here) — wire HasProperty explicitly.
    member.addReference(new Reference(member.getNodeId(), Identifiers.HasProperty,
            euRange.getNodeId().expanded(), /*forward*/ true));
}
```
  Add the imports (`UaObjectTypeNode`, `org.eclipse.milo.opcua.sdk.core.Reference`, `ExtensionObject`, `Range`, `NodeId`). Leave the existing `Recipe/*` nodes and `makeDoubleNode` untouched.
- [ ] **Step 2: Run** — `mvn -q -pl sim test -Dtest=MixerTypeNodeTest#browseMixerTypeMembersAndRanges` → GREEN. If browse is empty, apply the inverse-reference mitigation noted above and re-run.
- [ ] **Step 3: Commit** — `feat(sim): MixerType OPC-UA ObjectType (Rpm/Temp/Running/Secret) + EURange on numeric members`.

### Task 2.3: Expose the `Line1/Mixer1` instance (GREEN)

**Files:** modify `EmbeddedMiloSim.java`; extend `MixerTypeNodeTest`.

- [ ] **Step 1: Write the failing test** — add `MixerTypeNodeTest.readMixerInstanceValues()`: read `ns=2;s=Line1/Mixer1.Rpm` == 1535.0, `…Temp` == 200.0, `…Running` == true, `…Secret` == 42.0; and browse `ns=2;s=Line1/Mixer1` HasComponent → 4 members. Run → FAIL (instance absent).
- [ ] **Step 2: Implement** — `createMixerInstance()` called from `createNodes()`:
```java
UaObjectNode mixer1 = new UaObjectNode.UaObjectNodeBuilder(getNodeContext())
        .setNodeId(newNodeId("Line1/Mixer1")).setBrowseName(newQualifiedName("Mixer1"))
        .setDisplayName(LocalizedText.english("Mixer1"))
        .setTypeDefinition(newNodeId("MixerType")).buildAndAdd();
mixer1.addReference(new Reference(mixer1.getNodeId(), Identifiers.Organizes,
        Identifiers.ObjectsFolder.expanded(), false)); // browsable under Objects
UaVariableNode iRpm  = typeMember("Line1/Mixer1.Rpm",  "Rpm",  Identifiers.Double,  1535.0);
UaVariableNode iTemp = typeMember("Line1/Mixer1.Temp", "Temp", Identifiers.Double,  200.0);
UaVariableNode iRun  = typeMember("Line1/Mixer1.Running","Running", Identifiers.Boolean, true);
UaVariableNode iSec  = typeMember("Line1/Mixer1.Secret","Secret", Identifiers.Double,  42.0);
mixer1.addComponent(iRpm); mixer1.addComponent(iTemp);
mixer1.addComponent(iRun); mixer1.addComponent(iSec);
```
  (Reuse the `typeMember` helper — an instance member var has the same shape; the distinction is only the `HasComponent` parent. `UaObjectNode.addComponent` exists on `UaNode`.) If the `Organizes`-under-Objects inverse ref is not enough for the instance-browse assertion, apply the same forward-ref mitigation.
- [ ] **Step 3: Run** — both `MixerTypeNodeTest` methods GREEN.
- [ ] **Step 4: Commit** — `feat(sim): Line1/Mixer1 instance (typed by MixerType) + seeded member values`.

> **CHUNK 2 DONE-BIT (controller-direct):** `mvn -q install` green at bifrost root (incl. both `MixerTypeNodeTest` methods) + `timeout 600 bash scripts/run-ncmd-runtime-gate.sh` still `[GATE] PASS` (requires Docker for HiveMQ — the no-regression check). Controller runs both; kill orphan `bifrost-sim.jar`/`bifrost-heimdall.jar` JVMs before the gate. Carry-forward for Chunk 3: Mímir browses `ns=2;s=MixerType` (members via forward HasComponent, ranges via the `EURange` HasProperty child) and maps to an AAS-aligned `UdtDefinition`; Chunk 4's Muninn samples `ns=2;s=Line1/Mixer1.<Name>` by those stable NodeIds. **Type-name vocabulary invariant:** Chunk 2 pins each member's DataType by OPC-UA **numeric id** (Double=11, Boolean=1). Chunk-3 mimir must reconstitute those to the EXACT case-sensitive literals Chunk-1 uses on the spec side — `11→"Double"`, `1→"Boolean"` — because `SpecConformanceChecker` type-matches by `String.equals` (Chunk-1 carry-forward seam #2). Assert in Chunk 3 that the derived `Member.type` string equals the spec-side literal, so no casing/alias drift silently blocks a legit spec.

## Chunk 3: `mimir` repo — northbound modeler (ELABORATED — ready to execute)

> **Repo:** NEW `Labs\[iiot]\mimir` — the FIRST sibling repo, its OWN git repo (LOCAL/unpushed), Maven, Java 17, package `dev.krillin.mimir`, **its OWN copy of the value types — ZERO shared code, NO Maven dependency on bifrost**. Cross-repo integration is ONLY: (a) a Milo **client** browsing the running bifrost `sim`, and (b) a **built-jar subprocess** call to `bifrost-gates.jar`. **Seed:** lab `opcua/` (`OpcUaBrowser` browse idioms, `UaTypeIds` id→type map, `OpcUaTypeMapper` derive shape). **Goal:** mimir browses the sim's `MixerType` → derives a bifrost-shaped `UdtDefinition` JSON → the bifrost ① `schema` gate governs (admits a valid def / rejects a breaking re-derive).
>
> **CHUNK 3 DONE-BIT (controller-direct):** `mvn -q install` in `mimir` green (HERMETIC unit tests: the pure `DefinitionDeriver` maps browsed members → the correct `UdtDefinition`, and JSON serialization matches the bifrost `definition.schema.json` byte-shape) AND `mimir/scripts/run-mimir-gate.sh` `[GATE] PASS` run BY the controller: it starts the real `bifrost-sim.jar`, has mimir **browse the live MixerType** and emit `def.json`, then `java -jar ../bifrost/gates/target/bifrost-gates.jar schema <freshRegistry> def.json --promote` → **exit 0** and `registry/udt/Line1-Mixer/1.0.0.json` now exists; a mimir **breaking re-derive** (drop a member, bump to 1.1.0) → `gates schema` → **exit 1** (member.removed under FORWARD). Kill orphan `bifrost-sim.jar` JVMs before/after. bifrost stays public+local; mimir is a new local repo, **no push**.

### Design decisions (LOCKED — grounded in the realized Chunk-2 shape + bifrost `definition.schema.json` + the `Line1-Mixer/1.0.0.json` fixture)

- **Own value types** (serialize via Jackson to EXACTLY the bifrost def JSON — verified against `bifrost/scripts/fixtures/gates/spec/udt/Line1-Mixer/1.0.0.json`): `record UdtDefinition(String templateRef, String version, List<Member> members, List<Param> params, String conformsTo)`, `record Member(String name, String type, String semanticId, Range range)`, `record Range(double low, double high)`, `record Param(String name, String type)`. Emit `version` as a `"1.0.0"` **string**, `range` as `{"low":..,"high":..}` or `null`, `params` as `[]`, `conformsTo` as `null`. (The gate deserializes leniently — Chunk-1 seam #1 — so matching the record/JSON shape is what matters, not schema-validation; but this shape also satisfies `definition.schema.json`.)
- **templateRef + version are mimir INPUTS (CLI args), NOT the OPC-UA browseName.** The OPC-UA type is `MixerType`; the canonical registry ref the spec side uses is `Line1-Mixer`. mimir is told "emit `MixerType` as `Line1-Mixer@1.0.0`" — this is the type-name reconciliation point (equipment ref ≠ OPC-UA type name). So the derive CLI takes `<typeBrowseName>` (what to browse) and `<templateRef> <version>` (what to file it as).
- **CF-A closure (CRITICAL — ties Chunk-1 seam #2):** DataType numeric id → the EXACT Sparkplug `MetricDataType.toString()` literal via mimir's OWN `UaTypeNames` map: `1→"Boolean", 6→"Int32", 7→"UInt32", 8→"Int64", 10→"Float", 11→"Double", 12→"String", 13→"DateTime"` (mirrors lab `UaTypeIds`; the sim exercises only 11/1). An unmapped id → fail loudly (throw), never emit a guessed/UNKNOWN string that would silently mis-key the gate. **Unit-assert** `derive` emits `type=="Double"` for id 11 and `"Boolean"` for id 1 — the exact case bifrost `String.equals` expects.
- **CF-B closure:** synthesize `Member.semanticId` = `"urn:bifrost:sem:" + shortType + "/" + memberName`, where `shortType` = the browsed type browseName with a trailing `"Type"` stripped (`"MixerType"→"Mixer"`). Matches the fixture (`urn:bifrost:sem:Mixer/Rpm`). Put the convention in a one-line javadoc + a unit test asserting the exact IRI.
- **CF-C:** mimir derives ALL members faithfully, **including `Secret`** (a Double with no EURange → `range=null`). Do NOT special-case Secret — the "deny target" semantic lives on the spec side, invisible here. The derived def has 4 members `{Rpm,Temp,Running,Secret}` (a superset of the hand fixture's 3 — intended and more faithful).
- **CF-D closure:** resolve the namespace index by URI, never hardcode `ns=2`: `UShort ns = client.getNamespaceTable().getIndex("urn:bifrost:opcua:sim"); NodeId typeNode = new NodeId(ns, typeBrowseName);` (`UriArray.getIndex(String)` javap-verified on `NamespaceTable`). Treat the URI as the stable key.
- **CF-H:** decode the EURange `Range` ExtensionObject with `client.getStaticEncodingContext()` (Chunk-2 proved dynamic returns a generic `DynamicStructType`; `Range` is an ns=0 builtin so static resolves it). The gate script's live browse is the proof; add a defensive check that decode yielded a non-null `Range`.
- **Live browse vs hermetic tests:** mimir cannot depend on `bifrost-sim` (zero shared code; sim is a bifrost module). So `mvn test` covers ONLY the pure `DefinitionDeriver` + JSON serialization (hermetic). The **live** Milo-client→sim browse is proven exclusively by `run-mimir-gate.sh` (which boots `bifrost-sim.jar`) — mirroring how the lab separates pure `OpcUaTypeMapper` (unit-tested) from the live `OpcUaBrowser` shell. This is a conscious split, not a coverage gap.
- **Gate invocation is at the SCRIPT level** (`run-mimir-gate.sh` calls the `bifrost-gates.jar` subprocess), not inside mimir's Java — equivalent proof of the zero-shared-code cross-repo seam (jar subprocess + published bytes), cleaner and more testable than embedding a `ProcessBuilder` in mimir. (Deviates from the outline's "mimir invokes" wording; the script-orchestrates form matches every other bifrost gate.)

### Task 3.1: mimir repo skeleton + own value types + JSON golden test (RED→GREEN)
**Files:** create `mimir/` repo: `pom.xml`, `src/main/java/dev/krillin/mimir/{UdtDefinition,Member,Range,Param}.java`, `src/main/java/dev/krillin/mimir/DefinitionJson.java` (Jackson writer); test `src/test/java/dev/krillin/mimir/DefinitionJsonTest.java`.
- [ ] **Step 1:** `cd Labs\[iiot] && mkdir mimir && cd mimir && git init` (new LOCAL repo; add a `.gitignore` for `target/`, `build/`). Standalone `pom.xml`: groupId `dev.krillin.mimir`, artifactId `mimir`, Java 17; deps `org.eclipse.milo:milo-sdk-client:1.0.0`, `com.fasterxml.jackson.core:jackson-databind:2.17.x`, `org.junit.jupiter:junit-jupiter:5.x` (test); maven-shade-plugin → `finalName mimir`, Main `dev.krillin.mimir.MimirMain`. **NO `<parent>`, NO bifrost dependency.**
- [ ] **Step 2 (RED — test only):** write ONLY `DefinitionJsonTest` (no production code yet, so it fails to compile = RED, the same Java-TDD RED as Chunk 1). It builds `new UdtDefinition("Line1-Mixer","1.0.0", List.of(new Member("Rpm","Double","urn:bifrost:sem:Mixer/Rpm", new Range(0,3000)), new Member("Running","Boolean","urn:bifrost:sem:Mixer/Running", null)), List.of(), null)`, calls `DefinitionJson.toJson(def)`, RE-PARSES the string into a `JsonNode`, and asserts on the TREE (format-independent): `templateRef=="Line1-Mixer"`, `version=="1.0.0"`, `members[0].range.high==3000`, `members[1].range` isNull, `params` is an empty array, `conformsTo` isNull. Run → FAIL (won't compile).
- [ ] **Step 3 (GREEN):** implement the four records + `DefinitionJson.toJson` — a `new ObjectMapper()` with **`setSerializationInclusion(Include.ALWAYS)`** (emit `range:null`/`conformsTo:null`/`params:[]`) and **compact single-line output (NO `INDENT_OUTPUT`)** so the gate script's downstream text checks are stable. `mvn -q test` green. Commit: `feat(mimir): repo skeleton + own AAS-shaped value types + definition JSON writer`.

### Task 3.2: `DefinitionDeriver` — pure browsed-members → `UdtDefinition` (RED→GREEN)
**Files:** create `dev/krillin/mimir/{BrowsedMember, UaTypeNames, DefinitionDeriver}.java`; test `DefinitionDeriverTest.java`.
- [ ] **Step 1 (RED):** `record BrowsedMember(String name, int dataTypeId, Double low, Double high)` (low/high null ⇒ no range). `UaTypeNames.of(int id)` → the CF-A map (throws `IllegalArgumentException` on unmapped). `DefinitionDeriver.derive(List<BrowsedMember> members, String typeBrowseName, String templateRef, String version)` → `UdtDefinition`: for each browsed member → `new Member(name, UaTypeNames.of(dataTypeId), synthSemanticId(typeBrowseName,name), (low!=null&&high!=null)? new Range(low,high): null)`; `synthSemanticId` = CF-B. `DefinitionDeriverTest`: feed the 4 Mixer members `[Rpm(11,0,3000), Temp(11,0,450), Running(1,null,null), Secret(11,null,null)]`, typeBrowseName "MixerType", ref "Line1-Mixer", ver "1.0.0" → assert: 4 members; Rpm.type=="Double" & range==[0,3000]; Running.type=="Boolean" & range==null (CF-A); Secret present, range==null (CF-C); Rpm.semanticId=="urn:bifrost:sem:Mixer/Rpm" (CF-B); templateRef/version echoed. Negative: an unmapped dataTypeId throws. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement; `mvn -q test` green. Commit: `feat(mimir): DefinitionDeriver — browsed members → AAS-aligned UdtDefinition (CF-A/B/C)`.

### Task 3.3: `OpcUaMixerBrowser` live shell + `MimirMain derive` CLI
**Files:** create `dev/krillin/mimir/{OpcUaMixerBrowser, MimirMain}.java`. (No unit test — live; proven by the gate.)
- [ ] **Step 1:** `OpcUaMixerBrowser.browse(OpcUaClient client, String nsUri, String typeBrowseName) → List<BrowsedMember>`: resolve `ns` via `UShort ns = client.getNamespaceTable().getIndex(nsUri)` and **fail loudly if `ns == null`** ("namespace URI '<uri>' not found on server") — no NPE (N3); `NodeId typeNode = new NodeId(ns, typeBrowseName)`; browse forward `HasComponent` (refType `new NodeId(0,47)`, `BrowseDirection.Forward`, `Unsigned.uint(0xFF)`, `Unsigned.uint(0x3F)`) → for each child **keep the `if (r.getNodeClass() != NodeClass.Variable) continue;` filter** (N5, faithful to the seed): read `DataType` attr → numeric id; browse the member's forward `HasProperty` (`new NodeId(0,46)`) for a child browseName `"EURange"` → read+decode its `Range` via `getStaticEncodingContext()` (CF-H) → low/high (null if absent). Mirror lab `OpcUaBrowser.readMembers/buildMember/readEngInfo` idioms exactly. **Browse FAITHFULLY — no test hooks in the live wire path.**
- [ ] **Step 2:** `MimirMain` CLI `derive <endpoint> <nsUri> <typeBrowseName> <templateRef> <version> <outFile> [--omit <member>]`: `OpcUaClient.create(endpoint); connect();` → `browse(...)` → **apply `--omit` HERE** (filter the browsed member out of the list in `MimirMain`, not in the browser — N2) → `DefinitionDeriver.derive(...)` → `DefinitionJson.toJson(...)` → write `outFile` → print a one-line summary (members/ranges) → disconnect. Exit 0 on success, 2 on error/usage.
- [ ] **Step 3:** `mvn -q package` builds `mimir.jar`. Commit: `feat(mimir): live OPC-UA MixerType browser + derive CLI (ns-by-URI, EURange decode)`.

### Task 3.4: `run-mimir-gate.sh` (Chunk-3 acceptance)
**Files:** create `mimir/scripts/run-mimir-gate.sh` (+ a `.gitignore`'d `build/gate/` workdir); a committed `mimir/scripts/fixtures/registry/policy.json` = `{"mode":"FORWARD"}`.
- [ ] **Step 1:** Script (mirror `bifrost/scripts/run-schema-gate.sh` + the sim-boot/orphan-kill idiom from `run-ncmd-runtime-gate.sh`; all native paths via `cygpath -m`):
  0. Build `mimir.jar` (`mvn -q package`); ensure sibling `../bifrost/sim/target/bifrost-sim.jar` + `../bifrost/gates/target/bifrost-gates.jar` exist (build via `mvn -q -pl sim,gates,core install` in `../bifrost` if missing). Fail loudly if a sibling jar is absent.
  1. Kill orphan `bifrost-sim.jar` JVMs; start `bifrost-sim.jar`; wait for `"OPC-UA sim listening"`.
  2. Seed a FRESH `build/gate/registry/` containing `policy.json` (mode FORWARD).
  3. **Accept:** `java -jar mimir.jar derive opc.tcp://localhost:48400 urn:bifrost:opcua:sim MixerType Line1-Mixer 1.0.0 build/gate/def.json` → assert exit 0 + `def.json` exists. Assert on FORMAT-ROBUST structural facts (S1 — do NOT grep a literal `"high": 3000`, since the value serializes as `3000.0` and spacing varies): member count via `[ "$(grep -c '"name"' build/gate/def.json)" -eq 4 ]`, and a tolerant range check `grep -Eq '"high"[[:space:]]*:[[:space:]]*3000(\.0)?' build/gate/def.json`. Then `java -jar bifrost-gates.jar schema build/gate/registry build/gate/def.json --promote` → assert **exit 0** + `[ -f build/gate/registry/udt/Line1-Mixer/1.0.0.json ]` (the promoted registry file is the authoritative structural proof).
  4. **Reject:** `java -jar mimir.jar derive opc.tcp://localhost:48400 urn:bifrost:opcua:sim MixerType Line1-Mixer 1.1.0 build/gate/def-breaking.json --omit Running` → `java -jar bifrost-gates.jar schema build/gate/registry build/gate/def-breaking.json` → assert **exit 1** (Running removed → FORWARD member.removed). (No `--promote`.)
  5. Kill the sim; `echo "[GATE] PASS run-mimir-gate.sh"`; exit 0. `trap` cleanup kills orphans on EXIT.
- [ ] **Step 2:** Controller runs `bash mimir/scripts/run-mimir-gate.sh` → `[GATE] PASS`. Commit: `test(gate): run-mimir-gate — mimir derives live MixerType → ① schema admits; breaking re-derive rejected [GATE PASS]`.

> **CHUNK 3 DONE-BIT (controller-direct):** `mvn -q install` in `mimir` green + `run-mimir-gate.sh` `[GATE] PASS`, both run BY the controller. Carry-forward for Chunk 4 (muninn): consume the PROMOTED `registry/udt/Line1-Mixer/1.0.0.json` bytes → provenance-verify → NBIRTH(governed def) + egress-validated NDATA from `ns=2;s=Line1/Mixer1.<Name>` (discover NodeIds by browse per CF-E; resolve ns by URI per CF-D; same DataType-string vocabulary per CF-A).

## Chunk 4: `muninn` repo — northbound feeder (ELABORATED — ready to execute)

> **Repo:** NEW `Labs\[iiot]\muninn` — the SECOND sibling repo, its OWN git repo (LOCAL/unpushed), Maven, Java 17, package `dev.krillin.muninn`, **its OWN copy of the value types — ZERO shared code, NO Maven dependency on bifrost**. Cross-repo integration is ONLY: (a) reading **published registry bytes** (`recipe/<ref>/<ver>/{recipe-setpoints.yaml, manifest.json}` produced by `bifrost-gates.jar provenance publish`), (b) a Milo **client** browsing the running bifrost `sim` instance, and (c) an MQTT wire contract (Sparkplug B over HiveMQ CE). **Seed:** lab `spb40/` (`Spb40Edge`/`DefinitionCodec`/`SchemaResolver` Tahu idioms) + resequence `control/…/drift` (provenance-verify-by-bytes idiom). **Goal:** muninn provenance-verifies a governed definition (refuse-on-tamper) → Sparkplug B **NBIRTH** carrying the EXACT verified def bytes + **egress-validated NDATA** sampled live from `ns=2;s=Line1/Mixer1.<Name>` (drop non-conformant) → MQTT UNS.
>
> **CHUNK 4 DONE-BIT (controller-direct):** `mvn -q install` in `muninn` green (HERMETIC unit tests: `Sha256`/provenance-verify refuse-on-tamper, def-JSON read, `UaMetricTypes` CF-A/V literal lock, egress-validate drop cases, Sparkplug NBIRTH/NDATA byte-exact round-trip) AND `muninn/scripts/run-muninn-gate.sh` `[GATE] PASS` run BY the controller (needs **Docker** for HiveMQ CE + a throwaway **git** repo for `gates provenance publish`): it boots HiveMQ CE + `bifrost-sim.jar`, mints the provenance pair from a 4-member `Line1-Mixer` def fixture, then observes the MQTT bus while muninn feeds — asserting (1) NBIRTH `governed_definition` bytes **byte-identical** to `recipe-setpoints.yaml`, (2) conformant NDATA carries all 4 members {Rpm,Temp,Running,Secret}, (3) `--inject-bogus Secret` → Secret **dropped** from NDATA (other 3 remain), (4) a **tampered** `recipe-setpoints.yaml` → muninn **refuses to birth** (exit 1, no NBIRTH). Controller runs both; kills orphan `bifrost-sim.jar`/`muninn.jar` JVMs + stops the broker first. bifrost stays public+local; muninn is a new local repo, **no push**.

### Design decisions (LOCKED — grounded in the realized Chunk 1–3 shapes + the researched provenance/Tahu/Paho/HiveMQ APIs; do NOT re-derive)

- **Own value types** (read the governed def JSON via Jackson, LENIENTLY — the gate re-serialized it pretty; muninn only READS): `record UdtDefinition(String templateRef, String version, List<Member> members, List<Param> params, String conformsTo)`, `record Member(String name, String type, String semanticId, Range range)`, `record Range(double low, double high)`, `record Param(String name, String type)`. muninn ignores `range`/`semanticId`/`conformsTo`/`params` for egress (name+type only per **CF-V**) but must deserialize them without error → the reader disables `FAIL_ON_UNKNOWN_PROPERTIES`.
- **⭐ CF-P (provenance chain — the load-bearing seam, RESEARCH-CORRECTED):** muninn reads the **provenance** tree, NOT the schema tree. `gates schema --promote` writes `registry/udt/<ref>/<ver>.json`; `gates provenance publish` writes the DISJOINT `registry/recipe/<ref>/<ver>/{recipe-setpoints.yaml, manifest.json}`. muninn `feed <registryDir> <ref> <version> …` resolves `registryDir/recipe/<ref>/<version>/recipe-setpoints.yaml` (**CF-P3: filename is opaque and hardcoded `recipe-setpoints.yaml` regardless of content — it CONTAINS JSON def bytes**) + sibling `manifest.json`.
  - **CF-P1 (gate ordering — mint before feed):** `gates provenance publish <registry> <sourceRepoDir> <sourcePath> <ref> [<version>]` REQUIRES the def file committed in a CLEAN git repo — it runs `git status --porcelain -- <sourcePath>` (must be blank → else exit 1 "dirty"), `git log -1 --format=%H -- <sourcePath>` (must be a 40-hex sha → else exit 1 "no commit"), and hashes the `git show <sha>:<sourcePath>` **blob** bytes. Arg order is `registryDir, sourceRepoDir, sourcePath, ref, [version=1.0.0]` — NOTE sourcePath is #3 and ref is #4 (not what an outline might guess). The gate script mints this in a throwaway repo.
  - **CF-P2 (verify-then-use SAME bytes, no TOCTOU):** muninn `Files.readAllBytes(recipe-setpoints.yaml)` ONCE → recompute SHA-256 → compare to `manifest.contentSha256` → on mismatch REFUSE (exit 1, no MQTT connect, no birth) → parse THOSE SAME bytes for the def AND embed THOSE SAME bytes verbatim in NBIRTH. Never re-read.
  - **CF-P4 (byte-rep reconciliation for the spine assertion "NBIRTH bytes == governed registry bytes"):** the gate provenance-publishes the SAME JSON file that would be schema-promoted (a `Line1-Mixer/1.0.0.json` def), so `recipe-setpoints.yaml` == that JSON blob byte-for-byte. muninn embeds those exact bytes → NBIRTH `governed_definition` metric == registry `recipe-setpoints.yaml`. muninn's OWN minimal manifest record: `record MuninnManifest(String ref, String version, String defRef, String contentSha256)` annotated `@JsonIgnoreProperties(ignoreUnknown=true)` (bifrost's real manifest has 7 fields — `kind,ref,version,defRef,contentSha256,sourcePath,publishedAt` — muninn drops the 3 it doesn't need). SHA-256 algo mirrors bifrost/resequence EXACTLY: `MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(...))` → lowercase hex `String.format("%02x", b)` per byte, no separator, over RAW bytes (never `readString`/CRLF-transform). Refuse-on-mismatch is a hard unit test (mirror resequence `BifrostManifestSeamTest`: append one byte → verify fails).
- **⭐ CF-B (NBIRTH def-bytes fidelity — DECISION PINNED):** NBIRTH carries the verified canonical def bytes as a dedicated **`Bytes` metric named `governed_definition`** (value = the exact `byte[]` from CF-P2). A Tahu Sparkplug **UDT Template is structured and lossy** (no slot for AAS `semanticId`/`range`) → it CANNOT be byte-equal to the governed JSON, so it is NOT used as the authoritative carrier. A `Bytes` metric protobuf-round-trips byte-exact, satisfying the spine's "NBIRTH bytes == governed registry bytes" assertion unambiguously. (No structured template is emitted — YAGNI; the byte-exact metric is the contract.) Metric name constant `GOVERNED_DEFINITION = "governed_definition"`.
- **⭐ CF-V + CF-A (egress vocabulary lock):** muninn egress-validates each live sample by **member name + type** (NOT range — range is the southbound spec-gate's job). The def carries `"Double"`/`"Boolean"` string literals (mimir CF-A). When muninn reads OPC-UA `Line1/Mixer1.<Name>` and republishes as a Sparkplug `Metric`, that metric's `MetricDataType.toString()` MUST equal the def literal. muninn owns its OWN `UaMetricTypes.of(int opcuaId) → MetricDataType` map (mirrors mimir `UaTypeNames` but lands on the **Tahu enum**, not a String): `11→MetricDataType.Double, 1→MetricDataType.Boolean` (+ `6→Int32,7→UInt32,8→Int64,10→Float,12→String,13→DateTime` for completeness); unmapped id → throw (never guess). **Unit-assert `UaMetricTypes.of(11).toString().equals("Double")` and `of(1).toString().equals("Boolean")`** — the exact case-sensitive literals the def uses — so a casing/alias drift can never silently drop a conformant sample. (Tahu 1.0.14 `MetricDataType` has NO `valueOf`; `.toString()` yields the canonical name — this is the same literal spb40's `TahuTypes` reflects on.)
- **CF-DROP (testability hook — faithful path stays clean):** all 4 live members ARE in the def → a faithful browse yields only conformant samples, so the "drop non-conformant" path needs an INJECTION hook. `MuninnMain feed … --inject-bogus <member>` re-tags THAT member's outgoing metric with a deliberately WRONG `MetricDataType` (e.g. `String` for the numeric `Secret`) so egress-validate's type-check drops it. The hook is applied in the **`MuninnMain`/egress assembly layer, NEVER in the `OpcUaInstanceBrowser`** faithful read path (same N2 discipline mimir used for `--omit`). Required for the done-bit "non-conformant sample not published."
- **CF-SECRET (design-confirm, not a bug):** muninn faithfully births + egresses `Secret` to the UNS (just a Double; the "deny target" semantic is southbound-command-only per CF-C, invisible northbound). Correct per plan. (The `--inject-bogus Secret` DROP test corrupts Secret's TYPE to exercise the drop path; the faithful run publishes Secret normally.)
- **CF-D / CF-E (resolve-by-URI, discover-by-browse):** muninn resolves the namespace index by URI (`client.getNamespaceTable().getIndex("urn:bifrost:opcua:sim")`, fail loudly if null — never hardcode `ns=2`), and DISCOVERS the instance member NodeIds by browsing `Line1/Mixer1` forward `HasComponent` (+ reading each member's `DataType` attribute + value), NOT by string-concatenating `ns=2;s=Line1/Mixer1.<Name>`. mimir `OpcUaMixerBrowser` is the seed idiom (same `HAS_COMPONENT=new NodeId(0,47)`, `NodeClass.Variable` filter, `r.getNodeId().toNodeId(nt)` resolution).
- **Publish semantics (honest limitation, PINNED):** NBIRTH is `seq=0`, NDATA is `seq=1`, both **QoS 0, non-retained** (spb40 idiom). muninn does NOT implement full Sparkplug session semantics (no bdSeq / NDEATH-will / STATE / rebirth) — it is a governance-egress concept prototype, not a spec-compliant edge node (documented limitation; `tahu-edge` session machinery is deliberately unused). Because NDATA is non-retained, the gate's subscriber MUST be connected+subscribed BEFORE `feed` publishes → `observe` prints a readiness log line the gate waits for (wait-for-log handshake, mirroring the sim-boot pattern).
- **muninn owns its broker:** muninn ships its OWN `docker-compose.yml` (`hivemq-ce`, image `hivemq/hivemq-ce:latest`, `container_name: muninn-hivemq-ce`, port `1883:1883`, env `HIVEMQ_ALLOW_ALL_CLIENTS: "true"` — MANDATORY, HiveMQ CE 2026.x refuses all clients without it). Zero dependency on bifrost's compose. (Port 1883 is shared-by-convention — only one broker at a time; the gate stops it in `trap`. Controller kills a stale `bifrost-hivemq-ce` if it holds 1883.)
- **Own subscriber as the gate oracle:** muninn ships a `MuninnConsumer` (Paho `MqttCallback`, mirrors spb40 `SchemaResolvingConsumer`) exposed via `MuninnMain observe` — it captures the NBIRTH `governed_definition` bytes → a file and the NDATA member names → a file, so the gate asserts with `cmp`/`grep` (no external `mosquitto_sub` dependency).
- **Live vs hermetic split** (mirrors mimir): `mvn test` covers ONLY pure logic — provenance-verify, def-JSON read, `UaMetricTypes`, egress-validate, and Sparkplug encode/decode round-trip (all hermetic, no broker/sim). The LIVE browse→publish→subscribe path is proven exclusively by `run-muninn-gate.sh`. Conscious split, not a coverage gap.

### Task 4.1: muninn repo skeleton + own value types + def-JSON reader (RED→GREEN)

**Files:** create `muninn/` repo: `pom.xml`, `docker-compose.yml`, `.gitignore`, `src/main/java/dev/krillin/muninn/{UdtDefinition,Member,Range,Param}.java`, `src/main/java/dev/krillin/muninn/DefinitionJson.java` (Jackson reader); test `src/test/java/dev/krillin/muninn/DefinitionJsonReadTest.java`.

- [ ] **Step 1:** `cd Labs\[iiot] && mkdir muninn && cd muninn && git init` (new LOCAL repo). `.gitignore`:
```
target/
build/
dependency-reduced-pom.xml
```
- [ ] **Step 2:** Standalone `pom.xml` (mirror mimir's shape — **NO `<parent>`, NO bifrost dependency**): groupId `dev.krillin.muninn`, artifactId `muninn`, version `0.1.0-SNAPSHOT`, `maven.compiler.source/target=17`, `sourceEncoding=UTF-8`. Dependencies (exact):
  - `org.eclipse.milo:milo-sdk-client:1.0.0`
  - `org.eclipse.tahu:tahu-core:1.0.14`
  - `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`
  - `com.fasterxml.jackson.core:jackson-databind:2.17.2` (declare explicitly — beats Tahu's transitive 2.13.4 by nearest-wins)
  - `ch.qos.logback:logback-classic:1.3.14` (Tahu logs via slf4j; matches the seed's pinned logback line)
  - `org.junit.jupiter:junit-jupiter:5.10.2` (scope `test`)
  Build: `maven-surefire-plugin:3.2.5` (bare) + `maven-shade-plugin:3.5.1` bound to `package`/`shade` with `<finalName>muninn</finalName>`, `ManifestResourceTransformer` mainClass `dev.krillin.muninn.MuninnMain`, `ServicesResourceTransformer` (Milo + Paho SPI), and a `*:*` filter excluding `META-INF/*.SF`,`*.DSA`,`*.RSA`.
- [ ] **Step 3:** `docker-compose.yml` (muninn owns its broker):
```yaml
services:
  hivemq-ce:
    image: hivemq/hivemq-ce:latest
    container_name: muninn-hivemq-ce
    ports:
      - "1883:1883"
    environment:
      HIVEMQ_ALLOW_ALL_CLIENTS: "true"   # HiveMQ CE 2026.x is secure-by-default; without this it refuses ALL MQTT clients
    restart: unless-stopped
```
- [ ] **Step 4 (RED — test only):** write ONLY `DefinitionJsonReadTest` (no production code yet → won't compile = RED). It embeds a governed def JSON **string** matching the gate's promoted shape (pretty-printed, 4 members) — e.g. `{"templateRef":"Line1-Mixer","version":"1.0.0","members":[{"name":"Rpm","type":"Double","semanticId":"urn:bifrost:sem:Mixer/Rpm","range":{"low":0.0,"high":3000.0}},{"name":"Running","type":"Boolean","semanticId":"urn:bifrost:sem:Mixer/Running","range":null},{"name":"Secret","type":"Double","semanticId":"urn:bifrost:sem:Mixer/Secret","range":null}],"params":[],"conformsTo":null}` — calls `UdtDefinition def = DefinitionJson.fromBytes(json.getBytes(StandardCharsets.UTF_8))` and asserts: `def.templateRef().equals("Line1-Mixer")`, `def.members().size()==3`, member `Rpm` has `type().equals("Double")`, member `Running` has `type().equals("Boolean")` and `range()==null`. (Extra/unknown props tolerated: add a stray `"extra":1` and assert no throw.) Run → FAIL (won't compile): `mvn -q test -Dtest=DefinitionJsonReadTest`.
- [ ] **Step 5 (GREEN):** implement the four records + `DefinitionJson.fromBytes(byte[]) → UdtDefinition`: `ObjectMapper` with `configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)`; `mapper.readValue(bytes, UdtDefinition.class)` (jackson-databind 2.17 deserializes records natively). Wrap failures in `RuntimeException("Failed to parse governed definition JSON", e)`. `mvn -q test` green. Commit: `feat(muninn): repo skeleton + own AAS-shaped value types + lenient definition JSON reader`.

### Task 4.2: `Sha256` + `MuninnManifest` + `ProvenanceVerifier` — verify/refuse-on-tamper (RED→GREEN)

**Files:** create `dev/krillin/muninn/{Sha256, MuninnManifest, ProvenanceVerifier, VerifiedDefinition}.java`; test `ProvenanceVerifierTest.java`. Seed idiom: resequence `Sha256.kt` + `BifrostManifestSeamTest`.

- [ ] **Step 1 (RED):** `ProvenanceVerifierTest` (`@TempDir`): write a `recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml` containing known def JSON bytes; compute its real sha256 (in the test, via `MessageDigest` — or hardcode after a first run) and write a sibling `manifest.json` `{"kind":"recipe-setpoints","ref":"Line1-Mixer","version":"1.0.0","defRef":"<40-hex>","contentSha256":"<sha>","sourcePath":"Line1-Mixer-1.0.0.json","publishedAt":123}` (7 fields — proves `@JsonIgnoreProperties` drops the extras). Assertions:
  - `ProvenanceVerifier.verify(registryDir, "Line1-Mixer", "1.0.0")` returns a `VerifiedDefinition` whose `bytes()` equal the file bytes and whose `manifest().contentSha256()` equals the computed sha (happy path).
  - **Tamper:** append one byte to `recipe-setpoints.yaml` (`Files.write(path, extra, APPEND)`) → `verify(...)` throws `ProvenanceException` (or returns a refuse signal) — assert it REFUSES (mirror resequence `tampered → UNVERIFIED`). No laundered reference.
  - Missing manifest / missing recipe file → refuse (exit-2-worthy error, distinct from tamper).
  Run → FAIL (won't compile).
- [ ] **Step 2 (GREEN):** implement —
  - `Sha256.hex(byte[] b)`: `MessageDigest.getInstance("SHA-256").digest(b)` → `StringBuilder`, `for (byte x : d) s.append(String.format("%02x", x))`. (Byte-identical to bifrost `RecipePublish.sha256hex` + resequence `Sha256`.)
  - `record MuninnManifest(String ref, String version, String defRef, String contentSha256) {}` annotated `@JsonIgnoreProperties(ignoreUnknown = true)`.
  - `record VerifiedDefinition(byte[] bytes, MuninnManifest manifest) {}`.
  - `ProvenanceVerifier.verify(Path registryDir, String ref, String version) throws ProvenanceException`: `Path dir = registryDir.resolve("recipe").resolve(ref).resolve(version); Path canonical = dir.resolve("recipe-setpoints.yaml"); Path manifestFile = dir.resolve("manifest.json");` — if either missing → throw `ProvenanceException("no published recipe for " + ref + "@" + version)`. `byte[] bytes = Files.readAllBytes(canonical)` (raw, ONCE — CF-P2). `MuninnManifest m = mapper.readValue(manifestFile.toFile(), MuninnManifest.class)`. `String computed = Sha256.hex(bytes);` `if (m.contentSha256()==null || m.contentSha256().isBlank() || !computed.equals(m.contentSha256())) throw new ProvenanceException("provenance mismatch: computed " + computed + " != manifest " + m.contentSha256());`. Return `new VerifiedDefinition(bytes, m)`. (Filename `recipe-setpoints.yaml` treated as opaque — CF-P3.)
  Run → PASS. Commit: `feat(muninn): provenance-verify governed def by bytes (sha256 recompute vs manifest, refuse-on-tamper) — CF-P2/P4`.

### Task 4.3: `UaMetricTypes` + `EgressValidator` — vocabulary lock + drop cases (RED→GREEN)

**Files:** create `dev/krillin/muninn/{UaMetricTypes, SampledMember, EgressValidator}.java`; tests `UaMetricTypesTest.java`, `EgressValidatorTest.java`.

- [ ] **Step 1 (RED):**
  - `record SampledMember(String name, MetricDataType type, Object value) {}` (the OPC-UA read already resolved to a Tahu type via `UaMetricTypes`; `--inject-bogus` may set a wrong `type`).
  - `UaMetricTypes.of(int opcuaId) → MetricDataType` map (throws `IllegalArgumentException` on unmapped).
  - `EgressValidator.conformant(SampledMember s, UdtDefinition def) → boolean`: true iff def has a member with `name.equals(s.name())` AND that member's `type().equals(s.type().toString())`.
  - `UaMetricTypesTest`: `assertEquals("Double", UaMetricTypes.of(11).toString())`, `assertEquals("Boolean", UaMetricTypes.of(1).toString())` (**CF-A/V literal lock**); `assertThrows(IllegalArgumentException.class, () -> UaMetricTypes.of(999))`.
  - `EgressValidatorTest`: build the 3-member Mixer def (from Task 4.1 fixture: Rpm Double, Running Boolean, Secret Double). Conformant: `new SampledMember("Rpm", MetricDataType.Double, 1535.0)` → true. Drop — unknown member: `new SampledMember("Ghost", MetricDataType.Double, 0.0)` → false. Drop — type mismatch (the `--inject-bogus` shape): `new SampledMember("Secret", MetricDataType.String, "x")` → false.
  Run → FAIL.
- [ ] **Step 2 (GREEN):** implement `UaMetricTypes` (`Map.of(1, MetricDataType.Boolean, 6, MetricDataType.Int32, 7, MetricDataType.UInt32, 8, MetricDataType.Int64, 10, MetricDataType.Float, 11, MetricDataType.Double, 12, MetricDataType.String, 13, MetricDataType.DateTime)`) + `EgressValidator`. Run → PASS. Commit: `feat(muninn): UaMetricTypes (OPC-UA id → Tahu MetricDataType, CF-A/V lock) + EgressValidator (name+type, drop non-conformant)`.

### Task 4.4: Sparkplug codec — NBIRTH(governed_definition Bytes) + NDATA, byte-exact round-trip (RED→GREEN)

**Files:** create `dev/krillin/muninn/SparkplugCodec.java`; test `SparkplugCodecTest.java`. Seed idiom: spb40 `Spb40Edge`/`ThinCodec` (Tahu `SparkplugBPayloadBuilder`/`MetricBuilder`/`SparkplugBPayloadEncoder`/`Decoder`).

- [ ] **Step 1 (RED):** `SparkplugCodecTest` (hermetic — no MQTT):
  - `byte[] defBytes = "…governed json…".getBytes(UTF_8);` `SparkplugBPayload birth = SparkplugCodec.buildNbirth(defBytes);` `byte[] wire = new SparkplugBPayloadEncoder().getBytes(birth, false);` `SparkplugBPayload rt = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);` — find the metric named `SparkplugCodec.GOVERNED_DEFINITION`, assert `(byte[]) metric.getValue()` is **`Arrays.equals` to `defBytes`** (CF-B byte-exact through protobuf) and `metric.getDataType().toString().equals("Bytes")` (value-equality, not reference `==` — robust if the decoder reconstructs a fresh `MetricDataType` instance) and `birth.getSeq()==0L`.
  - `List<SampledMember> samples = List.of(new SampledMember("Rpm", MetricDataType.Double, 1535.0), new SampledMember("Running", MetricDataType.Boolean, true));` `SparkplugBPayload ndata = SparkplugCodec.buildNdata(samples, 1);` encode→decode→assert metric names == {Rpm,Running}, `Rpm` value 1535.0, `getSeq()==1L`.
  Run → FAIL.
- [ ] **Step 2 (GREEN):** implement `SparkplugCodec`:
```java
public static final String GOVERNED_DEFINITION = "governed_definition";
public static SparkplugBPayload buildNbirth(byte[] defBytes) throws Exception {
    return new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(0L)
        .addMetric(new MetricBuilder(GOVERNED_DEFINITION, MetricDataType.Bytes, defBytes).createMetric())
        .createPayload();
}
public static SparkplugBPayload buildNdata(List<SampledMember> conformant, long seq) throws Exception {
    SparkplugBPayloadBuilder b = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(seq);
    for (SampledMember s : conformant)
        b.addMetric(new MetricBuilder(s.name(), s.type(), s.value()).createMetric());
    return b.createPayload();
}
```
  (`new Date()` is fine here — this is runtime code, not a workflow script.) Run → PASS. Commit: `feat(muninn): Sparkplug B codec — NBIRTH governed_definition Bytes metric (byte-exact) + NDATA (CF-B)`.

### Task 4.5: live shell — OPC-UA instance browser + MQTT publisher + `MuninnConsumer` + `MuninnMain` CLI (feed/observe)

**Files:** create `dev/krillin/muninn/{OpcUaInstanceBrowser, MqttPublisher, MuninnConsumer, MuninnMain}.java`. (No unit test — live; proven by the gate. Mirror mimir's live-shell discipline.)

- [ ] **Step 1 — `OpcUaInstanceBrowser.sample(OpcUaClient client, String nsUri, String instanceNodeIdent) → List<SampledMember>`:** ⚠ `instanceNodeIdent` is the NodeId **string identifier** `"Line1/Mixer1"` (what follows `s=`), NOT the browseName `"Mixer1"` — passing the browseName resolves to no node. resolve `UShort ns = client.getNamespaceTable().getIndex(nsUri)`; **fail loudly if null** ("namespace URI '<uri>' not found on server" — CF-D, no NPE). `NodeId instance = new NodeId(ns, instanceNodeIdent)`. Browse forward `HasComponent` (`new NodeId(0,47)`, `BrowseDirection.Forward`, `Unsigned.uint(0xFF)`, `Unsigned.uint(0x3F)`) → for each ref keep `if (r.getNodeClass() != NodeClass.Variable) continue;` (CF-E, faithful — no test hooks here): `String name = r.getBrowseName().getName(); NodeId memberNode = r.getNodeId().toNodeId(nt).orElse(null);` read `DataType` attr → numeric id → `MetricDataType t = UaMetricTypes.of(id)` (CF-V); read the value via `client.readValue(0.0, TimestampsToReturn.Both, memberNode).getValue().getValue()`; add `new SampledMember(name, t, value)`. (Reuse mimir `OpcUaMixerBrowser` idioms verbatim for browse/DataType read.)
- [ ] **Step 2 — `MqttPublisher`** (Paho v3, mirror `Spb40Edge`): ctor connects `MqttClient(broker, "muninn-edge-"+edge, new MemoryPersistence())` with `MqttConnectOptions.setCleanSession(true)`. `publish(String topic, byte[] bytes, int qos, boolean retained)`. Topic builder `topic(group, msgType, edge) → "spBv1.0/"+group+"/"+msgType+"/"+edge`. `close()` guarded by `isConnected()`.
- [ ] **Step 3 — `MuninnConsumer`** (Paho `MqttCallback`, mirror `SchemaResolvingConsumer`): ctor **truncates/creates both `birthOutFile` and `ndataOutFile` empty at start** (so a prior sub-run's captures can NEVER leak into this run's assertions — the staleness hazard). connect `MqttClient(broker,"muninn-observer",…)`, subscribe `"spBv1.0/"+group+"/#"` QoS 1, **print `"[OBSERVE] subscribed spBv1.0/"+group+"/#"` then flush** (the gate's readiness handshake — subscribe is synchronous so the observer is genuinely ready once this prints). `messageArrived`: split topic on `/`, `type=p[2]`; decode via `SparkplugBPayloadDecoder().buildFromByteArray(m.getPayload(), null)`; if `type.equals("NBIRTH")` → find `governed_definition` metric → `Files.write(birthOutFile, (byte[]) metric.getValue())` (overwrite); if `type.equals("NDATA")` → **append** each `metric.getName()` (one per line) to `ndataOutFile` (`StandardOpenOption.APPEND`); count captured; when `birthSeen && ndataMembers >= expectNdata` → signal done (`CountDownLatch`). Expose `awaitCapture(long timeoutMs) → boolean`. (Each gate sub-run passes DISTINCT output paths too — belt-and-suspenders; see Task 4.6.)
- [ ] **Step 4 — `MuninnMain`** (positional CLI, mirror mimir; exit 0 ok / 1 governance-refuse / 2 usage-or-error):
  - `feed <registryDir> <ref> <version> <opcEndpoint> <nsUri> <instanceNodeIdent> <mqttUrl> <group> <edge> [--inject-bogus <member>]`:
    1. `VerifiedDefinition v = new ProvenanceVerifier().verify(Path.of(registryDir), ref, version)` — on `ProvenanceException` print `[MUNINN] refuse: <msg>` and **`System.exit(1)` BEFORE any MQTT/OPC connect** (CF-P2 refuse-to-birth). 
    2. `UdtDefinition def = DefinitionJson.fromBytes(v.bytes())`.
    3. `MqttPublisher pub = new MqttPublisher(mqttUrl, edge);` publish **NBIRTH**: `pub.publish(topic(group,"NBIRTH",edge), enc.getBytes(SparkplugCodec.buildNbirth(v.bytes()), false), 0, false)` (governed_definition == the SAME verified bytes — CF-P2/P4).
    4. `OpcUaClient c = OpcUaClient.create(opcEndpoint); c.connect();` `List<SampledMember> raw = OpcUaInstanceBrowser.sample(c, nsUri, instanceNodeIdent);`.
    5. **Apply `--inject-bogus <member>` HERE** (assembly layer — CF-DROP): map `raw` → if `--inject-bogus` names a member, replace that `SampledMember`'s `type` with a deliberately wrong `MetricDataType.String` (keep others intact). 
    6. **Egress-validate:** `List<SampledMember> ok = raw.stream().filter(s -> EgressValidator.conformant(s, def)).toList();` — log each dropped member (`[MUNINN] drop <name>: <reason>`).
    7. Publish **NDATA**: `pub.publish(topic(group,"NDATA",edge), enc.getBytes(SparkplugCodec.buildNdata(ok, 1), false), 0, false)`. Print summary (birthed N def bytes, published K/total members). `c.disconnect(); pub.close();` exit 0.
  - `observe <mqttUrl> <group> <birthOutFile> <ndataOutFile> [--expect-ndata N] [--timeout-ms M]`: start `MuninnConsumer`, print readiness line, `awaitCapture(timeout)`; write outputs; exit 0 if captured, 2 on timeout.
  Guard `args.length`/subcommand → usage + `exit(2)`; catch-all → `[MUNINN] error: <msg>` + `exit(2)`.
- [ ] **Step 5:** `mvn -q package` builds `muninn.jar`. Commit: `feat(muninn): live OPC-UA instance sampler + MQTT Sparkplug publisher + observer + feed/observe CLI (CF-D/E, --inject-bogus)`.

### Task 4.6: `run-muninn-gate.sh` (Chunk-4 acceptance) + fixtures

**Files:** create `muninn/scripts/run-muninn-gate.sh`; fixture `muninn/scripts/fixtures/Line1-Mixer-1.0.0.json` (the 4-member governed def: Rpm Double[0,3000], Temp Double[0,450], Running Boolean, Secret Double null-range — matching the sim instance so all 4 samples conform). **⚠ the fixture MUST carry the EXACT case-sensitive literals `"type":"Double"` / `"type":"Boolean"`** (= `UaMetricTypes.of(11|1).toString()`); any casing/alias drift makes egress-validate silently drop every live sample. `muninn/scripts/fixtures/.gitattributes` (`* -text` — pin byte fidelity so the git blob bifrost hashes is byte-identical to the checkout, CF-P4 on this Windows repo). `.gitignore`'d `build/gate/` workdir.

- [ ] **Step 1:** Script (mirror `mimir/scripts/run-mimir-gate.sh` + the HiveMQ/orphan idioms from `bifrost/scripts/run-ncmd-runtime-gate.sh`; `set -euo pipefail`; every native path via `cygpath -m`; `git -C` gets the Windows path):
  - **0. Preflight + build:** `command -v docker` (fail if absent — "Docker Desktop required for the MQTT broker"); `mvn -q package` (muninn.jar); ensure `../bifrost/{sim,gates}/target/{bifrost-sim,bifrost-gates}.jar` exist (build via `( cd ../bifrost && mvn -q -pl core,sim,gates install )` if missing); existence-check each jar (`fail` on missing). Define `kill_by_mainclass()` (jps→taskkill on jar-filename substring). Pre-run sweep: `kill_by_mainclass bifrost-sim.jar`, `kill_by_mainclass muninn.jar`, and **`docker rm -f bifrost-hivemq-ce muninn-hivemq-ce 2>/dev/null || true`** (a foreign broker container — e.g. bifrost's — holding host port 1883 would make our `compose up` fail to bind; the gate must be self-contained, not rely on the controller having killed it). `trap cleanup EXIT` → kill sim/muninn/observe PIDs + `kill_by_mainclass` both jars + `docker compose -f "$COMPOSE_WIN" stop hivemq-ce`.
  - **1. Broker:** `docker compose -f "$COMPOSE_WIN" up -d hivemq-ce`; TCP-probe `localhost:1883` (`for i in $(seq 1 30); do bash -c "echo > /dev/tcp/localhost/1883" && break; sleep 2; done`); dump `docker compose logs` + `fail` if never open.
  - **2. Sim:** start `bifrost-sim.jar > sim.log`; wait for `"OPC-UA sim listening"` (30×1s).
  - **3. Mint provenance pair** (CF-P1): `rm -rf build/gate; mkdir -p build/gate/{registry,srcrepo,out}`; `cp fixtures/Line1-Mixer-1.0.0.json build/gate/srcrepo/`; in `build/gate/srcrepo`: `git -C "$SRCREPO_WIN" init -q`, `git -C … config user.email/user.name`, `git -C … add Line1-Mixer-1.0.0.json`, `git -C … commit -qm seed`. Then `java -jar "$GATES_JAR_WIN" provenance publish "$REGISTRY_WIN" "$SRCREPO_WIN" Line1-Mixer-1.0.0.json Line1-Mixer 1.0.0` → assert exit 0 + `[ -f build/gate/registry/recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml ]` + `manifest.json`.
  - **4. Happy path** (each subprocess wrapped `set +e; …; code=$?; set -e`; sub-run uses DEDICATED out files `out/birth.bin`+`out/ndata.txt`): start observer in background — `java -jar "$MUNINN_JAR_WIN" observe tcp://localhost:1883 Bifrost-Line1 "$(cygpath -m "$(pwd)/build/gate/out/birth.bin")" "$(cygpath -m "$(pwd)/build/gate/out/ndata.txt")" --expect-ndata 4 --timeout-ms 20000 > build/gate/observe.log 2>&1 &` `OBS_PID=$!`; **wait for readiness** `"[OBSERVE] subscribed"` in observe.log (20×0.5s; `fail` if never ready). Then `java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" Line1-Mixer 1.0.0 opc.tcp://localhost:48400 urn:bifrost:opcua:sim Line1/Mixer1 tcp://localhost:1883 Bifrost-Line1 recipe-edge` → assert exit 0. `wait $OBS_PID` (observer exits on capture). Assert: `cmp -s build/gate/out/birth.bin build/gate/registry/recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml` (**byte-equal NBIRTH — the spine assertion**); `grep -q Rpm build/gate/out/ndata.txt && grep -q Temp … && grep -q Running … && grep -q Secret …` (4 members).
  - **5. Drop path** (DEDICATED out files `out/birth-drop.bin`+`out/ndata-drop.txt` — distinct from step 4 so no capture can leak; the observer also truncates them at start): fresh observer on the `-drop` files (`--expect-ndata 3`) → wait readiness → `feed … --inject-bogus Secret` → assert exit 0; `wait $OBS_PID`; assert `out/ndata-drop.txt` has Rpm+Temp+Running but **NOT** Secret (`grep -q Rpm … && grep -q Temp … && grep -q Running … && ! grep -q Secret build/gate/out/ndata-drop.txt`).
  - **6. Tamper path:** `cp -r build/gate/registry build/gate/registry-tampered`; append a byte to its `recipe-setpoints.yaml` (`printf X >> …`); `feed "$REGISTRY_TAMPERED_WIN" Line1-Mixer 1.0.0 …` → assert exit **1** (provenance refuse; no birth). (No observer needed — the refuse happens before MQTT connect.)
  - **7.** Kill sim/observer; stop broker (trap also does); `echo ""; echo "[GATE] PASS run-muninn-gate.sh"; exit 0`. `fail()` prints `[GATE] FAIL: $*`, tails sim.log/observe.log, `exit 1`.
- [ ] **Step 2:** Controller runs `timeout 600 bash muninn/scripts/run-muninn-gate.sh` → `[GATE] PASS`. Commit: `test(gate): run-muninn-gate — provenance-verify + NBIRTH(byte-exact) + egress-validated NDATA; drop + tamper rejected [GATE PASS]`.

> **CHUNK 4 DONE-BIT (controller-direct):** `mvn -q install` in `muninn` green + `run-muninn-gate.sh` `[GATE] PASS`, both run BY the controller (Docker + git required). Carry-forward for Chunk 5 (integration gate): muninn `feed` consumes the PROVENANCE pair (`recipe/<ref>/<ver>/{recipe-setpoints.yaml,manifest.json}`) that the spine gate mints from the mimir-derived, schema-promoted def (CF-P4: provenance-publish the SAME promoted `udt/Line1-Mixer/1.0.0.json` so one byte-rep flows schema→provenance→muninn→NBIRTH); the 5 spine assertions reuse muninn's `--inject-bogus` (non-conformant NDATA drop) + tamper-refuse + `cmp` byte-equality, adding the southbound `gates spec` out-of-range reject + `gates schema` compat-break reject. **New seams surfaced by Chunk 4 (make them Chunk-5 acceptance criteria):** (a) the Chunk-4 gate provenance-publishes a hand FIXTURE def, not the mimir-derived def — Chunk 5 MUST publish the mimir-derived+promoted bytes so schema-tree and provenance-tree carry the identical def (else "NBIRTH == governed registry" is proven against the wrong artifact); (b) muninn has no bdSeq/STATE/rebirth (honest limitation — fine for the concept gate, note it in the Ep5 blog); (c) port 1883 is single-broker — the Chunk-5 gate must own ONE broker lifecycle (not both bifrost's and muninn's compose).

## Chunk 5: MES fixtures + end-to-end integration gate (ELABORATED — ready to execute)

> **Repo:** `bifrost` (branch `feat/yggdrasil-spine`) — adds `scripts/run-yggdrasil-spine-gate.sh` + a general-spec fixture; NO new repo, NO Java changes. **Goal:** the ONE gate that composes the whole northbound spine — sim + ONE broker + `mimir` + `bifrost-gates.jar` + `muninn`, coupled ONLY by process + published bytes (zero shared code) — and proves the happy path + 5 negative/invariant assertions, all observed by the controller.
>
> **CHUNK 5 / FINAL DONE-BIT (controller-direct):** every repo `mvn install` green (bifrost, mimir, muninn) + `scripts/run-yggdrasil-spine-gate.sh` `[GATE] PASS` run BY the controller (needs Docker + git). The whole spine composes: Mímir(model) → Bifrost(govern ①schema/spec/③provenance) → Muninn(feed UNS). bifrost stays public+LOCAL; NO push without Eisen's OK.

### Design decisions (LOCKED — grounded in the realized Chunk 1–4 shapes + the researched `gates spec/schema/provenance` CLIs; do NOT re-derive)

- **ONE shared registry + ONE broker.** A single `build/gate/registry/` (with `policy.json` `{"mode":"FORWARD"}`) hosts BOTH the schema tree (`udt/<ref>/<ver>.json`) and the provenance tree (`recipe/<ref>/<ver>/{recipe-setpoints.yaml,manifest.json}`) — they are disjoint namespaces under one root. **ONE broker only** (Chunk-4 seam #3): the spine gate lives in `bifrost/scripts`, so it boots **bifrost's** `docker-compose.yml` (service `hivemq-ce`, container `bifrost-hivemq-ce`, `:1883`, `HIVEMQ_ALLOW_ALL_CLIENTS=true`); preflight `docker rm -f bifrost-hivemq-ce muninn-hivemq-ce`. muninn's clients are broker-agnostic (connect to `tcp://localhost:1883`). Group `Bifrost-Line1`, edge `recipe-edge` (muninn-gate convention; hyphen, no colon).
- **⭐ CF-P/seam-#1 — the governed bytes muninn births are the SCHEMA-PROMOTED bytes, not a fixture.** The load-bearing reconciliation for assertion (e) "NBIRTH == governed registry bytes": `mimir derive` emits COMPACT JSON; `gates schema --promote` re-serializes it PRETTY into `udt/Line1-Mixer/1.0.0.json` (`DefinitionStore.promote` → `writerWithDefaultPrettyPrinter`). So the authoritative governed artifact is the PROMOTED file. `gates provenance publish` writes the git-blob source bytes VERBATIM into `recipe/<ref>/<ver>/recipe-setpoints.yaml` (filename always `recipe-setpoints.yaml` regardless of source name/extension — CF-P3). Therefore the gate copies the **promoted `udt/Line1-Mixer/1.0.0.json`** (NOT mimir's compact `def.json`) into the throwaway git repo and provenance-publishes THAT → `recipe-setpoints.yaml` == promoted udt bytes == muninn's NBIRTH `governed_definition` bytes. One byte-rep flows schema→provenance→muninn→NBIRTH; assertion (e) `cmp -s` holds against BOTH the `udt/` file and the `recipe/` file.
- **mimir live-derive yields the 4-member def (Rpm/Temp/Running/Secret)** — matches the sim instance + muninn's `--expect-ndata 4`. Do NOT reuse the 3-member `scripts/fixtures/gates/spec/udt/Line1-Mixer/1.0.0.json` fixture for the muninn leg (it lacks `Secret`); the muninn leg consumes the mimir-derived+promoted def. The existing master-spec fixtures (`conformant-master-spec.json` Rpm=1500/Temp=200, `out-of-range-master-spec.json` Rpm=9999) only reference Rpm/Temp — both present in the 4-member def with matching types + ranges [0,3000]/[0,450] — so they conform / range-reject correctly against the promoted def. **Reuse them by path.**
- **The 5 assertions — exact CLI + expected exit (all researched/verified):**
  - **(a) compat-break equipment → `gates schema` REJECT:** `mimir derive … Line1-Mixer 1.1.0 def-breaking.json --omit Running` (live re-derive, 3-member) then `gates schema <registry> def-breaking.json` (NO `--promote`) → **exit 1**, rule `member.removed` (Running absent vs the promoted 4-member baseline under FORWARD). Mirrors the mimir gate's reject leg.
  - **(b) out-of-range master spec → `gates spec` REJECT:** `gates spec <registry> out-of-range-master-spec.json` (Rpm=9999, binds equipmentRef=Line1-Mixer@1.0.0 → loads the promoted `udt/Line1-Mixer/1.0.0.json`, Rpm range [0,3000]) → **exit 1**, rule `spec.range.above-max`.
  - **(c) tampered published spec → `gates provenance verify` REJECT (③):** provenance-publish the MASTER SPEC bytes (`gates provenance publish <registry> <specRepo> conformant-master-spec.json MixProductA 1.0.0 --kind master-spec` → `recipe/MixProductA/1.0.0/{recipe-setpoints.yaml,manifest.json}`), then `gates provenance verify <registry> MixProductA` → **exit 0** (OK), then TAMPER `recipe/MixProductA/1.0.0/recipe-setpoints.yaml` (append a byte) and `gates provenance verify <registry> MixProductA` → **non-zero** (content-hash mismatch). This is bifrost's ③ provenance leg on the MASTER-SPEC artifact — DISTINCT from muninn's equipment-def verify. muninn never reads `MixProductA`, so tampering it doesn't affect the muninn legs. (`--kind master-spec` is the semantically-correct label; verify recomputes sha regardless of kind.)
  - **(d) non-conformant NDATA → muninn DROPS:** `muninn feed … --inject-bogus Secret` → observer captures 3 NDATA members (Rpm/Temp/Running), Secret ABSENT (`! grep -q Secret`). Reuses Chunk-4's proven drop path.
  - **(e) NBIRTH bytes == governed registry bytes:** in the happy path, `cmp -s build/gate/out/birth.bin build/gate/registry/recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml` AND `cmp -s build/gate/out/birth.bin build/gate/registry/udt/Line1-Mixer/1.0.0.json` — the birthed bytes equal BOTH governed registry files (proving seam #1: schema-promoted == provenance-published == NBIRTH).
- **Runtime order:** ONE broker up (+TCP-probe) → sim up (+wait "OPC-UA sim listening") → seed registry `policy.json` → **[HAPPY]** mimir derive → `gates schema --promote` (exit 0, writes `udt/`) → copy promoted udt into throwaway repo, commit, `gates provenance publish` (writes `recipe/`) → `gates spec` conformant (exit 0) → also publish the master spec for (c) → muninn observe(bg)+feed → NBIRTH+NDATA → **assert (e)** cmp + 4 members → **[ASSERTIONS]** (a) schema reject · (b) spec reject · (c) provenance tamper reject · (d) muninn drop → `[GATE] PASS run-yggdrasil-spine-gate.sh`.
- **Chunk-4 carry-forward seams honored:** #1 (publish promoted bytes, above) · #2 (add an explicit **"0 drops on the happy path"** assertion — parse muninn feed's stdout for `[MUNINN] drop` and require NONE in the happy run, so a silent vocabulary-drift dropping a conformant sample fails loudly instead of timing out) · #3 (ONE broker, above) · #4 (derive `--expect-ndata`/member checklist from the promoted def's member list, not a bare hard-code — read the member names out of `udt/Line1-Mixer/1.0.0.json` and assert NDATA carries exactly those) · #5 (distinguish "refused (exit 1)" from "birthed-then-failed" — the tamper/refuse assertions check the EXIT CODE, the drop/happy assertions check NDATA presence; keep them separate) · #6 (one-edge-per-group — single edge `recipe-edge`, single group).
- **Reusable idioms (verbatim from mimir/muninn/ncmd gates):** `set -euo pipefail`; `cd "$(dirname "$0")/.."`; `kill_by_mainclass()` (jps→taskkill on jar filename); `trap cleanup EXIT` (kill sim/muninn by mainclass + `docker compose stop hivemq-ce`); docker preflight; HiveMQ up + `/dev/tcp` TCP-probe :1883 (30×2s); sim boot + grep-wait "OPC-UA sim listening" (30×1s); `wait_observer_ready`/`poll_ndata` (MSYS-safe, NOT `wait $PID`); `cygpath -m` for EVERY native path handed to java/docker/git (and `git -C` gets the Windows path); `set +e; …; code=$?; set -e` around every asserted `java -jar`; `fail()` with log-tails; `[GATE] PASS <name>` + `exit 0` terminator.

### Task 5.1: MES general-spec fixture (ISA-88 two-layer narrative)

**Files:** create `bifrost/scripts/fixtures/gates/spine/general-spec.json`. (The master-spec fixtures are REUSED from `scripts/fixtures/gates/spec/{conformant,out-of-range}-master-spec.json` — do NOT duplicate them.)

- [ ] **Step 1:** Author `general-spec.json` — the equipment-INDEPENDENT upstream layer (logical keys, no nodeId), matching the spec doc + `spec.schema.json` `generalSpec` shape:
```json
{ "specRef": "MixProductA", "version": "1.0.0", "productDomain": "MixProductA",
  "setpointIntents": [ { "key": "mixSpeed", "type": "Double", "value": 1500 },
                       { "key": "mixTemp",  "type": "Double", "value": 200 } ] }
```
- [ ] **Step 2:** Validate it parses against the published `core/src/main/resources/schema/spec.schema.json` (`generalSpec` branch) — a quick check via the networknt validator OR just confirm the field names/shape match `GeneralSpec(specRef, version, productDomain, setpointIntents[{key,type,value}])`. (No gate CLI consumes general specs today — this fixture is the documentary upstream layer whose site-binding IS the master spec; the gate enforces the master via `gates spec`.)
- [ ] **Step 3: Commit** — `test(spine): MES general-spec fixture (ISA-88 general layer, logical setpoint intents)`.

### Task 5.2: `run-yggdrasil-spine-gate.sh` — the ONE end-to-end gate (happy path + 5 assertions)

**Files:** create `bifrost/scripts/run-yggdrasil-spine-gate.sh`. `.gitignore`'d `build/gate/` workdir.

- [ ] **Step 1: Preamble + build + preflight.** `set -euo pipefail`; `cd "$(dirname "$0")/.."` (→ bifrost root). `command -v docker` (fail if absent). Build/ensure jars: `bifrost-gates.jar`+`bifrost-sim.jar` (`mvn -q -pl core,sim,gates install` if missing), `../mimir/target/mimir.jar` (`( cd ../mimir && mvn -q package )`), `../muninn/target/muninn.jar` (`( cd ../muninn && mvn -q package )`); existence-check each (`fail` on missing). Define `kill_by_mainclass()`. `COMPOSE_WIN="$(cygpath -m "$(pwd)/docker-compose.yml")"`. `trap cleanup EXIT` → kill `bifrost-sim.jar`/`muninn.jar` by mainclass + `docker compose -f "$COMPOSE_WIN" stop hivemq-ce`. Pre-run sweep: `kill_by_mainclass bifrost-sim.jar`, `kill_by_mainclass muninn.jar`, `docker rm -f bifrost-hivemq-ce muninn-hivemq-ce 2>/dev/null || true`. `cygpath -m` all jar paths (`GATES_JAR_WIN`, `SIM_JAR_WIN`, `MIMIR_JAR_WIN`, `MUNINN_JAR_WIN`).
- [ ] **Step 2: Broker + sim.** `docker compose -f "$COMPOSE_WIN" up -d hivemq-ce`; TCP-probe :1883 (30×2s; dump `docker compose logs` + `fail` on miss). `rm -rf build/gate; mkdir -p build/gate/{registry,srcrepo,specrepo,out}`; seed `echo '{"mode":"FORWARD"}' > build/gate/registry/policy.json`. Start `bifrost-sim.jar > build/gate/sim.log 2>&1 &` (SIM_PID); grep-wait "OPC-UA sim listening" (30×1s; `fail`). `REGISTRY_WIN="$(cygpath -m "$(pwd)/build/gate/registry")"`.
- [ ] **Step 3: HAPPY — govern the model (schema ① + provenance ③).** Constants `ENDPOINT=opc.tcp://localhost:48400`, `NS_URI=urn:bifrost:opcua:sim`, `TYPE=MixerType`, `REF=Line1-Mixer`, `VER=1.0.0`.
  - `java -jar "$MIMIR_JAR_WIN" derive "$ENDPOINT" "$NS_URI" "$TYPE" "$REF" "$VER" "$(cygpath -m "$(pwd)/build/gate/def.json")"` → assert exit 0.
  - `java -jar "$GATES_JAR_WIN" schema "$REGISTRY_WIN" "$(cygpath -m "$(pwd)/build/gate/def.json")" --promote` → assert exit 0 + `[ -f build/gate/registry/udt/Line1-Mixer/1.0.0.json ]` (the PROMOTED governed bytes).
  - Copy the PROMOTED file into the throwaway repo (seam #1): `cp build/gate/registry/udt/Line1-Mixer/1.0.0.json build/gate/srcrepo/Line1-Mixer-1.0.0.json`; `SRCREPO_WIN="$(cygpath -m "$(pwd)/build/gate/srcrepo")"`; `git -C "$SRCREPO_WIN" init -q`; **`git -C "$SRCREPO_WIN" config core.autocrlf false`** (⚠ CRITICAL byte-fidelity — the promoted udt JSON is **CRLF** because Jackson's `writerWithDefaultPrettyPrinter` uses `System.lineSeparator()` on Windows; with the machine default `core.autocrlf=true`, `git add`/commit would normalize the stored blob to LF → `provenance publish` (which writes the git blob verbatim) would emit an LF `recipe-setpoints.yaml` while the on-disk `udt/…json` stays CRLF → assertion (e)'s `cmp` vs the udt file FAILS. Setting autocrlf=false after `init`, before `add`, preserves CRLF verbatim — which is also exactly what `provenance publish` semantically promises); config user.email/name, `git -C "$SRCREPO_WIN" add Line1-Mixer-1.0.0.json`, `commit -qm seed`; `java -jar "$GATES_JAR_WIN" provenance publish "$REGISTRY_WIN" "$SRCREPO_WIN" Line1-Mixer-1.0.0.json "$REF" "$VER"` → assert exit 0 + `[ -f build/gate/registry/recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml ]`.
- [ ] **Step 4: HAPPY — govern the spec (conformance) + feed the UNS.**
  - `java -jar "$GATES_JAR_WIN" spec "$REGISTRY_WIN" "$(cygpath -m "$(pwd)/scripts/fixtures/gates/spec/conformant-master-spec.json")"` → assert exit 0 (master ⊨ equipment).
  - muninn observe(bg) on `out/birth.bin`+`out/ndata.txt` (`--expect-ndata 4 --timeout-ms 20000`) → `wait_observer_ready`. Then `java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VER" "$ENDPOINT" "$NS_URI" Line1/Mixer1 tcp://localhost:1883 Bifrost-Line1 recipe-edge > build/gate/feed-happy.log 2>&1` → assert exit 0; `poll_ndata out/birth.bin out/ndata.txt`.
  - **Assert (e):** `cmp -s build/gate/out/birth.bin build/gate/registry/recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml` AND `cmp -s build/gate/out/birth.bin build/gate/registry/udt/Line1-Mixer/1.0.0.json` (NBIRTH == BOTH governed registry files).
  - **Assert 4 members + seam-#2 "0 drops":** read the member names out of `build/gate/registry/udt/Line1-Mixer/1.0.0.json` (e.g. `grep -oE '"name"[[:space:]]*:[[:space:]]*"[^"]*"' | sed -E 's/.*"([^"]*)"$/\1/'`) and assert `out/ndata.txt` contains EACH via an ANCHORED match (`grep -q "^$mem$" build/gate/out/ndata.txt` — anchored to avoid substring false-positives, mirroring Chunk 4) for the full derived set {Rpm,Temp,Running,Secret}; AND assert the happy feed log has **NO** `[MUNINN] drop` line (`! grep -q '\[MUNINN\] drop' build/gate/feed-happy.log`) — a silent vocabulary drift would otherwise drop a conformant sample.
- [ ] **Step 5: ASSERTION (a) — schema compat-break REJECT.** `java -jar "$MIMIR_JAR_WIN" derive "$ENDPOINT" "$NS_URI" "$TYPE" "$REF" 1.1.0 "$(cygpath -m "$(pwd)/build/gate/def-breaking.json")" --omit Running` (exit 0 — deriving succeeds); `set +e; java -jar "$GATES_JAR_WIN" schema "$REGISTRY_WIN" "$(cygpath -m "$(pwd)/build/gate/def-breaking.json")"; code=$?; set -e` → assert `code -eq 1` (member.removed; NO `--promote`).
- [ ] **Step 6: ASSERTION (b) — spec out-of-range REJECT.** `set +e; java -jar "$GATES_JAR_WIN" spec "$REGISTRY_WIN" "$(cygpath -m "$(pwd)/scripts/fixtures/gates/spec/out-of-range-master-spec.json")"; code=$?; set -e` → assert `code -eq 1` (spec.range.above-max, Rpm=9999).
- [ ] **Step 7: ASSERTION (c) — tampered published master-spec REJECT (③).** `cp scripts/fixtures/gates/spec/conformant-master-spec.json build/gate/specrepo/master-spec.json`; `SPECREPO_WIN="$(cygpath -m "$(pwd)/build/gate/specrepo")"`; `git -C "$SPECREPO_WIN" init -q` then `git -C "$SPECREPO_WIN" config core.autocrlf false` (same byte-verbatim discipline as the srcrepo — keeps provenance genuinely blob-verbatim), config user.email/name, `add master-spec.json`, `commit -qm seed`; `java -jar "$GATES_JAR_WIN" provenance publish "$REGISTRY_WIN" "$SPECREPO_WIN" master-spec.json MixProductA 1.0.0 --kind master-spec` → assert exit 0. `java -jar "$GATES_JAR_WIN" provenance verify "$REGISTRY_WIN" MixProductA` → assert exit 0 (clean). Then `printf 'X' >> build/gate/registry/recipe/MixProductA/1.0.0/recipe-setpoints.yaml`; `set +e; java -jar "$GATES_JAR_WIN" provenance verify "$REGISTRY_WIN" MixProductA; code=$?; set -e` → assert `code -ne 0` (content-hash mismatch). (muninn never reads MixProductA → no interference with the Line1-Mixer legs.)
- [ ] **Step 8: ASSERTION (d) — non-conformant NDATA DROPPED.** Fresh observer on `out/birth-drop.bin`+`out/ndata-drop.txt` (`--expect-ndata 3`) → `wait_observer_ready`. `java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VER" "$ENDPOINT" "$NS_URI" Line1/Mixer1 tcp://localhost:1883 Bifrost-Line1 recipe-edge --inject-bogus Secret > build/gate/feed-drop.log 2>&1` → assert exit 0; `poll_ndata`. Assert `out/ndata-drop.txt` has Rpm+Temp+Running but **NOT** Secret (`! grep -q Secret`), AND `feed-drop.log` shows `[MUNINN] drop Secret`.
- [ ] **Step 9: Teardown + PASS.** Kill sim/observer; stop broker (trap also does); `echo ""; echo "[GATE] PASS run-yggdrasil-spine-gate.sh"; exit 0`. `fail()` prints `[GATE] FAIL: $*`, tails sim.log + feed logs, `exit 1`.
- [ ] **Step 10:** Iterate by running `bash scripts/run-yggdrasil-spine-gate.sh` until `[GATE] PASS`; ensure clean teardown (no orphan JVMs / broker). Commit: `test(gate): run-yggdrasil-spine-gate — mimir→bifrost→muninn end-to-end, 5 spine assertions [GATE PASS]`.

> **CHUNK 5 / FINAL DONE-BIT (controller-direct):** the controller runs `mvn install` in bifrost + mimir + muninn (all green) AND `bash scripts/run-yggdrasil-spine-gate.sh` → `[GATE] PASS` (Docker + git required), kills orphan JVMs + stops the broker first, NEVER trusts a subagent's PASS. This closes the track: a single "Line1 Mixer" flows Mímir(model) → Bifrost(govern: ① schema admits / spec conformance / ③ provenance) → Muninn(feed byte-exact NBIRTH + egress-validated NDATA → UNS), with all 5 assertions observed and zero shared code. THEN the portfolio blog (Ep5) is written on the real, running system.

---

## Notes for the executor
- **Windows/MSYS:** every path to a native JVM/git → `cygpath -m`. `git -C` needs the Windows path, not `/c/...`.
- **Full-stack gates run FOREGROUND** with `timeout`. Kill orphan JVMs holding ports before re-run (`jps -lm | grep -iE 'bifrost|mimir|muninn|Sim' → taskkill`).
- **Zero shared code:** mimir/muninn each keep their OWN value-type copies; NO Maven dependency on bifrost. Cross-repo = built-jar subprocess + published bytes only.
- **No push/PR/merge** anywhere without Eisen's explicit OK; bifrost is public (feature branch stays local).
- **@Skills:** superpowers:test-driven-development per task; superpowers:verification-before-completion before each done-bit (controller runs the gate).
