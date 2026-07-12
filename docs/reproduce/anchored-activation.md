# Reproducing the Anchored-Activation Gate (T7)

> **Scope.** This document lets a third party reproduce the T7 *anchored activation* experiment
> exactly, from a clean checkout, and understand every line the gate prints. The runnable code lives
> in the sibling **`bifrost`** repository; this lab repo is a PoC-stage consumer of that external
> Bifrost / Yggdrasil governance. The captured reference output this doc annotates is
> [`outputs/run-anchored-activation-gate.log`](outputs/run-anchored-activation-gate.log).

---

## 1. What this proves

Earlier tiers already made each governed activation **dual-signed** (T5: an Ed25519 signature over the
ledger, plus a *signed head* anchoring the tail) and **authorized** (T6: deny-by-default maker–checker,
activator needs `activate`, approver needs `approve`). T7 closes the two rollback attacks that a *signed
head alone* still leaves open, by combining two new defences:

- a **four-eyes head** — the ledger's head record is co-signed by **two distinct registered Ed25519
  keys** (`signedBy`/`sig` **and** `coSignedBy`/`coSig`), so a lone insider cannot forge a valid head; and
- a **monotonic external anchor** — an append-only witness (`AnchorStore` seam) records the tail's
  sequence number out-of-band. A `FileAnchorStore` is an on-box append-only projection; a
  `GitAnchorStore` reads the anchor from the **committed git HEAD** (`git show HEAD:<file>`), never from
  the mutable working tree, so a witness pushed to a protected remote survives an on-box rollback.

The two attacks, and how T7 detects each:

- **Attack #1 — lone re-anchor / truncation.** An insider truncates the ledger back to an earlier
  version and re-writes the signed head to match. T5's head re-signs *cleanly* over the shorter tail, so
  signed-verification passes. But the append-only anchor still records the higher sequence, so anchored
  verification sees `head.seq < anchor.seq` and fails **`identity.anchor.rollback`**. Demonstrated by
  **AN2** (file anchor) and **AN7** (rollback-to-empty).
- **Attack #2 — co-rollback.** The insider rolls back the ledger, the head **and** the on-box anchor
  file together. A `FileAnchorStore` is a local projection and gets rolled back with everything else, so
  it cannot witness the higher sequence. The `GitAnchorStore`'s committed HEAD, having been pushed
  off-box, is untouched and still witnesses the higher sequence → **`identity.anchor.rollback`**.
  Demonstrated by **AN3** (co-rollback) and **AN4** (working-tree tamper ignored).

Every fault is a **distinct, fail-closed `identity.*` code** with a non-zero exit, and (in the broker
leg **AN8**) Heimdall refuses to bind the edge when `REQUIRE_ANCHORED_ACTIVATION=on`.

> **Honest residual.** The git witness only helps if its history is genuinely off-box and
> tamper-resistant (protected remote / signed tag / append-only store). `FileAnchorStore` alone defends
> Attack #1 but **not** Attack #2; the trust ultimately rests on the anchor's off-box protection, not on
> this gate. This is stated in the gate script's own header and is reproduced here deliberately.

---

## 2. Prerequisites

| Requirement | Why it is needed |
|---|---|
| **JDK 17** | Builds and runs the Bifrost `core` + `gates` modules and the `gates` CLI JAR. |
| **Maven** (`mvn`) | `mvn -pl core,gates -am install` builds the identity-aware gates JAR the gate invokes. |
| **Python** (on `PATH` as `python`) | The gate's byte/JSON tamper helpers (`delete_line`, `entry0_hash`, `rewrite_head_seq0`, `strip_head_copair`) are inline Python heredocs. The gate **aborts immediately** if `python` is not found. |
| **git** (with a usable identity) | The `GitAnchorStore` used by **AN3/AN4** *commits* the anchor and later reads the **committed HEAD**. Without a configured `user.name` / `user.email`, the anchor repo cannot commit and those cases fail. |
| **Bash** | The gate is a bash script (`set -euo pipefail`); on Windows run it under Git Bash. It uses `cygpath -m` to hand Windows-style paths to the JVM. |
| **Docker Desktop** | **Only** for the optional broker leg **AN8** (Heimdall + HiveMQ CE + OPC-UA sim). AN1–AN7 are pure CLI and need no Docker. This doc runs with **`SKIP_AN8=1`**. |

**Build the code first**, from the `bifrost` repo root:

```bash
cd /path/to/bifrost
mvn -q install          # or at minimum: mvn -q -pl core,gates -am install -DskipTests
```

The gate itself also runs `mvn -q -pl core,gates -am install -DskipTests` as its step 0, so a prior full
build is convenient but not strictly required — a working Maven + JDK 17 toolchain is.

---

## 3. The command

Run from the **`bifrost`** repo root (the script does `cd "$(dirname "$0")/.."` so it also works from
anywhere, but the reference run was from the repo root):

```bash
cd /path/to/bifrost
SKIP_AN8=1 bash scripts/run-anchored-activation-gate.sh
```

`SKIP_AN8=1` force-skips the broker leg, so the run is **AN1–AN7 only** and needs no Docker. Expected
tail line: `[ANCHORED] GATE PASS (AN1-AN7)` with exit code `0`.

---

## 4. What the gate stages, step by step

All paths below are under the gate's work directory `build/anchored-gate/` (referred to as `$WORK`),
which is wiped (`rm -rf "$WORK"`) at the start of step 1. Fixtures come from
`scripts/fixtures/activation/` (referred to as `$FIX`).

### Step 0 — build the gates JAR
`mvn -q -pl core,gates -am install -DskipTests`, then asserts `gates/target/bifrost-gates.jar` exists.
A shell function `gates() { java -jar "$GATES_JAR_WIN" "$@"; }` wraps every CLI call, where
`$GATES_JAR_WIN` is the `cygpath -m` (forward-slash Windows) form of that JAR.

### Step 1 — principals, trust anchor, and the per-case registry stager
- `gates identity keygen alice --out "$KEYS_WIN"` and `... keygen bob ...` generate the two Ed25519
  keypairs into `$WORK/keys/` and **append their authorized-keys lines to `$WORK/authorized-keys.jsonl`**
  (`$AKF`). `alice` is the **activator**, `bob` the **approver**; both are registered. The gate greps the
  file to confirm it contains `"alice"` and `"bob"`.
- `stage_reg <dir>` builds a fresh governed registry for each case and sets `$reg` / `$reg_win`:
  - copies the two resolvable recipe artifacts
    `spec-mix-recipe-1.0.0.json` and `spec-mix-recipe-1.1.0.json` into `<reg>/spec/mix-recipe/`;
  - copies the canonical `$AKF` into `<reg>/identity/authorized-keys.jsonl` (the trust anchor);
  - writes `<reg>/identity/activation-policy.json` — a **deny-by-default** T6 policy with exactly two
    rules: `alice` may `activate` and `bob` may `approve`, both scoped to
    target `Line1`, kind `recipe`, ref `mix-recipe`. This is what lets the *legitimate* signed
    activations pass authZ so the test can then focus on the anchor/head faults.

### The mutation helpers (inline Python)
These are the exact primitives each attack case uses:
- `delete_line <file> <n>` — deletes 1-based line `n` (used to **truncate** a ledger/anchor tail).
- `entry0_hash <ledger>` — parses the ledger JSONL and prints `entry[0].entryHash` (the seq-0 hash a
  forged seq-0 head must point at).
- `rewrite_head_seq0 <head> <hash>` — rewrites the head JSON to `seq=0`, `tailEntryHash=<hash>`, leaving
  `signedBy`/`sig`/`coSignedBy`/`coSig` **as-is** (deliberately stale — the anchor check fires *before*
  the signed-head check, so the staleness is never reached). This forges Attack #1's re-anchored head.
- `strip_head_copair <head>` — removes `coSignedBy`/`coSig`, leaving `seq`/`tail`/`sig` intact, so the
  anchor and signed-head checks pass and the **four-eyes** check is the one that fires (AN5).
- `sactivate <ver> <by> <appr> <byKey> <apprKey> [--anchor-store … --anchor-dir …]` — the convenience
  wrapper for a full signed activation:
  `gates activate <reg> Line1 recipe mix-recipe <ver> --by … --approved-by … --by-key … --approved-by-key … <extra>`.

### The seven CLI cases
Each case calls `stage_reg` on its own registry, performs zero or more `sactivate` calls, mutates state,
then runs `gates identity verify-anchored …` and asserts the exit code **and** the expected substring.

| Case | Setup | Mutation | Verify command | Expected |
|---|---|---|---|---|
| **AN1** | one signed `activate 1.0.0` | none (happy path) | `verify-anchored <reg> Line1` | exit 0, `INTACT (anchored)`; also asserts the activation reported `signed=true` and wrote `<reg>/anchor/Line1.anchor.jsonl` |
| **AN2** | activate `1.0.0` then `1.1.0` (file anchor now seq1) | read entry-0 hash; `delete_line ledger 2` (truncate to seq0); `rewrite_head_seq0 head <E0>` — head now seq0 but the **append-only file anchor is still seq1** | `verify-anchored <reg> Line1` | exit 1, `identity.anchor.rollback` |
| **AN3** | activate `1.0.0` then `1.1.0` with `--anchor-store git --anchor-dir <gitrepo>`; snapshot the seq-0 ledger+head **before** the seq1 activation | **co-rollback**: copy the seq-0 ledger+head snapshot back over the reg; the **git witness committed up to seq1 is untouched** | `verify-anchored … --anchor-store git --anchor-dir <gitrepo>` | exit 1, `identity.anchor.rollback` |
| **AN4** | same git-anchored two-activation setup, snapshot seq-0 | `delete_line <gitrepo>/Line1.anchor.jsonl 2` — roll the git repo's **working-tree** anchor back to seq0 (HEAD still seq1) **and** co-roll-back ledger+head | `verify-anchored … --anchor-store git …` | exit 1, `identity.anchor.rollback` — proves `latest()` reads the **committed HEAD**, ignoring the tampered working tree |
| **AN5** | one signed `activate 1.0.0` | `strip_head_copair head` — remove the four-eyes co-pair; seq/tail/sig unchanged | `verify-anchored <reg> Line1` | exit 1, `identity.head.four-eyes.missing` |
| **AN6** | activate `1.0.0` then `1.1.0` (head seq1, anchor seq1) | `delete_line anchor 2` — anchor back to seq0 while the head stays seq1 (the **crash window**: head advanced before the anchor caught up) | `verify-anchored <reg> Line1` | exit 1, `identity.anchor.behind` |
| **AN7** | one signed `activate 1.0.0` (anchor seq0 recorded) | `rm` the ledger **and** head, **keep** `<reg>/anchor/Line1.anchor.jsonl` | `verify-anchored <reg> Line1` | exit 1, `identity.anchor.rollback` — the witness attests a tail that is now entirely gone |

### AN8 (broker leg — verified full)
When **not** skipped, AN8 stages a *full conformance* registry (UDT + recipe conformance + spec + policy
+ trust anchor), does the AN2-style truncation, then starts **HiveMQ CE** (`docker compose … up -d
hivemq-ce`, waits for `:1883`), the **OPC-UA sim**, and **Heimdall** with
`REQUIRE_ANCHORED_ACTIVATION=on ANCHOR_STORE=file`. It asserts the bridge log contains
`activation.edge.anchor-denied` and that it **never** prints `[BRIDGE] activation bound` or `[BRIDGE]
ready` — i.e. the edge fail-closes before binding. With `SKIP_AN8=1` this whole branch is bypassed and
the gate prints `AN8 skipped (SKIP_AN8 set)`.

The full run (Docker up) was executed and captured:
```
[ANCHORED] AN8 => PASS (activation.edge.anchor-denied, edge NEVER bound)
[ANCHORED] GATE PASS (AN1-AN7 +AN8)
```
So the reproduction is `SKIP_AN8=1` for a no-Docker CLI run, or the plain command (Docker up) for the
full `AN1–AN8`.

---

## 5. Expected output, annotated

The following is the **actual** captured run (AN1–AN7, `SKIP_AN8=1`) from
[`outputs/run-anchored-activation-gate.log`](outputs/run-anchored-activation-gate.log). Each line is
annotated with what it mutated, which `identity.*` code it must produce, and which attack it demonstrates.

```text
[ANCHORED] step 0: build the identity-aware gates jar (core+gates)
[ANCHORED] gates=C:/Users/Eisen/Desktop/Labs/[iiot]/bifrost/gates/target/bifrost-gates.jar
[ANCHORED] step 1: keygen alice/bob + build the canonical authorized-keys.jsonl
[ANCHORED] keys ready (alice activator, bob approver — both registered)
```
Step 0 built the gates JAR and echoed its `cygpath -m` path. Step 1 generated the two Ed25519 keypairs
and the canonical `authorized-keys.jsonl` trust anchor. `alice` = activator, `bob` = approver.

```text
[ANCHORED] ===== AN1: signed activate -> verify-anchored INTACT (file anchor) =====
[ANCHORED] AN1 => PASS (INTACT (anchored), exit 0)
```
**AN1 — happy path.** One signed activation of `1.0.0`; the activation reported `signed=true` and wrote a
file anchor. `verify-anchored` returned exit 0 with `INTACT (anchored)`. This is the baseline: a
well-formed four-eyes head plus a matching anchor verifies clean.

```text
[ANCHORED] ===== AN2: truncation re-anchor caught by append-only FILE anchor -> identity.anchor.rollback =====
[ANCHORED] AN2 => PASS (identity.anchor.rollback, exit 1)
```
**AN2 — Attack #1 (lone truncation re-anchor), file anchor.** Two activations pushed the anchor to seq1;
the gate truncated the ledger back to seq0 and forged a seq-0 head. The append-only **file** anchor still
records seq1, so `head.seq (0) < anchor.seq (1)` → `identity.anchor.rollback`, exit 1.

```text
[ANCHORED] ===== AN3: co-rollback caught by GIT witness (committed HEAD) -> identity.anchor.rollback =====
[ANCHORED] AN3 => PASS (git witness catches the co-rollback, exit 1)
```
**AN3 — Attack #2 (co-rollback), git witness.** Ledger, head **and** on-box state were all rolled back to
seq0. Because the **git** anchor's `latest()` reads the committed HEAD (which recorded seq1), the witness
survives the co-rollback and reports `identity.anchor.rollback`, exit 1. A file anchor would have been
rolled back too and would have missed this.

```text
[ANCHORED] ===== AN4: git witness survives a working-tree anchor tamper -> identity.anchor.rollback =====
[ANCHORED] AN4 => PASS (committed HEAD ignores the tampered working tree, exit 1)
```
**AN4 — Attack #2 hardening: working-tree tamper ignored.** Same git setup, but the attacker also edited
the git repo's *working-tree* anchor file back to seq0 (uncommitted) alongside the ledger/head rollback.
`verify-anchored --anchor-store git` still reads `git show HEAD:<file>` (seq1), ignores the dirty working
tree, and reports `identity.anchor.rollback`, exit 1. This proves `latest()` never trusts the mutable
working tree.

```text
[ANCHORED] ===== AN5: four-eyes head required -> identity.head.four-eyes.missing =====
[ANCHORED] AN5 => PASS (identity.head.four-eyes.missing, exit 1)
```
**AN5 — four-eyes head enforcement.** The head's `coSignedBy`/`coSig` co-pair was stripped (seq/tail/sig
left valid so the anchor and signed-head checks pass). Verification requires two distinct signers on the
head, so the missing co-pair yields `identity.head.four-eyes.missing`, exit 1. This is the "single insider
cannot mint a head" guarantee.

```text
[ANCHORED] ===== AN6: anchor-behind (crash window: head advanced, anchor lagging) -> identity.anchor.behind =====
[ANCHORED] AN6 => PASS (identity.anchor.behind, exit 1)
```
**AN6 — crash-window detection.** Two activations left head=seq1, anchor=seq1; the gate deleted the seq1
anchor line so the **anchor lags the head** (`anchor.seq (0) < head.seq (1)`). This is the benign-looking
"crashed after advancing the head, before the anchor caught up" state, and it is a *distinct* fault:
`identity.anchor.behind`, exit 1. It is deliberately separated from `anchor.rollback` (the anchor being
*ahead*), so operators can tell a crash window apart from a rollback attack.

```text
[ANCHORED] ===== AN7: rollback-to-empty (delete ledger+head, keep anchor) -> identity.anchor.rollback =====
[ANCHORED] AN7 => PASS (witness attests a tail now gone: identity.anchor.rollback, exit 1)
```
**AN7 — Attack #1 boundary: rollback to empty.** The ledger and head were deleted entirely while the
anchor witness was kept. The witness attests a tail (seq0) that no longer exists, so verification fails
`identity.anchor.rollback`, exit 1 — you cannot make a rollback "disappear" by deleting the ledger.

```text
[ANCHORED] ===== AN8: edge fail-closed on a rolled-back ledger (REQUIRE_ANCHORED_ACTIVATION=on) =====
[ANCHORED] AN8 skipped (SKIP_AN8 set)

[ANCHORED] GATE PASS (AN1-AN7)
```
**AN8 — skipped.** Because `SKIP_AN8=1` was set, the Docker/broker leg was bypassed. The final line
`[ANCHORED] GATE PASS (AN1-AN7)` (note: no `+AN8`) plus exit 0 is the success signal.

---

## 6. How to read a PASS

A successful run satisfies **all** of:

1. **Exit code `0`** (`echo $?` immediately after the command).
2. The final line reads exactly **`[ANCHORED] GATE PASS (AN1-AN7)`**. If AN8 also ran it would read
   `[ANCHORED] GATE PASS (AN1-AN7 +AN8)`.
3. Each case printed its own `AN<n> => PASS (...)` line, meaning that case's `verify-anchored` returned
   the **exact expected exit code** (0 for AN1, 1 for AN2–AN7) **and** the output contained the **exact
   expected substring** (`INTACT (anchored)`, `identity.anchor.rollback`, `identity.head.four-eyes.missing`,
   or `identity.anchor.behind`).

The gate runs under `set -euo pipefail` and every assertion routes through `fail()`, which prints
`[ANCHORED] FAIL: …` and `exit 1`. So **any** deviation aborts the whole run with a non-zero exit — there
is no partial-pass. If an AN case fails you will see the `[ANCHORED] FAIL: …` line naming that case (e.g.
`AN2 verify-anchored returned 0 — expected 1` or `AN4 missing identity.anchor.rollback (latest() must
ignore the working tree)`), followed by sim/bridge log tails. A failing case means either the code no
longer produces the expected fail-closed code for that mutation (a **regression in the tamper-evidence
guarantee**) or the environment is wrong (see troubleshooting).

---

## 7. Troubleshooting

- **`[ANCHORED] FAIL: python not found on PATH`** — the very first check (`command -v python`) failed.
  Install Python and ensure the executable is named `python` on `PATH` (not only `python3`). The tamper
  helpers are inline Python; nothing downstream runs without it.
- **Git identity missing / anchor repo cannot commit** — AN3/AN4 use a `GitAnchorStore` that commits the
  anchor and later reads the committed HEAD. If git has no `user.name` / `user.email`, the commit fails
  and those cases break. Configure an identity once:
  ```bash
  git config --global user.name  "Repro Runner"
  git config --global user.email "repro@example.com"
  ```
- **Stale gates JAR** — the gate rebuilds `core,gates` at step 0, but if you bypass that (or a build
  error leaves an old JAR), you may run outdated verification logic and get confusing mismatches. Force a
  clean build from the bifrost root: `mvn -q -pl core,gates -am install -DskipTests`, and confirm
  `gates/target/bifrost-gates.jar` was just written.
- **`cygpath: command not found`** — the gate uses `cygpath -m` to convert paths for the JVM. Run under
  **Git Bash** (which provides `cygpath`), not WSL or a bare `sh`.
- **AN8 / Docker** — with `SKIP_AN8=1` you should **not** need Docker at all; AN8 must print
  `AN8 skipped (SKIP_AN8 set)`. If you drop `SKIP_AN8` and Docker Desktop is not running, the gate
  auto-skips (`AN8 skipped (no docker)`); if Docker *is* up, AN8 additionally builds the `heimdall,sim`
  JARs, starts HiveMQ CE + the OPC-UA sim + Heimdall with `REQUIRE_ANCHORED_ACTIVATION=on`, and asserts
  `activation.edge.anchor-denied` with **no** `[BRIDGE] activation bound` / `[BRIDGE] ready`. That leg is
  slower and environment-heavy; keep `SKIP_AN8=1` for a fast, deterministic CLI-only reproduction.
```
