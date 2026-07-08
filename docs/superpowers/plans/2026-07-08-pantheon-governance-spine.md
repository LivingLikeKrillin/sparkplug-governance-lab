# Pantheon Governance Spine Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the northbound governance spine composes end-to-end — a single "Line1 Mixer" flows Mímir(model)→Bifrost(govern)→Muninn(feed UNS), coupled only by the published data/wire contract (zero shared code).

**Architecture:** Two orthogonal axes — equipment model per-SITE (Mímir, AAS-submodel-aligned), spec model per-PRODUCT-DOMAIN (MES, ISA-88 general/master), meeting at a `master ⊨ equipment` structural+range conformance gate. Muninn is an egress governance enforcement point (provenance-verify → Sparkplug NBIRTH governed definition → egress-validated NDATA → MQTT UNS). Cross-repo integration is by built-jar subprocess; the end-to-end gate lives in `bifrost/scripts/`.

**Tech Stack:** Java 17, Maven, Eclipse Milo 1.0.0 (OPC-UA), Eclipse Tahu (Sparkplug B), Jackson 2.17, HiveMQ CE (MQTT), JUnit 5. Repos are sibling checkouts under `Labs/[iiot]/`: `bifrost/` (built+public), NEW `mimir/`, NEW `muninn/`.

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-08-pantheon-governance-spine-design.md`.

**Progressive elaboration:** Chunk 1 is fully TDD-detailed (immediately executable, no cross-repo dependency). Chunks 2–5 are task-level outlines here; each is expanded to full bite-sized TDD detail (and plan-reviewed) JUST BEFORE it is executed, because the new-repo shapes (3–5) depend on the realized shape of chunks 1–2. Execute one chunk, controller-verify its done-bit, Eisen-gate, then elaborate + execute the next — exactly as the Bifrost extraction was run.

**Do NOT push any repo without Eisen's explicit OK. bifrost is public — its changes stay on a feature branch, local, until OK.**

---

## File Structure (decomposition locked)

**Chunk 1 — bifrost (feature branch `feat/pantheon-spine`):**
- Modify `core/src/main/java/dev/krillin/bifrost/core/schema/Member.java` — add `semanticId`, `range`.
- Create `core/.../schema/Range.java` — `record Range(double low, double high)`.
- Modify `core/.../schema/UdtDefinition.java` — add reserved nullable `conformsTo`.
- Modify `core/src/main/resources/schema/definition.schema.json` — add member `semanticId`/`range`.
- Create `core/.../schema/GeneralSpec.java`, `MasterSpec.java`, `Setpoint.java` — spec model records.
- Create `core/.../schema/SpecConformanceChecker.java` — structural+range evaluator.
- Create `core/src/main/resources/schema/spec.schema.json` — published general/master spec contract.
- Modify `core/.../schema/RecipeDefinitionStore.java` — parameterize `publish(kind, …)`.
- Modify `core/.../schema/RecipePublish.java` — accept a `kind` arg, default `recipe-setpoints`.
- Create `gates/src/main/java/dev/krillin/bifrost/gates/SpecGate.java` — `gates spec <registryDir> <masterSpecFile>`.
- Modify `gates/.../gates/GatesCli.java` — add `case "spec"`.
- Modify `gates/.../gates/ProvenancePublish.java` — pass optional `kind` through.
- Create `scripts/run-spec-gate.sh` — accept/reject gate.
- Tests: `core/src/test/.../schema/{SpecConformanceCheckerTest, SpecFormatConformanceTest}.java`, `gates/src/test/.../gates/SpecGateTest.java` (+ extend `FormatSpecConformanceTest` for the reshaped member).

**Chunk 2 — bifrost sim:** `sim/.../EmbeddedMiloSim.java` gains a Mixer **ObjectType** (type node) + a Mixer instance; Mímir browses the type, Muninn reads the instance.

**Chunk 3 — NEW repo `mimir`:** `mimir/` Maven repo (own `dev.krillin.mimir` package, its OWN copy of the value types — zero shared code). Reads sim OPC-UA Mixer type → derives AAS-aligned `UdtDefinition` JSON → invokes `bifrost-gates.jar schema`. Seeded by lab `opcua/`.

**Chunk 4 — NEW repo `muninn`:** `muninn/` Maven repo. Consumes governed definition (bytes) → provenance-verify (recompute sha vs manifest) → Sparkplug B edge node NBIRTH(=governed def) + egress-validated NDATA → MQTT. Seeded by lab `spb40/`.

**Chunk 5 — MES fixtures + integration gate:** master/general spec fixtures + `bifrost/scripts/run-pantheon-spine-gate.sh` orchestrating sim+broker+gates+mimir+muninn, asserting the 5 spine assertions.

---

## Chunk 1: Bifrost core additions (spec model + `gates spec` + AAS-aligned reshape)

> Work in `bifrost/` on a NEW branch `feat/pantheon-spine` (off `main`). No cross-repo dependency; fully unit-testable. Done-bit: `mvn -q install` green + `run-spec-gate.sh [GATE] PASS`.

### Task 1.0: Branch

- [ ] **Step 1:** `cd bifrost && git checkout -b feat/pantheon-spine` (off `main`). Confirm clean.

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
- [ ] **Step 4: Fix compile fallout** — every `new Member(a,b)` and `new UdtDefinition(a,b,c,d)` in `core` main+test must gain the new args (`new Member(n,t,null,null)`, `new UdtDefinition(...,null)`). Grep `new Member(` and `new UdtDefinition(` across `core/src`. **Verify `CompatibilityChecker` is untouched semantically** — it reads only `.name()`/`.type()`; confirm it still compiles and its tests pass unchanged.
- [ ] **Step 5: Update `definition.schema.json`** — add to the member object `properties`: `"semanticId": {"type":"string"}`, `"range": {"type":["object","null"],"properties":{"low":{"type":"number"},"high":{"type":"number"}},"required":["low","high"],"additionalProperties":false}`; add `semanticId` to member `required` (range stays optional/nullable). Add `"conformsTo": {"type":["string","null"]}` to the top object; keep `additionalProperties:false`. Update descriptions to note the AAS-vocabulary intent.
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

**Files:** Create `SpecConformanceChecker.java`; test `SpecConformanceCheckerTest.java`.

- [ ] **Step 1: Write the failing test** — `SpecConformanceCheckerTest`: given a `UdtDefinition` (Mixer: Rpm Double [0,3000], Temp Double [0,450], Running Boolean) and a `MasterSpec` with setpoints {Rpm=1500, Temp=200}: `check(def, spec)` → conformant (no violations). Negatives: setpoint member "Ghost" not in def → structural violation; Rpm type "Int32" ≠ def "Double" → type violation; Rpm=9999 > 3000 → range violation; setpoint on a member with null range but a numeric value is allowed (no range constraint).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** — `Verdict check(UdtDefinition def, MasterSpec spec)` returning `Verdict(compatible, List<Violation>)` (reuse existing `Verdict`/`Violation`): for each setpoint, find the member by name (structural, else `[spec.member.unknown]`); type-match (`[spec.type.mismatch]`); if member.range != null, assert low ≤ value ≤ high (`[spec.range.below-min]`/`[spec.range.above-max]`).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(core): SpecConformanceChecker (structural+range: master spec ⊨ equipment model)`.

### Task 1.4: `SpecGate` CLI + wire into `GatesCli`

**Files:** Create `gates/.../gates/SpecGate.java`; Modify `GatesCli.java`; test `gates/.../gates/SpecGateTest.java`.

- [ ] **Step 1: Write the failing test** — `SpecGateTest` (mirror `SchemaGateTest`, `@TempDir`): seed a registry `udt/Line1-Mixer/1.0.0.json` (Mixer def) + write a master-spec JSON; `SpecGate.run({registryDir, masterSpecFile})` → 0 conformant; out-of-range spec → 1; unknown-member spec → 1; bad args → 2; a master spec whose `equipmentRef@equipmentVersion` isn't in the registry → 2 (error). Add a `GatesCliTest` case: `GatesCli.run({"spec", registryDir, masterSpecFile})` → 0.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement `SpecGate`** — `run(args)`: `if (args.length < 2) usage→2`. Parse `MasterSpec` from `args[1]` via `JsonMapperFactory`. Load the pinned equipment def from `Path.of(args[0]).resolve("udt").resolve(spec.equipmentRef()).resolve(spec.equipmentVersion()+".json")` (NOT `latest()` — pinned version by path convention); if absent → err "equipment <ref>@<ver> not in registry" → 2. `Verdict v = new SpecConformanceChecker().check(def, spec)`; print + return 0 (conformant) / 1 (violations). Wire `case "spec": return SpecGate.run(rest);` into `GatesCli` (usage string → `<schema|spec|policy|provenance>`).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(gates): SpecGate — gates spec <registryDir> <masterSpecFile> (structural+range) + GatesCli wire`.

### Task 1.5: `kind` parameterization for master-spec publish

**Files:** Modify `RecipeDefinitionStore.java`, `RecipePublish.java`, `ProvenancePublish.java`; test update.

- [ ] **Step 1: Write the failing test** — extend `RecipePublishTest`/`ProvenancePublishTest`: publishing with an explicit `kind="master-spec"` yields a manifest whose `kind == "master-spec"`; default (no kind) stays `"recipe-setpoints"` (back-compat).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** — `RecipeDefinitionStore.publish(String kind, String ref, String version, byte[] bytes, String defRef, String sha, String sourcePath, long at)` (add leading `kind` param; the `new RecipeManifest("recipe-setpoints", …)` becomes `new RecipeManifest(kind, …)`). `RecipePublish.run`: accept an optional trailing `--kind <k>` flag (default `recipe-setpoints`); pass through. `ProvenancePublish`: `publish` subcommand forwards a `--kind` if present. Keep all existing call sites working (default kind).
- [ ] **Step 4: Run → PASS** (incl. existing recipe/provenance tests unchanged with default kind).
- [ ] **Step 5: Commit** — `feat(core,gates): thread kind through publish (master-spec vs recipe-setpoints)`.

### Task 1.6: `run-spec-gate.sh` (Chunk-1 acceptance)

**Files:** Create `scripts/run-spec-gate.sh`; fixtures under `scripts/fixtures/gates/spec/`.

- [ ] **Step 1:** Fixtures: a registry `udt/Line1-Mixer/1.0.0.json` (Mixer def with ranges), a conformant master spec (Rpm=1500,Temp=200), an out-of-range master spec (Rpm=9999). Script (mirror `run-schema-gate.sh`): builds `core,gates` if jar missing; `gates spec <registry> <conformant>` → exit 0 (accept), `gates spec <registry> <out-of-range>` → exit 1 (reject); print `[GATE] PASS run-spec-gate.sh`. Use `cygpath -m` for jar/file paths.
- [ ] **Step 2: Run** — `bash scripts/run-spec-gate.sh` → `[GATE] PASS`.
- [ ] **Step 3: Commit** — `test(gate): run-spec-gate — accept conformant + reject out-of-range master spec [GATE PASS]`.

> **CHUNK 1 DONE-BIT (controller-direct):** `mvn -q install` green at bifrost root; `run-spec-gate.sh` `[GATE] PASS`; `run-schema-gate.sh`/`run-provenance-gate.sh` still `[GATE] PASS` (no regression). Controller runs these, not a subagent claim.

---

## Chunk 2: sim Mixer type node (OUTLINE — elaborate before executing)

**Repo:** `bifrost/sim`. **Goal:** the sim exposes a Mixer **ObjectType** (type node Mímir can browse) + a Mixer instance (values Muninn reads).
- Task 2.1: In `EmbeddedMiloSim`, define a Milo `ObjectTypeNode` "MixerType" (ns=2) with typed member variables Rpm(Double)/Temp(Double)/Running(Boolean)/Secret(Double), each with an EU-range property (Milo `EURange`/`Range` property or an AnalogItem) so Mímir can read `low/high` — reuse the koshei-sim node-building pattern. TDD via a browse-read test (Milo client reads the type's members + ranges).
- Task 2.2: Expose a Mixer **instance** node `ns=2;s=Line1/Mixer1` with the member variables (reuse existing writable-node pattern) for Muninn to sample.
- Task 2.3: Extend/keep `run-ncmd-runtime-gate.sh` green (the writable Recipe/Rpm node stays for Heimdall). Done-bit: sim smoke — a Milo client browses MixerType and reads Rpm's EU range.

## Chunk 3: `mimir` repo — northbound modeler (OUTLINE)

**Repo:** NEW `Labs/[iiot]/mimir` (Maven, `dev.krillin.mimir`, OWN value types — zero shared code). **Seed:** lab `opcua/` (OpcUaBrowser, OpcUaTypeMapper, TypeFlattener, UaTypeSpace, UaEngInfo).
- Task 3.1: repo skeleton + pom (milo-sdk-client, jackson; own `UdtDefinition`/`Member`/`Range` copies).
- Task 3.2: Milo client browses the sim's MixerType → derives an AAS-aligned `UdtDefinition` JSON (members + type + semanticId(local IRI) + range from EURange). TDD against the sim (integration).
- Task 3.3: `mimir` emits the definition JSON to a path + invokes `bifrost-gates.jar schema <registryDir> <def.json> --promote` (subprocess, sibling path, cygpath -m). Done-bit: `run-mimir-gate.sh` — mimir derives → gates schema admits → registry has the Mixer def; a breaking re-derive → gates schema rejects.

## Chunk 4: `muninn` repo — northbound feeder (OUTLINE)

**Repo:** NEW `Labs/[iiot]/muninn` (Maven, `dev.krillin.muninn`, own types). **Seed:** lab `spb40/` (DefinitionCodec, DefinitionPublisher, Spb40Edge, SchemaResolver) + Tahu.
- Task 4.1: repo skeleton + pom (tahu-core/edge, paho, milo-sdk-client, jackson).
- Task 4.2: consume the governed definition (registry bytes) + **provenance-verify** (recompute sha256 vs the published manifest — mirror resequence's `BifrostManifestSeamTest`). Refuse on mismatch. TDD.
- Task 4.3: Sparkplug B edge node: **NBIRTH** carries the governed definition (as a UDT template); read sim Mixer1 instance → **NDATA**; **egress-validate** each sample against the governed definition (member/type), drop non-conformant. TDD (a non-conformant sample is not published).
- Task 4.4: publish to MQTT (HiveMQ CE). Done-bit: `run-muninn-gate.sh` — muninn provenance-verifies + births the governed def + emits conformant NDATA to MQTT (a subscriber sees the birth def + values); a tampered def → muninn refuses to birth; a non-conformant sample → not emitted.

## Chunk 5: MES fixtures + end-to-end integration gate (OUTLINE)

**Repo:** `bifrost/scripts` + fixtures. **Goal:** the ONE gate proving the whole spine.
- Task 5.1: MES fixtures — general spec + master spec (Git-committed, in the gate's throwaway repo), Mixer setpoints.
- Task 5.2: `bifrost/scripts/run-pantheon-spine-gate.sh` orchestrating: start sim + HiveMQ CE → **mimir** derives Mixer type → `gates schema` admits → **MES** master spec → `gates spec` conformance → `gates provenance` publishes → **muninn** provenance-verifies + NBIRTH + egress-validated NDATA → MQTT subscriber verifies.
- Task 5.3: The 5 assertions (all observed): compat-breaking equipment change → `gates schema` REJECT; out-of-range master spec (Rpm=9999) → `gates spec` REJECT; tampered published spec → provenance verify REJECT; non-conformant NDATA sample → muninn drops (not on UNS); NBIRTH def bytes == governed registry bytes. Print `[GATE] PASS`.
- **FINAL DONE-BIT (controller-direct):** all repos `mvn install` green; `run-pantheon-spine-gate.sh` `[GATE] PASS` run by the controller. The whole northbound spine composes via the data contract with zero shared code.

---

## Notes for the executor
- **Windows/MSYS:** every path to a native JVM/git → `cygpath -m`. `git -C` needs the Windows path, not `/c/...`.
- **Full-stack gates run FOREGROUND** with `timeout`. Kill orphan JVMs holding ports before re-run (`jps -lm | grep -iE 'bifrost|mimir|muninn|Sim' → taskkill`).
- **Zero shared code:** mimir/muninn each keep their OWN value-type copies; NO Maven dependency on bifrost. Cross-repo = built-jar subprocess + published bytes only.
- **No push/PR/merge** anywhere without Eisen's explicit OK; bifrost is public (feature branch stays local).
- **@Skills:** superpowers:test-driven-development per task; superpowers:verification-before-completion before each done-bit (controller runs the gate).
