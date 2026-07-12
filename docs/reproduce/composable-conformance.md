# Reproduce: Composable Runtime Conformance (the "killer gate")

This document lets a third party reproduce the composable-runtime-conformance experiment
**exactly**. Follow it top to bottom. Every command, file path, and expected output line below is
taken verbatim from the real gate script and one captured run; nothing is invented.

- Gate script under test: `bifrost/scripts/run-composable-conformance-gate.sh`
- Captured reference output: `sparkplug-governance-lab/docs/reproduce/outputs/run-composable-conformance-gate.log`

> Repository layout assumed by this doc: the lab (`sparkplug-governance-lab`) and the runnable
> product (`bifrost`) are **sibling** directories, e.g. `…/Labs/[iiot]/bifrost` and
> `…/Labs/[iiot]/sparkplug-governance-lab`. The lab is a PoC-stage consumer of the external
> Bifrost / Yggdrasil governance; **all runnable code lives in `bifrost`**, which is where you run
> the gate.

---

## 1. What this proves

There is exactly **ONE governed `ConformancePolicy`** artifact and **ONE `ConformanceEvaluator`**,
and the identical governed artifact is enforced at **two different boundaries**:

- **Design-time** — the CI gate `gates spec` evaluates a proposed `MasterSpec` before it is ever
  deployed.
- **Runtime** — the Heimdall edge daemon (`heimdall` ② conformance check) evaluates a live NCMD
  command before it is written to OPC-UA.

The headline result is **no drift between "what CI checked" and "what runs."** The Mixer/weld
`Recipe/Rpm`-style range (here the weld-lobe cross-member rule and the exact-recipe deviation rule)
was **migrated out of a hand-authored `policy.json` and INTO the governed model**. The admissible
bound therefore lives in a single source of truth. The decisive demonstration is case **C3**: a
**single edit** to that one governed policy file moves **both** boundaries at once — the CI gate and
the live edge flip their verdict together, from the same byte change, with the Heimdall
authorization `policy.json` left untouched.

The gate runs five assertions:

| Case | Boundary                | What it demonstrates                                                                 |
|------|-------------------------|--------------------------------------------------------------------------------------|
| C1   | runtime (Heimdall)      | Composition holds live: a cross-member rule DENIES a bad command, then APPLYs a good one. |
| C2   | design-time (CI gate)   | The **same** governed policy yields the **same verdict** on a spec at CI as at the edge. |
| C3   | single-source flip      | Editing the ONE governed policy moves BOTH the CI gate and the runtime edge together. |
| C4   | governed "dial"         | Swapping the policy's mode re-scopes the SAME node from envelope to exact-recipe conformance. |
| C5   | governed-policy lifecycle | `provenance publish` + `verify` accept clean policy bytes and reject a one-byte tamper. |

---

## 2. Prerequisites

You need all of the following on your PATH / installed:

- **JDK 17** (`java -version` → 17.x). The build and all jars target Java 17.
- **Apache Maven** (`mvn -version`).
- **Docker Desktop**, running. The runtime leg (C1, C3, C4) needs an MQTT broker; the gate starts a
  **HiveMQ CE** container via `docker-compose.yml` and waits for **host port `1883`** to open. Port
  1883 must be free.
- **git** — used inside C5 to build a throwaway source repo for the provenance publish/verify leg.
- A **bash** shell. The script is bash and uses `cygpath`, `jps`, `taskkill`, and
  `/dev/tcp` — on Windows run it from **Git Bash** (the environment it was authored and captured in).
- The **Bifrost project built first**. From the `bifrost` repo root run:

  ```bash
  mvn install
  ```

  This produces the four jars the gate needs (`core`, `sim`, `gates`, `heimdall`). The script will
  build them itself if they are missing (step 0 below), but running `mvn install` up front is the
  reliable path and matches the recorded procedure.

---

## 3. The command

From the **bifrost** repo root:

```bash
cd bifrost
bash scripts/run-composable-conformance-gate.sh
```

The script header also documents a guarded form with an overall timeout:

```bash
timeout 600 bash scripts/run-composable-conformance-gate.sh
```

It runs `set -euo pipefail` and `cd`s to the repo root itself, so it must be launched from within
the `bifrost` checkout. A `trap cleanup EXIT` tears everything down (kills the sim + edge jars, stops
the broker) even on failure.

---

## 4. What the gate stages, step by step

### Fixtures (the governed artifacts)

All fixtures live under `bifrost/scripts/fixtures/conformance/` (referred to as `$FIX` in the
script). The gate copies three of these subtrees into a fresh work registry
(`build/conformance-gate/registry/`) that **both** the CI gate and the edge read:

- `udt/Weld-Controller/1.0.0.json` — the equipment `UdtDefinition`. Declares members `WeldCurrent`
  (range 0–12), `WeldTime` (0–500), `ElectrodeForce` (0–6). This is the structural/type/envelope
  layer the conformance policy composes on top of.
- `conformance/Weld-Controller/1.0.0.json` — **the ONE governed `ConformancePolicy`** (envelope
  mode). Its `dial.mode` is `"envelope"` and it carries the single cross-member rule that is the
  star of the experiment:

  ```json
  { "id": "weld-lobe", "ifMember": "ElectrodeForce", "ifOp": "lt", "ifValue": 3.0,
    "thenMember": "WeldCurrent", "thenOp": "le", "thenValue": 8.0 }
  ```

  Read as: *when `ElectrodeForce < 3.0`, require `WeldCurrent ≤ 8.0`.* Its `nodeBindings` map the OPC
  node `ns=2;s=Weld/WeldCurrent` to member `WeldCurrent` and the read node
  `ns=2;s=BodyShop/Weld1.ElectrodeForce` to member `ElectrodeForce`, so the runtime edge can
  evaluate the cross-member rule from live values. This file is the one C3 edits.
- `conformance/Weld-Controller/recipe-1.0.0.json` — the same governed policy in **recipe mode**
  (`dial.mode` = `"recipe"`, `activeRecipeRef` = `WeldSchedule` `1.0.0`, `recipeTolerance` = `0.0`,
  no cross-constraints). Used by C4.
- `spec/WeldSchedule/1.0.0.json` — the approved recipe (`WeldCurrent = 9.0`) the recipe-mode dial
  points at.
- `specs/reject-master-spec.json` — proposed `MasterSpec` `WeldRejectA`: `ElectrodeForce = 2.5`,
  `WeldCurrent = 9.0`. This is the **same shape** as the C1 runtime command that gets denied.
- `specs/accept-master-spec.json` — control `MasterSpec` `WeldAcceptA`: `ElectrodeForce = 4.0`,
  `WeldCurrent = 9.0`. `ElectrodeForce = 4.0` makes the rule's antecedent (`ElectrodeForce < 3.0`)
  false, so the constraint does not fire.
- `weld-policy.json` — the Heimdall **authorization** policy (`POLICY_PATH`), the *separate* plane.
  It allows principal `recipe-writer` on group `Bifrost:Line1` / edge `recipe-edge` to command
  `ns=2;s=Weld/WeldCurrent`, default deny. **C3 deliberately does NOT touch this file** — that is
  the whole point: authorization is unchanged; only the one governed *conformance* policy moves.

### The jars / topology (all on one broker + one embedded OPC-UA sim)

- `sim/target/bifrost-sim.jar` — the embedded OPC-UA sim. Publishes `WeldControllerType` /
  `BodyShop/Weld1` with `ElectrodeForce = 2.5` seeded, and exposes writable node
  `ns=2;s=Weld/WeldCurrent`.
- `heimdall/target/bifrost-heimdall.jar` — the edge daemon. Enforces ① authorization
  (`POLICY_PATH`) then ② conformance (`CONFORMANCE_PATH` over `REGISTRY_PATH`). The same jar also
  contains `dev.krillin.bifrost.heimdall.RogueNcmd`, the single-metric NCMD publisher the gate uses
  to inject commands.
- `gates/target/bifrost-gates.jar` — the CI governor. Provides `spec` (design-time conformance) and
  `provenance` (publish/verify).

### Stage-by-stage narration

**Step 0 — build jars if missing.** If any of the four jars is absent the script runs
`mvn -q -pl core,sim,gates,heimdall install`, then asserts each jar exists. It computes Windows-style
paths via `cygpath -m` and echoes `heimdall=…`, `sim=…`, `gates=…`.

**Step 1 — start the broker.** `docker compose -f docker-compose.yml up -d hivemq-ce`, then polls
`/dev/tcp/localhost/1883` up to 30× (2s apart). On success prints `HiveMQ CE up on :1883`.

**Step 2 — build the work registry + start the sim.** `rm -rf build/conformance-gate`, recreate it,
and copy `udt/`, `conformance/`, `spec/` into `build/conformance-gate/registry/`. Launch the sim jar,
poll its log for `OPC-UA sim listening`, print the pid. The writable weld node is
`ns=2;s=Weld/WeldCurrent`.

Two helpers drive the runtime legs:
- `pub <node> <value> <dataType>` runs `RogueNcmd` with `SPB_GROUP=Bifrost:Line1`,
  `SPB_EDGE=recipe-edge` (the Sparkplug identity the weld authz policy allows) to publish an NCMD.
- `start_bridge <CONFORMANCE_PATH> <log>` launches Heimdall with `POLICY_PATH=weld-policy.json`,
  `REGISTRY_PATH=<work registry>`, and `CONFORMANCE_PATH=<the governed policy under test>`, then
  waits for both `[BRIDGE] ready` and `[BRIDGE] conformance loaded`. `stop_bridge` kills it and
  sleeps 2s so the broker drops the fixed clientId before the next daemon connects.

**C1 — composition, runtime.** `start_bridge` with the **envelope** policy
(`conformance/Weld-Controller/1.0.0.json`). Publish `WeldCurrent = 9` → expect a runtime **DENY**
whose reason contains `conformance.cross.weld-lobe` (because seeded `ElectrodeForce = 2.5 < 3` forces
`WeldCurrent ≤ 8`). Then publish `WeldCurrent = 7` → expect **APPLY ok=true**.

**C2 — design == runtime.** Without touching the policy, run the CI gate against the two specs:
- `gates spec <registry> reject-master-spec.json` must exit **1** (mirrors the C1 runtime DENY).
- `gates spec <registry> accept-master-spec.json` must exit **0** (`ElectrodeForce = 4.0` ⇒
  antecedent false). Same governed policy, same verdict on a spec at CI as on a command at the edge.

**C3 — single-source flip.** `stop_bridge`, then `sed -i 's/"thenValue": *8\.0/"thenValue": 10.0/'`
on the **one** governed envelope policy file (weld-lobe consequent 8 → 10); assert the file now
contains `"thenValue": 10.0`. The authz `policy.json` is untouched. Re-run
`gates spec <registry> reject-master-spec.json` → now exit **0** (the design boundary moved).
`start_bridge` again and publish the same `WeldCurrent = 9` → now **APPLY** (the runtime boundary
moved). One edit, both boundaries.

**C4 — the governed dial.** `stop_bridge`, `start_bridge` with the **recipe-mode** policy
(`conformance/Weld-Controller/recipe-1.0.0.json`, active `WeldSchedule` setpoint `WeldCurrent = 9`,
tolerance `0`). Publish `WeldCurrent = 7` → expect **DENY** `conformance.recipe.deviation`. Publish
`WeldCurrent = 9` → expect **APPLY**. The same OPC node was re-scoped from envelope to exact-recipe
conformance purely by swapping the governed dial. `stop_bridge`.

**C5 — governed-policy lifecycle.** Copy the governed policy bytes into a throwaway git repo
(`git init`, `core.autocrlf false` so the blob stays byte-verbatim), commit. Run
`gates provenance publish <registry> <srcrepo> Weld-Controller-policy-1.0.0.json
Weld-Controller-policy 1.0.0` → expect exit 0 and a written
`registry/recipe/Weld-Controller-policy/1.0.0/recipe-setpoints.yaml`. Run
`gates provenance verify <registry> Weld-Controller-policy` → exit **0** (clean). Then append a
single byte `X` to the published file and re-verify → exit **1** (content-hash mismatch).

**Step 9 — teardown.** Kill the sim + any Heimdall, `docker compose stop hivemq-ce`, print the PASS
line, `exit 0`.

---

## 5. Expected output, annotated

Below are the real key lines from the captured run
(`docs/reproduce/outputs/run-composable-conformance-gate.log`). Some non-ASCII glyphs render as `?`
in the captured log — that is a console-encoding artifact, not a failure.

**Setup (steps 0–2):**

```
[GATE] step 0: build jars if missing (core, sim, gates, heimdall)
[GATE] heimdall=C:/Users/Eisen/Desktop/Labs/[iiot]/bifrost/heimdall/target/bifrost-heimdall.jar
[GATE] sim=C:/Users/Eisen/Desktop/Labs/[iiot]/bifrost/sim/target/bifrost-sim.jar
[GATE] gates=C:/Users/Eisen/Desktop/Labs/[iiot]/bifrost/gates/target/bifrost-gates.jar
[GATE] step 1: start HiveMQ CE (broker) + wait for :1883
[GATE] HiveMQ CE up on :1883
[GATE] step 2: set up the work registry (copy governed fixtures) + start the OPC-UA sim
[GATE] OPC-UA sim listening (pid 51118)
```

The broker opened on 1883 and the sim came up. Your pids will differ.

**C1 — composition at runtime:**

```
[GATE] ===== C1: composition (runtime) — envelope cross-member DENY then APPLY =====
[GATE] edge up with envelope conformance loaded (pid 51125)
[GATE] C1a: WeldCurrent=9 DENIED by composed cross-member rule (conformance.cross.weld-lobe)
[GATE] C1 OK: composition holds at runtime (9 denied, 7 applied)
```

The live edge denied `WeldCurrent = 9` because the composed cross-member rule (`ElectrodeForce =
2.5 < 3 ⇒ WeldCurrent ≤ 8`) was violated, and applied `WeldCurrent = 7`. The bound came from the
**governed model** (`conformance/Weld-Controller/1.0.0.json`, `thenValue: 8.0`), not from any
hand-authored `policy.json`.

**C2 — design == runtime:**

```
[GATE] ===== C2: design==runtime — same governed policy drives the CI gate =====
[GATE] ref=WeldRejectA equipment=Weld-Controller@1.0.0 setpoints=2
[GATE] FAIL ? ? violations:
  - [conformance.cross.weld-lobe] cross-member weld-lobe: when ElectrodeForce lt 3.0, require WeldCurrent le 8.0 (was 9.0)
[GATE] C2a: design-time gate REJECTED the same-shape spec (exit 1)
[GATE] ref=WeldAcceptA equipment=Weld-Controller@1.0.0 setpoints=2
[GATE] PASS ?
[GATE] C2 OK: design==runtime — one governed policy, identical verdict on gate and edge
```

This is the crux. The CI gate rejected `WeldRejectA` (exit 1) citing **the exact same rule id**
`conformance.cross.weld-lobe` the runtime edge cited in C1 — same policy, same evaluator, same
verdict, one at design-time and one at runtime. The control spec `WeldAcceptA` passed (exit 0)
because `ElectrodeForce = 4.0` makes the antecedent false.

**C3 — single-source flip:**

```
[GATE] ===== C3: single-source flip — edit ONE governed policy, BOTH boundaries move =====
[GATE] ref=WeldRejectA equipment=Weld-Controller@1.0.0 setpoints=2
[GATE] PASS ?
[GATE] C3a: design-time gate now ACCEPTS the same spec (flip seen by the CI gate)
[GATE] C3 OK: one governed edit moved BOTH the CI gate and the runtime edge (single source of truth)
```

After the single `sed` edit of the governed policy (weld-lobe consequent `8.0 → 10.0`), the
previously-rejected `WeldRejectA` now **passes** the CI gate, and the same `WeldCurrent = 9` command
is now **applied** at the live edge. One byte-level change to one governed file moved both
boundaries. The Heimdall authorization `policy.json` was never touched.

**C4 — the governed dial:**

```
[GATE] ===== C4: dial — swap envelope -> recipe-mode (active WeldSchedule, tol 0) =====
[GATE] C4a: WeldCurrent=7 DENIED as a deviation from the approved recipe (conformance.recipe.deviation)
[GATE] C4 OK: the governed dial re-scoped the SAME node from envelope to exact-recipe conformance
```

Swapping to recipe mode re-scoped the same node: `WeldCurrent = 7` was denied as
`conformance.recipe.deviation` (approved setpoint is 9, tolerance 0) and `WeldCurrent = 9` applied.

**C5 — governed-policy lifecycle:**

```
[GATE] ===== C5: lifecycle — provenance publish/verify the governed policy bytes =====
[PUBLISH] recipe Weld-Controller-policy/1.0.0 defRef=12f33e96783ac3bd154375c39790d663bef9b04e sha256=0e136c921fa4d397af512d11561a0a436200cd2aba6ceb5e43cc1a74e76050d9
[PROV-GATE] verify ref=Weld-Controller-policy version=1.0.0 sha256=0e136c921fa4d397af512d11561a0a436200cd2aba6ceb5e43cc1a74e76050d9 => OK
[GATE] C5a: provenance verify accepted the clean published policy (exit 0)
[PROV-GATE] verify ref=Weld-Controller-policy version=1.0.0 sha256=fded7139c920ba98449fb6b4470e07f5b9913f06ac2cea7bb9275ead8c96082e manifest=0e136c921fa4d397af512d11561a0a436200cd2aba6ceb5e43cc1a74e76050d9 => MISMATCH (tampered or unresolvable)
[GATE] C5 OK: provenance verify accepted clean, rejected a one-byte tamper (governed-policy lifecycle)
```

Clean bytes verified `OK`; after appending one byte the recomputed sha256 no longer matched the
published manifest, so verify reported `MISMATCH` and exited 1. Note the sha256 values are specific
to the exact policy bytes — after C3's `8.0 → 10.0` edit the published policy is the *flipped*
version, so `0e136c9…` is the hash of that content. Your hash will match only if your bytes
(including line endings) are identical; `core.autocrlf false` in the throwaway repo keeps them
verbatim.

**Teardown + PASS:**

```
[GATE] step 9: teardown

[GATE] PASS run-composable-conformance-gate.sh
```

---

## 6. How to read a PASS

A full, correct run ends with **both** of these:

1. The final line `[GATE] PASS run-composable-conformance-gate.sh`.
2. Process **exit code 0**. Check it immediately after the run:

   ```bash
   bash scripts/run-composable-conformance-gate.sh; echo "exit=$?"
   # ... [GATE] PASS run-composable-conformance-gate.sh
   # exit=0
   ```

Because the script runs `set -euo pipefail` and every case calls `fail` (which prints
`[GATE] FAIL: …` plus log tails and `exit 1`) the moment an assertion is not met, a PASS line can
only appear if **all of C1–C5 passed**. There is no partial PASS.

What a case failure would mean:
- **C1 fail** — the runtime edge did not deny 9 (or did not apply 7): the composed cross-member rule
  is not being enforced at runtime, or the sim/edge did not wire up.
- **C2 fail** — the CI gate verdict diverged from the runtime verdict: **design ≠ runtime drift**,
  the exact failure this experiment exists to rule out.
- **C3 fail** — after editing the one governed policy, a boundary did not move: the two boundaries
  are not reading the same single source of truth.
- **C4 fail** — the dial swap did not re-scope the node.
- **C5 fail** — provenance publish/verify did not accept clean bytes or did not reject the tamper.

On any failure the script prints tails of the sim log and the C1/C3/C4 bridge logs and the pub log
(under `build/conformance-gate/`) to help you diagnose.

---

## 7. Troubleshooting

- **Docker not found / broker won't start.** The preflight prints
  `[GATE] FAIL: docker not found on PATH — Docker Desktop is required for the MQTT broker`. Start
  Docker Desktop and retry. If the broker never opens :1883 the script dumps
  `docker compose logs --tail 40 hivemq-ce`.
- **Port 1883 already in use.** Another broker (or a leftover HiveMQ container) is holding the port.
  Stop it: `docker ps` then `docker stop <id>`, or `docker compose -f docker-compose.yml stop
  hivemq-ce`. The gate binds the broker on host `:1883`.
- **Stale jars / code changes not reflected.** Step 0 only rebuilds when a jar is *missing*. If you
  changed source, force a clean rebuild before running the gate:

  ```bash
  mvn -pl core,sim,gates,heimdall clean install
  ```

- **Orphaned sim / edge processes from an aborted run.** The script mops these up on start and on
  exit via `jps -lm` + `taskkill` matched on `bifrost-sim.jar` / `bifrost-heimdall.jar`. If a run was
  killed hard, verify with `jps -lm` and kill any lingering `bifrost-*.jar` before retrying.
  (`$!` from an MSYS-backgrounded native `java -jar` does not reliably match the real Win32 PID — a
  known MSYS quirk — so these taskkills are best-effort; matching on the jar filename is the reliable
  path.)
- **Heimdall never reaches ready.** `start_bridge` fails with
  `Heimdall did not reach '[BRIDGE] ready' + '[BRIDGE] conformance loaded'`. Check the named bridge
  log (`build/conformance-gate/bridge-c1.log` etc.) — usually the broker or sim was not actually up,
  or a previous daemon still held the fixed clientId (the 2s `stop_bridge` sleep exists for this).
- **C5 hash mismatch on a clean run.** Line-ending translation changed the bytes. Ensure the
  throwaway repo keeps `core.autocrlf false` (the script sets it) so the committed blob is
  byte-verbatim.
- **Cleanup / fresh start.** The gate's own working state lives in `bifrost/build/conformance-gate/`
  and is `rm -rf`'d at the start of each run. To reclaim space or force a fully clean slate you can
  delete it between runs:

  ```bash
  rm -rf build/conformance-gate
  ```

  The registry the gate uses is a *copy* of the fixtures, so a run never mutates the committed
  fixtures under `scripts/fixtures/conformance/` (the C3 `sed` and C5 tamper both operate on the
  copy).
