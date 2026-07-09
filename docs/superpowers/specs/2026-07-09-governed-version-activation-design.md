# Governed Version-Activation (T3) — Design

**Status:** approved for planning (2026-07-09)
**Repo:** `bifrost` (Java 17, Maven multi-module: core/gates/heimdall/sim), branch `feat/yggdrasil-spine`
**Grounding:** R1 deep-research verdict — the governance-distinct MINIMAL of "deployment" is the **audited version-ACTIVATION binding** (who activated which governed artifact version at which line/edge when) + **edge provenance/sync-verify** + **approval/effectivity as governed EVENTS**. The file-transfer / GitOps / registry-sync plumbing and autonomous mid-run changes are explicitly CUT (generic ops; safety).

---

## 1. Problem

Today "which governed artifact version is active at an edge" is **implicit**: a gate script sets `CONFORMANCE_PATH` / `REGISTRY_PATH` env vars, and Heimdall loads whatever those point at — the conformance policy's `equipmentRef@version`, and (recipe-mode) the dial's `activeRecipeRef@version`, which resolves the runtime recipe `MasterSpec` at `registry/spec/<ref>/<version>.json`. There is:

- **no tracked record** of "which version is active where right now",
- **no audited activation event** (who activated it, who approved it, when),
- **no rollback** and no reconstructable activation history,
- **no edge check** that the bytes the edge is about to run are the exact bytes that were approved.

The registry already versions artifacts and has a content-hash discipline (`RecipeManifest.contentSha256` + `provenance verify` recomputing sha256). What is missing is the **governance of the activation act itself** — turning "this version is now the one that runs at Line1" into a governed, approved, audited, reversible event, and making the runtime honor and content-verify it.

## 2. Scope decisions (agreed)

- **Q1 = A — close the loop.** The activation ledger is the single source of truth for "what runs"; Heimdall reads the active pointer at startup and binds to it. Proven by a gate where activating a new version changes which recipe setpoint the edge admits. (Rejected: control-plane-only ledger the runtime ignores — audit divorced from enforcement is unconvincing.)
- **Q2 = A — generic ledger, recipe runtime proof.** The ledger data model is generic over `kind ∈ {equipment, recipe, conformance-policy}` + ref + version + target; the end-to-end runtime binding is demonstrated through **recipe** — i.e. the runtime `MasterSpec` at `spec/<ref>/<version>.json` that Heimdall's recipe-mode conformance binds (the ISA-88 recipe-download analog). Equipment/policy activation ride the same ledger but their runtime flip is not separately demonstrated.
- **Q3 = A — SoD recorded + content-hash-bound; crypto deferred to T5.** Activation requires two distinct string principals (`activatedBy` ≠ `approvedBy`, four-eyes) recorded in the event, and the event captures the **sha256 of the exact runtime bytes being activated** (approval attests to those bytes). The edge recomputes that hash at bind time and refuses on mismatch — the "edge provenance/sync-verify" of the R1 verdict. Cryptographically-signed approval is out of scope (belongs with the T5 OIDC/identity track).

### 2.1 The store the activation governs (resolves a review finding)

There are two distinct recipe-shaped stores in the codebase, and they must not be conflated:
- **`RecipeDefinitionStore`** at `registry/recipe/<ref>/<version>/{recipe-setpoints.yaml, manifest.json}` — git-anchored, `contentSha256`-sealed, exercised by `provenance publish/verify`. **NOT what Heimdall binds at runtime.**
- **`MasterSpecStore`** at `registry/spec/<ref>/<version>.json` → a `MasterSpec` (structured `setpoints`) — **this is what Heimdall recipe-mode actually loads** (`NcmdOpcUaBridgeMain.loadConformance` → `MasterSpecStore.load(registryDir, dial.activeRecipeRef(), dial.activeRecipeVersion())`). It has no manifest and no independent sha256.

**T3 governs the runtime artifact — the `MasterSpec` in `spec/`.** Because that artifact has no independent seal, T3 seals it **at the activation event**: the activation captures the sha256 of the exact `spec/<ref>/<version>.json` bytes, and the edge verifies against that captured hash at bind time. This is an **activation-event-anchored** seal (the four-eyes approval attests to the bytes), deliberately distinct from the git-anchored `recipe/` provenance-publish. Unifying the two seals (git-anchoring the `spec/` artifacts too) is future work, disclosed in §9.

## 3. Architecture

A new `core.activation` package owns the governed event + ledger + service (pure, testable); a `gates` CLI leg drives it; Heimdall consumes and content-verifies the active pointer at the runtime boundary.

```
recipe MasterSpec registered at  registry/spec/mix-recipe/1.1.0.json   (structured setpoints)          [EXISTING store]
gates activate Line1 recipe mix-recipe 1.1.0 --by alice --approved-by bob
   └─ ActivationService: resolve+hash spec bytes (sha256) + SoD(alice≠bob) ──► append ActivationEvent   [NEW]
        └─ registry/activation/Line1.jsonl   (append-only; last event per (kind,ref) = active pointer;
                                              event carries contentSha256 of the approved bytes)         [NEW]
Heimdall (re)start, ACTIVATION_TARGET=Line1
   └─ ActivationLedger.active(Line1, recipe, mix-recipe) ──► version 1.1.0
   └─ MasterSpecStore.load(spec, mix-recipe, 1.1.0) ; recompute sha256 == event.contentSha256 ?
        ├─ match  ──► bind recipe 1.1.0 → ② recipe-mode conformance admits 1.1.0's setpoints          [NEW binding]
        └─ mismatch/absent ──► FAIL-CLOSED, refuse to bind                                              [NEW edge provenance]
audit
   └─ gates activation-log Line1  ──►  who activated what when, approvals, rollbacks, content hashes     [NEW]
```

## 4. Components

### 4.1 `core.activation.ActivationEvent` (record, immutable)
```
target        String   e.g. "Line1"   (the edge/line the activation binds to)
kind          String   "equipment" | "recipe" | "conformance-policy"
ref           String   artifact ref = the dial's activeRecipeRef, e.g. "mix-recipe"
version       String   SemVer being activated, e.g. "1.1.0"
contentSha256 String   sha256 of the exact runtime bytes activated (spec/<ref>/<version>.json)
activatedBy   String   principal that requested activation
approvedBy    String   distinct principal that approved (four-eyes)
activatedAt   long     epoch millis (injected clock)
priorVersion  String   the version active immediately before this event (null if first)
action        String   "ACTIVATE" | "ROLLBACK"
```
Jackson-serializable (one JSONL line). `action=ROLLBACK` reactivates a version that appears earlier in history — recorded distinctly so the audit trail reads as a reversal.

### 4.2 `core.activation.ActivationLedger`
Reads/appends events at `registry/activation/<target>.jsonl` (one JSON object per line, append-only).
- `void append(ActivationEvent e)` — append a single line (create file/dirs if absent).
- `Optional<ActivationEvent> active(String target, String kind, String ref)` — the **last** event matching `(kind,ref)` = the current active pointer (empty if never activated).
- `List<ActivationEvent> history(String target)` — the full ordered audit trail for the target.

Append-only JSONL, single control-plane writer (no concurrent-writer coordination — out of scope). Cross-generation immutable sealing (hash-chain/WORM) is deferred to T4.

### 4.3 `core.activation.ArtifactResolver` (port) + recipe adapter
A small interface so `ActivationService` stays decoupled from any one artifact store:
```
interface ArtifactResolver { Optional<ResolvedArtifact> resolve(String kind, String ref, String version); }
record ResolvedArtifact(java.nio.file.Path path, String sha256) {}
```
Empty result ⇒ the version is unresolvable (absent or invalid) ⇒ activation refuses.

**`RecipeArtifactResolver`** (the demonstrated kind): resolves `registry/spec/<ref>/<version>.json`; refuses (empty) if the file is absent OR does not parse as a `MasterSpec` (validity gate — reuse `MasterSpecStore.load` to confirm it deserializes); on success computes sha256 over the file bytes and returns `ResolvedArtifact(path, sha256)`. (equipment/policy resolvers are future; recipe is the demonstrated kind.)

### 4.4 `core.activation.ActivationService`
Constructed with an `ArtifactResolver`, an `ActivationLedger`, and a `java.time.Clock` (deterministic tests). One method:
```
ActivationVerdict activate(ActivationRequest req)   // target,kind,ref,version,by,approvedBy,rollback
```
Governance logic, fail-closed, in order:
1. **Artifact:** `resolve(kind,ref,version)` → empty ⇒ refuse (`activation.artifact.unresolved`).
2. **SoD:** `approvedBy` blank ⇒ refuse (`activation.approval.missing`); `approvedBy == by` ⇒ refuse (`activation.approval.self`).
3. **Rollback guard:** if `rollback`, the target version MUST already appear in this target's history (`activation.rollback.unknown-version` otherwise).
4. Compute `priorVersion` from `ledger.active(target,kind,ref)`.
5. Build the event (`action = rollback ? ROLLBACK : ACTIVATE`, `contentSha256 =` the resolved sha, `activatedAt =` clock) and `append`.
6. Return an ok `ActivationVerdict` carrying the appended event.

`ActivationVerdict(boolean ok, ActivationEvent event, List<Violation> violations)` reusing `core.schema.Violation`. On refusal `event` is null and the ledger is NOT appended.

### 4.5 `gates` CLI legs (in `GatesCli` + a new `ActivateGate`)
- `gates activate <registryDir> <target> <kind> <ref> <version> --by <p> --approved-by <p> [--rollback]`
  → runs `ActivationService.activate`; prints `[GATE] activated target=… kind=… ref=… version=… by=… approvedBy=… sha256=…`; exit **0** ok / **1** refused (prints the violation rules) / **2** usage/error.
- `gates active <registryDir> <target> <kind> <ref>` → prints the current active version + sha256, or `none`; exit 0.
- `gates activation-log <registryDir> <target>` → prints the ordered history (audit trail); exit 0.

`GatesCli` usage string extends to include `activate|active|activation-log`. The `ref` argument addresses the **runtime `spec/` store** (= the dial's `activeRecipeRef`).

### 4.6 Heimdall runtime binding + edge provenance (closing the loop)
`NcmdOpcUaBridgeMain` gains two env vars, mirroring the existing nullable `CONFORMANCE_PATH` back-compat pattern:
- `ACTIVATION_PATH` — registry dir holding the ledger (default: reuse `REGISTRY_PATH`).
- `ACTIVATION_TARGET` — the edge/line, e.g. `Line1`.

When `ACTIVATION_TARGET` is set, `loadConformance` resolves the **active recipe version** from the ledger instead of the dial's `activeRecipeVersion()`:
1. `ActivationLedger.active(target, "recipe", cp.dial().activeRecipeRef())` → the active `ActivationEvent` (fail-closed refuse if absent: `activation.edge.no-active-pointer`).
2. Locate `spec/<activeRecipeRef>/<activeEvent.version()>.json` (fail-closed refuse if the file is absent: `activation.edge.artifact-missing`).
3. **Edge provenance FIRST (verify-then-trust):** read the raw file bytes and require `sha256(bytes) == activeEvent.contentSha256()`; on mismatch fail-closed (`activation.edge.content-mismatch`) — the bytes on the edge are not the bytes that were approved. This byte-level check runs **before** JSON parsing, so even a validity-breaking one-byte tamper surfaces deterministically as `content-mismatch` (not a parse error).
4. Only after the hash matches, parse those verified bytes into the `MasterSpec` (`MasterSpecStore.load`) and bind it into the `Conformance` used by ② recipe-mode.

When `ACTIVATION_TARGET` is unset → unchanged legacy behavior (the dial's `activeRecipeVersion()` is used; no regression). The `ref` still comes from the dial (`activeRecipeRef`); only the **version** now comes from the ledger.

- **Startup-only:** the active pointer is read at (re)start, never mid-run — consistent with R1 (no autonomous mid-run change; lot/cycle-boundary cutover; operator-in-loop). A version flip takes effect at the next edge restart / cycle boundary.
- **Fail-closed everywhere:** absent pointer, absent spec file, or content mismatch each refuse to bind — an edge configured for governed activation never silently falls back to the dial or runs unverified bytes.

## 5. The killer gate — `scripts/run-activation-gate.sh`

Mirrors existing gate scripts (`cygpath -m` path discipline). The runtime assertion (A4) uses a **recipe-mode** harness modeled on `run-composable-conformance-gate.sh`'s C4 (a `mode:"recipe"` conformance policy + `spec/<ref>/<version>.json` MasterSpecs), NOT the full-loop gate (whose dial is `mode:"envelope"`, `activeRecipeRef:null` — recipe-mode is off there, so it cannot demonstrate recipe activation). Two MasterSpec versions with **different** setpoints are registered in `spec/`. The recipe-mode policy pins `recipeTolerance: 0` (as composable-C4 does) so the admissibility assertions are unambiguous (`Rpm=1600` vs a `1500` setpoint deviates for any tol < ~0.067; `0` is the clean choice).

Assertions:
- **A1 activate + audit:** register `spec/mix-recipe/1.0.0.json`; `activate Line1 recipe mix-recipe 1.0.0 --by alice --approved-by bob` → exit 0; `active Line1 recipe mix-recipe` → `1.0.0` + sha256; `activation-log Line1` shows the ACTIVATE event with both principals and the content hash.
- **A2 SoD refuse:** `--by alice --approved-by alice` → exit 1 (`activation.approval.self`); missing `--approved-by` → exit 1 (`activation.approval.missing`); ledger unchanged.
- **A3a activate-time refuse:** `activate … mix-recipe 9.9.9` (unregistered version) → exit 1 (`activation.artifact.unresolved`); ledger unchanged.
- **A3b edge provenance:** with `1.0.0` active, tamper `spec/mix-recipe/1.0.0.json` on the edge (one byte), start Heimdall with `ACTIVATION_TARGET=Line1` → Heimdall fails-closed to bind (`activation.edge.content-mismatch`) — the running bytes ≠ the approved bytes.
- **A4 runtime binding — the closed loop (recipe admissibility):** with `mix-recipe@1.0.0` active (setpoint `Rpm=1500`) and `ACTIVATION_TARGET=Line1`: an authorized NCMD `Rpm=1500` → **APPLY**, and `Rpm=1600` → **DENY** (`conformance.recipe.deviation`). Then register `spec/mix-recipe/1.1.0.json` (setpoint `Rpm=1600`), `activate … 1.1.0 --by alice --approved-by bob`, restart Heimdall → now `Rpm=1600` → **APPLY** and `Rpm=1500` → **DENY**. The active version changes which setpoint the edge admits — activation governs what runs. (Recipe-mode is an admissibility gate: the recipe does not supply the write value; it constrains which NCMD value is accepted.)
- **A5 rollback:** `activate Line1 recipe mix-recipe 1.0.0 --rollback --by alice --approved-by bob` → `active` back to `1.0.0`; `activation-log` reads `ACTIVATE(1.0.0) → ACTIVATE(1.1.0) → ROLLBACK(1.0.0)` — full audit trail + reversibility; a restarted Heimdall again admits `Rpm=1500`.

## 6. Store touch-points the plan must confirm first

- **`MasterSpecStore.load(registryDir, ref, version)` already resolves a specific version** (`spec/<ref>/<version>.json`) — no new resolution API is needed for the runtime bind. What T3 needs additionally is **content-hash access to those exact bytes** for the activation seal and the edge check. The plan's first task: add a small helper (either `MasterSpecStore.path(registryDir, ref, version)` returning the resolved `Path`, or compute the path + read bytes directly inside `RecipeArtifactResolver` and the Heimdall edge check). No behavior change to existing `load`.
- **`RecipeDefinitionStore` (`recipe/`) is NOT the runtime store** and is not on T3's binding path; the earlier draft's claim that adding `RecipeDefinitionStore.resolve(ref,version)` enables the runtime bind was wrong and is removed.

## 7. Error handling / fail-closed summary

- Unresolvable/invalid version at activate time → refused, ledger untouched.
- Missing or self-approval → refused.
- Rollback to a version never activated on this target → refused.
- Heimdall with `ACTIVATION_TARGET` set: absent active pointer, absent spec file, or sha256 mismatch → each refuses to bind (no silent dial fallback, no unverified bytes).
- Single control-plane writer assumed; concurrent activation coordination is out of scope.

## 8. Testing

- **core:** `ActivationEvent` JSON round-trip; `ActivationLedger` append/active/history (last-wins active pointer, multi-artifact `(kind,ref)` separation); `RecipeArtifactResolver` resolve+hash + refuse on absent/invalid; `ActivationService` — artifact refuse, SoD refuse (missing + self), rollback-unknown refuse, `priorVersion` computation, happy-path append. Injected `Clock` for deterministic `activatedAt`.
- **gates:** `ActivateGate` exit codes + output for activate/active/activation-log; unknown/usage → 2.
- **heimdall:** `loadConformance` honors `ACTIVATION_TARGET` → binds the ledger's active version; fail-closed on absent pointer / absent spec / content mismatch; unset → legacy dial behavior.
- **gate script:** A1–A5.
- **controller-direct verification (the #1 rule):** the controller personally runs `mvn install` (BUILD SUCCESS), `run-activation-gate.sh` (A1–A5 PASS), and no-regression on `run-spec-gate`, `run-ncmd-runtime-gate`, `run-template-conformance-gate`, `run-yggdrasil-spine-gate`, `run-yggdrasil-full-loop-gate`, `run-composable-conformance-gate`.

## 9. Honest limitations (do not oversell)

- **Activation effects at edge (re)start / cycle boundary, not mid-run** — deliberate (R1: no autonomous mid-run change; operator-in-loop). Not a hot-swap.
- **Approval = recorded four-eyes SoD (two string principals), not cryptographically signed** — honest RBAC-seam; signed approval = T5 identity.
- **The runtime `MasterSpec` (`spec/`) is sealed at the activation event, not by an independent git-anchored manifest** like the `recipe/` store. The seal is the four-eyes-attested `contentSha256` captured at activation and re-checked at the edge — enough for edge tamper/drift detection, but the approver attests the bytes rather than a pre-existing signed manifest. Unifying `spec/` under the git-anchored provenance-publish seal is future work.
- **File-transfer/sync remains the gate script staging files** — T3 governs the activation EVENT + active pointer + edge content-verify, NOT the transport (R1 CUT: don't reinvent GitOps/file-transfer).
- **Effectivity = immediate + append-only history** — no future-dated/scheduled effectivity ("what was active at time T" is reconstructed from the log). Scheduled effectivity is speculative, cut.
- **Ledger is append-only JSONL, not itself cross-generationally immutable-sealed** — hash-chain/WORM hardening = T4 record-of-record.
- **Single control-plane writer** — no distributed activation consensus.

## 10. What T3 unblocks

Establishes the audited activation seam the roadmap builds on: **T4** (lineage/record-of-record — hash-chain/seal the activation history cross-generationally; unify the `spec/` seal with git-anchored provenance) and **T5** (identity — replace recorded string principals with authenticated individuals + cryptographically-signed approval). Neither is in T3 scope.
