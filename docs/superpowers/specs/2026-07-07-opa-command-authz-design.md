# OPA command authorization — context-conditional policy, evaluated in-JVM (WASM)

- **Status:** **DESIGN — awaiting spec review + approval.** Not started.
- **Date:** 2026-07-07
- **Branch (planned):** `feat/opa-command-authz` off `main` (repo: `sparkplug-governance-lab`, PUBLIC; `governance-ci.yml` runs `mvn test`).
- **Track:** Elevates the `acl` (command authorization) module from a hand-rolled decision engine to an **OPA/Rego policy** evaluated **locally, in-process, via a WebAssembly bundle** — completing the one remaining "설계 제안" cell of the UNS-series coverage table (② 명령 인가: "정책 엔진(OPA) 질의 구조"). The reference architecture aim for the broader command-security layer (capability tokens, mTLS, pre-authorization) is out of scope here (roadmap).

## 1. Problem & value (why this is not a lateral swap)

`acl` already does policy-as-code: a JSON `CommandPolicy` evaluated by a hand-rolled `CommandAuthorizer` (first-match, deny-by-default, target match, `type`/`min`/`max`/trigger-only constraints). That engine sees only the **request** (`command`, `target`, `value`, `type`). It is structurally **context-blind** — it cannot reason about *when* a command arrives or *what state* the equipment is in.

The value of OPA here is not "use a different engine" (that would be make-work). It is that **Rego expresses context-conditional authorization the flat engine fundamentally cannot**: a decision that composes the request with **operational context** (time-of-day, equipment state) — e.g. "a high-RPM setpoint is authorized only during the day shift *and* only while the line is in Execute." This is a genuine capability upgrade (declarative, standard, composable policy), and it is proven the same way the schema gate is: a pure `mvn test` in CI, with **no runtime OPA dependency** — the policy is compiled to WASM and evaluated in-process.

## 2. Locked decisions (brainstorm 2026-07-07)

| # | Decision |
|---|----------|
| 1 | **Scope = narrow (A).** OPA becomes the `acl` command-policy decision engine for a richer, context-conditional policy. The broader command-security architecture (capability tokens, mTLS, pre-auth, edge-enforcement wiring) stays roadmap. |
| 2 | **Integration = WASM-in-JVM (c).** `opa build -t wasm` compiles Rego → `policy.wasm`; a pure-JVM WASM runtime (**Chicory**, no native deps) evaluates it in-process. No OPA server, no network, no subprocess. |
| 3 | **CI-parity.** The compiled `.wasm` is **committed**, so the JVM proof is a pure `mvn test` (no `opa` binary at test time) — the same CI-green proof mode as `SchemaGate` (①). Regen of the bundle is a documented `opa build` script. |
| 4 | **Demonstration = S3 combined.** One Rego policy composes **time + state** context (high-RPM allowed only day-shift AND state==Execute; `SafeHold` only from Execute), demonstrating multi-dimension context reasoning a flat engine can't. |
| 5 | **Keep + alongside (non-breaking).** `CommandAuthorizer` (hand-rolled), `CommandPolicyGate`, and `NcmdOpcUaBridge` are **unchanged**. `OpaCommandAuthorizer` is added as the go-forward richer engine; a test proves it **subsumes** the simple cases and **extends** with context cases. |
| 6 | **No edge-enforcement wiring this track.** Wiring OPA into `NcmdOpcUaBridge` (edge D3 enforcement) is a follow-up; here OPA is added + proven by the CI test (avoids touching the live-demo bridge). |
| 7 | **Context is pure INPUT; no builtin *implementations* needed.** The Rego evaluates pure logic over `input` (`context.hour`, `context.state` supplied by the caller) — it does **not** call `time.now_ns()` or any builtin, so the decision is deterministic/testable. NOTE (corrected): an `opa build -t wasm` module **always declares imports for `opa_abort`, `opa_println`, and `opa_builtin0..4`** regardless of whether the policy uses builtins — WASM instantiation fails if any declared import is unsatisfied. So Chicory must still supply **all of them as host stubs**; dec.7 only means those stubs are **never invoked** (trivial throw-if-called stubs suffice — no real `time`/builtin semantics). Memory: **verified against the actual opa 0.70.0 build — `env.memory` is IMPORTED** (the host creates the `Memory` and passes it in; it is also re-exported as `memory`). The complete import set is `env.memory` + `env.opa_abort` + `env.opa_builtin0..4` — no `opa_println` in this build. |

### Non-goals (roadmap)
- Capability tokens / mTLS / pre-authorization / signing — the broader command-security direction (see the companion engine's edge-authorization design notes).
- Wiring OPA into the live `NcmdOpcUaBridge` edge enforcement (follow-up).
- Real integration with the companion engine's FSM for `context.state` — here `state` is an input field; a live FSM feed is a later cross-repo step.
- Replacing `CommandAuthorizer` / migrating existing consumers.
- Time builtins / non-deterministic policy (context is supplied input, dec.7).

## 3. Architecture

### 3.1 Components (all in `dev.krillin.sparkplug.acl`, or an `acl.opa` sub-package)
- **`policy/command_authz.rego`** (NEW) — a **freshly authored demonstration policy** with its own vocabulary (`write`, `SafeHold`, `recipe.rpmSetpoint`); it is NOT a mechanical translation of the committed `command-policy.json` (whose command identities differ) — it re-implements the *kinds* of checks the hand-rolled engine does, plus the S3 context rules. `package acl.command_authz`; `default allow = false`; the entrypoint is a single rule **`decision := {"allow": allow, "reason": reason, "rule": matched_rule_id}`** (built with `-e acl/command_authz/decision` — the `-e` name MUST match this rule name). To keep faithful subsumption (see §3.3), the demo rules are authored **non-overlapping** (disjoint match-sets) so first-match vs Rego-OR semantics can't diverge. Committed source.
- **`policy/command_authz_test.rego`** (NEW) — Rego-native unit tests (`opa test`), the policy author's proof.
- **`policy/command_authz.wasm`** (NEW, committed) — `opa build -t wasm -e acl/command_authz/decision` output (the `policy.wasm` extracted from the bundle). Committed so `mvn test` needs no `opa` binary.
- **`scripts/build-policy.sh`** (NEW) — regenerates the `.wasm` from the `.rego` (requires the `opa` binary; documented, not run in the standard `mvn test`). Its header **pins the exact `opa` version + build flags** for reproducibility (the `.wasm` is a committed binary in a public repo); add a `.gitattributes` `*.wasm binary` marker.
- **`OpaPolicy`** (NEW) — a thin wrapper over Chicory that loads `command_authz.wasm` and evaluates it. Uses the **one-shot `opa_eval` entrypoint path** (NOT the `opa_eval_ctx_*` context API — they are *alternative* paths, not a sequence): instantiate the module supplying host stubs for `opa_abort`, `opa_println`, `opa_builtin0..4` (dec.7 — declared imports, never invoked); then `opa_malloc` + write the **input JSON string** into the module's **exported** memory and pass its `(addr, len)` directly to `opa_eval` (which parses the input internally — do NOT `opa_json_parse` the input; `opa_json_parse` is only for a `data` value, which this policy doesn't need → `dataAddr = 0`), call `opa_eval(0, entrypoint, 0 /*data*/, inputAddr, inputLen, heapPtr, 0 /*json*/)` — with `format=0` this **returns the address of a NUL-terminated JSON string directly**; read the bytes from exported memory until NUL (do NOT use `opa_json_dump`, which is for the ctx-API value path). Reset the heap pointer to a captured base before each eval. **The result is a set wrapped as `[{"result": <value>}]`** — unwrap `[0].result` to get the `{allow, reason, rule}` object. Exposes `eval(inputJson): decisionJson`. This is the **main implementation effort** (the ABI glue); the §6 spike de-risks it end-to-end (instantiate-with-stubs + a trivial policy round-trip) before the real policy.
- **`OpaCommandAuthorizer`** (NEW) — domain adapter: builds the `input` JSON from `(CommandRequest, Context)`, calls `OpaPolicy.eval`, parses the `{allow, reason}` result into the existing `Decision` type. Same `Decision` output shape as `CommandAuthorizer` (so it's a drop-in richer engine).
- **`OpaCommandDemo`** (NEW, optional) — a `main` printing a few decisions (allow day/Execute, deny night, deny non-Execute), mirroring `CommandAclDemo`.

### 3.2 Input / output contract
```jsonc
input = {
  "command": "SafeHold",                         // or "write"
  "target":  { "group": "...", "edge": "...", "device": "..." },
  "type":    "Double",                           // metric datatype
  "value":   1500,
  "context": { "state": "Execute", "hour": 14 }  // supplied by the caller (test/demo); lab-local, no cross-repo feed
}
decision = { "allow": true|false, "reason": "...", "rule": "rpm-day-execute" }   // deny-by-default
// (wrapped by opa_eval as [{"result": decision}]; OpaPolicy unwraps [0].result)
```
`OpaCommandAuthorizer` maps this to the existing `Decision`: `allow==true` → `Decision.allow(rule)` (the `rule` field supplies the `ruleId` that `Decision.allow` requires — the Rego MUST emit it on the allow path); `allow==false` → `Decision.deny(reason)`.

### 3.3 The S3 policy (concretely)
- **deny-by-default:** `default allow = false`.
- **Subsumption (parity with the hand-rolled engine) — with a semantics caveat:** the Rego reproduces target/command match + `type` match + `min`/`max` bounds + trigger-only (`value==true`) for constraint-less rules, **AND the three fail-closed deny branches** `CommandAuthorizer` has (`CommandAuthorizer.java`): type-mismatch → deny, value-present-but-not-a-Number → deny, trigger-only `value!=true` → deny. **CAVEAT:** `CommandAuthorizer` is **first-match** (the first matching rule is final, even when it *denies*), whereas Rego `allow` bodies are a logical **OR** (any satisfied body → allow). These diverge only when rule match-sets overlap and an earlier one denies while a later one allows. Faithful parity therefore requires either disjoint match-sets (dec: the demo policy is authored disjoint, §3.1) or explicit deny-overrides/priority encoding in Rego. The §4 subsumption test asserts parity on the *specific disjoint fixture*, not general equivalence.
- **Extension (the S3 upgrade — context reasoning):**
  - `recipe.rpmSetpoint write` with `value > 1000` → allow **only if** `06 ≤ context.hour < 22` **and** `context.state == "Execute"`; otherwise deny (`"high-rpm restricted to day-shift & Execute"`).
  - `SafeHold` → allow **only if** `context.state == "Execute"` (deny if already Held / in Fault).
- The comparison the test makes explicit: for the S3 cases, `CommandAuthorizer` (context-blind) would allow (or has no rule) while `OpaCommandAuthorizer` correctly denies/allows on context — the concrete evidence that OPA expresses what the flat engine can't.

## 4. Proof (CI-parity with ①)
- **`OpaCommandAuthorizerTest`** (pure `mvn test`, no services, no `opa` binary) — loads `command_authz.wasm` via Chicory and asserts:
  1. **deny-by-default** — unmatched command → deny.
  2. **subsumption** — an in-bounds `rpmSetpoint` (e.g. 800) with any context → allow, matching `CommandAuthorizer` on the same request; an out-of-bounds value → deny.
  3. **extension (S3)** — `rpmSetpoint=1500` with `{hour:14,state:"Execute"}` → allow; `{hour:2,...}` → deny; `{hour:14,state:"Idle"}` → deny; `SafeHold` with `state:"Execute"` → allow, `state:"Held"` → deny.
  4. **context-blindness contrast (load-bearing)** — for a case (3) denies on context (e.g. `rpm=1500, hour=2`), **positively assert `CommandAuthorizer.authorize(...).allowed() == true`** for that same request (1500 is within the hand-rolled `max`, so it allows) — isolating the denial to OPA's context check alone. This is the concrete evidence of the capability gap (not merely "does not deny for that reason").
- **`command_authz_test.rego`** via `opa test` — the policy-native proof.
- **CI:** `governance-ci.yml` already runs `mvn test` → `OpaCommandAuthorizerTest` is auto-included; ② becomes "구현됨 · CI에서 검증" with the same rigor as ①.
- **`.wasm`↔`.rego` drift guard (REQUIRED, not optional):** because the standard `mvn test` job has no `opa` binary, nothing rebuilds the `.wasm` — a stale committed bundle would let CI stay green while the human-readable Rego diverges from what's enforced. Add a **separate CI job** (opa-installed) that runs `build-policy.sh` then `git diff --exit-code -- '*.wasm'`, failing if the committed bundle is stale. (Same shift-left rigor as `SchemaGate`/`CommandPolicyGate`.)
- **Docs:** update **both** `ADR-0011-command-authorization.md` and `ADR-0011-command-authorization.en.md` with an "OPA/Rego + in-JVM WASM evaluation" section; README ② row updated.

## 5. Blast radius
- **Added:** `acl` Rego policy (+ test) + committed `.wasm` + `build-policy.sh` + `OpaPolicy` (Chicory ABI) + `OpaCommandAuthorizer` + `OpaCommandAuthorizerTest` (+ optional demo). One new dependency: **Chicory** (`com.dylibso.chicory:runtime`, pure-JVM, test+main scope).
- **Unchanged:** `CommandAuthorizer`, `CommandPolicy`, `CommandPolicyGate`, `CommandAuthorizerTest`, `NcmdOpcUaBridge`, all other modules. No behavior change to existing consumers.
- **ep5 effect:** ② "부분 구현 · OPA 설계 제안" → "구현됨 · CI에서 검증"; coverage table ①②③ all-green (a separate blog doc-PR pins this).

## 6. Open questions
1. **Chicory + OPA WASM ABI version** — verify at plan time: the current Chicory release, the OPA WASM ABI version `opa build` emits, and whether a minimal community adapter exists vs implementing the ABI glue directly. The `.wasm` entrypoint (`-e acl/command_authz/decision`) and the `opa_eval` result-shape must be confirmed against the built bundle (javap/opa inspect). This is the main technical risk; a spike (build a trivial Rego → wasm → eval "hello" via Chicory) de-risks it before the real policy.
2. **`opa` binary in CI** — needed only to *regenerate* the `.wasm` (committed) and to run `opa test`. Decide: commit the `.wasm` + skip `opa` in the standard job (JVM test is the proof), and optionally add a separate `opa test` step. Leaning: commit `.wasm`, JVM test is authoritative, `opa test` optional.
3. **Package placement** — `acl` directly vs a new `acl.opa` sub-package. Leaning `acl.opa` to keep the WASM/OPA machinery isolated from the existing hand-rolled types.
4. **`Context` type** — a small Java record `Context(String state, int hour)` vs a generic map. Leaning a typed record for the adapter, serialized into the `input.context` JSON.
