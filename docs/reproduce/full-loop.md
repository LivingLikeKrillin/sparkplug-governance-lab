# Reproduce: Yggdrasil Full-Loop Gate (observe → command → observe)

> Gate script: `bifrost/scripts/run-yggdrasil-full-loop-gate.sh`
> Captured reference run: `docs/reproduce/outputs/full-loop.log`

This is the headline experiment of the lab. The runnable code lives in the sibling
`bifrost` repo; the lab consumes it as an external governance product. Everything below
is grounded in the actual gate script and its captured output — no invented steps.

---

## 1. What it proves

**One claim:** a command that survives the full governance boundary — schema + provenance
at design time, then per-command authorization *and* range conformance at the runtime
write boundary — actually moves the physical plant, and that change is observed back through
the northbound Unified Namespace (UNS). A command that fails governance never moves anything.

Concretely, on ONE HiveMQ broker and ONE OPC-UA sim, in a single run:

1. **Observe #1** — the UNS reports the mixer at `Rpm = 1535.0` (initial governed observation).
2. **Command** — an *authorized* Sparkplug `NCMD` of `Recipe/Rpm = 1500` is accepted at the
   write boundary (`[BRIDGE] APPLY ok=true`), written to OPC-UA, and the sim transfers the
   setpoint into the instance process value (`Line1/Mixer1.Rpm = 1500`).
3. **Observe #2** — the UNS now reports `Rpm = 1500.0`. **This is the closed loop:** the
   command changed what the northbound observer sees, `1535 → 1500`.
4. **Rogue DENY** — an `NCMD` to `Recipe/Secret` (a node with no allow-rule) is denied by
   default and *never* applied.
5. **Above-max DENY** — an `NCMD` of `Recipe/Rpm = 9999` is denied as above-max (defense in
   depth), no new APPLY is emitted, and the UNS still reads `Rpm = 1500.0` — a denied command
   does not move the plant.

The value `1535` is the sim's initial `Rpm` reading; `1500` is the authorized setpoint;
`9999` is the out-of-range probe; the accepted range `[0, 3000]` comes from the **governed
Mixer model** (`heimdall/registry/udt/Line1-Mixer/1.0.0.json`, member `Rpm.range = [0, 3000]`),
*not* a hand-authored limit — see §5, L5.

---

## 2. Prerequisites

You run the gate from the **`bifrost`** repo (sibling of this lab). You need:

- **JDK 17** on `PATH` (`java`, `jps` — the script uses `jps -lm` to find/kill stray JVMs).
- **Maven** (`mvn`) — the script builds the jars if they are missing.
- **Docker Desktop**, running. The gate starts a **HiveMQ CE** MQTT broker container that
  binds **host port `1883`** — that port must be free.
- **Git** — the provenance stage seeds a throwaway git repo to publish the recipe from.
- A **bash** shell. On Windows this is Git Bash / MSYS: the script uses `cygpath`, `taskkill`,
  and `/dev/tcp` port probing, so run it under Git Bash, not PowerShell or WSL.
- **Python** is part of the general lab toolchain but is **not** invoked by this particular
  gate; the loop is all JVM + Docker.

**Build first.** From the `bifrost` repo root, do a full reactor build once so all modules and
their test-verified state are current:

```bash
cd /path/to/bifrost
mvn install
```

The gate will lazily build only what it needs if jars are missing (see §4, step 1), but a
clean `mvn install` up front is the reliable starting point.

---

## 3. The command

```bash
cd /path/to/bifrost
bash scripts/run-yggdrasil-full-loop-gate.sh
```

The script `cd`s to its own repo root (`cd "$(dirname "$0")/.."`), so you may launch it from
anywhere as long as the path to the script is correct. It needs Docker Desktop running and
host port `1883` free.

Expected terminal ending:

```
[GATE] PASS run-yggdrasil-full-loop-gate.sh
```

with process **exit code `0`**.

---

## 4. What the gate stages, step by step

The script uses `set -euo pipefail` (fail fast) and installs an `EXIT` trap (`cleanup`) that
kills the sim / Heimdall / muninn JVMs and stops the broker container on any exit — so a
failed run does not leak processes. All working artifacts land under `build/loopgate/` (`$WORK`).

### Step 1 — preflight + build (5 jars)

Verifies `docker` is on `PATH`. If any of `gates`, `sim`, or `heimdall` jars are missing it runs
`mvn -q -pl core,sim,gates,heimdall install`; it also `mvn -q package`s the two sibling repos
`../mimir` and `../muninn` if their jars are absent. The five jars composed here are:

| Jar | Role |
|-----|------|
| `../mimir/target/mimir.jar` | model the equipment type (northbound, design-time `derive`) |
| `gates/target/bifrost-gates.jar` | govern: schema gate ① + provenance gate ③ |
| `sim/target/bifrost-sim.jar` | embedded OPC-UA sim (`Recipe/*` setpoints + `Line1/Mixer1` PV; internal setpoint→PV transfer) |
| `heimdall/target/bifrost-heimdall.jar` | southbound write boundary: authorization ② → OPC-UA write |
| `../muninn/target/muninn.jar` | northbound feeder/observer (NBIRTH/NDATA over Sparkplug B) |

It then kills any stray `bifrost-sim` / `bifrost-heimdall` / `muninn` JVMs from earlier runs and
removes leftover `bifrost-hivemq-ce` / `muninn-hivemq-ce` containers. Windows-native paths for
every jar and for `docker-compose.yml` are computed via `cygpath -m`. It resolves and asserts the
**runtime policy fixture** `heimdall/registry/policy.json` exists (`POLICY_PATH`).

### Step 2 — start ONE HiveMQ CE broker + the sim

Runs `docker compose -f docker-compose.yml up -d hivemq-ce`. The compose file pins
`hivemq/hivemq-ce:latest` with `HIVEMQ_ALLOW_ALL_CLIENTS=true` (HiveMQ CE 2026.x refuses all
MQTT clients without a security extension; this env var enables the bundled allow-all extension
for local dev). It then probes `localhost:1883` up to 30 times (2 s apart) via bash `/dev/tcp`
until the port opens, printing `[GATE] HiveMQ CE up on :1883`.

It wipes and recreates `build/loopgate/{registry,srcrepo,out}` and writes a **staging** compat
policy the schema gate reads: `build/loopgate/registry/policy.json` = `{"mode":"FORWARD"}`
(this is the *schema-compatibility* mode file, distinct from the runtime authorization
`heimdall/registry/policy.json`).

It launches the sim jar (stdout/stderr → `build/loopgate/sim.log`), records its PID, and waits
(up to 30 × 1 s) for the line `OPC-UA sim listening`, printing
`[GATE] OPC-UA sim listening (pid …)`.

### Step 3 — govern the MODEL (schema ① + provenance ③)

This populates a fresh governed registry from the live sim:

1. **Mimir derive** — `mimir.jar derive opc.tcp://localhost:48400 urn:bifrost:opcua:sim
   MixerType Line1-Mixer 1.0.0 …/def.json` introspects the sim's OPC-UA address space and emits
   a candidate UDT definition. (In the captured log it reports 4 members with their ranges:
   `Rpm [0,3000]`, `Temp [0,450]`, `Running`/`Secret` unranged.)
2. **Schema gate** — `bifrost-gates.jar schema <registry> def.json --promote` validates the
   candidate against the compat policy and, being an initial registration, promotes it to
   `build/loopgate/registry/udt/Line1-Mixer/1.0.0.json`.
3. **Provenance publish** — the promoted UDT is copied into `build/loopgate/srcrepo/`, a
   throwaway git repo is `init`/`add`/`commit`ted there (`core.autocrlf false`, identity
   `gate@local`), then `bifrost-gates.jar provenance publish …` mints the recipe at
   `build/loopgate/registry/recipe/Line1-Mixer/1.0.0/recipe-setpoints.yaml`, binding it to the
   committed source by `defRef` + `sha256`.

Prints `[GATE] registry populated (udt + recipe) for Line1-Mixer@1.0.0`.

### Step 4 — start the Heimdall daemon (southbound write boundary)

Exports the daemon's environment and launches `bifrost-heimdall.jar`
(stdout/stderr → `build/loopgate/bridge.log`), waiting up to 45 × 2 s for `[BRIDGE] ready`.
The env it sets:

| Env var | Value | Purpose |
|---------|-------|---------|
| `MQTT_URL` | `tcp://localhost:1883` | broker |
| `OPCUA_URL` | `opc.tcp://localhost:48400` | sim endpoint to write |
| `SPB_GROUP` | `Bifrost:Line1` | Sparkplug group id |
| `SPB_EDGE` | `recipe-edge` | Sparkplug edge node id |
| `POLICY_PATH` | `heimdall/registry/policy.json` | ② per-command **authorization** rules (deny-by-default) |
| `REGISTRY_PATH` | `heimdall/registry` | governed registry root |
| `CONFORMANCE_PATH` | `heimdall/registry/conformance/Line1-Mixer/1.0.0.json` | ② **range conformance** binding |

Two design points to note here:

- `POLICY_PATH` (`heimdall/registry/policy.json`) is the runtime authorization file. It has
  allow-rules only for `Recipe/Rpm`, `Recipe/Temp`, and `Recipe/ApplyRecipe` for principal
  `recipe-writer` on target group `Bifrost:Line1` / edge `recipe-edge`, with `"default": "deny"`.
  There is **no** rule for `Recipe/Secret` — that is what makes L4's rogue a deny-by-default.
- `CONFORMANCE_PATH` activates the composable range check: the conformance file binds
  OPC node `ns=2;s=Recipe/Rpm` to member `Rpm`, and the range `[0, 3000]` is read from the
  **governed model** (`udt/Line1-Mixer/1.0.0.json`), not from `policy.json`. That is why L5's
  above-max bound is model-derived.

Note this gate does **not** enable signed/authorized-activation (no `REQUIRE_SIGNED_ACTIVATION`,
no `ACTIVATION_TARGET`, no `authorized-keys`); it exercises the authorization ② + conformance
axis of the write boundary, which is the axis the loop needs.

The script defines two small helpers used by the assertions:
- `pub <node> <value> <type>` — publishes a raw Sparkplug `NCMD` by running the
  `RogueNcmd` main class off the Heimdall jar (an unsigned/unprivileged client). It is used for
  BOTH the authorized command and the rogue probes; whether it is honored is decided entirely by
  Heimdall's policy + conformance, not by the publisher.
- `apply_count <node>` — counts `[BRIDGE] APPLY cmd=<node>` lines in `bridge.log`.

The `observe_and_feed <tag>` helper runs one full observe+feed cycle: it starts a
`muninn.jar observe` subscriber (waiting for `[OBSERVE] subscribed`), then runs `muninn.jar feed`
which reads the OPC-UA instance through the governed registry and emits NBIRTH/NDATA over
Sparkplug B; it captures the `Rpm=` line into `build/loopgate/out/values-<tag>.txt`.

### Steps L1–L5 — the assertions

Run in order against the live loop (detailed in §5).

### Step 9 — teardown

Kills the sim / Heimdall JVMs (by PID and by main-class), kills any muninn JVMs, and
`docker compose stop hivemq-ce`. Then prints the PASS line and `exit 0`. (The `EXIT` trap runs
the same cleanup again defensively.)

---

## 5. Expected output, annotated

Below are the real lines from `docs/reproduce/outputs/full-loop.log`, with what each assertion
actually checks.

```
[GATE] step 1: preflight + build (5 jars)
[GATE] step 2: start ONE HiveMQ CE broker + sim
[GATE] HiveMQ CE up on :1883
[GATE] OPC-UA sim listening (pid 48732)
```
Broker port `1883` opened and the OPC-UA sim reached its listening line. (The `pid` is
whatever the JVM got on your machine.)

```
[GATE] step 3: govern the MODEL (schema ① + provenance ③) to populate the registry
[MIMIR] derived Line1-Mixer@1.0.0 members=4 -> …/build/loopgate/def.json
[MIMIR]   Rpm : Double range=[0.0, 3000.0]
[MIMIR]   Temp : Double range=[0.0, 450.0]
[MIMIR]   Running : Boolean range=null
[MIMIR]   Secret : Double range=null
[GATE] new templateRef 'Line1-Mixer' 1.0.0 … initial registration allowed …
[GATE] promoted to registry
[PUBLISH] recipe Line1-Mixer/1.0.0 defRef=87c85da4… sha256=1fee6fba…
[GATE] registry populated (udt + recipe) for Line1-Mixer@1.0.0
```
The `SLF4J(W): No SLF4J providers…` lines above these in the raw log are harmless NOP-logger
warnings, not errors. The derived `Rpm range=[0.0, 3000.0]` is the origin of the `[0, 3000]`
bound enforced later. The `defRef`/`sha256` are the provenance binding of the recipe to its
committed source.

```
[GATE] step 4: start the Heimdall daemon (southbound write boundary)
[GATE] Heimdall ready (pid 48783)
```
The write boundary is up, policy + conformance loaded.

```
[GATE] ===== L1 OBSERVE#1 (before): NDATA Rpm == 1535.0 =====
[GATE] L1 OK: initial UNS observation Rpm=1535.0
```
**L1 — observe before.** Runs `observe_and_feed 1` and asserts `values-1.txt` begins with
`Rpm=1535`. This is the baseline northbound observation before any command. `1535.0` is the
sim's initial mixer reading.

```
[GATE] ===== L2 COMMAND: authorized NCMD Recipe/Rpm=1500 -> APPLY + transfer =====
[GATE] L2 OK: authorized command applied + transferred to the instance PV
```
**L2 — authorized command.** `pub ns=2;s=Recipe/Rpm 1500 Double` publishes the NCMD. The gate
then waits (10 × 2 s) for `[BRIDGE] APPLY cmd=ns=2;s=Recipe/Rpm ok=true` in `bridge.log`
(authorization ② passed and the OPC-UA write succeeded), AND waits (10 × 1 s) for
`[SIM] transfer Line1/Mixer1.Rpm = 1500` in `sim.log` (the sim transferred the setpoint into the
instance process value). Both must appear. This is the command physically taking effect.

```
[GATE] ===== L3 OBSERVE#2 (after): NDATA Rpm == 1500.0 — THE CLOSED LOOP =====
[GATE] L3 OK: the governed+authorized command changed the UNS observation 1535 -> 1500
```
**L3 — observe after = closed loop.** Runs `observe_and_feed 2` and asserts `values-2.txt`
begins with `Rpm=1500`. The northbound UNS now reflects the commanded value. Observe→command→
observe is closed: the same channel that read `1535` now reads `1500` **because of** the
authorized command.

```
[GATE] ===== L4 rogue deny-by-default: Recipe/Secret -> DENY, never APPLY =====
[GATE] L4 OK: rogue node denied, never applied
```
**L4 — rogue DENY.** `pub ns=2;s=Recipe/Secret 1.0 Double`, sleep 3 s, then assert
`[BRIDGE] DENY cmd=ns=2;s=Recipe/Secret` **is** present and `[BRIDGE] APPLY cmd=ns=2;s=Recipe/Secret`
is **not**. `Recipe/Secret` has no allow-rule in `policy.json` and `"default":"deny"`, so it is
rejected without ever being written. Proves deny-by-default.

```
[GATE] ===== L5 defense-in-depth: Recipe/Rpm=9999 -> DENY above-max, UNS unchanged =====
[GATE] L5 OK: out-of-range command denied; UNS observation still Rpm=1500.0
```
**L5 — above-max DENY, UNS unchanged.** Records `apply_count(Recipe/Rpm)` as `APPLY_BEFORE`,
publishes `Recipe/Rpm = 9999`, sleeps 3 s, then asserts:
- `[BRIDGE] DENY cmd=ns=2;s=Recipe/Rpm … above-max` is present — the `9999` exceeds the
  model-derived range high `3000`, so conformance ② rejects it even though `Recipe/Rpm` is an
  *authorized* node (defense in depth: authorization alone would allow it, conformance catches it);
- `apply_count(Recipe/Rpm)` is unchanged (`APPLY_AFTER == APPLY_BEFORE`) — no new write happened;
- `observe_and_feed 3` still yields `Rpm=1500` in `values-3.txt` — a denied command did **not**
  move the plant.

```
[GATE] step 9: teardown

[GATE] PASS run-yggdrasil-full-loop-gate.sh
```

---

## 6. How to read a PASS

A PASS is exactly two things together:

1. Final stdout line `[GATE] PASS run-yggdrasil-full-loop-gate.sh`.
2. Process **exit code `0`** (check with `echo $?` right after the run).

Because the script is `set -euo pipefail` with a `fail()` that `exit 1`s, any failed assertion
aborts immediately with a `[GATE] FAIL: …` line, then dumps the tails of `sim.log`, `bridge.log`,
and each `feed-*.log` / `observe-*.log` to help diagnose. What a failure at each stage means:

- **L1 fails** — the initial observation was not `1535`. The observe/feed path through muninn +
  registry is broken, or the sim did not start with its expected initial value.
- **L2 fails** — either no `[BRIDGE] APPLY … ok=true` (authorization denied the command, or the
  OPC-UA write failed) or no `[SIM] transfer …=1500` (the setpoint→PV transfer is not wired).
- **L3 fails** — the command applied but the UNS still doesn't read `1500`: the loop did not
  close; the northbound observation isn't reflecting the plant.
- **L4 fails** — either the rogue `Recipe/Secret` was NOT denied, or worse, it was APPLIED —
  a deny-by-default breach.
- **L5 fails** — either `9999` was not denied as above-max (conformance not enforcing the
  governed range), or an APPLY slipped through, or the UNS moved off `1500` after a denied
  command.

---

## 7. Troubleshooting

- **`[GATE] FAIL: docker not found on PATH`** — Docker CLI isn't visible to the shell. Start
  Docker Desktop and re-open the shell.
- **Broker won't come up / `HiveMQ CE did not open :1883`** — the script probes for 60 s
  (30 × 2 s) then dumps `docker compose logs`. Usual causes: Docker Desktop still starting, or
  the first-ever `hivemq/hivemq-ce:latest` pull is slow. Re-run once the image is cached.
- **Port `1883` busy** — something else owns the MQTT port (a prior broker, Mosquitto, a
  leftover container). Free it: `docker rm -f bifrost-hivemq-ce muninn-hivemq-ce`, and stop any
  local broker using `1883`.
- **Broker slow to start** — the port probe already waits up to 60 s and the Heimdall wait is
  up to 90 s (45 × 2 s); on a cold machine just let it run. If it still times out, check
  `docker compose logs hivemq-ce`.
- **Stale jars after a code change** — the script only rebuilds when a jar is *missing*, so if
  you edited source but the jars already exist, force a rebuild before running:
  ```bash
  mvn -q -pl core,gates,heimdall,sim -am install -DskipTests
  ```
  (and `mvn -q package` in `../mimir` / `../muninn` if you touched those).
- **Leftover `java` / sim / Heimdall processes from a killed run** — the `EXIT` trap normally
  cleans up, but if you `Ctrl-C`'d hard, clear them by main class:
  ```bash
  jps -lm | grep -Ei 'bifrost-sim|bifrost-heimdall|muninn'   # find PIDs
  # then taskkill //F //PID <pid> for each (Git Bash on Windows)
  docker rm -f bifrost-hivemq-ce muninn-hivemq-ce            # drop stray broker containers
  ```
- **Inspect a failure** — the per-stage logs live under `build/loopgate/`: `sim.log`,
  `bridge.log`, `pub.log`, and `out/values-{1,2,3}.txt` (the captured `Rpm=` observations).
```
