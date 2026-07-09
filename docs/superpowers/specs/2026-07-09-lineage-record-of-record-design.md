# Tamper-Evident Activation Ledger — Lineage / Record-of-Record (T4) — Design

**Status:** approved for planning (2026-07-09)
**Repo:** `bifrost` (Java 17, Maven multi-module: core/gates/heimdall/sim), new branch `feat/t4-lineage` off `main` (spec/plan live in the lab repo `sparkplug-governance-lab` R&D journal, branch `feat/t4-lineage`; code lives in `bifrost`).
**Builds on:** T3 governed version-activation (`core.activation` package; `registry/activation/<target>.jsonl` append-only ledger; Heimdall binds the ledger's active version at startup with a verify-then-trust `contentSha256` edge check). See `2026-07-09-governed-version-activation-design.md`.

---

## 1. Problem

T3 made "which governed recipe version is active at an edge" a governed EVENT: four-eyes-approved (SoD), content-sealed (`contentSha256` of the exact runtime bytes), audited (append-only JSONL ledger at `registry/activation/<target>.jsonl`), reversible (guarded rollback) — and Heimdall binds the ledger's active pointer at startup, content-verifying the bytes.

But the **audit trail itself is not tamper-evident.** `ActivationLedger` appends one JSON line per event; the "active pointer" is simply the *last* line matching `(kind, ref)`. Each event seals the *artifact bytes it activated* (`contentSha256`), but **no event commits to the prior ledger history.** So the record of *what happened* — who activated/approved which version when, and the ordered sequence of activations and rollbacks — can be silently rewritten:

- **edit** a past event's `approvedBy`/`version`/`contentSha256` → no trace;
- **delete** a middle event (make an approval disappear) → no trace;
- **reorder** events (make a rollback look like it never happened) → no trace.

Tamper is *detectable* today only by an out-of-band party who independently retained the original bytes. That is not a **record-of-record**: an audit ledger that the control plane can rewrite is not an authoritative history. T3 named this explicitly as a deferred limitation ("Ledger is append-only JSONL, not itself cross-generationally immutable-sealed — hash-chain/WORM hardening = T4 record-of-record").

**T4 makes the activation history tamper-EVIDENT by hash-chaining it**, and — consistent with T3's "activation governs what actually runs, not just an audit record" thesis — **enforces chain integrity at the Heimdall edge** (a broken chain fails closed, the edge refuses to bind) in addition to an audit-plane `verify-chain` gate.

## 2. Scope decisions (agreed in brainstorming)

- **Thesis = ① tamper-evident ledger (record-of-record).** Of the two threads T3 left for T4, this design takes the **hash-chained activation history**. The other thread — unifying the `spec/` runtime seal with the git-anchored `recipe/` provenance manifest — is deliberately **scoped OUT** of T4 and remains future work (§9).
- **Integrity model = hash-chain only.** Each event commits to the entire prior history via a chain. This catches **localized** tamper: edit / delete / reorder / mid-truncation of past events. It does **NOT** catch **tail-truncation** (dropping the last N events) nor a **full re-chain from genesis** (an attacker who recomputes every hash) — both require an external/signed head anchor, which belongs to **T5 identity** (a signed head is exactly what authenticated-individual + crypto signing provides). This boundary is stated as an honest limitation, not hidden (§9).
- **Enforcement = edge-enforced + audit CLI.** Chain verification is not audit-only: Heimdall **re-verifies the ledger chain before trusting the active pointer** and fails closed if broken (mirroring T3's verify-then-trust). A `gates activation verify-chain` leg + a `run-lineage-gate.sh` gate provide the audit-plane view. (Rejected: audit-plane-only — leaves the tamper-evidence unenforced at runtime, weaker than T3's closed-loop thesis.)
- **Construction = `prevHash` on a persistence wrapper (Approach A).** A new `LedgerEntry(event, prevHash, entryHash)` wraps the **unchanged** `ActivationEvent`; each JSONL line self-links to the prior line's `entryHash`. (Rejected: a sidecar `.chain` file — two files to keep in sync, no added strength; and a content-addressed git-like DAG — reinvents git, YAGNI.)

### 2.1 The chain is per-target-file; the active pointer is per-(kind,ref) — orthogonal

`registry/activation/<target>.jsonl` already holds all events for one target, across every `(kind, ref)` on that target. **The hash chain spans the whole file** — every appended line links to the immediately prior line, regardless of its `kind`/`ref`. The **active pointer is unchanged**: still the last event matching a given `(kind, ref)`. These two notions are independent: the chain proves the *whole target history is intact*; the active-pointer query walks that same history for the last `(kind,ref)` match. This keeps a single, simple, append-ordered chain per target, matching the existing one-file-per-target layout.

## 3. Architecture

Additive to `core.activation`. The chain crypto is isolated in one place (`LedgerChain`) so the writer (`ActivationLedger.append`) and every verifier (the gate CLI, the Heimdall edge) compute the **byte-for-byte identical** hash — a hash both sides must agree on is a classic drift hazard, so it has exactly one implementation.

```
gates activate Line1 recipe mix-recipe 1.1.0 --by alice --approved-by bob   [T3, unchanged logic]
   └─ ActivationService.activate → builds ActivationEvent (business fields; T3 order preserved)
        └─ ActivationLedger.append(event):                                   [T4: chains on write]
             read file tail → prevHash = tail.entryHash  (empty file → GENESIS)
             entryHash = LedgerChain.entryHash(event, prevHash)
             write LedgerEntry{event, prevHash, entryHash} as one JSONL line
                → registry/activation/Line1.jsonl   (append-only, now a hash chain)

gates activation verify-chain <registry> Line1        [T4: audit plane]
   └─ ActivationLedger.verifyChain(Line1) → ChainVerdict
        walk genesis→tail: recompute each entryHash, check prev links + genesis
        exit 0 intact / 1 tamper (first break + reason) / 2 usage|no-such-target

Heimdall (re)start, ACTIVATION_TARGET=Line1           [T4: edge enforcement]
   └─ verifyChain(Line1) intact?  ── broken ─► FAIL-CLOSED activation.edge.ledger-chain-broken (no bind)
        └─ intact ─► active(Line1,recipe,mix-recipe) ─► contentSha256 recheck (T3) ─► bind
```

## 4. Components

### 4.1 `core.activation.LedgerEntry` (record, immutable) — the persisted unit

```
event      ActivationEvent   the T3 event, byte-for-byte UNCHANGED (all business fields)
prevHash   String            entryHash of the immediately prior entry in this target file
                             (GENESIS for the first/genesis entry)
entryHash  String            LedgerChain.entryHash(event, prevHash) — this entry's identity
```

Serialized as one JSONL line: `{"event":{…T3 fields…},"prevHash":"…","entryHash":"…"}`. Keeping the chain metadata in the wrapper (not on `ActivationEvent`) means the T3 event record, its JSON shape as consumed by `ActivationService`/tests, and the A1–A5 governance semantics are untouched — the blast radius is the *persistence* layer only.

### 4.2 `core.activation.LedgerChain` (new) — the single chain implementation

Pure, static, no I/O. Reuses `core.activation.Sha256`.

- `GENESIS` — the fixed genesis predecessor, 64 hex zeros (`"0000…0000"`). The first entry's `prevHash`.
- `String preimage(ActivationEvent e, String prevHash)` — the **canonical hash preimage**: an **explicit, ordered, delimiter-joined concatenation** of the event's fields followed by `prevHash` (e.g. `target|kind|ref|version|contentSha256|activatedBy|approvedBy|activatedAt|priorVersion|action|prevHash`, with a delimiter that cannot occur in the values, and `null` fields rendered as a fixed sentinel). **Deliberately not** `mapper.writeValueAsString(...)` — a hash whose input is a JSON serialization is hostage to Jackson field-order / whitespace / escaping stability; an explicit field concatenation removes that fragility.
- `String entryHash(ActivationEvent e, String prevHash)` — `Sha256.hex(preimage(e, prevHash).getBytes(StandardCharsets.UTF_8))`. (`Sha256.hex` takes `byte[]`; the preimage is a `String`, so it is encoded as **UTF-8** — the charset is named explicitly because the writer and every verifier must hash byte-for-byte identical input, per §3.)
- `ChainVerdict verify(List<LedgerEntry> entries)` — walk in order:
  - first entry's `prevHash` must equal `GENESIS` (else `ledger.chain.genesis-broken`);
  - each entry's stored `entryHash` must equal `entryHash(entry.event, entry.prevHash)` (else `ledger.chain.entry-hash-mismatch` at index i — catches an edit to the entry's own content);
  - each entry `i>0` must have `prevHash == entries[i-1].entryHash` (else `ledger.chain.prev-link-broken` at index i — catches delete / reorder / insertion).
  - Empty list ⇒ vacuously intact.
- `ChainVerdict(boolean intact, Integer brokenIndex, String rule)` — `intact=true` ⇒ the other fields are null; on break, the first break's index + rule slug.

### 4.3 `core.activation.ActivationLedger` (changed)

- `void append(ActivationEvent e)` — **now chains**: read the file's last non-blank line → parse as `LedgerEntry` → `prevHash = tail.entryHash()`; empty/absent file → `prevHash = GENESIS`. Compute `entryHash`. Append `LedgerEntry{e, prevHash, entryHash}` as one JSONL line. **Append reads the file to take the last line's `entryHash` (an O(n) read at this scale via `Files.readAllLines`), but does NOT re-verify the whole chain per append** — the cheap part is skipping verification, not the read (see §7 for the honest consequence). *(Erratum: earlier drafts called this "O(1)"; the shipped `tailEntryHash` reads the whole file — corrected to match the code.)*
- `List<LedgerEntry> history(String target)` — full ordered entries (was `List<ActivationEvent>`; callers that want events project `.event()`).
- `Optional<ActivationEvent> active(String target, String kind, String ref)` — **unchanged behavior**: last entry whose `event` matches `(kind,ref)`, projected to `.event()`.
- `ChainVerdict verifyChain(String target)` — `LedgerChain.verify(history(target))`. Absent file ⇒ the caller decides (the gate treats no-such-target as exit 2; Heimdall treats an absent ledger the same as T3's `no-active-pointer`, since a target configured for activation must have a ledger).

Reading the tail: read all lines, take the last non-blank. (A tail-only seek optimization is unnecessary at this scale and out of scope.)

### 4.4 `gates` CLI leg — `activation verify-chain`

Extend the existing `activate|active|activation-log` family (in `GatesCli` / `ActivateGate`):

- `gates activation verify-chain <registryDir> <target>` — runs `ActivationLedger.verifyChain`. Prints `[GATE] verify-chain target=… entries=N => INTACT` (exit **0**) or `… => BROKEN at index=i rule=ledger.chain.… ` (exit **1**). No-such-target ledger / usage → exit **2**. Exit semantics mirror `provenance verify` (1 = tamper detected).

**Note for the plan:** the current CLI family is *flat* — `activate`, `active`, `activation-log` are three separate top-level tokens in `GatesCli`, each routing to `ActivateGate.run(args)` which switches on `args[0]`. `activation` is a **new top-level dispatch token** (a new `case "activation"` in `GatesCli`) with its own sub-command `verify-chain` dispatched inside `ActivateGate` — it is *not* an extension of the existing flat `activation-log` command. (The legacy `activation-log` token stays as-is; introducing the namespaced `activation <sub>` form alongside it is intentional.)

(`activation-log` may additionally print each entry's `entryHash`/`prevHash` for auditor convenience — non-load-bearing, nice-to-have.)

### 4.5 Heimdall edge enforcement (closing the loop)

In `NcmdOpcUaBridgeMain.loadConformance`, on the T3 activation-bound path (`ACTIVATION_TARGET` set), **before** trusting the active pointer:

1. `verifyChain(target)` — if not intact, **fail-closed** `activation.edge.ledger-chain-broken` (print the broken index + rule); do **not** read the active pointer, do **not** bind, never print `[BRIDGE] ready`.
2. Only on an intact chain: proceed with T3 exactly as-is — `active(target,"recipe",ref)` → locate `spec/<ref>/<version>.json` → **byte-level `contentSha256` recheck** → parse → bind.

So the edge sequence becomes **verify-chain → verify-content → bind**, all fail-closed, all **startup-only** (unchanged from T3 — the chain is re-verified at each (re)start, not mid-run). Legacy path (`ACTIVATION_TARGET` unset) is completely unaffected — no chain check, dial behavior as before.

## 5. The killer gate — `scripts/run-lineage-gate.sh`

Mirrors existing gates (`cygpath -m` path discipline). Reuses the recipe-mode harness of `run-activation-gate.sh` (a `mode:"recipe"` policy + two `spec/mix-recipe/{1.0.0,1.1.0}.json` MasterSpecs with different setpoints, `recipeTolerance:0`). Assertions (`LN` prefix, chosen to avoid collision with A/C/P/L used by other gates):

- **LN1 — chain intact + audited:** register + `activate mix-recipe 1.0.0`, then `activate mix-recipe 1.1.0` (both `--by alice --approved-by bob`). `verify-chain Line1` → exit 0 `INTACT entries=2`. `activation-log Line1` shows both entries with chained `prevHash`/`entryHash` (entry #2's `prevHash` == entry #1's `entryHash`; entry #1's `prevHash` == GENESIS).
- **LN2 — edit past event detected:** tamper the first ledger line in place (e.g. change `approvedBy` `bob`→`mallory` inside `event`). `verify-chain` → exit 1 `entry-hash-mismatch at index=0` (the stored `entryHash` no longer matches the recomputed hash of the edited event).
- **LN3 — delete middle event detected:** remove one line from the middle of a ≥3-entry ledger. `verify-chain` → exit 1 `prev-link-broken` (the following entry's `prevHash` no longer matches its new predecessor). (Reorder is the same failure class; one assertion suffices. Note: the `prev-link-broken` slug is position-dependent — a delete/reorder that disturbs the **first** entry surfaces as `genesis-broken` instead; LN3 tampers a **middle** entry so the asserted slug holds.)
- **LN4 — edge enforcement (the closed loop):** with an **intact** chain and `1.1.0` active (`Rpm=1600`), start Heimdall `ACTIVATION_TARGET=Line1` → binds; authorized NCMD `Rpm=1600` → **APPLY** (T3 behavior preserved atop the chained ledger). Then **tamper the ledger** (edit a past line) and **restart** Heimdall → **fail-closed** `activation.edge.ledger-chain-broken`, never `[BRIDGE] ready`, no bind. A governed activation history that has been rewritten is refused at the runtime boundary — tamper-evidence is *enforced*, not merely auditable.

## 6. Store / code touch-points the plan must confirm first

- **`ActivationLedger` return-type change** (`history` now `List<LedgerEntry>`) ripples to every caller: `ActivationService` (`versionInHistory`, `priorVersion` via `active`), `ActivateGate` (`activation-log`), the Heimdall bind path, and all `ActivationLedgerTest`/`ActivationServiceTest`/`LoadConformanceActivationTest` fixtures. `active(...)` keeps its `Optional<ActivationEvent>` signature (projects `.event()`), so `ActivationService`/Heimdall call sites that only use `active` change minimally. The plan's first task audits these call sites.
- **Canonical preimage vs the T3 `contentSha256`** are unrelated hashes: `contentSha256` seals the *artifact bytes* (`spec/<ref>/<version>.json`), `entryHash` seals the *ledger entry*. Both use `Sha256`; neither feeds the other.
- **`ActivationEvent` is not modified** — confirm no test asserts on the raw JSONL line shape as a flat event (they should go through `ActivationLedger`/`LedgerEntry`); any that reads the file as flat `ActivationEvent` must move to `LedgerEntry`.

## 7. Error handling / fail-closed summary

- **append trusts the stored tail `entryHash`** as the new `prevHash` (it reads the file for the tail line but does not re-verify the whole chain on every write). Consequence, stated honestly: if the chain was *already* broken before an append, `append` will happily link onto the (tampered) tail — the break is caught by `verify-chain` / the Heimdall edge, **not** at append time. This is the standard append-cheap / verify-on-demand posture; a "verify-before-append" mode is deliberately out of scope (it would make every activation O(n) and could brick a ledger on a transient read error).
- **verify-chain:** any mismatch → report the **first** break (index + rule), exit 1. Empty ledger → intact (exit 0). Absent target ledger → exit 2 (nothing to verify), distinct from "intact".
- **Heimdall:** broken chain → fail-closed `ledger-chain-broken` (no bind, no `[BRIDGE] ready`); intact → T3 content-check path (absent pointer / absent spec / content mismatch each still fail closed).
- **Single control-plane writer** assumed (T3, unchanged) — no concurrent-append coordination; the chain assumes serialized appends.

## 8. Testing

- **core — `LedgerChain`:** `preimage` determinism (same inputs → identical string; a changed field → different string; `null` fields render to the sentinel, not `"null"` colliding with a literal); `GENESIS` is the genesis `prevHash`; `entryHash` stability across runs; `verify` on a hand-built intact chain = intact; and each tamper class detected — edit a middle event (`entry-hash-mismatch@i`), edit an entry's stored `prevHash` in place (also `entry-hash-mismatch@i`, since the stored `prevHash` is part of the entry's own hash preimage — proving `prevHash` is covered by the entry hash and not only by the prev-link check), delete a middle entry (`prev-link-broken`), reorder two middle entries (`prev-link-broken`), corrupt the genesis `prevHash` (`genesis-broken`); empty list → intact.
- **core — `ActivationLedger`:** `append` chains (2nd entry's `prevHash` == 1st entry's `entryHash`; 1st's `prevHash` == GENESIS); `history` returns entries in append order; `active` unchanged (last-`(kind,ref)`-wins, across interleaved kinds/refs); `verifyChain` intact after N appends, broken after an out-of-band edit.
- **core — `ActivationService`:** all T3 semantics preserved atop the chained ledger — artifact-unresolved refuse, SoD refuse (missing + self), rollback-unknown refuse, `priorVersion` computation, happy-path append now produces a valid chain link. Injected `Clock`.
- **heimdall — `LoadConformanceActivationTest` (extended):** intact chain → binds the ledger's active version (T3 behavior preserved); **broken chain → fail-closed, no bind, `ledger-chain-broken`**; legacy (`ACTIVATION_TARGET` unset) → unaffected.
- **gate script:** `run-lineage-gate.sh` LN1–LN4.
- **controller-direct verification (the #1 rule):** the controller personally runs `mvn install` (BUILD SUCCESS), `run-lineage-gate.sh` (LN1–LN4 PASS), and **no-regression** on `run-activation-gate` (A1–A5, updated to the `LedgerEntry` line shape), `run-spec-gate`, `run-ncmd-runtime-gate`, `run-template-conformance-gate`, `run-yggdrasil-spine-gate`, `run-yggdrasil-full-loop-gate`, `run-composable-conformance-gate`.

## 9. Honest limitations (do not oversell)

- **Hash-chain catches localized tamper only.** Edit / delete / reorder / mid-truncation of past events are detected. **Tail-truncation** (dropping the last N events — there is no external anchor to the head) and a **full re-chain from genesis** (an attacker recomputes every `entryHash`) are **NOT** detected by the chain alone. Both need a **signed or externally-anchored head** — that is exactly **T5 identity** (authenticated individual + crypto-signed approval yields a signable head). T4 deliberately stops at self-verifying chain integrity.
- **Self-attested integrity, not authenticity.** As with T3 provenance, the chain proves *the history has not been altered since it was written by the control plane*, not *who* wrote it or that they were authorized. No signature / trust anchor yet (T5).
- **append trusts the tail** (reads the file for the last line, no per-append full re-verify); a pre-existing break is surfaced at verify/bind, not at append time (§7).
- **Canonical-preimage delimiter-injection assumption:** the preimage assumes field values never contain the `` SEP delimiter or the null-sentinel sequence; this is not enforced. A malicious *writer* injecting a delimiter to force a preimage collision is outside T4's threat model (tamper-evidence of the *recorded* history against retroactive edits) and is closed by T5 signed/authenticated writes — the same "self-attested, not authentic" seam. Documented in the `LedgerChain` javadoc.
- **Per-target chains are independent** — no cross-target global ordering or single global chain (deliberate; matches the one-file-per-target layout; a global Merkle log is out of scope / YAGNI).
- **Single control-plane writer** — the chain assumes serialized appends; no distributed-writer consensus.
- **Scope is the activation ledger (`spec/` runtime governance).** Unifying the `spec/` activation-event seal with the git-anchored `recipe/` provenance manifest — the *other* T4 thread — is **not** in this design (thesis ① was chosen); it remains future work.
- **Registries are gate-regenerated** — there is no persistent production ledger to migrate. T4 changes the on-disk JSONL line shape (flat event → `LedgerEntry`); the A1–A5 gate and existing tests are updated accordingly. Strict back-compat reading of legacy flat lines is unnecessary (no legacy data) and is not built.

## 10. What T4 unblocks

Turns the activation audit trail into a tamper-evident **record-of-record** and enforces its integrity at the runtime edge. This is the direct precursor to **T5 identity**: a hash-chained head is the object you *sign*, and signing closes exactly the tail-truncation / full-re-chain gap T4 leaves open — replacing "self-attested chain integrity" with "authenticated, non-repudiable, externally-anchored history" (authenticated individuals + attributes + crypto-signed approval, plugged at the broker auth seam). The unification of the `spec/` seal with git-anchored provenance remains available as a separate future thread.
