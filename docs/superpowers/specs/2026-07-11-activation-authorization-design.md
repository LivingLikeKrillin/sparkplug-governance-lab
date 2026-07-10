# Activation Authorization — IAM authZ plane (T6) — Design

**Status:** approved for planning (2026-07-11)
**Repo:** `bifrost` (Java 17, Maven multi-module: core/gates/heimdall/sim), new branch `feat/t6-activation-authz` off `main` (spec/plan live in the lab repo `sparkplug-governance-lab` R&D journal, branch `feat/t6-activation-authz`; code lives in `bifrost`).
**Builds on:** T5 identity/signed-activation (`core.identity`; dual Ed25519 signatures per activation event; `registry/identity/authorized-keys.jsonl` trust anchor; Heimdall `REQUIRE_SIGNED_ACTIVATION` fail-closes on a broken/unsigned signed-ledger before binding). See `2026-07-11-identity-signed-activation-design.md`. Mirrors the existing NCMD command-authz pattern in `core.acl` (`CommandPolicy`/`CommandAuthorizer`, deny-by-default, first-match). The roadmap so far: T1 · T3 · T4 · T5, all merged; this is **T6** (T7 = the deferred dual-signed / externally-anchored head).

---

## 1. Problem

T5 made every activation **authentic**: the activator and approver cryptographically sign the event, and the edge verifies those signatures against a registry of authorized keys. But T5 was explicit (its §9) that it proves *authenticity, not authorization*:

> Signing proves *who* attested an activation (non-repudiation), not that they were *permitted* to — role/scope authorization is a future thread.

So today any two **registered** principals can activate **any** target/kind/ref. `alice` (a mixer operator) and `bob` can sign an activation of a *reactor* recipe on a *different line* and the system accepts it — their keys are registered, their signatures verify, four-eyes holds. Nothing asks *is alice permitted to activate this resource, and is bob permitted to approve it?*

**T6 adds the authorization plane**: a deny-by-default policy that says which authenticated principal may **activate** and which may **approve** which `(target, kind, ref)` — enforced at the pre-deploy gate *and* re-verified at the Heimdall edge (the same closed-loop discipline as T3/T4/T5). This completes the "Bifrost = IAM for the OT governance boundary" thesis: T5 = **authN**, T6 = **authZ**.

## 2. Scope decisions (agreed in brainstorming)

- **Thesis = authorize the activation act only.** T6 governs "who may activate/approve which `(target,kind,ref)`" — the T3–T5 activation-lifecycle surface. Authorizing the *other* gate acts (schema promote, spec/template admit, provenance publish) is a general authZ plane, deliberately **out of scope** (would dilute the thesis; §7). (Rejected: a general "who may perform any governed act" plane; and folding activation authZ into the existing NCMD `acl` — the command path — which would rebaseline mature code for no thesis gain.)
- **Model = direct principal→resource rules, deny-by-default, mirroring `core.acl`.** A rule binds a principal to an action on a resource; no matching rule ⇒ deny. This mirrors the shipped `CommandAuthorizer` (first-match, deny-by-default, `null`/`*` wildcard) so the codebase tells one story: *the same policy-as-code shape, now over a cryptographically authenticated subject.* (Rejected: RBAC roles — adds a role-binding + role-permission indirection, heavier thesis, breaks the acl precedent; ABAC/OPA attributes — the heaviest design, needs an attribute schema; the OPA-in-wasm path already exists for commands and stays a *command* concern.)
- **SoD × authZ = maker-checker (distinct `ACTIVATE` vs `APPROVE` permissions).** An activation is authorized iff the **activator** matches an `ACTIVATE` rule for the resource **and** the **approver** matches an `APPROVE` rule for the same resource. This pairs 1:1 with T5's dual signature — each signed role is independently authorized, and the requester's authority is genuinely distinct from the approver's (real separation of duties, not two copies of one permission). (Rejected: both need one `activate` permission — collapses maker-checker; approver-only — lets an unauthorized activator initiate.)
- **Enforcement = design-time gate + Heimdall edge re-verify (closed loop).** The activate gate refuses an unauthorized activation (never records it); Heimdall, at bind time in `REQUIRE_SIGNED_ACTIVATION` mode, re-checks the ledger event's activator/approver against the **current** policy and fails closed. Consistent with T3 (content hash) / T4 (chain) / T5 (signatures) re-verifying at the edge. Consequence (a feature): a principal whose permission is revoked *after* activation can no longer have their version bound at the next startup. (Rejected: design-time only — weaker than the roadmap's "governance governs what actually runs" thesis, and misses revocation-at-bind.)
- **authZ presupposes authN (T5).** Authorization is meaningful only over an *authenticated* subject — authorizing an unverified principal string is theater. So T6 enforcement rides the T5 signed path: the gate authorizes the key-authenticated activator/approver; the edge enforces authZ only in `REQUIRE_SIGNED_ACTIVATION` mode. The unsigned/legacy path carries **no** authZ (and no authN) — stated honestly, not hidden (§7).

## 3. Architecture

Additive. A small authZ unit in `core.activation` (it governs the activation act and consumes activation concepts), reading a policy file co-located with the T5 identity config under `registry/identity/`. The evaluator mirrors `core.acl.CommandAuthorizer` (first-match, deny-by-default, wildcard) so there is one authorization idiom in the codebase.

```
gates activate Line1 recipe mix-recipe 1.1.0 \
      --by alice --approved-by bob --by-key alice.key --approved-by-key bob.key   [T5 signed]
   └─ policy = ActivationPolicyStore.load(reg)                                     [T6, absent ⇒ deny-all]
   └─ ActivationService.activate(req, signer, policy)                             [T3/T4/T5 checks first]
        · four-eyes string SoD (alice≠bob)                 [T3]
        · signer preflight: key↔principal, crypto four-eyes [T5]
        · signer identity == named principals               [T5 followup]
        └─ authorizer.authorize(policy, alice, ACTIVATE, Line1,recipe,mix-recipe)  [T6] ─ deny ─► REFUSE activation.authz.denied
           authorizer.authorize(policy, bob,  APPROVE,  Line1,recipe,mix-recipe)  [T6] ─ deny ─► REFUSE (ledger untouched)
        └─ both allow ─► build event ─► ActivationLedger.append(e, signer)  [T5 signed line + head]

gates identity authorize <reg> alice activate Line1 recipe mix-recipe    [T6 audit plane]
   └─ ActivationAuthorizer over ActivationPolicyStore.load(reg)
        exit 0 allow (prints ruleId) / 1 deny (prints reason) / 2 usage

Heimdall (re)start, ACTIVATION_TARGET=Line1, REQUIRE_SIGNED_ACTIVATION=on   [T6 edge]
   └─ SignedLedgerVerifier.verify(Line1) intact?  ── broken ─► FAIL-CLOSED (T5)
        └─ active(Line1,recipe,mix-recipe) event e
             authorizer.authorize(e.activatedBy(), ACTIVATE, …)  ── deny ─► FAIL-CLOSED activation.edge.authz-denied
             authorizer.authorize(e.approvedBy(),  APPROVE,  …)  ── deny ─► FAIL-CLOSED (no bind)
        └─ both allow ─► contentSha256 recheck (T3) ─► bind
```

## 4. Components

### 4.1 `core.activation.ActivationAction` (enum)
`ACTIVATE`, `APPROVE`. Serialized lowercase in the policy JSON (`"activate"`/`"approve"`).

### 4.2 `core.activation.ActivationRule` (record)
`record ActivationRule(String id, String principal, ActivationAction action, String target, String kind, String ref)`.
- `principal` / `target` / `kind` / `ref`: a `null` or `"*"` field matches any value; otherwise exact match (identical to `acl` `fieldMatches`).
- `id`: rule identifier, surfaced in the allow decision (audit).

### 4.3 `core.activation.ActivationPolicy` (record)
`record ActivationPolicy(String version, List<ActivationRule> rules, @JsonProperty("default") String defaultEffect)` — `defaultEffect` maps to JSON `"default"` and must be `"deny"` (mirrors `CommandPolicy`). Deny-by-default is structural: an empty/absent rule list denies everything.

### 4.4 `core.activation.ActivationPolicyStore`
Loads `registry/identity/activation-policy.json`.
- `static ActivationPolicy load(Path registryRoot)` — reads `registryRoot/identity/activation-policy.json`; **absent file ⇒ deny-all** (a policy with no rules), fail-closed. Malformed JSON / a `default` other than `"deny"` ⇒ coded `IllegalStateException` (`activation.authz.policy.*`), fail-closed.
- Whole-file read per load (small policy, matches T4/T5 registry reads); not cached.

### 4.5 `core.activation.ActivationAuthorizer`
Mirrors `CommandAuthorizer` — first-match, deny-by-default, pure (no I/O).
- `AuthzDecision authorize(ActivationPolicy policy, String principal, ActivationAction action, String target, String kind, String ref)`.
- Walks `policy.rules()` in order; the first rule whose `principal` + `action` + `target` + `kind` + `ref` all match is the verdict (allow, with `ruleId`). No match ⇒ `deny("no-matching-rule (deny-by-default)")`.
- `AuthzDecision(boolean allowed, String ruleId, String reason)` with `allow(id)` / `deny(reason)` factories (mirrors `acl.Decision`; a parallel type, since `acl.Decision` lives in the command domain).

### 4.6 `core.activation.ActivationService` — authZ injection
The **canonical signed signature is `activate(ActivationRequest r, LedgerSigner signer, ActivationPolicy policy)`** (the service constructs one stateless `ActivationAuthorizer` and evaluates `policy`). The T3/T4/T5 checks run **first and unchanged**; the authZ check runs after signer preflight and identity-binding, **before** the ledger write:
- `authorize(policy, r.by(), ACTIVATE, target,kind,ref)` and `authorize(policy, r.approvedBy(), APPROVE, target,kind,ref)`; either deny ⇒ refuse `activation.authz.denied` (with the denied principal/action + reason), ledger untouched.
- **Overload discipline (explicit, to avoid a fail-open bypass):**
  - `activate(ActivationRequest r)` — the **unsigned/legacy T3 path**, unchanged: no signer, **no authZ** (authZ presupposes authN; §7). Delegates to the signed method with `signer=null`, and when `signer==null` the authZ block is skipped entirely.
  - The prior 2-arg `activate(r, signer)` (T5) is **removed** in favor of the 3-arg method — there is deliberately **no signed overload that skips authZ**, so a signed entry can never be written without an authZ decision. Every current signed caller migrates: the gate passes the loaded policy (§6); the existing T5 signed unit tests (`ActivationServiceSignedTest`, `ActivationLedgerSignedTest`) migrate to pass an explicit allow-all (or rule-specific) test policy — see §8.
- **Composition:** authZ is enforced exactly when a signer is present (the signed path). On the null-signer path there is no authenticated subject and thus no authZ — and such an entry cannot bind at a `REQUIRE_SIGNED_ACTIVATION` edge (§5), so gate-time authZ is not a hole: skipping authZ requires going unsigned, and unsigned can't reach a require-signed edge.

## 5. Heimdall edge enforcement
The authZ re-check runs in `loadConformance` **after** the active event is resolved (`ledger.active(target,"recipe",ref)`), NOT inside `assertLedgerTrustworthy` (which has only `ledgerDir/target/requireSigned` and runs before `active` is resolved). It is gated on `config.requireSignedActivation()` — so it only runs once `SignedLedgerVerifier` has already passed (authZ presupposes authN):
- `policy = ActivationPolicyStore.load(ledgerDir)` (the **current** policy — revocation-fresh); `authorize(policy, active.activatedBy(), ACTIVATE, target, "recipe", ref)` and `authorize(policy, active.approvedBy(), APPROVE, …)`; a deny throws `activation.edge.authz-denied: <principal> <action> <reason>` — fail-closed, no bind.
- **Audit line:** emit an `[BRIDGE] activation authz = ok (by <activatedBy>/<approvedBy>)` line **after** the authZ check passes (do NOT retro-label the earlier `activation trust = signed` line, which prints before authZ runs — that would over-claim).
- **No-regression precision:** with `REQUIRE_SIGNED_ACTIVATION` **off**, no edge authZ runs — so the T3/T4 gates (`run-activation-gate.sh`, `run-lineage-gate.sh`, which use *unsigned* activation + require-signed off) are genuinely unchanged. **The T5 `run-identity-gate.sh` IS affected** and must be updated (§8): its *gate-time* signed activations now hit deny-by-default authZ (which fires on any signed activation, independent of the edge flag), so it must seed an `activation-policy.json`; and its I7 edge case (require-signed on) now additionally exercises edge authZ. This is a required change to that gate, not a no-op.

## 6. Gate CLI (`gates` / `IdentityGate`)
- `identity authorize <reg> <principal> <activate|approve> <target> <kind> <ref>` — audit-plane check over `ActivationPolicyStore`; exit `0` allow (prints `ruleId`) / `1` deny (prints reason) / `2` usage. Placed under `identity` (the identity/authz plane), leaving `activation verify-chain` (T4) and `identity verify-signed` (T5) untouched.
- `activate` needs **no new flag**: **every signed activation is authZ-checked** (the gate loads `ActivationPolicyStore.load(reg)` and passes it to the 3-arg `activate`). An **absent** `registry/identity/activation-policy.json` loads as deny-all, so it **denies every signed activation** — introducing T6 to an existing registry requires authoring the policy first. This is the fail-closed default, called out in the gate script (§8) and §7. (Unsigned `activate(r)` is unaffected — no authN, no authZ.)

## 7. Honest limitations (do not oversell)
- **Direct principal grants only — no roles/hierarchy.** Every principal is named explicitly in a rule; many principals ⇒ many rules. RBAC (roles) and ABAC (attributes) are future threads. (The OPA-in-wasm path exists for *command* authz; activation authZ stays rule-based to keep the thesis tight.)
- **authZ presupposes T5 signing.** With `REQUIRE_SIGNED_ACTIVATION` off (and on the unsigned/legacy activate path), there is **no** authZ — because there is no authenticated subject to authorize. authZ-off and authN-off travel together. Not hidden: enabling T6 in production means running signed.
- **Policy is a plaintext registry file** (`activation-policy.json`), same trust seam as `authorized-keys.jsonl`: whoever can write the registry can rewrite the policy. Bootstrap/distribution/change-control of the policy are out-of-band (no policy-signing yet — a natural extension once T7's head/anchor work lands).
- **Revocation is bind/startup-fresh, not live.** The edge re-checks authZ at bind (startup). A version already bound in a *running* edge is not retroactively un-bound when a permission is revoked until the next restart — same freshness model as T5's key checks.
- **Deny-by-default onboarding cost.** An absent/empty policy denies every signed activation. Adopting T6 requires authoring the policy first — intentional (fail-closed), but a real operational step.
- **Scope is the activation act.** Other governed acts (schema/spec/template/provenance) are not authorized by T6 (§2).

## 8. Testing

### 8.1 Gate — `scripts/run-activation-authz-gate.sh` (controller-run)
Model on `run-identity-gate.sh` (reuse its keygen + `authorized-keys.jsonl` staging + fixture staging + broker harness). Seed an `activation-policy.json` granting `alice→ACTIVATE` and `bob→APPROVE` on `(Line1, recipe, mix-recipe)`. Deterministic, fresh registry per destructive case.

| Case | Scenario | Expect |
|---|---|---|
| AZ1 | signed activate by authorized alice(ACTIVATE)+bob(APPROVE) | admitted, exit 0 |
| AZ2 | activator `carol` lacks an ACTIVATE rule (approver ok) | REFUSED `activation.authz.denied`, exit 1, ledger untouched |
| AZ3 | approver `carol` lacks an APPROVE rule (activator ok) | REFUSED `activation.authz.denied`, exit 1 |
| AZ4 | absent/empty policy file → deny-all | REFUSED `activation.authz.denied`, exit 1 |
| AZ5 | `identity authorize` audit-plane CLI: allow case + deny case | exit 0 (prints ruleId) / exit 1 (prints reason) |
| AZ6 | edge: AZ1 activation, then remove alice's ACTIVATE rule, restart Heimdall with `REQUIRE_SIGNED_ACTIVATION=on` | fail-closed `activation.edge.authz-denied`, never binds |
| AZ7 | edge (positive): authorized signed ledger + policy, require-signed on | binds; `[BRIDGE] activation authz = ok` |

### 8.2 JUnit (unit layer)
- `ActivationPolicyTest` / `ActivationRuleTest` — wildcard (`null`/`*`) matching, action match.
- `ActivationPolicyStoreTest` — load; absent file → deny-all; malformed / `default != "deny"` → coded `IllegalStateException`.
- `ActivationAuthorizerTest` — first-match, deny-by-default, allow with ruleId; each `activation.authz.*` reason; ACTIVATE vs APPROVE isolation.
- `ActivationServiceSignedTest` / `ActivationLedgerSignedTest` (**migrated**) — pass an explicit allow-all/rule-specific policy to the 3-arg `activate`; add: activator-denied → refuse + ledger untouched; approver-denied → refuse; deny-all policy → refuse; T3 unsigned `activate(r)` still writes with no authZ.
- Heimdall `RequireSignedActivationTest` (extended) — edge authz-denied on a revoked principal; edge authz-ok on an authorized one.

### 8.3 No-regression (controller-run, must stay green)
- `run-activation-gate.sh` (A1–A5), `run-lineage-gate.sh` (LN1–LN4) — unsigned + require-signed off ⇒ **no authZ**, unchanged.
- **`run-identity-gate.sh` (I1–I7) — REQUIRED UPDATE:** add the `activation-policy.json` seeding (alice→ACTIVATE, bob→APPROVE on Line1/recipe/mix-recipe) to `stage_reg` and the I7 registry so its legitimate signed activations pass the new gate-time authZ. Without this, I1/I2/I5/I6/I7 break (their signed activations would be denied by the absent-policy deny-all). I3/I4a–c refusals are unaffected (they fail before the authZ check).
- `run-template-conformance-gate.sh`, `run-yggdrasil-full-loop-gate.sh` — no signed activation, unchanged.
- Full `mvn install` green.

## 9. Why this matters (portfolio framing)
Completes the identity story of **Bifrost-as-IAM**: T5 established *authN* (who cryptographically signed an activation), T6 adds *authZ* (whether that authenticated principal is permitted to activate/approve this resource), deny-by-default, with maker-checker separation that mirrors the dual signature, enforced both pre-deploy and at the runtime edge. It reuses the codebase's existing policy-as-code idiom (`acl`) over a now-cryptographic subject, so the two authorization domains — runtime commands and design-time activations — read as one governance model. It stops honestly short of roles/attributes and policy-signing, naming each as the next thread rather than overselling a full IAM.
