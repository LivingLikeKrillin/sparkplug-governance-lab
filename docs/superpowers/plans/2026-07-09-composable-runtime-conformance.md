# Composable Runtime Conformance Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote conformance into one governed, composable, declarative rule contract (a `ConformancePolicy` artifact + a `ConformanceEvaluator`), evaluated at BOTH design-time (`gates spec`) AND the runtime write boundary (Heimdall), so a single governed source drives both — positioning Bifrost as the IAM-like control plane for the OT governance boundary.

**Architecture:** A pure `core.conformance.ConformanceEvaluator` composes a small CLOSED rule set (structural + type + envelope-from-model + cross-member-from-policy + recipe) into a rich `ConformanceVerdict`. The `ConformancePolicy` (declarative JSON, schema-gated + provenance-published + SemVer'd like recipes) carries cross-member constraints, the strictness dial, and node↔member bindings. `SpecGate` and Heimdall both call the evaluator. Additive-first: Phase A adds everything design-time (nothing runtime touched); Phase B wires Heimdall and migrates the Mixer range out of `policy.json` in one commit so no gate ever sees a broken window.

**Tech Stack:** Java 17, Jackson (Bifrost `JsonMapperFactory`/`AclMapperFactory`), Eclipse Milo 1.0.0 (sim + Heimdall applier), Eclipse Tahu (Sparkplug NCMD), JUnit 5, Maven, Bash gate scripts. All in the bifrost multi-module repo (`core`/`gates`/`heimdall`/`sim`) — `gates` and `heimdall` already depend on `core`.

**Repo:** `bifrost` (branch `feat/yggdrasil-spine`; commit locally, do NOT push).
**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-09-composable-runtime-conformance-design.md`

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `core/.../conformance/CrossConstraint.java` | one conditional-bound rule (`if member op value ⇒ then member op value`) | Create |
| `core/.../conformance/ConformancePolicy.java` | governed artifact: `crossConstraints[]`, `dial`, `nodeBindings[]` | Create |
| `core/.../conformance/NodeBinding.java` | `opcNodeId → {equipmentRef,version,member}` | Create |
| `core/.../conformance/ConformanceVerdict.java` | `(boolean ok, List<Violation> violations)` (replaces `SpecVerdict`) | Create |
| `core/.../conformance/ConformanceEvaluator.java` | pure composed evaluator (the core) | Create |
| `core/src/main/resources/schema/conformance-policy.schema.json` | published policy schema | Create |
| `core/.../schema/SpecConformanceChecker.java` | subsumed by the evaluator | Delete |
| `core/.../schema/SpecVerdict.java` | replaced by `ConformanceVerdict` | Delete |
| `gates/.../SpecGate.java` | rewire to the evaluator (+ optional policy, graceful-degrade) | Modify |
| `sim/.../EmbeddedMiloSim.java` | add `WeldControllerType` + `BodyShop/Weld1` instance | Modify |
| `heimdall/.../NcmdOpcUaBridgeMain.java` | load `UdtDefinition` + `ConformancePolicy` at startup | Modify |
| `heimdall/.../NcmdOpcUaBridge.java` | ② conformance after ① authz | Modify |
| `heimdall/.../Applier.java` | interface — add `double readDouble(String) throws Exception` | Modify |
| `heimdall/.../OpcUaApplier.java` | implement `readDouble` (parse existing `read`) | Modify |
| `heimdall/registry/policy.json` | numeric rules → `constraint{type}` (drop min/max) | Modify (Phase B) |
| `heimdall/registry/udt/Line1-Mixer/1.0.0.json`, `heimdall/registry/conformance/Line1-Mixer/1.0.0.json` | Mixer model + conformance policy for RUNTIME (ncmd gate) | Create (Phase B) |
| `scripts/fixtures/conformance/…` | Weld model + policy (envelope + recipe variants) + WeldSchedule spec — the killer gate's `REGISTRY_PATH`/`CONFORMANCE_PATH` point HERE | Create |
| `scripts/run-composable-conformance-gate.sh` | the C1–C5 killer gate | Create |

**`Violation` reuse:** keep `core.schema.Violation(String rule, String detail)`. Rule ids reuse existing spellings so downstream greps match: `spec.member.unknown`, `spec.type.mismatch`, `spec.range.below-min`, `spec.range.above-max`, plus new `conformance.cross.<id>` and `conformance.recipe.deviation`.

---

## Chunk 1: Phase A — design-time (additive, low-regression)

### Task A1: core value types + policy schema

**Files:** Create `core/src/main/java/dev/krillin/bifrost/core/conformance/{CrossConstraint,NodeBinding,ConformancePolicy,ConformanceVerdict}.java` + `core/src/main/resources/schema/conformance-policy.schema.json`. Test: `core/src/test/java/dev/krillin/bifrost/core/conformance/ConformancePolicyJsonTest.java`.

- [ ] **Step 1: Write the failing test** — `ConformancePolicyJsonTest`: round-trip a policy JSON (a cross-constraint, `dial=envelope`, one binding) through `JsonMapperFactory.create()` and assert fields.

```java
package dev.krillin.bifrost.core.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import org.junit.jupiter.api.Test;

class ConformancePolicyJsonTest {
    @Test void roundTrip() throws Exception {
        String json = """
          {"policyRef":"WeldPolicy","version":"1.0.0","equipmentRef":"Weld-Controller","equipmentVersion":"1.0.0",
           "dial":{"mode":"envelope"},
           "crossConstraints":[{"id":"weld-lobe","ifMember":"ElectrodeForce","ifOp":"lt","ifValue":3.0,
                                "thenMember":"WeldCurrent","thenOp":"le","thenValue":8.0}],
           "nodeBindings":[{"opcNodeId":"ns=2;s=BodyShop/Weld1.WeldCurrent","member":"WeldCurrent"}]}
          """;
        ObjectMapper m = JsonMapperFactory.create();
        ConformancePolicy p = m.readValue(json, ConformancePolicy.class);
        assertEquals("WeldPolicy", p.policyRef());
        assertEquals("envelope", p.dial().mode());
        assertEquals(1, p.crossConstraints().size());
        assertEquals("weld-lobe", p.crossConstraints().get(0).id());
        assertEquals("WeldCurrent", p.nodeBindings().get(0).member());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=ConformancePolicyJsonTest` → FAIL (classes missing).

- [ ] **Step 3: Create the records.** All `@JsonIgnoreProperties(ignoreUnknown=true)` NOT needed (Bifrost `JsonMapperFactory` is lenient). Use `Include.ALWAYS` compact style like existing records.

```java
// CrossConstraint.java
package dev.krillin.bifrost.core.conformance;
/** One conditional-bound rule: if <ifMember> <ifOp> <ifValue> then require <thenMember> <thenOp> <thenValue>.
 *  ops: "lt","le","gt","ge","eq". A small CLOSED algebra element. */
public record CrossConstraint(String id, String ifMember, String ifOp, double ifValue,
                              String thenMember, String thenOp, double thenValue) {}
```
```java
// NodeBinding.java
package dev.krillin.bifrost.core.conformance;
/** Links a governed member to its OPC-UA nodes. {@code opcNodeId} = the WRITE/setpoint node matched
 *  against an incoming command (nullable for read-only siblings never commanded). {@code readNodeId} =
 *  the live INSTANCE node whose current value is read for cross-member rules (nullable if the member
 *  is never a cross-member sibling). The two namespaces differ (setpoint vs instance), so both are
 *  explicit — no string-concat convention. Only bound + numeric siblings are ever read (see B2). */
public record NodeBinding(String opcNodeId, String readNodeId, String member) {}
```
> The A1 test JSON `nodeBindings` entry becomes e.g. `{"opcNodeId":"ns=2;s=Weld/WeldCurrent","readNodeId":"ns=2;s=BodyShop/Weld1.WeldCurrent","member":"WeldCurrent"}`; sibling-only members bind `opcNodeId:null` with a `readNodeId`.
```java
// ConformancePolicy.java
package dev.krillin.bifrost.core.conformance;
import java.util.List;
/** Governed declarative conformance config (the IAM-like policy artifact). Dial holds the
 *  strictness mode ("envelope"|"recipe") and, for recipe-mode, the active recipe ref + tolerance. */
public record ConformancePolicy(String policyRef, String version, String equipmentRef, String equipmentVersion,
                                Dial dial, List<CrossConstraint> crossConstraints, List<NodeBinding> nodeBindings) {
    public record Dial(String mode, String activeRecipeRef, String activeRecipeVersion, Double recipeTolerance) {}
    public List<CrossConstraint> crossConstraints() { return crossConstraints == null ? List.of() : crossConstraints; }
    public List<NodeBinding> nodeBindings() { return nodeBindings == null ? List.of() : nodeBindings; }
}
```
```java
// ConformanceVerdict.java
package dev.krillin.bifrost.core.conformance;
import java.util.List;
import dev.krillin.bifrost.core.schema.Violation;
public record ConformanceVerdict(boolean ok, List<Violation> violations) {}
```

- [ ] **Step 4: Create `conformance-policy.schema.json`** — a JSON Schema documenting the above (mirror the shape of `core/src/main/resources/schema/spec.schema.json`; it is documentary — the gate uses the lenient mapper, same as the other schemas). Include `policyRef`, `version`, `equipmentRef`, `equipmentVersion`, `dial{mode,activeRecipeRef,activeRecipeVersion,recipeTolerance}`, `crossConstraints[]`, `nodeBindings[]`.

- [ ] **Step 5: Run to verify pass** — `mvn -q -pl core test -Dtest=ConformancePolicyJsonTest` → PASS.

- [ ] **Step 6: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/conformance core/src/main/resources/schema/conformance-policy.schema.json core/src/test/java/dev/krillin/bifrost/core/conformance` ; `git commit -m "feat(core): ConformancePolicy governed artifact (cross-constraints, dial, bindings) + schema"`.

### Task A2: `ConformanceEvaluator` — the composed pure evaluator (subsumes SpecConformanceChecker)

**Files:** Create `core/.../conformance/ConformanceEvaluator.java`. Test: `core/.../conformance/ConformanceEvaluatorTest.java`. Delete `core/.../schema/SpecConformanceChecker.java` (after callers move — its only caller is `SpecGate`, migrated in A3; the checker's test `SpecConformanceCheckerTest` is ported here).

**Interface (serves both boundaries):** the candidate state is a `List<Setpoint>` (`core.schema.Setpoint(member,type,double value)`) — design-time passes `MasterSpec.setpoints()`; runtime builds the commanded setpoint + sibling reads. `activeRecipe` is a `MasterSpec` (nullable; only used when `dial.mode()=="recipe"`).

```java
ConformanceVerdict evaluate(UdtDefinition def, ConformancePolicy policy /*nullable*/,
                            MasterSpec activeRecipe /*nullable*/, List<Setpoint> state)
```

- [ ] **Step 1: Write the failing tests** — cover every rule + composition + graceful-degrade. Key cases:

```java
package dev.krillin.bifrost.core.conformance;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.Test;

class ConformanceEvaluatorTest {
    static UdtDefinition weld() {
        return new UdtDefinition("Weld-Controller", SemVer.parse("1.0.0"), List.of(
            new Member("WeldCurrent","Double",null,new Range(0,12)),
            new Member("WeldTime","Double",null,new Range(0,500)),
            new Member("ElectrodeForce","Double",null,new Range(0,6))), List.of(), null);
    }
    static ConformancePolicy lobe(String mode, String rRef, String rVer, Double tol) {
        return new ConformancePolicy("WeldPolicy","1.0.0","Weld-Controller","1.0.0",
            new ConformancePolicy.Dial(mode, rRef, rVer, tol),
            List.of(new CrossConstraint("weld-lobe","ElectrodeForce","lt",3.0,"WeldCurrent","le",8.0)),
            List.of());
    }
    ConformanceEvaluator ev = new ConformanceEvaluator();

    @Test void envelopePassesButCrossMemberDenies() {  // THE composition case
        var v = ev.evaluate(weld(), lobe("envelope",null,null,null), null, List.of(
            new Setpoint("WeldCurrent","Double",9.0), new Setpoint("ElectrodeForce","Double",2.5)));
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("conformance.cross.weld-lobe")));
    }
    @Test void withinLobeAndEnvelopePasses() {
        var v = ev.evaluate(weld(), lobe("envelope",null,null,null), null, List.of(
            new Setpoint("WeldCurrent","Double",7.0), new Setpoint("ElectrodeForce","Double",2.5)));
        assertTrue(v.ok());
    }
    @Test void envelopeAboveMax() {
        var v = ev.evaluate(weld(), null, null, List.of(new Setpoint("WeldCurrent","Double",13.0)));
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("spec.range.above-max")));
    }
    @Test void structuralUnknownMember() {
        var v = ev.evaluate(weld(), null, null, List.of(new Setpoint("Ghost","Double",1.0)));
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("spec.member.unknown")));
    }
    @Test void gracefulDegradeNoPolicy() {  // structural+type+envelope only, == old SpecConformanceChecker
        var v = ev.evaluate(weld(), null, null, List.of(new Setpoint("WeldCurrent","Double",7.0)));
        assertTrue(v.ok());
    }
    @Test void recipeModeDeviationDenies() {
        MasterSpec recipe = masterSpec("WeldCurrent", 9.0);  // approved schedule 9kA
        var v = ev.evaluate(weld(), lobe("recipe","WeldSchedule","1.0.0",0.0), recipe, List.of(
            new Setpoint("WeldCurrent","Double",7.0), new Setpoint("ElectrodeForce","Double",4.0)));
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("conformance.recipe.deviation")));
    }
    // masterSpec(member,value) helper — use the 6-arg MasterSpec(specRef, version, site,
    // equipmentRef, equipmentVersion, List<Setpoint> setpoints), e.g.
    //   new MasterSpec("WeldSchedule","1.0.0","BodyShop","Weld-Controller","1.0.0",
    //                  List.of(new Setpoint(member,"Double",value)));
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=ConformanceEvaluatorTest` → FAIL.

- [ ] **Step 3: Implement `ConformanceEvaluator`.** Compose the rules; accumulate violations; graceful-degrade on null policy. Reuse `Range` bounds; reuse existing rule-id spellings.

```java
package dev.krillin.bifrost.core.conformance;
import java.util.*;
import dev.krillin.bifrost.core.schema.*;

/** Pure composed conformance evaluator (the IAM-like policy evaluation engine). Rules: structural,
 *  type, envelope (from model), cross-member (from policy), recipe (recipe-mode). Violations accumulate. */
public final class ConformanceEvaluator {

    public ConformanceVerdict evaluate(UdtDefinition def, ConformancePolicy policy,
            MasterSpec activeRecipe, List<Setpoint> state) {
        List<Violation> v = new ArrayList<>();
        Map<String, Member> members = new LinkedHashMap<>();
        for (Member m : def.members()) members.put(m.name(), m);
        Map<String, Double> values = new LinkedHashMap<>();
        for (Setpoint s : state) values.put(s.member(), s.value());

        // structural + type + envelope, per candidate setpoint
        for (Setpoint s : state) {
            Member m = members.get(s.member());
            if (m == null) { v.add(new Violation("spec.member.unknown",
                    "setpoint targets member '" + s.member() + "' absent from '" + def.templateRef() + "'")); continue; }
            if (!m.type().equals(s.type())) v.add(new Violation("spec.type.mismatch",
                    "member '" + s.member() + "' type " + m.type() + " but setpoint " + s.type()));
            if (m.range() != null) {
                if (s.value() < m.range().low()) v.add(new Violation("spec.range.below-min",
                        "member '" + s.member() + "' value " + s.value() + " is below min " + m.range().low()));
                if (s.value() > m.range().high()) v.add(new Violation("spec.range.above-max",
                        "member '" + s.member() + "' value " + s.value() + " is above max " + m.range().high()));
            }
        }
        // cross-member (policy)
        if (policy != null) {
            for (CrossConstraint c : policy.crossConstraints()) {
                Double a = values.get(c.ifMember());
                Double b = values.get(c.thenMember());
                if (a == null || b == null) continue;              // both members must be present in the state
                if (cmp(a, c.ifOp(), c.ifValue()) && !cmp(b, c.thenOp(), c.thenValue())) {
                    v.add(new Violation("conformance.cross." + c.id(),
                        "cross-member " + c.id() + ": when " + c.ifMember() + " " + c.ifOp() + " " + c.ifValue()
                        + ", require " + c.thenMember() + " " + c.thenOp() + " " + c.thenValue()
                        + " (was " + b + ") — e.g. weld-lobe above-max"));
                }
            }
        }
        // recipe-mode
        if (policy != null && policy.dial() != null && "recipe".equals(policy.dial().mode()) && activeRecipe != null) {
            double tol = policy.dial().recipeTolerance() == null ? 0.0 : policy.dial().recipeTolerance();
            Map<String, Double> recipe = new LinkedHashMap<>();
            for (Setpoint s : activeRecipe.setpoints()) recipe.put(s.member(), s.value());
            for (Setpoint s : state) {
                Double target = recipe.get(s.member());
                if (target == null) continue;
                if (Math.abs(s.value() - target) > Math.abs(target) * tol) v.add(new Violation("conformance.recipe.deviation",
                        "member '" + s.member() + "' value " + s.value() + " deviates from approved recipe setpoint " + target));
            }
        }
        return new ConformanceVerdict(v.isEmpty(), List.copyOf(v));
    }

    private static boolean cmp(double x, String op, double y) {
        return switch (op) {
            case "lt" -> x < y; case "le" -> x <= y; case "gt" -> x > y; case "ge" -> x >= y; case "eq" -> x == y;
            default -> throw new IllegalArgumentException("bad op: " + op);
        };
    }
}
```
> Note the cross-member violation `detail` deliberately contains `above-max` so a runtime DENY on the weld-lobe still greps as a range-ish denial if needed; the primary rule id is `conformance.cross.<id>`.

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl core test -Dtest=ConformanceEvaluatorTest` → PASS (all cases).

- [ ] **Step 5: Delete `SpecConformanceChecker.java`** ONLY after A3 migrates `SpecGate` — for THIS task, leave it in place (A3 deletes it). If `SpecConformanceCheckerTest` exists, port its cases into `ConformanceEvaluatorTest` (they become the structural/type/envelope cases above) but do not delete the old test until A3.

- [ ] **Step 6: Commit** — `git add core/src/main/java/.../conformance/ConformanceEvaluator.java core/src/test/.../conformance/ConformanceEvaluatorTest.java` ; `git commit -m "feat(core): ConformanceEvaluator — composed structural+type+envelope+cross-member+recipe, rich verdict"`.

### Task A3: rewire `SpecGate` to the evaluator; delete `SpecConformanceChecker`/`SpecVerdict`

**Files:** Modify `gates/.../SpecGate.java`; Delete `core/.../schema/SpecConformanceChecker.java`, `core/.../schema/SpecVerdict.java`, and (if present) `core/.../schema/SpecConformanceCheckerTest.java`. Grep the repo for `SpecVerdict`/`SpecConformanceChecker` and fix all references (`gates` tests may reference them).

- [ ] **Step 1: Grep for references** — `grep -rn "SpecConformanceChecker\|SpecVerdict" core gates` — enumerate every call site to migrate.

- [ ] **Step 2: Modify `SpecGate.run`** — build the candidate state from the master spec's setpoints, load an OPTIONAL `ConformancePolicy` (graceful-degrade if absent), call the evaluator. The design-time gate does NOT use recipe-mode (pass `activeRecipe=null`; and force envelope semantics by passing `policy` with cross-member only — recipe branch is skipped because `activeRecipe==null`).

```java
// replace the SpecConformanceChecker block:
ConformancePolicy policy = ConformancePolicyStore.loadFor(registryDir, def.templateRef(), def.version().toString())
        .orElse(null);   // graceful-degrade: null => structural+type+envelope only
ConformanceVerdict verdict = new ConformanceEvaluator().evaluate(def, policy, null, spec.setpoints());
System.out.println("[GATE] ref=" + spec.specRef() + " equipment=" + spec.equipmentRef()
        + "@" + spec.equipmentVersion() + " setpoints=" + spec.setpoints().size());
if (verdict.ok()) { System.out.println("[GATE] PASS ✅"); return 0; }
System.out.println("[GATE] FAIL ❌ — violations:");
for (Violation viol : verdict.violations()) System.out.println("  - [" + viol.rule() + "] " + viol.detail());
return 1;
```
> `ConformancePolicyStore.loadFor(registryDir, ref, ver)` → `Optional<ConformancePolicy>` reading `<registryDir>/conformance/<ref>/<ver>.json` (create this tiny store in `core.conformance`, mirroring `DefinitionStore`; lenient `JsonMapperFactory`). For Phase-A spec fixtures there is no policy file → `Optional.empty()` → graceful-degrade → identical accept/reject to today.

- [ ] **Step 3: Create `ConformancePolicyStore`** (core.conformance) — `Optional<ConformancePolicy> loadFor(Path registryDir, String ref, String version)` reading `conformance/<ref>/<version>.json`; empty if the file is absent. Unit-test the load + absent cases.

- [ ] **Step 4: Delete** `SpecConformanceChecker.java`, `SpecVerdict.java` (+ its test). Fix any remaining references found in Step 1.

- [ ] **Step 5: Build + run the spec gate (no-regression)** — controller runs: `mvn -q -pl core,gates install` → BUILD SUCCESS; then `bash scripts/run-spec-gate.sh` → `[GATE] PASS` (accept conformant, reject out-of-range — identical behavior via graceful-degrade).

- [ ] **Step 6: Commit** — `git add -A core gates` ; `git commit -m "refactor(gates): SpecGate uses ConformanceEvaluator; delete SpecConformanceChecker/SpecVerdict (graceful-degrade keeps spec gate green)"`.

### Task A4: sim `WeldControllerType` + `BodyShop/Weld1` instance

**Files:** Modify `sim/.../EmbeddedMiloSim.java` (add a `createWeldType()` + `createWeldInstance()` mirroring `createMixerType()`/`createMixerInstance()`). Test: extend `sim/.../MixerTypeNodeTest.java` (or a new `WeldControllerNodeTest`).

- [ ] **Step 1: Write the failing test** — browse `ns=2;s=WeldControllerType` members {WeldCurrent,WeldTime,ElectrodeForce} with EURange on all three; read `BodyShop/Weld1` seeded values (WeldCurrent=6.0, WeldTime=200.0, **ElectrodeForce=2.5** — seeded below 3.0 so the runtime gate's weld-lobe case triggers).

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core,sim test -Dtest=WeldControllerNodeTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement `createWeldType()`/`createWeldInstance()`** in `SimNamespace.createNodes()` — mirror the existing Mixer helpers exactly (`typeMember`, `attachEuRange`): type `WeldControllerType` with members WeldCurrent(Double)[0,12], WeldTime(Double)[0,500], ElectrodeForce(Double)[0,6] all with EURange; instance `BodyShop/Weld1` typed by it, seeded WeldCurrent=6.0/WeldTime=200.0/ElectrodeForce=2.5. Members client-writable? No — instance members are read-only (ubyte(1)) like the Mixer; the runtime gate writes via a `Weld/WeldCurrent` **setpoint** node analogous to `Recipe/Rpm` (add a writable `Weld/WeldCurrent` Double setpoint node with an internal transfer to `BodyShop/Weld1.WeldCurrent`, mirroring the Recipe→Mixer transfer). Add writable `Weld/WeldCurrent` + transfer.

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl core,sim test -Dtest=WeldControllerNodeTest,MixerTypeNodeTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS (Mixer tests unaffected).

- [ ] **Step 5: Commit** — `git add sim` ; `git commit -m "feat(sim): WeldControllerType + BodyShop/Weld1 instance (+ Weld/WeldCurrent setpoint transfer) for conformance track"`.

### Task A5: Phase-A controller verification

- [ ] **Step 1: `mvn -q install` at bifrost root** → BUILD SUCCESS (core + gates + sim + heimdall all compile; new tests green).
- [ ] **Step 2: `bash scripts/run-spec-gate.sh`** → `[GATE] PASS` (design-time no-regression).
- [ ] **Step 3:** confirm nothing runtime changed yet (Heimdall untouched in Phase A).

---

## Chunk 2: Phase B — runtime wiring + Mixer migration + killer gate

### Task B1: Heimdall startup registry-loading

**Files:** Modify `heimdall/.../NcmdOpcUaBridgeMain.java` (+ its `Config`/defaults) and `NcmdOpcUaBridge.java` (accept the new deps). Test: extend `NcmdOpcUaBridgeMainDefaultsTest`.

- [ ] **Step 1: Write the failing test** — assert `resolve(getenv)` yields new `Config` fields: `registryPath` (default `registry`) and `conformancePath` (default `null`/empty = ② OFF). Keep it a pure config/loader test (no live OPC/MQTT).

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core,heimdall test -Dtest=NcmdOpcUaBridgeMainDefaultsTest` → FAIL.

- [ ] **Step 3: Implement** — add `registryPath` (env `REGISTRY_PATH`, default `registry`) and `conformancePath` (env `CONFORMANCE_PATH`, default null) to `Config`. **`CONFORMANCE_PATH` is the explicit selector** that resolves "which equipment/policy is pinned" for this daemon (the ncmd gate points it at the Mixer policy; the killer gate at the Weld policy) — this avoids a registry-wide scan and matches the existing `POLICY_PATH` pattern. In `main`, when `conformancePath` is set:
  - load the `ConformancePolicy` from `conformancePath` (lenient `JsonMapperFactory`),
  - load its `UdtDefinition` via `new DefinitionStore(Path.of(registryPath)).load(policy.equipmentRef(), policy.equipmentVersion())`,
  - if `policy.dial().mode()=="recipe"`, load the active recipe `MasterSpec` via a NEW tiny `MasterSpecStore.load(Path registryDir, String ref, String version)` → `Optional<MasterSpec>` reading `<registryDir>/spec/<ref>/<version>.json` (mirror `DefinitionStore`; lenient `JsonMapperFactory`; unit-test load + absent). Ref/ver from `policy.dial().activeRecipeRef()/activeRecipeVersion()`. Place the `WeldSchedule` fixture at `<REGISTRY_PATH>/spec/WeldSchedule/1.0.0.json` (killer-gate fixtures under `scripts/fixtures/conformance/…`). Create `MasterSpecStore` in `core.conformance` (or `core.schema`) as part of this task.
  - print `[BRIDGE] conformance loaded <equipmentRef>@<ver> dial=<mode>`.

  When `conformancePath` is null → pass nulls (② is a no-op; the bridge behaves exactly as today — pure authz). Pass everything into `new NcmdOpcUaBridge(group, edge, policy, applier, conformanceDef, conformancePolicy, activeRecipe)` (nullable trio).

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl core,heimdall test -Dtest=NcmdOpcUaBridgeMainDefaultsTest` → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(heimdall): load governed UdtDefinition + ConformancePolicy at bridge startup"`.

### Task B2: Heimdall ② conformance wiring (after ① authz)

**Files:** Modify `heimdall/.../NcmdOpcUaBridge.java` (the `handle` path), `heimdall/.../Applier.java` (the INTERFACE — add `double readDouble(String nodeId) throws Exception`), `heimdall/.../OpcUaApplier.java` (implement `readDouble` — parse its existing `read(nodeId)` string to double). Test: `NcmdOpcUaBridgeTest` (its fake `Applier` must also implement `readDouble`).

- [ ] **Step 1: Write the failing test** — with the Weld model + weld-lobe policy + a fake applier returning ElectrodeForce=2.5: an authorized `Weld/WeldCurrent=9` NCMD → bridge DENY with reason containing `conformance.cross.weld-lobe`, applier NOT called; `Weld/WeldCurrent=7` → APPLY. Also: an authorized command that fails envelope (`=13`) → DENY reason contains `above-max`.

- [ ] **Step 2: Run to verify it fails** → FAIL.

- [ ] **Step 3: Implement** — in `handle`, AFTER `authorizer.authorize` returns allow (the `d.allowed()` path) and BEFORE the existing `applier.apply` try-block. Use the LOCAL `dataType` (String) and `cr` (`CommandRequest`) that already exist in `handle` — **NOT `req.type()`** (`req` is the `SparkplugBPayload`, which has no `type()`). Only read siblings that a cross-constraint actually references AND that have a numeric `readNodeId` binding — so Mixer (no cross-constraints) reads NO siblings and never touches its Boolean `Running`. Wrap the read in try/catch and **DENY fail-closed** on read failure (checked `Exception` from `readDouble`):

```java
// ① authz already passed (d.allowed()). ② conformance (opt-in: only when a policy is loaded):
if (conformancePolicy != null && conformanceDef != null) {
    NodeBinding binding = conformancePolicy.nodeBindings().stream()
            .filter(b -> name.equals(b.opcNodeId())).findFirst().orElse(null);
    if (binding != null) {
        try {
            List<Setpoint> state = new ArrayList<>();
            state.add(new Setpoint(binding.member(), dataType, ((Number) value).doubleValue()));
            // siblings referenced by a cross-constraint, read from their bound INSTANCE node (numeric only)
            Set<String> needed = new LinkedHashSet<>();
            for (CrossConstraint c : conformancePolicy.crossConstraints()) {
                needed.add(c.ifMember()); needed.add(c.thenMember());
            }
            needed.remove(binding.member());
            for (String sibMember : needed) {
                NodeBinding sb = conformancePolicy.nodeBindings().stream()
                        .filter(b -> sibMember.equals(b.member()) && b.readNodeId() != null).findFirst().orElse(null);
                if (sb != null) state.add(new Setpoint(sibMember, "Double", applier.readDouble(sb.readNodeId())));
            }
            ConformanceVerdict cv = new ConformanceEvaluator()
                    .evaluate(conformanceDef, conformancePolicy, activeRecipe, state);
            if (!cv.ok()) {
                String reason = cv.violations().get(0).rule() + ": " + cv.violations().get(0).detail();
                System.out.println("[BRIDGE] DENY cmd=" + name + " val=" + value + " reason=" + reason);
                return NcmdResponse.apply(cmdId, false, "denied: " + reason);
            }
        } catch (Exception confEx) {   // fail-closed: any conformance/read error DENIES
            System.out.println("[BRIDGE] DENY cmd=" + name + " val=" + value + " reason=conformance-error: " + confEx.getMessage());
            return NcmdResponse.apply(cmdId, false, "denied: conformance-error");
        }
    }
}
// ... existing applier.apply(...) + [BRIDGE] APPLY log ...
```
> The DENY line reuses the EXACT existing format `[BRIDGE] DENY cmd=<name> val=<v> reason=<r>`. An envelope violation's rule id is `spec.range.above-max`, so `reason` contains `above-max` (satisfies the ncmd gate's `.*above-max` grep). Imports needed: `java.util.{ArrayList,List,LinkedHashSet,Set}`, `dev.krillin.bifrost.core.conformance.*`, `dev.krillin.bifrost.core.schema.Setpoint`. `conformancePolicy`/`conformanceDef`/`activeRecipe` are the new bridge fields from B1 (nullable → ② is a no-op, preserving pure-authz back-compat).

- [ ] **Step 4: Run to verify pass** → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(heimdall): ② conformance check after ① authz — cross-member + envelope from the governed model"`.

### Task B3: the killer gate `run-composable-conformance-gate.sh` (Weld domain, additive)

**Files:** Create `scripts/run-composable-conformance-gate.sh` + fixtures under `scripts/fixtures/` (Weld `UdtDefinition`, `ConformancePolicy` envelope + a recipe variant, a `WeldSchedule` master spec). Mirror `run-ncmd-runtime-gate.sh` + `run-yggdrasil-spine-gate.sh` idioms (Docker broker, sim, Heimdall daemon, `RogueNcmd` publisher, `jps -lm` kill).

- [ ] **Step 1: Write the gate** — assertions:
  - **C1 composition:** ElectrodeForce=2.5 (seeded), authorized NCMD `Weld/WeldCurrent=9` → `[BRIDGE] DENY ... conformance.cross.weld-lobe`; `=7` → `[BRIDGE] APPLY ok=true`.
  - **C2 design==runtime:** `gates spec` on a master spec (WeldCurrent=9 with the weld-lobe policy present in the registry) → REJECT (exit 1); mirrors C1's runtime DENY.
  - **C3 single-source dual-eval:** tighten the governed policy's weld-lobe `thenValue` 8→ (or `ifValue` 3.0→3.5), re-run BOTH `gates spec` and a runtime NCMD → both verdicts flip, `policy.json` untouched.
  - **C4 dial:** publish a **recipe-mode** policy variant (`dial.mode=recipe`, `activeRecipeRef=WeldSchedule`, `recipeTolerance=0`) that **omits the weld-lobe cross-constraint** (so no sibling read; the frozen ElectrodeForce=2.5 is never involved), with a `WeldSchedule` master spec whose setpoints are **WeldCurrent only** (=9 kA). Runtime `Weld/WeldCurrent=7` → state=[WeldCurrent=7] → `[BRIDGE] DENY ... conformance.recipe.deviation` (7≠9); `Weld/WeldCurrent=9` → envelope pass + recipe 9==9 → `[BRIDGE] APPLY ok=true`. (No writable `Weld/ElectrodeForce` is needed because recipe-mode checks only the commanded member's value.)
  - **C5 lifecycle:** `gates provenance publish` the policy bytes; `gates provenance verify` clean → 0; tamper the published policy → verify exit 1.

- [ ] **Step 2: Syntax check** — `bash -n scripts/run-composable-conformance-gate.sh` → SYNTAX OK.
- [ ] **Step 3: Controller runs it** — `bash scripts/run-composable-conformance-gate.sh` → `[GATE] PASS` (C1–C5). (This is a controller done-bit; the implementer writes the script, the controller runs it.)
- [ ] **Step 4: Commit** — `chmod +x` + `git add scripts/run-composable-conformance-gate.sh scripts/fixtures` ; `git commit -m "test(gate): run-composable-conformance-gate — composition + single-source dual-eval + dial + lifecycle [C1-C5]"`.

### Task B4: Mixer migration (SAME commit, no broken window)

**Files:** Create `heimdall/registry/udt/Line1-Mixer/1.0.0.json` (Mixer def with Rpm range [0,3000], Temp [0,450]) + `heimdall/registry/conformance/Line1-Mixer/1.0.0.json` (policyRef `Line1-Mixer-policy`, equipmentRef `Line1-Mixer`@1.0.0, dial=envelope, **no cross-constraints**, nodeBindings `{opcNodeId:"ns=2;s=Recipe/Rpm", readNodeId:null, member:"Rpm"}` and same for `Temp`). Modify `heimdall/registry/policy.json` (rpm/temp rules → `constraint{type}` only). Modify `scripts/run-ncmd-runtime-gate.sh` to **activate ② for the Mixer daemon** (REQUIRED, not optional — else ② is OFF and T3 regresses).

- [ ] **Step 1:** create the Mixer `udt` + `conformance` registry files under `heimdall/registry/`. (The `conformance` policy has NO cross-constraints, so ② reads zero siblings — `Running`/`Secret` are never read.)
- [ ] **Step 2:** edit `heimdall/registry/policy.json`: `rpm` rule `constraint` → `{"type":"Double"}` (drop min/max); `temp` likewise; `activate` (`constraint==null` trigger-only) UNCHANGED.
- [ ] **Step 3 (REQUIRED gate edit — same commit):** in `scripts/run-ncmd-runtime-gate.sh`, in the `step 3` Heimdall-daemon `export` block (next to `POLICY_PATH`), add:
```bash
export REGISTRY_PATH="$(cygpath -m "$(pwd)/heimdall/registry")"
export CONFORMANCE_PATH="$(cygpath -m "$(pwd)/heimdall/registry/conformance/Line1-Mixer/1.0.0.json")"
```
This turns ON ② conformance for the Mixer so the range now enforced from the governed model. Without it, ② stays OFF (default `CONFORMANCE_PATH` null) and T3 (`Rpm=9999`) would APPLY — a regression.
- [ ] **Step 4: Controller runs `bash scripts/run-ncmd-runtime-gate.sh`** → `[GATE] PASS`: T1 (Rpm=1500) → authz type-ok + ② in-range → APPLY; T2 (Recipe/Secret) → authz deny-by-default (no binding, ② not reached); T3 (Rpm=9999) → authz allows (type-ok, no bounds) → ② DENY with reason `spec.range.above-max` (contains `above-max`, matches the gate grep). If the grep fails, fix the DENY reason string (must contain `above-max`), NOT the range source.
- [ ] **Step 5: Commit (single commit — model+binding+policy.json+gate-env together)** — `git add heimdall/registry scripts/run-ncmd-runtime-gate.sh` ; `git commit -m "refactor(heimdall): migrate Mixer Recipe/Rpm range from policy.json to the governed model (conformance ②); ncmd gate activates ② via CONFORMANCE_PATH, no regression"`.

### Task B5: Phase-B controller final verification (the #1 rule)

- [ ] **Step 1: `mvn -q install` at bifrost root** → BUILD SUCCESS (all modules + all new tests).
- [ ] **Step 2: `bash scripts/run-composable-conformance-gate.sh`** → `[GATE] PASS` (C1–C5), controller-run.
- [ ] **Step 3: No-regression, controller-run:** `run-spec-gate.sh`, `run-ncmd-runtime-gate.sh`, `run-yggdrasil-spine-gate.sh`, `run-yggdrasil-full-loop-gate.sh` → all `[GATE] PASS`.
- [ ] **Step 4: Update memory** — record the composable-conformance track DONE (controller-verified), honest limitations, all local/unpushed.

---

## Notes / risks
- **Sibling live-read for cross-member at runtime:** the bridge reads sibling member values from the live sim via `OpcUaApplier.readDouble`. Ensure the Weld instance seeds ElectrodeForce=2.5 so C1 triggers deterministically. Document the sibling-node resolution convention in the Weld policy fixture (explicit bindings preferred over string-concat).
- **`above-max` token:** the ncmd gate greps `\[BRIDGE\] DENY cmd=... .*above-max`. The envelope violation rule id is `spec.range.above-max` → the DENY `reason=` contains it. Keep it; do not rename the rule id.
- **Additive-first is mandatory:** B3 (Weld gate) must pass BEFORE B4 (Mixer migration). B4 is one commit that adds the model+binding AND drops the policy.json range AND re-verifies the ncmd gate — never split those.
- **No push** — all commits local on `feat/yggdrasil-spine` until Eisen OKs.
