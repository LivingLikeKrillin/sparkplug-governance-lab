# Governed Version-Activation (T3) — Design

**Status:** approved for planning (2026-07-09)
**Repo:** `bifrost` (Java 17, Maven multi-module: core/gates/heimdall/sim), branch `feat/yggdrasil-spine`
**Grounding:** R1 deep-research verdict — the governance-distinct MINIMAL of "deployment" is the **audited version-ACTIVATION binding** (who activated which governed artifact version at which line/edge when) + **edge provenance/sync-verify** + **approval/effectivity as governed EVENTS**. The file-transfer / GitOps / registry-sync plumbing and autonomous mid-run changes are explicitly CUT (generic ops; safety).

---

## 1. Problem

Today "which governed artifact version is active at an edge" is **implicit**: a gate script sets `CONFORMANCE_PATH` / `REGISTRY_PATH` env vars, and Heimdall loads whatever file those point at (the conformance policy's `equipmentRef@version`, the dial's `activeRecipeRef@version`). There is:

- **no tracked record** of "which version is active where right now",
- **no audited activation event** (who activated it, who approved it, when),
- **no rollback** and no reconstructable activation history.

The registry already versions + provenance-seals artifacts (`RecipeManifest{ref,version,defRef(git SHA),contentSha256,...}` + `provenance verify` recomputing sha256). What is missing is the **governance of the activation act itself** — turning "this version is now the one that runs at Line1" into a governed, approved, audited, reversible event, and making the runtime honor it.

## 2. Scope decisions (agreed)

- **Q1 = A — close the loop.** The activation ledger is the single source of truth for "what runs"; Heimdall reads the active pointer at startup and binds to it. Proven by a gate where activating a new version changes what Heimdall actually applies. (Rejected: control-plane-only ledger that the runtime ignores — audit divorced from enforcement is unconvincing.)
- **Q2 = A — generic ledger, recipe runtime proof.** The ledger data model is generic over `kind ∈ {equipment, recipe, conformance-policy}` + ref + version + target; the end-to-end runtime binding is demonstrated through **recipe** (the ISA-88 recipe-download analog; the artifact the full-loop gate already exercises via `Recipe/Rpm`). Equipment/policy activation ride the same ledger but their runtime flip is not separately demonstrated.
- **Q3 = A — SoD recorded + provenance-bound; crypto deferred to T5.** Activation requires two distinct string principals (`activatedBy` ≠ `approvedBy`, four-eyes) recorded in the event, and only bytes that provenance-verify (sha256 vs manifest) may be activated. Cryptographically-signed approval is out of scope (belongs with the T5 OIDC/identity track).

## 3. Architecture

Ports-and-adapters consistent with the existing codebase. A new `core.activation` package owns the governed event + ledger + service (pure, testable); a `gates` CLI leg drives it; Heimdall consumes the active pointer at the runtime boundary.

```
author commits recipe v1.1.0
   └─ provenance publish  ──►  RecipeDefinitionStore  (registry/recipe/<ref>/<version>/, sha256-sealed)   [EXISTING]
gates activate Line1 recipe mix-recipe 1.1.0 --by alice --approved-by bob
   └─ ActivationService: provenance-verify(sha256) + SoD(alice≠bob) ──► append ActivationEvent          [NEW]
        └─ registry/activation/Line1.jsonl   (append-only; last event per (kind,ref) = active pointer)  [NEW]
Heimdall (re)start, ACTIVATION_TARGET=Line1
   └─ ActivationLedger.active(Line1, recipe, mix-recipe) ──► version 1.1.0 ──► bind + ② conformance      [NEW binding]
audit
   └─ gates activation-log Line1  ──►  who activated what when, approvals, rollbacks                     [NEW]
```

## 4. Components

### 4.1 `core.activation.ActivationEvent` (record, immutable)
```
target        String   e.g. "Line1"   (the edge/line the activation binds to)
kind          String   "equipment" | "recipe" | "conformance-policy"
ref           String   artifact ref, e.g. "mix-recipe"
version       String   SemVer being activated, e.g. "1.1.0"
contentSha256 String   the provenance-verified sha256 of the activated bytes (edge provenance)
activatedBy   String   principal that requested activation
approvedBy    String   distinct principal that approved (four-eyes)
activatedAt   long     epoch millis
priorVersion  String   the version active immediately before this event (null if first)
action        String   "ACTIVATE" | "ROLLBACK"
```
Jackson-serializable (JSONL line). `action=ROLLBACK` reactivates a version that appears earlier in history — semantically an activation whose target version is a prior one; recorded distinctly so the audit trail reads as a reversal, not a fresh forward step.

### 4.2 `core.activation.ActivationLedger`
Reads/appends events at `registry/activation/<target>.jsonl` (one JSON object per line, append-only).
- `void append(ActivationEvent e)` — append a single line (create file/dirs if absent).
- `Optional<ActivationEvent> active(String target, String kind, String ref)` — the **last** event matching `(kind,ref)` = the current active pointer (null/empty if never activated).
- `List<ActivationEvent> history(String target)` — the full ordered audit trail for the target.

Append-only JSONL, single control-plane writer (no concurrent-writer coordination — out of scope). The ledger is the audit record; cross-generation immutable sealing (hash-chain/WORM) is deferred to T4.

### 4.3 `core.activation.ProvenanceVerifier` (port) + recipe adapter
A small interface so `ActivationService` stays decoupled from any one artifact store:
```
interface ProvenanceVerifier { Optional<String> verify(String kind, String ref, String version); }
   // returns the verified sha256, or empty if unresolvable / tampered
```
The recipe implementation resolves `RecipeDefinitionStore.resolve(ref, version)` (see §6), recomputes sha256 over the canonical bytes, compares to `manifest.contentSha256()`, and returns the sha on match. (equipment/policy verifiers are future; recipe is the demonstrated kind.)

### 4.4 `core.activation.ActivationService`
Constructed with a `ProvenanceVerifier` + an `ActivationLedger`. One method:
```
ActivationVerdict activate(ActivationRequest req)   // req: target,kind,ref,version,by,approvedBy,rollback
```
Governance logic, fail-closed, in order:
1. **Provenance:** `verify(kind,ref,version)` → empty ⇒ refuse (`activation.provenance.unverified`).
2. **SoD:** `approvedBy` blank ⇒ refuse (`activation.approval.missing`); `approvedBy == by` ⇒ refuse (`activation.approval.self`).
3. **Rollback guard:** if `rollback`, the target version MUST already appear in this target's history (`activation.rollback.unknown-version` otherwise).
4. Compute `priorVersion` from `ledger.active(target,kind,ref)`.
5. Build the event (`action = rollback ? ROLLBACK : ACTIVATE`, `contentSha256` = the verified sha, `activatedAt` from an injected clock) and `append`.
6. Return an ok `ActivationVerdict` carrying the appended event.

`ActivationVerdict(boolean ok, ActivationEvent event, List<Violation> violations)` reusing `core.schema.Violation`.

### 4.5 `gates` CLI legs (in `GatesCli` + a new `ActivateGate`)
- `gates activate <registryDir> <target> <kind> <ref> <version> --by <p> --approved-by <p> [--rollback]`
  → runs `ActivationService.activate`; prints `[GATE] activated target=… kind=… ref=… version=… by=… approvedBy=… sha256=…`; exit **0** ok / **1** refused (with the violation rules) / **2** usage/error.
- `gates active <registryDir> <target> <kind> <ref>` → prints the current active version + sha256, or `none`; exit 0.
- `gates activation-log <registryDir> <target>` → prints the ordered history (audit trail); exit 0.

`GatesCli` usage string extends to include `activate|active|activation-log`.

### 4.6 Heimdall runtime binding (closing the loop)
`NcmdOpcUaBridgeMain` gains two env vars, mirroring the existing nullable `CONFORMANCE_PATH` back-compat pattern:
- `ACTIVATION_PATH` — registry dir holding the ledger (default: reuse `REGISTRY_PATH`).
- `ACTIVATION_TARGET` — the edge/line, e.g. `Line1`.

When `ACTIVATION_TARGET` is set, Heimdall resolves the **active recipe version** via `ActivationLedger.active(target,"recipe",ref)` and binds that version (instead of the dial's implicit `activeRecipeRef@version`). The `ref` comes from the conformance policy's dial (`activeRecipeRef`); the **version** now comes from the ledger. When `ACTIVATION_TARGET` is unset → unchanged legacy dial behavior (no regression).

- **Startup-only:** Heimdall reads the active pointer at (re)start, never mid-run — consistent with R1 (no autonomous mid-run change; lot/cycle-boundary cutover; operator-in-loop). A version flip takes effect at the next edge restart / cycle boundary.
- **Fail-closed:** if `ACTIVATION_TARGET` is set but the ledger has **no** active pointer for the recipe, Heimdall refuses to bind (does NOT silently fall back to the dial). An edge configured for governed activation must have a governed active version.

## 5. The killer gate — `scripts/run-activation-gate.sh`

Mirrors existing gate scripts (pure CLI where possible; `cygpath -m` path discipline; reuses the full-loop harness for the runtime assertion). Assertions:

- **A1 activate + audit:** publish + `activate Line1 recipe mix-recipe 1.0.0 --by alice --approved-by bob` → exit 0; `active Line1 recipe mix-recipe` → `1.0.0` + sha256; `activation-log Line1` shows the ACTIVATE event with both principals.
- **A2 SoD refuse:** `--by alice --approved-by alice` → exit 1 (`activation.approval.self`); missing `--approved-by` → exit 1 (`activation.approval.missing`).
- **A3 provenance refuse:** activate a version whose canonical bytes were tampered (or an unpublished version) → exit 1 (`activation.provenance.unverified`); the ledger is NOT appended.
- **A4 runtime binding (the closed loop):** using the full-loop sim+broker+Heimdall harness with `ACTIVATION_TARGET=Line1` — with `mix-recipe@1.0.0` active, an authorized NCMD applies the **1.0.0** setpoint (observed on the instance PV). Then publish + `activate mix-recipe 1.1.0` (a different Rpm), restart Heimdall → the **same** authorized NCMD now applies the **1.1.0** setpoint. Proves the activation pointer governs what actually runs.
- **A5 rollback:** `activate Line1 recipe mix-recipe 1.0.0 --rollback --by alice --approved-by bob` → `active` back to `1.0.0`; `activation-log` reads `ACTIVATE(1.0.0) → ACTIVATE(1.1.0) → ROLLBACK(1.0.0)` — full audit trail + reversibility.

## 6. Known extension the plan must confirm first

`RecipeDefinitionStore` already stores versions addressably on disk (`registry/recipe/<ref>/<version>/{recipe-setpoints.yaml, manifest.json}`, versions IMMUTABLE) but its public API exposes only `latest(ref)`. **The plan's first task must add `Optional<Resolved> resolve(String ref, String version)`** (reusing the existing `resolveUnchecked(Path vdir)` helper on `recipe/<ref>/<version>`) — required so activation can provenance-verify and bind a *specific* version, and so A4 can hold two versions simultaneously. This is a small additive method, no behavior change to `latest`/`publish`.

## 7. Error handling / fail-closed summary

- Unresolvable or tampered version → activation refused, ledger untouched.
- Missing or self-approval → refused.
- Rollback to a version never activated on this target → refused.
- Heimdall with `ACTIVATION_TARGET` set but no active pointer → refuses to bind (no silent dial fallback).
- Single control-plane writer assumed; concurrent activation coordination is out of scope.

## 8. Testing

- **core:** `ActivationEvent` JSON round-trip; `ActivationLedger` append/active/history (incl. last-wins active pointer, multi-artifact separation); `ActivationService` — provenance refuse, SoD refuse (missing + self), rollback-unknown refuse, `priorVersion` computation, happy-path append. Injected clock for deterministic `activatedAt`.
- **gates:** `ActivateGate` exit codes + output for activate/active/activation-log; unknown/usage → 2.
- **heimdall:** `resolve()` honors `ACTIVATION_TARGET` → active version; fail-closed on missing pointer; unset → legacy behavior.
- **gate script:** A1–A5.
- **controller-direct verification (the #1 rule):** the controller personally runs `mvn install` (BUILD SUCCESS), `run-activation-gate.sh` (A1–A5 PASS), and no-regression on `run-spec-gate`, `run-ncmd-runtime-gate`, `run-template-conformance-gate`, `run-yggdrasil-spine-gate`, `run-yggdrasil-full-loop-gate`, `run-composable-conformance-gate`.

## 9. Honest limitations (do not oversell)

- **Activation effects at edge (re)start / cycle boundary, not mid-run** — deliberate (R1: no autonomous mid-run change; operator-in-loop). Not a hot-swap.
- **Approval = recorded four-eyes SoD (two string principals), not cryptographically signed** — honest RBAC-seam; signed approval = T5 identity.
- **File-transfer/sync remains the gate script copying files** — T3 governs the activation EVENT + active pointer + edge provenance, NOT the transport (R1 CUT: don't reinvent GitOps/file-transfer).
- **Effectivity = immediate + append-only history** — no future-dated/scheduled effectivity ("what was active at time T" is reconstructed from the log). Scheduled effectivity is speculative, cut.
- **Ledger is append-only JSONL, not itself cross-generationally immutable-sealed** — hash-chain/WORM hardening = T4 record-of-record.
- **Single control-plane writer** — no distributed activation consensus.

## 10. What T3 unblocks

Establishes the audited activation seam the roadmap builds on: **T4** (lineage/record-of-record — seal the activation history cross-generationally) and **T5** (identity — replace recorded string principals with authenticated individuals + cryptographically-signed approval). Neither is in T3 scope.
