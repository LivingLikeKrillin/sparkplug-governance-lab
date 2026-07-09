# Yggdrasil Full-Loop — closing the governance loop (observe → command → observe)

**Date:** 2026-07-09
**Track:** Yggdrasil governance spine (follow-on to the northbound spine, which is COMPLETE and proven by `run-yggdrasil-spine-gate.sh`).
**Status:** design — approved to proceed to plan.

## 1. Purpose

The northbound spine (Mímir → Bifrost → Muninn) is proven: a governed equipment model flows to the UNS. The southbound write-boundary (Heimdall) is proven in isolation: an authorized NCMD is applied to the sim, rogue/out-of-range commands are denied deny-by-default.

But the two axes are **not yet a closed loop**. Today they coexist on ONE sim/broker/registry, yet the southbound WRITE target (`ns=2;s=Recipe/Rpm`, writable) and the northbound OBSERVE target (`ns=2;s=Line1/Mixer1.Rpm`, read-only) are **different sim nodes** — so an applied command never shows up in Muninn's next NDATA.

**Goal:** with ONE new integration gate, prove that a **governed + authorized command changes the UNS observation** — i.e. Heimdall applies an authorized setpoint, and Muninn's *next* NDATA reflects the change. This proves **governance closes the loop** (observe → command → observe). It is explicitly **not** a proof of process physics.

## 2. Scope

**In scope (3 repos touched, all small/additive):**
1. **bifrost/sim** — internal transfer wiring (Option B): a write to `Recipe/Rpm`/`Recipe/Temp` also updates the corresponding `Line1/Mixer1.*` instance node.
2. **muninn** — the observer captures NDATA metric *values* (not only names), so the gate can assert the value change.
3. **bifrost/scripts** — a new `run-yggdrasil-full-loop-gate.sh` composing sim + gates + Heimdall (southbound) + Muninn (northbound) into the closed-loop proof; plus unifying the Sparkplug group id to `Bifrost:Line1` across the gates.

**Out of scope (deliberate non-goals, stated as honest limitations):**
- Process physics / control dynamics (see §7).
- Runtime spec-conformance at the write boundary (the "composable runtime conformance" idea — its own future track).
- Full Sparkplug session semantics (bdSeq/NDEATH/LWT/STATE/rebirth) — the current one-shot feeder is retained (see §7 for the extensibility note).
- Re-proving the 5 northbound spine assertions — the spine gate already owns those; the full-loop gate is focused on the loop + deny reaffirmation.

## 3. Design decision: Option B (internal transfer), not Option A (node unification)

Option A (retarget Heimdall/ncmd and Muninn to the same physical node) was rejected: retargeting the proven Heimdall/ncmd path is high-regression. Option B keeps both paths untouched and adds a sim-internal transfer that models "setpoint command → PV settles to setpoint":

- On a client write to `Recipe/Rpm` (the southbound setpoint node, `ubyte(3)` writable), the sim **also** sets `Line1/Mixer1.Rpm` (the northbound PV node, `ubyte(1)` client-read-only) to the same value. Same for `Temp`.
- The instance node stays **client-read-only**; the update is a sim-internal action, not a client write.
- The transfer is **synchronous and immediate** (SP == PV instantly): no ramp, no dynamics, no background thread — fully deterministic. This is a design choice, not a limitation to hide: the loop being proven is the *governance data path*, and emulating physics would add a polling thread + timing non-determinism to the gate harness with zero contribution to the thesis.
- Fires **only on write**, so the initial seed (Rpm=1535) is unchanged until a command lands → the Chunk-2 `MixerTypeNodeTest` and the ncmd gate do not regress.

## 4. Component design

### 4.1 sim — internal transfer (`EmbeddedMiloSim`)

The `Recipe/Rpm` node already has an `AttributeObserver` (currently logs `[SIM] SET ...`). Extend it (and add one for `Recipe/Temp`) so that on a `Value` write it lazily resolves the instance node from the node manager and copies the value:

```
resolve Line1/Mixer1.Rpm via getNodeManager().get(newNodeId("Line1/Mixer1.Rpm"))
   → setValue(new DataValue(new Variant(v)))
   → log "[SIM] transfer Line1/Mixer1.Rpm = <v>"
```

- **Lazy resolution at write-time** (not a captured reference), because the instance nodes are created after the Recipe nodes in `createNodes()`. Lazy lookup avoids reordering and is robust.
- **Value unwrap:** the observer's `value` arg may arrive as a `DataValue` or a raw value — reuse the exact unwrap idiom already in the `Recipe/Rpm` SET observer (a few lines above in `createNodes()`), not a bare `new Variant(value)`.
- A distinct `[SIM] transfer ...` log line gives the gate a witness for the transfer step, separate from the existing `[SIM] SET ...`.
- **Units/type:** Heimdall writes `Recipe/Rpm` as a `Double`; the instance member is a `Double`; the transfer copies the `Double` verbatim.

**TDD:** extend `MixerTypeNodeTest` — a client writes `Recipe/Rpm=1500`, then reads `Line1/Mixer1.Rpm` and asserts it reflects 1500. Assert the initial seed (1535) is present before any write (no-regression anchor).

### 4.2 muninn — observer captures values (`MuninnConsumer`)

The observer currently writes only `metric.getName()` per NDATA metric. Add value capture **without breaking the existing name-only contract** (the spine gate greps `^Rpm$` on `ndata.txt`):

- Keep `ndata.txt` (names only) unchanged → spine gate no-regression.
- Emit an **additional** values file, one `name=value` line per NDATA metric (e.g. `Rpm=1500.0`). The full-loop gate greps `^Rpm=` on it for the value assertion.
- **Non-breaking wiring (mandatory):** the values file is opt-in via a NEW **optional** `observe` flag `--ndata-values <file>` (plumbed as an optional, nullable `MuninnConsumer` constructor param). When the flag is absent — as in the existing spine/muninn gates — no values file is written and their `observe <mqtt> <group> <birthOut> <ndataOut> --expect-ndata N` invocation is byte-for-byte unchanged. Only the new full-loop gate passes `--ndata-values`. Do NOT add a required positional arg (that would shift the spine gate's positional args and break it).
- When the flag IS present, the values file is truncated/created-empty at construction, same discipline as the existing two out files.

**Rationale for A over feed-side logging:** the closed-loop thesis is that the command changes the **UNS observation**; the UNS consumer (the observer) directly capturing the value change is the most faithful proof.

**TDD:** a `MuninnConsumer` test (extend or new) asserting an NDATA metric's value is written to the values file as `name=value`.

### 4.3 group-id unification → `Bifrost:Line1`

Today Heimdall (southbound NCMD) uses group `Bifrost:Line1` (colon); Muninn (northbound NDATA) uses `Bifrost-Line1` (hyphen). They are the same logical Line1, and the split is a cosmetic inconsistency that undermines the demo's credibility.

Unify to **`Bifrost:Line1`** (the code-anchored, hierarchy-convention `enterprise:site:...` name):
- Blast radius is minimal in this direction: `Bifrost-Line1` lives only in shell-script variables (`run-yggdrasil-spine-gate.sh`, `muninn/scripts/run-muninn-gate.sh`) — muninn code takes `group` as a CLI arg (zero hardcoding). Change those two `GROUP=` variables + use `Bifrost:Line1` in the new gate.
- Heimdall side (code defaults, 4 tests, policy fixtures) stays as-is → no code/test churn.
- **Verified safe:** muninn's `MqttPublisher.topic` / `MuninnConsumer` only concatenate `group` into the topic; a colon is a legal MQTT topic-level character and the ncmd gate already uses it against the same broker.
- **Re-verify:** re-run `run-yggdrasil-spine-gate.sh` and `run-muninn-gate.sh` after the change (both already exercised by this track).

### 4.4 `run-yggdrasil-full-loop-gate.sh` (new)

Composes the sim, gates jar, Heimdall daemon (southbound), and Muninn (northbound) on ONE broker. One-edge/one-instance/localhost, matching the spine gate's discipline (MSYS-safe process management, readiness handshakes, `jps -lm` kill-by-jar).

```
step 1  preflight + build 5 jars (sim, gates, heimdall | mimir, muninn); docker required
step 2  start ONE HiveMQ CE broker (wait :1883) → start sim (wait "OPC-UA sim listening")
step 3  MODEL govern (populate the registry so muninn feed can provenance-verify):
        mimir derive → gates schema --promote → provenance publish (promoted udt bytes,
        autocrlf=false throwaway repo). Reuse of the spine gate's happy-path govern; NO
        reject legs, NO spec conformance leg (those belong to the spine gate).
step 4  start Heimdall daemon (SPB_GROUP=Bifrost:Line1, POLICY_PATH=heimdall/registry/
        policy.json) → wait "[BRIDGE] ready"
─── OBSERVE #1 (before) ───
step 5  muninn observe#1 (Bifrost:Line1, --ndata-values) → muninn feed#1
        assert: NDATA Rpm=1535.0 (values file)
        (NBIRTH byte-equality is NOT re-asserted here — it is the spine gate's contract, §2 out-of-scope.)
─── COMMAND ───
step 6  RogueNcmd ns=2;s=Recipe/Rpm 1500 Double
        assert: [BRIDGE] APPLY cmd=...Recipe/Rpm ok=true
              + [SIM] SET ns=2;s=Recipe/Rpm = 1500(.0)
              + [SIM] transfer Line1/Mixer1.Rpm = 1500(.0)
─── OBSERVE #2 (after) = THE closed-loop proof ───
step 7  muninn observe#2 → muninn feed#2
        assert: NDATA Rpm=1500.0
        ⇒ a governed + authorized command changed the UNS observation 1535 → 1500.
─── deny reaffirmation ───
step 8  rogue:  RogueNcmd ns=2;s=Recipe/Secret ... → [BRIDGE] DENY, never APPLY
        d-i-d:  RogueNcmd ns=2;s=Recipe/Rpm 9999   → [BRIDGE] DENY above-max, no new APPLY
        negative loop proof: muninn feed#3 still shows Rpm=1500.0 (denied command did NOT
        move the UNS).
step 9  teardown (kill sim/heimdall/muninn by jar; stop broker)
```

**Assertions (the gate's contract):**
- (L1) OBSERVE #1 NDATA Rpm == 1535.0 (initial governed observation).
- (L2) authorized NCMD Rpm=1500 → APPLY ok=true, witnessed by `[SIM] SET` and `[SIM] transfer`.
- (L3) OBSERVE #2 NDATA Rpm == 1500.0 — **the closed loop**.
- (L4) rogue `Recipe/Secret` → DENY, never APPLY (deny-by-default).
- (L5) `Recipe/Rpm=9999` → DENY above-max, no new APPLY (defense-in-depth), and OBSERVE #3 still Rpm==1500.0 (denied command did not move the UNS).

## 5. Data flow (closed loop)

```
RogueNcmd ──NCMD(Bifrost:Line1)──▶ Heimdall ②authz ──OPC write──▶ sim Recipe/Rpm
                                                                       │ (internal transfer)
                                                                       ▼
                                                                 Line1/Mixer1.Rpm
                                                                       │ OPC read
                                                                       ▼
Muninn feed ──egress-validate──▶ NDATA(Bifrost:Line1) ──▶ Muninn observe ──▶ Rpm=1500.0
```

## 6. Error handling / determinism

- **Ordering is enforced by sequential one-shot invocations:** feed#1 completes → command applied + witnessed → feed#2. No concurrency between the before/after observations.
- **Deterministic by construction:** synchronous transfer, one-shot feeder, one sample per member, static seeds. No polling thread, no timing race.
- The gate uses the spine gate's proven MSYS process-management + readiness-handshake idioms verbatim.

## 7. Honest limitations (verbatim material for the Ep5 blog)

- **Proves governance closes the loop, not process physics.** The transfer is instant setpoint==PV (no ramp, dynamics, delay, or overshoot). A real PV converges over physical time via the control loop; that is the PLC/control domain, not this governance proof. Emulating it would add a polling thread + timing non-determinism to the harness with no governance payoff.
- **transfer-on-write, not commit-gated.** The PV updates on the `Recipe/Rpm` write directly, not gated behind the `ApplyRecipe` rising-edge commit handshake. Commit-gating (staged setpoint → committed on activate) is a future seam.
- **Runtime range IS enforced at the write boundary — but from a SEPARATE hand-authored source, not the governed spec.** Correction (verified against the shipped code): Heimdall ② does more than authz-"who" — `CommandAuthorizer` also enforces a per-command `min`/`max` `constraint` from `policy.json` at runtime (L5's `above-max` DENY is exactly this). What is NOT yet done is *composing* that runtime bound from the governed equipment model: design-time `gates spec` reads the equipment EURange, while Heimdall reads an independently hand-maintained number in `policy.json` — two separate range sources that are not derived from one governed rule-set. Unifying them (one governed contract evaluated at BOTH design-time and the runtime write boundary) is the "composable runtime conformance" future track. Do NOT claim "Heimdall checks who only" — that contradicts the gate's own L5.
- **Sparkplug session semantics still absent, but the skeleton is additively extensible — not a rewrite.** Muninn remains a one-shot feeder (one NBIRTH seq=0 + one NDATA seq=1). However: `SparkplugCodec.buildNdata(list, seq)` already parameterizes the sequence number; `buildNbirth` needs only an added `bdSeq` metric; `MqttPublisher` needs only a `setWill(...)` call for NDEATH-as-LWT. What is genuinely new is a stateful `EdgeNodeSession` orchestrator (bdSeq/monotonic seq/birth-metric-set for rebirth, STATE subscription, rebirth-NCMD handling, report loop) layered **on top of** the existing (reused) codec/publisher/validator — a clean additive layer, or a swap to Tahu's higher-level edge-node API with the governance metrics plugged in.
- **Single broker / single edge / single instance / localhost / one sample per member.**

## 8. Verification (controller-direct — the #1 rule for this portfolio)

Done-bit is controller-run, never a subagent's PASS claim:
- `mvn install` green at bifrost root (all modules incl. the extended `MixerTypeNodeTest`).
- `mvn install`/`package` green in muninn (incl. the extended `MuninnConsumer` test).
- `run-yggdrasil-full-loop-gate.sh` → `[GATE] PASS` with all of L1–L5 observed, run BY the controller.
- No-regression: re-run `run-yggdrasil-spine-gate.sh`, `run-muninn-gate.sh`, `run-ncmd-runtime-gate.sh` → all still `[GATE] PASS`.

All repos remain LOCAL / unpushed. bifrost is public — `feat/yggdrasil-spine` stays local; no push/PR/merge without Eisen's explicit OK.
