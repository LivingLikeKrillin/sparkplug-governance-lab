# OPA Command Authorization Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an OPA/Rego command-authorization policy that composes request + operational **context** (time, equipment state) — a decision the hand-rolled `CommandAuthorizer` structurally cannot make — evaluated **in-process via a committed WASM bundle** (no runtime OPA), proven by a pure `mvn test` in CI.

**Architecture:** A freshly authored `command_authz.rego` (deny-by-default; disjoint rules; a `decision := {allow, reason, rule}` entrypoint) is compiled by `opa build -t wasm` to a **committed** `policy.wasm`. `OpaPolicy` (a thin Chicory wrapper implementing the OPA WASM ABI, one-shot `opa_eval`, builtin host **stubs**) evaluates it in-process. `OpaCommandAuthorizer` builds the input JSON from `(CommandRequest, Context)` and maps the result to the existing `Decision`. `CommandAuthorizer`/`CommandPolicyGate`/`NcmdOpcUaBridge` are untouched.

**Tech Stack:** Java 17 (CI runs Java 21) / Maven / JUnit 5. OPA (Rego → WASM, build-time only). Chicory (`com.dylibso.chicory:runtime`, pure-JVM WASM runtime). Jackson (already present).

**Spec:** `docs/superpowers/specs/2026-07-07-opa-command-authz-design.md` (APPROVED). Read it first — especially §3.1 (ABI: one-shot `opa_eval`, memory is *exported*, builtins are declared imports needing *stub* host functions) and §3.3 (first-match-vs-OR caveat → disjoint rules).

**Prereq:** the `opa` binary must be installed for the build/spike steps that compile Rego → WASM (`build-policy.sh`, Chunk 0/1). The committed `.wasm` makes `mvn test` itself hermetic (no `opa`, no network). Repo: `sparkplug-governance-lab`, branch `feat/opa-command-authz` (spec already committed there). Merge only on Eisen's explicit OK.

---

## File Structure
| File | C/M | Responsibility |
|---|---|---|
| `pom.xml` | Modify | add `com.dylibso.chicory:runtime` dependency |
| `src/main/java/dev/krillin/sparkplug/acl/opa/OpaPolicy.java` | Create | Chicory wrapper over `policy.wasm` — OPA WASM ABI, one-shot `opa_eval`, `eval(inputJson): decisionJson` |
| `src/main/java/dev/krillin/sparkplug/acl/opa/Context.java` | Create | `record Context(String state, int hour)` — the operational context input |
| `src/main/java/dev/krillin/sparkplug/acl/opa/OpaCommandAuthorizer.java` | Create | build input JSON from `(CommandRequest, Context)`, call `OpaPolicy`, map → `Decision` |
| `src/main/java/dev/krillin/sparkplug/acl/opa/OpaCommandDemo.java` | Create (optional) | `main` printing a few decisions (mirrors `CommandAclDemo`) |
| `src/main/resources/opa/command_authz.rego` | Create | the demo policy (deny-by-default, disjoint rules, `decision` entrypoint) |
| `src/main/resources/opa/command_authz.wasm` | Create (committed binary) | `opa build -t wasm` output, extracted `policy.wasm` |
| `src/test/rego/command_authz_test.rego` | Create | Rego-native `opa test` proof |
| `scripts/build-policy.sh` | Create | regenerate `.wasm` from `.rego` (pins `opa` version) |
| `.gitattributes` | Create/Modify | `*.wasm binary` |
| `src/test/java/dev/krillin/sparkplug/acl/opa/OpaWasmSpikeTest.java` | Create (Chunk 0, may delete) | de-risk: trivial Rego→wasm→Chicory round-trip |
| `src/test/java/dev/krillin/sparkplug/acl/opa/OpaCommandAuthorizerTest.java` | Create | subsumption + extension + context-blindness contrast |
| `.github/workflows/governance-ci.yml` | Modify | add `policy-wasm` drift-guard job (opa build + `git diff --exit-code`) |
| `docs/adr/ADR-0011-command-authorization.md` + `.en.md` | Modify | "OPA/Rego + in-JVM WASM" section |
| `README.md:55` | Modify | `acl` row: OPA-evaluated context-conditional authorization |

---

## Chunk 0: Spike — de-risk Chicory + the OPA WASM ABI

> The spec's #1 risk. Prove the ABI glue end-to-end against a TRIVIAL policy before authoring the real one. This chunk PRODUCES the reusable `OpaPolicy` skeleton.

### Task 0.1: add Chicory + confirm its API

**Files:** Modify `pom.xml`

- [ ] **Step 1:** Add to `pom.xml` `<dependencies>` (verify the latest version on Maven Central for `com.dylibso.chicory:runtime` — use a recent 1.x):
```xml
    <dependency>
      <groupId>com.dylibso.chicory</groupId>
      <artifactId>runtime</artifactId>
      <version>1.1.0</version> <!-- VERIFY latest on Maven Central; pin exactly -->
    </dependency>
```
- [ ] **Step 2:** `mvn -B -q dependency:resolve` → confirms it downloads. Note the exact Chicory package/class names for the spike (`com.dylibso.chicory.wasm.Parser`, `com.dylibso.chicory.runtime.Instance`, `HostFunction`, `ImportValues`, `Memory`, `ValueType` — the API has shifted across versions; the spike below pins the actual calls).
- [ ] **Step 3: Commit** `git add pom.xml && git commit -m "build(acl): add Chicory pure-JVM WASM runtime"`

### Task 0.2: trivial-policy round-trip spike (the ABI skeleton)

**Files:** Create `src/main/resources/opa/spike.rego` (temp), the spike wasm, `OpaPolicy.java`, `OpaWasmSpikeTest.java`

- [ ] **Step 1: Author a trivial Rego** `src/main/resources/opa/spike.rego`:
```rego
package spike
decision := {"ok": input.x > 5}
```
- [ ] **Step 2: Build it to wasm** (needs `opa`):
```bash
opa build -t wasm -e spike/decision src/main/resources/opa/spike.rego -o /tmp/spike-bundle.tar.gz
tar -xzf /tmp/spike-bundle.tar.gz -C /tmp   # extracts /tmp/policy.wasm
cp /tmp/policy.wasm src/main/resources/opa/spike.wasm
```
- [ ] **Step 3: Inspect the module's imports/exports** so the ABI wiring is exact:
```bash
opa inspect /tmp/spike-bundle.tar.gz   # entrypoints
# and dump wasm imports/exports (wasm-objdump if available, or Chicory's parser in the test)
```
Confirm the module **imports** `env.opa_abort`, `env.opa_println`, `env.opa_builtin0..4` (and `env.memory`? — per the OPA ABI the module *exports* `memory`; verify) and **exports** `opa_malloc`, `opa_eval`, `opa_json_dump`, `opa_json_parse`, `memory`, `opa_heap_ptr_get`/`opa_heap_ptr_set`, `entrypoints`.

- [ ] **Step 4: Write the spike test** `src/test/java/dev/krillin/sparkplug/acl/opa/OpaWasmSpikeTest.java` — loads `spike.wasm` through `OpaPolicy`, evaluates `{"x": 9}` → `{"ok": true}` and `{"x": 1}` → `{"ok": false}`:
```java
package dev.krillin.sparkplug.acl.opa;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpaWasmSpikeTest {
    @Test void trivialPolicyRoundTrips() {
        OpaPolicy p = OpaPolicy.fromResource("/opa/spike.wasm");
        assertTrue(p.eval("{\"x\": 9}").contains("\"ok\":true"));
        assertTrue(p.eval("{\"x\": 1}").contains("\"ok\":false"));
    }
}
```

- [ ] **Step 5: Implement `OpaPolicy`** `src/main/java/dev/krillin/sparkplug/acl/opa/OpaPolicy.java`. This is the ABI glue — the ALGORITHM is fixed by the OPA WASM ABI (below); wire it with the **actual Chicory API confirmed in Steps 2–3**. Algorithm:
  1. **Instantiate** the module supplying host-function **stubs** for the declared imports `opa_abort(int)` (throw/log), `opa_println(int)` (no-op), and `opa_builtin0..4(...)` (throw "no builtins expected" — never invoked per dec.7). (If `memory` is an import in the built module, provide one; per the OPA ABI it is usually exported — use whichever the inspection showed.)
  2. Grab exports: `memory`, `opa_malloc`, `opa_eval`, `opa_json_dump`, `opa_heap_ptr_get`, `opa_heap_ptr_set`, and the entrypoint id from `entrypoints` (or pass entrypoint 0 for a single-entrypoint build).
  3. **eval(inputJson):** UTF-8 bytes of `inputJson`; `addr = opa_malloc(len)`; write bytes into exported `memory` at `addr`; `heap = opa_heap_ptr_get()`; call **one-shot** `resultAddr = opa_eval(0 /*reserved*/, entrypointId, 0 /*data addr, none*/, addr, len, heap, 0 /*format=JSON*/)`; read the NUL-terminated / length-dumped result via `opa_json_dump(resultAddr)` → a C-string addr → read bytes from memory until NUL → UTF-8 string.
  4. The result is a **result set** `[{"result": <decision>}]` — `OpaPolicy.eval` returns that raw JSON; callers unwrap `[0].result` (or add an `evalEntrypoint` helper that unwraps). Keep `eval` returning the raw string for the spike; add the unwrap in the domain adapter (Chunk 2).
  - `fromResource(path)`: read the wasm bytes from the classpath, parse+instantiate once, reuse.
  - **Fail-closed:** any ABI error / non-`[{"result":...}]` shape → throw `IllegalStateException` (the domain adapter converts a throw to `Decision.deny`).

- [ ] **Step 6: Run the spike** `mvn -B test -Dtest=OpaWasmSpikeTest` → **iterate the Chicory wiring until GREEN.** This is the de-risk gate; do not proceed until the trivial round-trip passes.
- [ ] **Step 7: Commit** the working skeleton:
```bash
git add pom.xml src/main/java/dev/krillin/sparkplug/acl/opa/OpaPolicy.java \
        src/main/resources/opa/spike.rego src/main/resources/opa/spike.wasm \
        src/test/java/dev/krillin/sparkplug/acl/opa/OpaWasmSpikeTest.java
git commit -m "feat(acl): OpaPolicy — in-JVM OPA-WASM eval via Chicory (spike-proven)"
```
(The `spike.rego`/`spike.wasm`/`OpaWasmSpikeTest` may be deleted in Chunk 3 cleanup once the real policy test covers `OpaPolicy`, or kept as a minimal ABI regression — decide at cleanup.)

---

## Chunk 1: the Rego policy + committed wasm

### Task 1.1: author `command_authz.rego` + Rego tests

**Files:** Create `src/main/resources/opa/command_authz.rego`, `src/test/rego/command_authz_test.rego`

- [ ] **Step 1: Write the policy** `src/main/resources/opa/command_authz.rego`. Deny-by-default; **disjoint** rules (§3.3); the three fail-closed denies reproduced; `decision` entrypoint with `rule`:
```rego
package acl.command_authz

# Fresh demo policy (own vocabulary: "write", "SafeHold", "recipe.rpmSetpoint") — NOT a translation
# of command-policy.json. Deny-by-default; rules authored non-overlapping so first-match(hand-rolled)
# vs OR(rego) can't diverge. Context (hour, state) is pure input — no builtins.

default allow := false

reason := r { not allow; r := deny_reason }
reason := "allow" { allow }

matched_rule_id := id { allow; id := allow_rule_id }
matched_rule_id := "none" { not allow }

# ---- extension: high-rpm setpoint requires day-shift AND Execute (context-conditional) ----
allow if {
	input.command == "write"
	input.target.group == "line1"
	input.value == to_number(input.value)          # value is numeric
	input.value > 1000
	input.value <= 3000                              # upper bound (parity w/ max)
	input.context.hour >= 6
	input.context.hour < 22
	input.context.state == "Execute"
}
allow_rule_id := "rpm-high-day-execute" if {
	input.command == "write"; input.value > 1000
}

# ---- subsumption: normal-range write (<=1000) allowed regardless of context ----
allow if {
	input.command == "write"
	input.target.group == "line1"
	input.value == to_number(input.value)
	input.value >= 0
	input.value <= 1000
}

# ---- extension: SafeHold only from Execute ----
allow if {
	input.command == "SafeHold"
	input.context.state == "Execute"
}

# ---- deny reasons (human-readable; deny-by-default otherwise) ----
deny_reason := "high-rpm restricted to day-shift(06-22) & Execute" if {
	input.command == "write"; input.value > 1000; not allow
}
deny_reason := "SafeHold requires state=Execute" if {
	input.command == "SafeHold"; not allow
}
deny_reason := "no-matching-rule (deny-by-default)" if {
	not rpm_case; not safehold_case
}
rpm_case if { input.command == "write" }
safehold_case if { input.command == "SafeHold" }

decision := {"allow": allow, "reason": reason, "rule": matched_rule_id}
```
> NOTE: this is illustrative Rego (OPA v0.60+/1.0 `if`/`contains` syntax). The implementer refines it during `opa test` (Step 2) — the REQUIREMENTS are: deny-by-default, disjoint rules, the S3 extension (rpm day+Execute; SafeHold Execute), subsumption of the <=1000 case, and a `decision` object with `allow`/`reason`/`rule`. Reproduce the fail-closed edges (type mismatch / non-numeric / trigger-only) if you extend beyond this demo set.

- [ ] **Step 2: Write Rego tests** `src/test/rego/command_authz_test.rego`:
```rego
package acl.command_authz
test_rpm_high_day_execute_allow { decision.allow with input as {"command":"write","target":{"group":"line1"},"value":1500,"context":{"hour":14,"state":"Execute"}} }
test_rpm_high_night_deny { not decision.allow with input as {"command":"write","target":{"group":"line1"},"value":1500,"context":{"hour":2,"state":"Execute"}} }
test_rpm_high_nonexecute_deny { not decision.allow with input as {"command":"write","target":{"group":"line1"},"value":1500,"context":{"hour":14,"state":"Idle"}} }
test_rpm_normal_allow_any_context { decision.allow with input as {"command":"write","target":{"group":"line1"},"value":800,"context":{"hour":2,"state":"Idle"}} }
test_safehold_execute_allow { decision.allow with input as {"command":"SafeHold","target":{"group":"line1"},"context":{"state":"Execute"}} }
test_safehold_held_deny { not decision.allow with input as {"command":"SafeHold","target":{"group":"line1"},"context":{"state":"Held"}} }
test_unknown_deny { not decision.allow with input as {"command":"reboot","target":{"group":"line1"},"context":{"hour":14,"state":"Execute"}} }
```
- [ ] **Step 3: Run `opa test`** until green: `opa test src/main/resources/opa/command_authz.rego src/test/rego/ -v`
- [ ] **Step 4: Commit** `git add src/main/resources/opa/command_authz.rego src/test/rego/command_authz_test.rego && git commit -m "feat(acl): command_authz Rego policy (context-conditional) + opa tests"`

### Task 1.2: build + commit the wasm, build script, .gitattributes

**Files:** Create `scripts/build-policy.sh`, `src/main/resources/opa/command_authz.wasm`, `.gitattributes`

- [ ] **Step 1: Write `scripts/build-policy.sh`** (pins opa version in the header):
```bash
#!/usr/bin/env bash
# Regenerates src/main/resources/opa/command_authz.wasm from command_authz.rego.
# Pinned toolchain: opa 0.70.0 (build with: opa version). Requires the `opa` binary.
set -euo pipefail
cd "$(dirname "$0")/.."
opa build -t wasm -e acl/command_authz/decision \
  src/main/resources/opa/command_authz.rego -o build/policy-bundle.tar.gz
tar -xzf build/policy-bundle.tar.gz -C build   # → build/policy.wasm
cp build/policy.wasm src/main/resources/opa/command_authz.wasm
echo "built src/main/resources/opa/command_authz.wasm"
```
- [ ] **Step 2: Run it** `bash scripts/build-policy.sh` → produces the committed `.wasm`.
- [ ] **Step 3: `.gitattributes`** — add `*.wasm binary`.
- [ ] **Step 4: Commit** `git add scripts/build-policy.sh src/main/resources/opa/command_authz.wasm .gitattributes && git commit -m "build(acl): build-policy.sh + committed command_authz.wasm"`

---

## Chunk 2: the domain adapter + the JVM proof

### Task 2.1: `Context` + `OpaCommandAuthorizer`

**Files:** Create `Context.java`, `OpaCommandAuthorizer.java`

- [ ] **Step 1: `Context.java`**:
```java
package dev.krillin.sparkplug.acl.opa;
/** Operational context supplied to the policy (lab-local input; no cross-repo feed). */
public record Context(String state, int hour) {}
```
- [ ] **Step 2: `OpaCommandAuthorizer.java`** — builds the input JSON, calls `OpaPolicy`, unwraps `[0].result`, maps to `Decision` (fail-closed on any error):
```java
package dev.krillin.sparkplug.acl.opa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.krillin.sparkplug.acl.CommandRequest;
import dev.krillin.sparkplug.acl.Decision;

/** OPA/Rego command authorizer: evaluates command_authz.wasm in-process (Chicory). Context-aware —
 *  the go-forward richer engine alongside the hand-rolled CommandAuthorizer. Fail-closed. */
public final class OpaCommandAuthorizer {
    private static final ObjectMapper M = new ObjectMapper();
    private final OpaPolicy policy;

    public OpaCommandAuthorizer() { this.policy = OpaPolicy.fromResource("/opa/command_authz.wasm"); }
    OpaCommandAuthorizer(OpaPolicy policy) { this.policy = policy; }   // test seam

    public Decision authorize(CommandRequest req, Context ctx) {
        try {
            ObjectNode input = M.createObjectNode();
            input.put("command", req.command());
            ObjectNode t = input.putObject("target");
            t.put("group", req.target().group());
            t.put("edge", req.target().edge());
            t.put("device", req.target().device());
            input.put("type", req.type());
            input.set("value", M.valueToTree(req.value()));
            ObjectNode c = input.putObject("context");
            c.put("state", ctx.state());
            c.put("hour", ctx.hour());

            String resultJson = policy.eval(M.writeValueAsString(input));      // [{"result": {...}}]
            JsonNode dec = M.readTree(resultJson).path(0).path("result");
            if (dec.isMissingNode()) return Decision.deny("opa: empty result set (deny-by-default)");
            boolean allow = dec.path("allow").asBoolean(false);
            return allow ? Decision.allow(dec.path("rule").asText("opa"))
                         : Decision.deny(dec.path("reason").asText("opa-deny"));
        } catch (Exception e) {
            return Decision.deny("opa-eval-error: " + e.getMessage());       // fail-closed
        }
    }
}
```
- [ ] **Step 3: Compile** `mvn -B -q -DskipTests compile` → SUCCESS.
- [ ] **Step 4: Commit** `git add src/main/java/dev/krillin/sparkplug/acl/opa/Context.java src/main/java/dev/krillin/sparkplug/acl/opa/OpaCommandAuthorizer.java && git commit -m "feat(acl): OpaCommandAuthorizer — CommandRequest+Context → Decision via OPA"`

### Task 2.2: `OpaCommandAuthorizerTest` (subsumption + extension + contrast) — TDD-style

**Files:** Create `OpaCommandAuthorizerTest.java`

- [ ] **Step 1: Write the test** — pure `mvn test` (loads the real committed wasm):
```java
package dev.krillin.sparkplug.acl.opa;

import dev.krillin.sparkplug.acl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpaCommandAuthorizerTest {
    private final OpaCommandAuthorizer opa = new OpaCommandAuthorizer();
    private final CommandAuthorizer hand = new CommandAuthorizer();
    private Target line1() { return new Target("line1", "*", "*"); }
    private CommandRequest write(double v) { return new CommandRequest(line1(), "write", v, "Double"); }
    private CommandRequest safeHold() { return new CommandRequest(line1(), "SafeHold", Boolean.TRUE, "Boolean"); }

    @Test void denyByDefault_unknownCommand() {
        assertFalse(opa.authorize(new CommandRequest(line1(), "reboot", 1, "Int"),
                                  new Context("Execute", 14)).allowed());
    }
    @Test void subsumption_normalRange_allowedAnyContext() {
        assertTrue(opa.authorize(write(800), new Context("Idle", 2)).allowed());
    }
    @Test void extension_highRpm_dayExecute_allow() {
        assertTrue(opa.authorize(write(1500), new Context("Execute", 14)).allowed());
    }
    @Test void extension_highRpm_night_deny() {
        assertFalse(opa.authorize(write(1500), new Context("Execute", 2)).allowed());
    }
    @Test void extension_highRpm_nonExecute_deny() {
        assertFalse(opa.authorize(write(1500), new Context("Idle", 14)).allowed());
    }
    @Test void extension_safeHold_execute_allow_held_deny() {
        assertTrue(opa.authorize(safeHold(), new Context("Execute", 14)).allowed());
        assertFalse(opa.authorize(safeHold(), new Context("Held", 14)).allowed());
    }
    @Test void contrast_handRolledIsContextBlind_allows1500_soDenialIsContextOnly() {
        // The hand-rolled engine, given a policy that bounds rpm at max 3000, ALLOWS 1500 —
        // it cannot see hour/state. So OPA's night-deny of the same request is a context-only
        // capability the flat engine structurally lacks. (Build a minimal in-bounds policy.)
        CommandPolicy p = new CommandPolicy("1", java.util.List.of(
            new Rule("rpm", line1(), "write", new Constraint("Double", 0.0, 3000.0))), "deny");
        assertTrue(hand.authorize(p, write(1500)).allowed(), "hand-rolled allows 1500 (context-blind)");
        assertFalse(opa.authorize(write(1500), new Context("Execute", 2)).allowed(), "OPA denies at night");
    }
}
```
> Adjust `Target`/`Rule`/`Constraint` constructor arities to the real records (verify: `Target(group,edge,device)`, `Rule(id,target,command,constraint)`, `Constraint(type,min,max)` — check the source). The `write`/`SafeHold`/`group="line1"` vocabulary must match the Rego authored in Task 1.1.

- [ ] **Step 2: Run, confirm PASS:** `mvn -B test -Dtest=OpaCommandAuthorizerTest`
- [ ] **Step 3: (optional) `OpaCommandDemo`** mirroring `CommandAclDemo` — a `main` printing 3–4 decisions. Skip if time-boxed.
- [ ] **Step 4: Full suite** `mvn -B test` → all green (existing 142+ tests + the new ones).
- [ ] **Step 5: Commit** `git add src/test/java/dev/krillin/sparkplug/acl/opa/OpaCommandAuthorizerTest.java && git commit -m "test(acl): OpaCommandAuthorizer — subsumption + context extension + context-blindness contrast"`

---

## Chunk 3: CI drift-guard + docs

### Task 3.1: `.wasm`↔`.rego` drift-guard CI job

**Files:** Modify `.github/workflows/governance-ci.yml`

- [ ] **Step 1: Add a job** (after `gates`) that installs `opa`, rebuilds the wasm, and fails if the committed binary is stale:
```yaml
  policy-wasm:
    name: OPA policy wasm drift-guard
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: install opa
        run: |
          curl -L -o /usr/local/bin/opa https://openpolicyagent.org/downloads/v0.70.0/opa_linux_amd64_static
          chmod +x /usr/local/bin/opa
      - name: opa test (policy-native)
        run: opa test src/main/resources/opa/command_authz.rego src/test/rego/ -v
      - name: rebuild wasm + assert no drift
        run: |
          bash scripts/build-policy.sh
          git diff --exit-code -- 'src/main/resources/opa/*.wasm' \
            || { echo "::error::command_authz.wasm is stale — run scripts/build-policy.sh and commit"; exit 1; }
```
(Pin the opa version to match `build-policy.sh`. The existing `test` job — pure `mvn -B test` — already runs `OpaCommandAuthorizerTest` hermetically; this job additionally guards the binary + runs the Rego-native tests.)
- [ ] **Step 2: Commit** `git add .github/workflows/governance-ci.yml && git commit -m "ci(acl): OPA policy wasm drift-guard + opa test job"`

### Task 3.2: ADR-0011 (both langs) + README

**Files:** Modify `docs/adr/ADR-0011-command-authorization.md` + `.en.md`, `README.md`

- [ ] **Step 1:** Add an "OPA/Rego + in-JVM WASM evaluation" section to both ADR files: the decision to externalize the command-policy decision to Rego for context-conditional authorization; evaluated in-process via a committed WASM bundle (Chicory) — no runtime OPA; the hand-rolled `CommandAuthorizer` kept for the context-blind path; the broader command-security architecture (capability tokens/mTLS) noted as roadmap.
- [ ] **Step 2:** Update `README.md:55` (`acl` row) to note OPA-evaluated context-conditional authorization (in-JVM WASM), keeping the existing NCMD/deny-by-default text.
- [ ] **Step 3: Commit** `git add docs/adr/ADR-0011-command-authorization*.md README.md && git commit -m "docs(acl): ADR-0011 + README — OPA/Rego in-JVM WASM authorization"`

### Task 3.3: verify + (optional) spike cleanup

- [ ] **Step 1: Controller-direct verification:** `mvn -B test` → all green (the authoritative CI-parity proof). `bash scripts/build-policy.sh && git diff --exit-code -- '*.wasm'` → no drift.
- [ ] **Step 2: (optional) cleanup** — if `OpaWasmSpikeTest`/`spike.rego`/`spike.wasm` are redundant with the real policy test, `git rm` them; OR keep the spike as a minimal ABI regression. Decide + commit.
- [ ] **Step 3: STOP — do not merge.** Present `mvn test` result + the drift-guard result; await Eisen's explicit OK for the PR.

---

## Done criteria
- `mvn -B test` green (existing suite + `OpaWasmSpikeTest` [if kept] + `OpaCommandAuthorizerTest`): deny-by-default, subsumption (normal-range any-context allow), extension (rpm day+Execute allow / night deny / non-Execute deny; SafeHold Execute allow / Held deny), and the **context-blindness contrast** (hand-rolled allows 1500, OPA denies at night).
- `opa test` green (Rego-native).
- `scripts/build-policy.sh` reproduces the committed `.wasm` with **no `git diff`** (drift-guard).
- CI: the `test` job proves ② hermetically (no opa, no network) = ①-parity; the `policy-wasm` job guards drift + runs `opa test`.
- `CommandAuthorizer`/`CommandPolicyGate`/`NcmdOpcUaBridge` unchanged.
- ADR-0011 (both langs) + README updated. **STOP — await Eisen's explicit merge OK.**
