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
   └─ ActivationService.activate(req, signer, authorizer)                          [T3/T4/T5 checks first]
        · four-eyes string SoD (alice≠bob)                 [T3]
        · signer preflight: key↔principal, crypto four-eyes [T5]
        · signer identity == named principals               [T5 followup]
        └─ authorizer.authorize(alice, ACTIVATE, Line1,recipe,mix-recipe)  [T6] ── deny ─► REFUSE activation.authz.denied
           authorizer.authorize(bob,   APPROVE,  Line1,recipe,mix-recipe)  [T6] ── deny ─► REFUSE (ledger untouched)
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
`activate(ActivationRequest r, LedgerSigner signer, ActivationAuthorizer/Policy authz)`. The T3/T4/T5 checks run **first and unchanged**; the authZ check runs after signer preflight and identity-binding, **before** the ledger write:
- `activator ⊨ ACTIVATE (target,kind,ref)` and `approver ⊨ APPROVE (target,kind,ref)`, else refuse `activation.authz.denied` (with the denied principal/action + reason), ledger untouched.
- **Composition:** authZ is applied only on the signed path (a non-null signer). A null signer (legacy/unsigned) skips authZ — authZ over an unauthenticated principal is not enforced (§7). The existing `activate(r)` / `activate(r, signer)` overloads are preserved (delegate with a null/deny-all authz that is a no-op on the unsigned path).

## 5. Heimdall edge enforcement
In `NcmdOpcUaBridgeMain`, the `REQUIRE_SIGNED_ACTIVATION` bind path (after `SignedLedgerVerifier` passes) gains an authZ re-check over the **current** `ActivationPolicyStore.load(ledgerDir)`:
- `authorize(active.activatedBy(), ACTIVATE, target, "recipe", ref)` and `authorize(active.approvedBy(), APPROVE, …)`; a deny throws `activation.edge.authz-denied: <principal> <action> <reason>` — fail-closed, no bind.
- Only runs when `REQUIRE_SIGNED_ACTIVATION` is on (authZ presupposes authN). Default-off ⇒ no authZ, exactly as the T3/T4/T5 no-regression gates expect (no change to those gates).
- The `[BRIDGE] activation trust = signed` audit line is extended to note authZ was enforced (e.g. `signed+authz`).

## 6. Gate CLI (`gates` / `IdentityGate`)
- `identity authorize <reg> <principal> <activate|approve> <target> <kind> <ref>` — audit-plane check over `ActivationPolicyStore`; exit `0` allow (prints `ruleId`) / `1` deny (prints reason) / `2` usage. Placed under `identity` (the identity/authz plane), leaving `activation verify-chain` (T4) and `identity verify-signed` (T5) untouched.
- `activate` needs **no new flag**: when `registry/identity/activation-policy.json` exists and the activation is signed, authZ is enforced automatically (deny-by-default). (An absent policy file denies all signed activations — so introducing T6 to an existing registry requires authoring the policy; this is the fail-closed default and is called out in the gate script + §7.)

## 7. Honest limitations (do not oversell)
- **Direct principal grants only — no roles/hierarchy.** Every principal is named explicitly in a rule; many principals ⇒ many rules. RBAC (roles) and ABAC (attributes) are future threads. (The OPA-in-wasm path exists for *command* authz; activation authZ stays rule-based to keep the thesis tight.)
- **authZ presupposes T5 signing.** With `REQUIRE_SIGNED_ACTIVATION` off (and on the unsigned/legacy activate path), there is **no** authZ — because there is no authenticated subject to authorize. authZ-off and authN-off travel together. Not hidden: enabling T6 in production means running signed.
- **Policy is a plaintext registry file** (`activation-policy.json`), same trust seam as `authorized-keys.jsonl`: whoever can write the registry can rewrite the policy. Bootstrap/distribution/change-control of the policy are out-of-band (no policy-signing yet — a natural extension once T7's head/anchor work lands).
- **Revocation is bind/startup-fresh, not live.** The edge re-checks authZ at bind (startup). A version already bound in a *running* edge is not retroactively un-bound when a permission is revoked until the next restart — same freshness model as T5's key checks.
- **Deny-by-default onboarding cost.** An absent/empty policy denies every signed activation. Adopting T6 requires authoring the policy first — intentional (fail-closed), but a real operational step.
- **Scope is the activation act.** Other governed acts (schema/spec/template/provenance) are not authorized by T6 (§2).

## 8. Why this matters (portfolio framing)
Completes the identity story of **Bifrost-as-IAM**: T5 established *authN* (who cryptographically signed an activation), T6 adds *authZ* (whether that authenticated principal is permitted to activate/approve this resource), deny-by-default, with maker-checker separation that mirrors the dual signature, enforced both pre-deploy and at the runtime edge. It reuses the codebase's existing policy-as-code idiom (`acl`) over a now-cryptographic subject, so the two authorization domains — runtime commands and design-time activations — read as one governance model. It stops honestly short of roles/attributes and policy-signing, naming each as the next thread rather than overselling a full IAM.
