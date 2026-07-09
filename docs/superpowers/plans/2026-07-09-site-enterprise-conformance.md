# site ⊨ enterprise conformance (T1) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add prescriptive model governance — a site equipment `UdtDefinition` must conform to an enterprise template (`site ⊨ enterprise`) via a new `gates template` leg — and prove the governance core is **standard-agnostic** by ingesting the SAME standard from three genuinely-different external shapes (Ignition UDT, CFIHOS, AAS) through pluggable adapters that all map to the one internal canonical model.

**Architecture:** Ports & adapters / dependency inversion. The invariant CORE (`TemplateConformanceChecker`) operates ONLY on `core.schema.UdtDefinition` (the port); external standards are driven-adapters (`IgnitionUdtAdapter`/`CfihosTemplateAdapter`/`AasSubmodelAdapter`) that read a FOREIGN-vocabulary JSON tree and produce a `UdtDefinition` — the core imports no adapter and no external schema. Mirrors the existing `SpecGate` → checker → `ConformanceVerdict` pattern.

**Tech Stack:** Java 17, Jackson (`core.schema.JsonMapperFactory`), JUnit 5, Maven. All in the bifrost multi-module repo (`core`, `gates`). Branch `feat/yggdrasil-spine` (commit locally, do NOT push).

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-09-site-enterprise-conformance-design.md`

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `core/.../conformance/TemplateConformanceChecker.java` | pure `site ⊨ enterprise` checker → `ConformanceVerdict` | Create |
| `core/.../conformance/adapter/TemplateAdapter.java` | interface `UdtDefinition adapt(JsonNode external)` | Create |
| `core/.../conformance/adapter/IgnitionUdtAdapter.java` | Ignition UDT export tree → UdtDefinition | Create |
| `core/.../conformance/adapter/CfihosTemplateAdapter.java` | CFIHOS equipment-class tree → UdtDefinition | Create |
| `core/.../conformance/adapter/AasSubmodelAdapter.java` | AAS submodel-template tree → UdtDefinition | Create |
| `gates/.../TemplateGate.java` | `gates template <registryDir> <siteDefFile>` (mirror `SpecGate`) | Create |
| `gates/.../AdaptTemplate.java` | `gates adapt-template <ignition\|cfihos\|aas> <extFile> <outFile> <ref> <version>` CLI (5 args) | Create |
| `gates/.../GatesCli.java` | add `template` + `adapt-template` legs + usage | Modify |
| `scripts/fixtures/template/*` | native template, conforming + 3 violating sites, Ignition/CFIHOS/AAS externals | Create |
| `scripts/run-template-conformance-gate.sh` | P1 accept / P2×3 reject / P3 three-adapter equivalence | Create |

**Existing shapes (verified):** `UdtDefinition(String templateRef, SemVer version, List<Member> members, List<Param> params, String conformsTo)` · `Member(String name, String type, String semanticId, Range range)` · `Range(double low, double high)` · `ConformanceVerdict(boolean ok, List<Violation> violations)` · `Violation(String rule, String detail)` (in `core.schema`) · `DefinitionStore(Path).load(String ref, String version) → Optional<UdtDefinition>` (reads `udt/<ref>/<version>.json`) · on-disk UdtDefinition JSON = `{templateRef, version, members:[{name,type,semanticId,range:{low,high}|null}], params:[], conformsTo}` · `GatesCli` dispatches `switch(args[0]) → XxxGate.run(rest)`.

---

## Chunk 1: core checker + gates template leg (native path)

### Task 1: `TemplateConformanceChecker`

**Files:** Create `core/src/main/java/dev/krillin/bifrost/core/conformance/TemplateConformanceChecker.java`. Test: `core/src/test/java/dev/krillin/bifrost/core/conformance/TemplateConformanceCheckerTest.java`.

Semantics (site may specialize/tighten/extend, not violate): for each ENTERPRISE-template member — structural (site has it), type match, semanticId match, range-envelope (site range ⊆ template range). Site EXTRA members are allowed. Reuses `core.schema.{UdtDefinition,Member,Range,Violation}` + `core.conformance.ConformanceVerdict`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.krillin.bifrost.core.conformance;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.Test;

class TemplateConformanceCheckerTest {
    static UdtDefinition template() {  // enterprise WeldController-corp, all members required, ranges are envelopes
        return new UdtDefinition("WeldController-corp", SemVer.parse("1.0.0"), List.of(
            new Member("WeldCurrent","Double","corp:weld/current", new Range(0,15)),
            new Member("WeldTime","Double","corp:weld/time", new Range(0,600)),
            new Member("ElectrodeForce","Double","corp:weld/force", new Range(0,8))), List.of(), null);
    }
    static Member m(String n,String t,String sem,Range r){ return new Member(n,t,sem,r); }
    TemplateConformanceChecker chk = new TemplateConformanceChecker();

    @Test void conformingSiteTightenedAndExtended() {  // tighten ranges + add WeldVoltage
        UdtDefinition site = new UdtDefinition("Ulsan-Weld", SemVer.parse("1.0.0"), List.of(
            m("WeldCurrent","Double","corp:weld/current", new Range(0,12)),
            m("WeldTime","Double","corp:weld/time", new Range(0,500)),
            m("ElectrodeForce","Double","corp:weld/force", new Range(0,6)),
            m("WeldVoltage","Double","ulsan:weld/voltage", new Range(0,20))), List.of(), "WeldController-corp@1.0.0");
        assertTrue(chk.check(site, template()).ok());
    }
    @Test void exceedsEnvelopeRejected() {
        UdtDefinition site = withCurrent(new Range(0,20));  // 20 > template 15
        var v = chk.check(site, template());
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("template.range.exceeds-envelope")));
    }
    @Test void missingRequiredMemberRejected() {
        UdtDefinition site = new UdtDefinition("Ulsan-Weld", SemVer.parse("1.0.0"), List.of(
            m("WeldCurrent","Double","corp:weld/current", new Range(0,12)),
            m("ElectrodeForce","Double","corp:weld/force", new Range(0,6))), List.of(), "WeldController-corp@1.0.0"); // no WeldTime
        var v = chk.check(site, template());
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("template.member.missing")));
    }
    @Test void semanticIdMismatchRejected() {
        UdtDefinition site = new UdtDefinition("Ulsan-Weld", SemVer.parse("1.0.0"), List.of(
            m("WeldCurrent","Double","ulsan:current", new Range(0,12)),
            m("WeldTime","Double","corp:weld/time", new Range(0,500)),
            m("ElectrodeForce","Double","corp:weld/force", new Range(0,6))), List.of(), "WeldController-corp@1.0.0");
        var v = chk.check(site, template());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("template.semanticId.mismatch")));
    }
    @Test void typeMismatchRejected() {
        UdtDefinition site = withCurrentType("Float");
        assertTrue(chk.check(site, template()).violations().stream().anyMatch(x -> x.rule().equals("template.type.mismatch")));
    }
    // helpers withCurrent(Range)/withCurrentType(String) build a full 3-member site varying only WeldCurrent.
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=TemplateConformanceCheckerTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (class missing).

- [ ] **Step 3: Implement `TemplateConformanceChecker`:**

```java
package dev.krillin.bifrost.core.conformance;
import java.util.*;
import dev.krillin.bifrost.core.schema.*;

/** Prescriptive model governance: a site UdtDefinition must CONFORM to an enterprise template.
 *  Subtyping/Liskov — the site may specialize (tighten ranges), extend (add members), but not violate.
 *  Per template member: structural (present), type, semanticId, range-envelope (site range ⊆ template range).
 *  Site members NOT in the template are allowed (extension). Violations accumulate. */
public final class TemplateConformanceChecker {

    public ConformanceVerdict check(UdtDefinition site, UdtDefinition template) {
        List<Violation> v = new ArrayList<>();
        Map<String, Member> siteMembers = new LinkedHashMap<>();
        for (Member m : site.members()) siteMembers.put(m.name(), m);

        for (Member t : template.members()) {
            Member s = siteMembers.get(t.name());
            if (s == null) {
                v.add(new Violation("template.member.missing",
                    "site '" + site.templateRef() + "' is missing required member '" + t.name()
                    + "' from template '" + template.templateRef() + "'"));
                continue;  // absent member cannot be type/range checked
            }
            if (!Objects.equals(t.type(), s.type())) v.add(new Violation("template.type.mismatch",
                "member '" + t.name() + "' type " + s.type() + " != template " + t.type()));
            if (!Objects.equals(t.semanticId(), s.semanticId())) v.add(new Violation("template.semanticId.mismatch",
                "member '" + t.name() + "' semanticId '" + s.semanticId() + "' != template '" + t.semanticId() + "'"));
            // range envelope: site range ⊆ template range (site may tighten, not exceed)
            if (t.range() != null) {
                if (s.range() == null) {
                    v.add(new Violation("template.range.exceeds-envelope",
                        "member '" + t.name() + "' is unbounded but template envelope is [" + t.range().low() + "," + t.range().high() + "]"));
                } else {
                    if (s.range().low() < t.range().low()) v.add(new Violation("template.range.exceeds-envelope",
                        "member '" + t.name() + "' low " + s.range().low() + " < template low " + t.range().low()));
                    if (s.range().high() > t.range().high()) v.add(new Violation("template.range.exceeds-envelope",
                        "member '" + t.name() + "' high " + s.range().high() + " > template high " + t.range().high()));
                }
            }
        }
        return new ConformanceVerdict(v.isEmpty(), List.copyOf(v));
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl core test -Dtest=TemplateConformanceCheckerTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS (all methods).

- [ ] **Step 5: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/conformance/TemplateConformanceChecker.java core/src/test/java/dev/krillin/bifrost/core/conformance/TemplateConformanceCheckerTest.java` ; `git commit -m "feat(core): TemplateConformanceChecker — site ⊨ enterprise (superset+type+semanticId+range-envelope)"`.

### Task 2: `TemplateGate` + `gates template` leg

**Files:** Create `gates/src/main/java/dev/krillin/bifrost/gates/TemplateGate.java`. Modify `gates/.../GatesCli.java`. Test: `gates/src/test/java/dev/krillin/bifrost/gates/TemplateGateTest.java`.

Read `SpecGate.java` first to mirror it exactly (JsonMapperFactory read, DefinitionStore load, `[GATE]` output, exit 0/1/2).

- [ ] **Step 1: Write the failing test** — `TemplateGateTest` with `@TempDir`: write a native template to `<reg>/udt/WeldController-corp/1.0.0.json`, write a conforming site file → `TemplateGate.run([reg, siteFile])` == 0; write a violating site (exceeds envelope) → == 1; a site whose `conformsTo` points at an absent template → == 2.

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core,gates test -Dtest=TemplateGateTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement `TemplateGate`** (mirror `SpecGate`):

```java
package dev.krillin.bifrost.gates;
import java.nio.file.Path;
import java.util.Optional;
import dev.krillin.bifrost.core.schema.*;
import dev.krillin.bifrost.core.conformance.*;

/** site ⊨ enterprise conformance gate. Loads the site UdtDefinition, reads its conformsTo="ref@version",
 *  loads that enterprise template from <registryDir>/udt/<ref>/<version>.json, checks conformance.
 *  Exit 0 conform / 1 violations / 2 error. Usage: TemplateGate <registryDir> <siteDefFile> */
public final class TemplateGate {
    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (args.length < 2) { System.err.println("Usage: TemplateGate <registryDir> <siteDefFile>"); return 2; }
        Path registryDir = Path.of(args[0]);
        try {
            UdtDefinition site = JsonMapperFactory.create().readValue(Path.of(args[1]).toFile(), UdtDefinition.class);
            if (site.conformsTo() == null || !site.conformsTo().contains("@")) {
                System.err.println("[GATE] error: site def has no conformsTo=<ref>@<version>"); return 2;
            }
            String[] rv = site.conformsTo().split("@", 2);
            Optional<UdtDefinition> tOpt = new DefinitionStore(registryDir).load(rv[0], rv[1]);
            if (tOpt.isEmpty()) { System.err.println("[GATE] error: enterprise template " + site.conformsTo() + " not in registry"); return 2; }
            ConformanceVerdict v = new TemplateConformanceChecker().check(site, tOpt.get());
            System.out.println("[GATE] site=" + site.templateRef() + " conformsTo=" + site.conformsTo()
                + " members=" + site.members().size());
            if (v.ok()) { System.out.println("[GATE] PASS ✅"); return 0; }
            System.out.println("[GATE] FAIL ❌ — violations:");
            for (Violation viol : v.violations()) System.out.println("  - [" + viol.rule() + "] " + viol.detail());
            return 1;
        } catch (Exception e) { System.err.println("[GATE] error: " + e.getMessage()); return 2; }
    }
}
```
Add to `GatesCli.run` switch: `case "template": return TemplateGate.run(rest);` and update the usage string to `<schema|spec|policy|provenance|template|adapt-template>`.

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl core,gates test -Dtest=TemplateGateTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS.

- [ ] **Step 5: Commit** — `git add gates/src/main/java/dev/krillin/bifrost/gates/TemplateGate.java gates/.../GatesCli.java gates/src/test/.../TemplateGateTest.java` ; `git commit -m "feat(gates): gates template leg — site ⊨ enterprise conformance gate"`.

---

## Chunk 2: the three standard-family adapters (ports & adapters)

### Task 3: `TemplateAdapter` interface + `IgnitionUdtAdapter`

**Files:** Create `core/.../conformance/adapter/TemplateAdapter.java`, `IgnitionUdtAdapter.java`. Test: `IgnitionUdtAdapterTest.java`.

**Adapters read a FOREIGN-vocabulary JSON tree (`JsonNode`) — NOT a Bifrost record — and produce a `UdtDefinition`.** This is the anti-corruption boundary: the core imports no external schema; adapters depend on the core's `UdtDefinition`.

- [ ] **Step 1: Write the failing test** — the load-bearing equivalence proof `adapt(ignitionFixture) ≡ native template`:

```java
package dev.krillin.bifrost.core.conformance.adapter;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import dev.krillin.bifrost.core.schema.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class IgnitionUdtAdapterTest {
    static UdtDefinition nativeTemplate() {
        return new UdtDefinition("WeldController-corp", SemVer.parse("1.0.0"), List.of(
            new Member("WeldCurrent","Double","corp:weld/current", new Range(0,15)),
            new Member("WeldTime","Double","corp:weld/time", new Range(0,600)),
            new Member("ElectrodeForce","Double","corp:weld/force", new Range(0,8))), List.of(), null);
    }
    @Test void ignitionExportAdaptsToNative() throws Exception {
        // FOREIGN vocabulary: Ignition tag JSON (dataType/engLow/engHigh/tags) — NOT Bifrost field names.
        String ignition = """
          { "name":"WeldController-corp", "typeId":"WeldController-corp",
            "tags":[
              {"name":"WeldCurrent","dataType":"Float8","engLow":0,"engHigh":15,"semanticId":"corp:weld/current"},
              {"name":"WeldTime","dataType":"Float8","engLow":0,"engHigh":600,"semanticId":"corp:weld/time"},
              {"name":"ElectrodeForce","dataType":"Float8","engLow":0,"engHigh":8,"semanticId":"corp:weld/force"}] }
          """;
        JsonNode tree = JsonMapperFactory.create().readTree(ignition);
        UdtDefinition adapted = new IgnitionUdtAdapter().adapt(tree, "WeldController-corp", "1.0.0");
        assertEquals(nativeTemplate(), adapted);   // ⭐ record equality: adapt(external) ≡ native
    }
}
```
> `semanticId` in the Ignition fixture rides in a designated custom UDT property `semanticId` (Ignition has no native corporate IRI — honest carry per spec §3.2). `dataType:"Float8"` → `"Double"` and `engLow`/`engHigh` → `Range` are the genuine transformations.

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=IgnitionUdtAdapterTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement the interface + adapter:**

```java
// TemplateAdapter.java
package dev.krillin.bifrost.core.conformance.adapter;
import com.fasterxml.jackson.databind.JsonNode;
import dev.krillin.bifrost.core.schema.UdtDefinition;
/** Anti-corruption adapter: maps a FOREIGN external-standard JSON tree to the internal canonical UdtDefinition.
 *  ref/version parameterize the produced template's identity (the external doc may not carry Bifrost's). */
public interface TemplateAdapter { UdtDefinition adapt(JsonNode external, String ref, String version); }
```
```java
// IgnitionUdtAdapter.java
package dev.krillin.bifrost.core.conformance.adapter;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import dev.krillin.bifrost.core.schema.*;
/** Ignition UDT export (tags/dataType/engLow/engHigh) -> UdtDefinition. Ignition dataTypes map to our type
 *  vocabulary; engLow/engHigh -> Range; semanticId is read from a designated custom property (honest carry). */
public final class IgnitionUdtAdapter implements TemplateAdapter {
    public UdtDefinition adapt(JsonNode ext, String ref, String version) {
        List<Member> members = new ArrayList<>();
        for (JsonNode tag : ext.path("tags")) {
            Range range = tag.has("engLow") && tag.has("engHigh")
                ? new Range(tag.get("engLow").asDouble(), tag.get("engHigh").asDouble()) : null;
            members.add(new Member(tag.get("name").asText(), mapType(tag.get("dataType").asText()),
                tag.hasNonNull("semanticId") ? tag.get("semanticId").asText() : null, range));
        }
        return new UdtDefinition(ref, SemVer.parse(version), members, List.of(), null);
    }
    private static String mapType(String ignition) {
        return switch (ignition) { case "Float8","Float4" -> "Double"; case "Int4","Int8" -> "Int32"; case "Boolean" -> "Boolean"; default -> ignition; };
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl core test -Dtest=IgnitionUdtAdapterTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS. (`UdtDefinition`/`Member`/`Range` are records → `equals` is structural, so `assertEquals` is a true equivalence proof.)

- [ ] **Step 5: Commit** — `git commit -am "feat(core): ports&adapters — TemplateAdapter + IgnitionUdtAdapter (commercial UDT export -> canonical, adapt≡native)"`.

### Task 4: `CfihosTemplateAdapter`

**Files:** Create `core/.../conformance/adapter/CfihosTemplateAdapter.java` + test. Same pattern; FOREIGN vocabulary = CFIHOS `properties[]` with `propertyId`(IRI, → semanticId natively), `datatype:"REAL"`→"Double", `minValue`/`maxValue`→Range, `requirement:"M"/"O"` (map "M"→include; "O"→drop with a logged decision — optional-member support is a reserved future extension per spec §7). Test: `adapt(cfihosFixture) ≡ native` (the fixture uses `properties`/`propertyId`/`datatype`/`minValue`/`maxValue`/`requirement`, NOT Bifrost names). Steps mirror Task 3 (red→green→commit). Commit: `feat(core): CfihosTemplateAdapter (process-RDL class -> canonical; requirement M/O honest drop)`.

### Task 5: `AasSubmodelAdapter`

**Files:** Create `core/.../conformance/adapter/AasSubmodelAdapter.java` + test. FOREIGN vocabulary = AAS submodel JSON: `submodelElements[]` of `modelType:"Property"` with `idShort`(→name), `valueType:"xs:double"`(→"Double"), native `semanticId.keys[0].value`(IRI → semanticId — AAS carries it natively), and range via `qualifiers[]` (`{type:"Min",value:"0"}`,`{type:"Max",value:"15"}`) → Range. Test: `adapt(aasFixture) ≡ native` (fixture uses AAS vocabulary). Steps mirror Task 3. **Two transforms to get right (the equivalence test guards them):** AAS qualifier `value`s are STRINGS (`"0"`/`"15"`) → `Double.parseDouble(...)`; `semanticId` is NESTED → navigate `el.path("semanticId").path("keys").get(0).path("value").asText()`. Commit: `feat(core): AasSubmodelAdapter (Industrie-4.0 submodel -> canonical; native semanticId)`.

### Task 6: `gates adapt-template` CLI (so the gate can invoke adapters as a subprocess)

**Files:** Create `gates/.../AdaptTemplate.java`; add `case "adapt-template": return AdaptTemplate.run(rest);` to `GatesCli`. Test: `AdaptTemplateTest`.

- [ ] **Step 1: Write the failing test** — `AdaptTemplate.run([kind, extFile, outFile, ref, version])`: reads the external JSON, picks the adapter by `kind` (`ignition|cfihos|aas`), writes the adapted `UdtDefinition` JSON (via `JsonMapperFactory`) to `outFile`; exit 0; unknown kind → 2. Assert the written file deserializes to a `UdtDefinition` equal to the native template.

- [ ] **Step 2–4:** implement (dispatch kind→adapter, read tree, adapt, write via `JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(outFile, def)`); red→green.

- [ ] **Step 5: Commit** — `feat(gates): gates adapt-template CLI (ignition|cfihos|aas external -> canonical UdtDefinition)`.

---

## Chunk 3: fixtures + killer gate + controller verification

### Task 7: fixtures + `run-template-conformance-gate.sh`

**Files:** Create under `scripts/fixtures/template/`: `native-template.json` (WeldController-corp UdtDefinition, the enterprise standard), `site-conforming.json` (conformsTo, tightened+extended → accept), `site-exceeds.json` / `site-missing.json` / `site-semanticid.json` (the 3 violations), and the 3 externals `ext-ignition.json` / `ext-cfihos.json` / `ext-aas.json` (**each in its OWN foreign vocabulary — a grep for Bifrost field names `members`/`type`/`range`/`low`/`high` on these three MUST return nothing**). Create `scripts/run-template-conformance-gate.sh` (mirror `run-spec-gate.sh` — pure CLI, NO Docker; build `gates/target/bifrost-gates.jar` if missing). **Carry over `run-spec-gate.sh`'s Windows/Git-Bash discipline: convert every path with `cygpath -m` before `java -jar`** (POSIX-style paths fail the JVM here). **Non-circularity grep must target the QUOTED JSON key `"low"`/`"members"` (NOT bare `low` — the Ignition fixture's `engLow` contains the substring `low`).**

- [ ] **Step 1: Write the gate script** — assertions (each runs `java -jar gates.jar template …` / `adapt-template …`):
  - **P1 accept:** set up `$WORK/reg/udt/WeldController-corp/1.0.0.json` = native-template; `gates template $WORK/reg site-conforming.json` → exit 0.
  - **P2 reject ×3 (exit 1 each):** `gates template` on site-exceeds / site-missing / site-semanticid → each exit 1, and grep the FAIL output for `template.range.exceeds-envelope` / `template.member.missing` / `template.semanticId.mismatch` respectively.
  - **P3 three-adapter equivalence:** for each `kind` in ignition cfihos aas:
    1. `gates adapt-template $kind ext-$kind.json $WORK/adapted-$kind.json WeldController-corp 1.0.0`.
    2. **grep-verify non-circularity:** assert `ext-$kind.json` contains the foreign vocab token (`engLow`/`propertyId`/`submodelElements`) AND does NOT contain `"members"`/`"low"` (fail the gate if it does).
    3. put `adapted-$kind.json` into a fresh registry `$WORK/reg-$kind/udt/WeldController-corp/1.0.0.json`; run `gates template $WORK/reg-$kind site-conforming.json` → exit 0 (SAME as P1) AND `gates template $WORK/reg-$kind site-exceeds.json` → exit 1 (SAME as P2). → the adapted template drives IDENTICAL verdicts to the native one.
  - End: `echo "[GATE] PASS run-template-conformance-gate.sh"; exit 0`. `fail()` tails logs.

- [ ] **Step 2: Syntax check** — `bash -n scripts/run-template-conformance-gate.sh` → SYNTAX OK; `chmod +x`.

- [ ] **Step 3: Commit** — `git add scripts/run-template-conformance-gate.sh scripts/fixtures/template` ; `git commit -m "test(gate): run-template-conformance-gate — P1 accept, P2×3 reject, P3 three-adapter (Ignition/CFIHOS/AAS) equivalence"`.

### Task 8: controller-direct final verification (the #1 rule)

- [ ] **Step 1:** controller runs `mvn -q install` at bifrost root → BUILD SUCCESS (core + gates incl. the checker, 3 adapters, TemplateGate, AdaptTemplate tests).
- [ ] **Step 2:** controller runs `bash scripts/run-template-conformance-gate.sh` → `[GATE] PASS` with P1/P2×3/P3 all observed.
- [ ] **Step 3: No-regression (controller-run):** `run-spec-gate.sh`, `run-ncmd-runtime-gate.sh`, `run-yggdrasil-spine-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-composable-conformance-gate.sh` → all `[GATE] PASS`.
- [ ] **Step 4:** update memory (T1 DONE, controller-verified, standard-agnostic 3-adapter platform proof); report with evidence. All local/unpushed.

---

## Notes / risks
- **Adapters read `JsonNode` (foreign vocabulary), never a Bifrost record** — this IS the anti-corruption boundary; do not shortcut by deserializing the external as a `UdtDefinition`.
- **`adapt(external) ≡ native` is proven by record `equals`** (UdtDefinition/Member/Range are records) — a true structural equivalence, not a string compare. Keep the native template and the adapters' output identical (same members, order, semanticId, range).
- **Non-circularity is load-bearing:** the 3 external fixtures MUST use their own vocabulary; the gate greps to enforce it. A fixture pre-shaped as Bifrost JSON would make P3 vacuous.
- **Field casing** (Ignition `dataType`/`engLow`; AAS `submodelElements`/`valueType`/`qualifiers`; CFIHOS `propertyId`/`minValue`) is illustrative — confirm against a real Ignition Maker export / AAS metamodel serialization at implementation; adjust the adapter + fixture together (the equivalence test guards correctness).
- **No push** — all commits local on `feat/yggdrasil-spine`.
