# Yggdrasil Full-Loop Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove a governed + authorized command changes the northbound UNS observation (observe → command → observe), by wiring a sim-internal setpoint→PV transfer, teaching Muninn's observer to capture NDATA values, and composing Heimdall (southbound) + Muninn (northbound) into one closed-loop gate.

**Architecture:** Option B (sim-internal transfer): a client write to `Recipe/Rpm`/`Temp` (Heimdall's southbound target) synchronously copies the value to the `Line1/Mixer1.*` instance node (Muninn's northbound observe target). No node retargeting, no dynamics thread — synchronous, deterministic. Muninn's observer gains an opt-in `--ndata-values` file so the gate can assert the value change. The Sparkplug group id is unified to `Bifrost:Line1`. A new `run-yggdrasil-full-loop-gate.sh` composes both axes on one broker.

**Tech Stack:** Java 17, Eclipse Milo 1.0.0 (OPC-UA server/client), Eclipse Tahu 1.0.14 (Sparkplug B), Paho v3 (MQTT), HiveMQ CE (broker, docker), JUnit 5, Maven, Bash/MSYS gate scripts.

**Repos touched (all LOCAL/unpushed — no push without explicit OK):**
- `bifrost` (branch `feat/yggdrasil-spine`) — sim transfer, spine-gate group var, new full-loop gate.
- `muninn` (branch `master`) — observer value capture, muninn-gate group var.

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-09-yggdrasil-full-loop-design.md`

---

## File Structure

| File | Repo | Responsibility | Change |
|------|------|----------------|--------|
| `sim/src/main/java/dev/krillin/bifrost/sim/EmbeddedMiloSim.java` | bifrost | OPC-UA sim; add setpoint→PV transfer on `Recipe/Rpm`/`Temp` write | Modify |
| `sim/src/test/java/dev/krillin/bifrost/sim/MixerTypeNodeTest.java` | bifrost | TDD: write `Recipe/Rpm` → assert `Line1/Mixer1.Rpm` reflects it | Modify |
| `muninn/src/main/java/dev/krillin/muninn/MuninnConsumer.java` | muninn | Observer; opt-in `name=value` capture to a values file | Modify |
| `muninn/src/main/java/dev/krillin/muninn/MuninnMain.java` | muninn | `observe` CLI; add optional `--ndata-values <file>` | Modify |
| `muninn/src/test/java/dev/krillin/muninn/NdataValueLineTest.java` | muninn | TDD: an NDATA metric round-trips to `name=value` | Create |
| `scripts/run-yggdrasil-spine-gate.sh` | bifrost | Spine gate; `GROUP` var → `Bifrost:Line1` | Modify |
| `muninn/scripts/run-muninn-gate.sh` | muninn | Muninn gate; `GROUP` var → `Bifrost:Line1` | Modify |
| `scripts/run-yggdrasil-full-loop-gate.sh` | bifrost | NEW closed-loop gate composing both axes | Create |

---

## Chunk 1: sim internal transfer

### Task 1: sim setpoint→PV internal transfer

**Files:**
- Modify: `bifrost/sim/src/main/java/dev/krillin/bifrost/sim/EmbeddedMiloSim.java` (the `Recipe/Rpm` observer at ~136-144, the `Recipe/Temp` node at ~146, add a private helper)
- Test: `bifrost/sim/src/test/java/dev/krillin/bifrost/sim/MixerTypeNodeTest.java`

**Context for the implementer:**
- The observer lambda already unwraps `DataValue` → raw value (lines 139-141). REUSE that exact idiom; do not `new Variant(value)` on the raw arg.
- Instance nodes (`Line1/Mixer1.Rpm` etc.) are created *after* the Recipe observers are wired, so resolve them **lazily at write-time** via the node manager: `getNodeManager().getNode(newNodeId("Line1/Mixer1.Rpm"))` returns `Optional<UaNode>` (verified against milo-sdk-server 1.0.0). `getNodeManager()` and `newNodeId(String)` are both in scope inside `SimNamespace`.
- `UaVariableNode.setValue(...)` is a server-side mutation and bypasses the client access-level (`ubyte(1)` read-only) — same mechanism the `ApplyDone` handshake already uses.
- All imports needed (`AttributeId`, `DataValue`, `Variant`, `UaVariableNode`, `NodeId`) are already present in the file. `UaNode` is `org.eclipse.milo.opcua.sdk.server.nodes.UaNode` — add that import.

- [ ] **Step 1: Add the `writeDouble` test helper**

In `MixerTypeNodeTest.java`, next to the existing `writeBoolean` helper (line ~146), add:

```java
    private static void writeDouble(OpcUaClient client, String nodeId, double value) throws Exception {
        client.writeValues(List.of(NodeId.parse(nodeId)), List.of(new DataValue(new Variant(value))));
    }
```

- [ ] **Step 2: Write the failing test**

Add this test method to `MixerTypeNodeTest.java`:

```java
    @Test
    void writingRecipeSetpointTransfersToInstancePv() throws Exception {
        try (EmbeddedMiloSim sim = new EmbeddedMiloSim().start()) {
            OpcUaClient client = OpcUaClient.create("opc.tcp://localhost:" + EmbeddedMiloSim.BIND_PORT);
            client.connect();
            try {
                // Baseline: instance PV holds its seeded value before any setpoint write.
                assertEquals(1535.0, readValue(client, "ns=2;s=Line1/Mixer1.Rpm"));
                assertEquals(200.0, readValue(client, "ns=2;s=Line1/Mixer1.Temp"));

                // Write the southbound setpoint -> the sim transfers it to the northbound instance PV.
                writeDouble(client, "ns=2;s=Recipe/Rpm", 1500.0);
                assertEquals(1500.0, readValue(client, "ns=2;s=Line1/Mixer1.Rpm"));

                writeDouble(client, "ns=2;s=Recipe/Temp", 250.0);
                assertEquals(250.0, readValue(client, "ns=2;s=Line1/Mixer1.Temp"));

                // The other members are untouched by the Rpm/Temp transfer.
                assertEquals(Boolean.TRUE, readValue(client, "ns=2;s=Line1/Mixer1.Running"));
                assertEquals(42.0, readValue(client, "ns=2;s=Line1/Mixer1.Secret"));
            } finally {
                client.disconnect();
            }
        }
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run (from `bifrost/`): `mvn -q -pl core,sim test -Dtest=MixerTypeNodeTest#writingRecipeSetpointTransfersToInstancePv`
Expected: FAIL — `Line1/Mixer1.Rpm` still 1535.0 after the write (no transfer wired yet).

- [ ] **Step 4: Add the transfer helper to `SimNamespace`**

In `EmbeddedMiloSim.java`, add the import near the other `sdk.server.nodes` imports:

```java
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
```

Add this private method to `SimNamespace` (e.g. just after `createNodes()`):

```java
        /**
         * Internal setpoint -> PV transfer (models "command applied, PV settles to setpoint",
         * instant/no-dynamics). Resolves the instance node lazily at write-time (it is created
         * after the Recipe observers are wired) and sets it server-side, bypassing the instance's
         * client-read-only access level (same mechanism as the ApplyDone handshake).
         */
        private void transferToInstance(String instanceIdentifier, Object value) {
            getNodeManager().getNode(newNodeId(instanceIdentifier)).ifPresent(n -> {
                if (n instanceof UaVariableNode v) {
                    v.setValue(new DataValue(new Variant(value)));
                    System.out.println("[SIM] transfer " + instanceIdentifier + " = " + value);
                }
            });
        }
```

- [ ] **Step 5: Wire the transfer into the `Recipe/Rpm` observer**

Replace the existing `Recipe/Rpm` observer body so it transfers after logging. The block currently at lines ~136-144 becomes:

```java
            UaVariableNode rpm = makeDoubleNode("Recipe/Rpm", "Rpm", 0.0);
            rpm.addAttributeObserver((node, attributeId, value) -> {
                if (attributeId == AttributeId.Value) {
                    Object v = value instanceof DataValue dv && dv.getValue() != null
                            ? dv.getValue().getValue()
                            : value;
                    System.out.println("[SIM] SET ns=2;s=Recipe/Rpm = " + v);
                    transferToInstance("Line1/Mixer1.Rpm", v);
                }
            });
```

- [ ] **Step 6: Add an observer to `Recipe/Temp` (currently has none)**

Replace the plain `makeDoubleNode("Recipe/Temp", ...)` line (~146) with:

```java
            UaVariableNode temp = makeDoubleNode("Recipe/Temp", "Temp", 0.0);
            temp.addAttributeObserver((node, attributeId, value) -> {
                if (attributeId == AttributeId.Value) {
                    Object v = value instanceof DataValue dv && dv.getValue() != null
                            ? dv.getValue().getValue()
                            : value;
                    System.out.println("[SIM] SET ns=2;s=Recipe/Temp = " + v);
                    transferToInstance("Line1/Mixer1.Temp", v);
                }
            });
```

- [ ] **Step 7: Run the new test + the full sim suite to verify pass + no regression**

Run (from `bifrost/`): `mvn -q -pl core,sim test -Dtest=MixerTypeNodeTest`
Expected: PASS — all 4 methods green (`browseMixerTypeMembersAndRanges`, `readMixerInstanceValues`, `applyRecipeHandshakeSetsAndRearmsDone`, `writingRecipeSetpointTransfersToInstancePv`). The seed-unchanged assertions confirm no regression to the Chunk-2 behavior.

- [ ] **Step 8: Commit (bifrost)**

```bash
cd bifrost
git add sim/src/main/java/dev/krillin/bifrost/sim/EmbeddedMiloSim.java sim/src/test/java/dev/krillin/bifrost/sim/MixerTypeNodeTest.java
git commit -m "feat(sim): internal setpoint->PV transfer (Recipe/Rpm|Temp write -> Line1/Mixer1.* PV, instant)

Models 'governed command applied -> PV settles to setpoint' for the closed-loop gate.
Lazy node-manager resolution at write-time; server-side setValue bypasses instance read-only.
Fires only on write, so the 1535/200 seeds are unchanged (no Chunk-2 / ncmd-gate regression)."
```

---

## Chunk 2: muninn observer value capture

### Task 2: Muninn observer captures NDATA values (opt-in)

**Files:**
- Modify: `muninn/src/main/java/dev/krillin/muninn/MuninnConsumer.java`
- Modify: `muninn/src/main/java/dev/krillin/muninn/MuninnMain.java` (the `observe` method, ~128-158)
- Create: `muninn/src/test/java/dev/krillin/muninn/NdataValueLineTest.java`

**Context for the implementer:**
- `MuninnConsumer` currently records only `metric.getName()` to `ndataOutFile` (line ~96). We ADD an optional values file; the existing name-only file and behavior stay byte-identical so the spine/muninn gates do not regress.
- The values file is opt-in via a NEW optional `--ndata-values <file>` flag on `observe`. When absent (spine/muninn gates), no values file is created and the positional args are unchanged. Do NOT add a required positional arg.
- Extract the `name=value` formatting into a static `valueLine(Metric)` so it is unit-testable without a broker.
- `Metric` = `org.eclipse.tahu.message.model.Metric`; `metric.getValue()` returns `Object` (a `Double` for the Rpm sample).

- [ ] **Step 1: Write the failing test (formatting + Sparkplug round-trip)**

Create `muninn/src/test/java/dev/krillin/muninn/NdataValueLineTest.java`:

```java
package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.junit.jupiter.api.Test;

/**
 * The closed-loop gate asserts the UNS observation's VALUE changes (Rpm 1535 -> 1500). That
 * requires the observer to render each NDATA metric as {@code name=value}, and the value must
 * survive the Sparkplug B protobuf round-trip. This locks both.
 */
class NdataValueLineTest {

    @Test
    void doubleMetricRendersAsNameEqualsValue() throws Exception {
        Metric m = new Metric.MetricBuilder("Rpm", MetricDataType.Double, 1500.0).createMetric();
        assertEquals("Rpm=1500.0", MuninnConsumer.valueLine(m));
    }

    @Test
    void valueSurvivesSparkplugRoundTripThenRenders() throws Exception {
        SparkplugBPayload ndata = SparkplugCodec.buildNdata(
                List.of(new SampledMember("Rpm", MetricDataType.Double, 1500.0)), 1L);
        byte[] wire = new SparkplugBPayloadEncoder().getBytes(ndata, false);
        SparkplugBPayload decoded = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);

        List<String> lines = decoded.getMetrics().stream().map(MuninnConsumer::valueLine).toList();
        assertTrue(lines.contains("Rpm=1500.0"), "expected Rpm=1500.0 in " + lines);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `muninn/`): `mvn -q test -Dtest=NdataValueLineTest`
Expected: FAIL — `MuninnConsumer.valueLine` does not exist (compile error).

- [ ] **Step 3: Add `valueLine` + optional values-file capture to `MuninnConsumer`**

In `MuninnConsumer.java`:

(a) Add the field + import. Near the top imports, `Metric` is already imported. Add a nullable field beside `ndataOutFile`:

```java
    private final Path valuesOutFile;   // nullable: only written when the observe --ndata-values flag is set
```

(b) Change the constructor signature to accept it and truncate it when present. The constructor becomes:

```java
    public MuninnConsumer(String broker, String group, Path birthOutFile, Path ndataOutFile,
            Path valuesOutFile, int expectNdata) throws Exception {
        this.birthOutFile = birthOutFile;
        this.ndataOutFile = ndataOutFile;
        this.valuesOutFile = valuesOutFile;
        this.expectNdata = expectNdata;

        // Truncate/create both capture files empty so no prior run leaks in.
        Files.write(birthOutFile, new byte[0]);
        Files.write(ndataOutFile, new byte[0]);
        if (valuesOutFile != null) {
            Files.write(valuesOutFile, new byte[0]);
        }

        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        sub = new MqttClient(broker, "muninn-observer", new MemoryPersistence());
        sub.setCallback(this);
        sub.connect(o);
        sub.subscribe("spBv1.0/" + group + "/#", 1);
        System.out.println("[OBSERVE] subscribed spBv1.0/" + group + "/#");
        System.out.flush();
    }
```

(c) In the NDATA branch of `messageArrived` (the `else if (type.equals("NDATA"))` loop, ~94-100), append the values line when the file is set:

```java
        } else if (type.equals("NDATA")) {
            for (Metric metric : payload.getMetrics()) {
                Files.write(ndataOutFile, (metric.getName() + "\n").getBytes(),
                        StandardOpenOption.APPEND);
                if (valuesOutFile != null) {
                    Files.write(valuesOutFile, (valueLine(metric) + "\n").getBytes(),
                            StandardOpenOption.APPEND);
                }
                ndataCount++;
            }
        }
```

(d) Add the static formatter (e.g. below the constructor):

```java
    /** Renders an NDATA metric as {@code name=value} for the gate's value assertion. */
    static String valueLine(Metric metric) {
        return metric.getName() + "=" + metric.getValue();
    }
```

- [ ] **Step 4: Thread the optional flag through `MuninnMain.observe`**

In `MuninnMain.java`, replace the `observe` method body's flag-parsing + construction so it accepts `--ndata-values`:

```java
    private static void observe(String[] args) throws Exception {
        // observe <mqttUrl> <group> <birthOutFile> <ndataOutFile>
        //         [--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]
        if (args.length < 5) {
            usage();
            System.exit(2);
        }
        String mqttUrl = args[1];
        String group = args[2];
        String birthOutFile = args[3];
        String ndataOutFile = args[4];

        int expectNdata = 4;
        long timeoutMs = 20000L;
        String valuesOutFile = null;
        for (int i = 5; i < args.length; i++) {
            if ("--expect-ndata".equals(args[i]) && i + 1 < args.length) {
                expectNdata = Integer.parseInt(args[++i]);
            } else if ("--timeout-ms".equals(args[i]) && i + 1 < args.length) {
                timeoutMs = Long.parseLong(args[++i]);
            } else if ("--ndata-values".equals(args[i]) && i + 1 < args.length) {
                valuesOutFile = args[++i];
            } else {
                System.err.println("unknown argument: " + args[i]);
                usage();
                System.exit(2);
            }
        }

        MuninnConsumer consumer = new MuninnConsumer(mqttUrl, group,
                Path.of(birthOutFile), Path.of(ndataOutFile),
                valuesOutFile != null ? Path.of(valuesOutFile) : null, expectNdata);
        boolean got = consumer.awaitCapture(timeoutMs);
        consumer.close();
        System.exit(got ? 0 : 2);
    }
```

Also update the `observe` usage line in `usage()` to append ` [--ndata-values <file>]`.

- [ ] **Step 5: Run the test to verify it passes + full muninn suite**

Run (from `muninn/`): `mvn -q test`
Expected: PASS — `NdataValueLineTest` (2/2) green, all existing tests still green.

- [ ] **Step 6: Commit (muninn)**

```bash
cd muninn
git add src/main/java/dev/krillin/muninn/MuninnConsumer.java src/main/java/dev/krillin/muninn/MuninnMain.java src/test/java/dev/krillin/muninn/NdataValueLineTest.java
git commit -m "feat(observe): opt-in --ndata-values file capturing NDATA name=value

Enables the full-loop gate to assert the UNS observation VALUE changes (Rpm 1535->1500).
Opt-in flag; name-only ndata.txt and positional args unchanged (spine/muninn gate no-regression).
valueLine extracted static + unit-tested through a Sparkplug round-trip."
```

---

## Chunk 3: group-id unification + full-loop gate

### Task 3: Unify the Sparkplug group id to `Bifrost:Line1`

**Files:**
- Modify: `bifrost/scripts/run-yggdrasil-spine-gate.sh` (line 41: `GROUP="Bifrost-Line1"`)
- Modify: `muninn/scripts/run-muninn-gate.sh` (line 38: `GROUP="Bifrost-Line1"`)

**Context:** Muninn code takes `group` as a CLI arg (zero hardcoding), so this is a pure script-variable change. A colon is a legal MQTT topic-level char and the ncmd gate already uses `Bifrost:Line1` against the same broker.

- [ ] **Step 1: Change the spine-gate group var**

In `bifrost/scripts/run-yggdrasil-spine-gate.sh` line 41, change:
```bash
GROUP="Bifrost-Line1"
```
to:
```bash
GROUP="Bifrost:Line1"
```

- [ ] **Step 2: Change the muninn-gate group var**

In `muninn/scripts/run-muninn-gate.sh` line 38, change `GROUP="Bifrost-Line1"` to `GROUP="Bifrost:Line1"`.

- [ ] **Step 3: Re-verify the spine gate (controller-run; no code depends on the old name)**

Run (from `bifrost/`): `bash scripts/run-yggdrasil-spine-gate.sh`
Expected: `[GATE] PASS run-yggdrasil-spine-gate.sh`. (This is a full end-to-end re-run; requires Docker + free :1883.)

- [ ] **Step 4: Re-verify the muninn gate**

Run (from `muninn/`): `bash scripts/run-muninn-gate.sh`
Expected: `[GATE] PASS run-muninn-gate.sh`.

- [ ] **Step 5: Commit both repos**

```bash
cd bifrost && git add scripts/run-yggdrasil-spine-gate.sh \
  && git commit -m "refactor(gate): unify Sparkplug group id to Bifrost:Line1 (was Bifrost-Line1)

Aligns Muninn's northbound group with Heimdall's southbound group so the same logical Line1
node is commanded and observed under one group id. Script-var only (muninn code takes group as arg)."
cd ../muninn && git add scripts/run-muninn-gate.sh \
  && git commit -m "refactor(gate): unify Sparkplug group id to Bifrost:Line1 (was Bifrost-Line1)"
```

### Task 4: The closed-loop gate `run-yggdrasil-full-loop-gate.sh`

**Files:**
- Create: `bifrost/scripts/run-yggdrasil-full-loop-gate.sh`

**Context for the implementer:**
- This mirrors `run-yggdrasil-spine-gate.sh`'s proven idioms verbatim: `kill_by_mainclass`, MSYS-safe backgrounding, `cygpath -m`, readiness handshakes, `poll_ndata`, single-broker preflight `docker rm -f`.
- It additionally starts the **Heimdall daemon** (like `run-ncmd-runtime-gate.sh` step 3) and publishes NCMDs via `dev.krillin.bifrost.heimdall.RogueNcmd`.
- Heimdall env: `SPB_GROUP=Bifrost:Line1`, `SPB_EDGE=recipe-edge`, `POLICY_PATH=heimdall/registry/policy.json`, `MQTT_URL`, `OPCUA_URL`.
- Muninn observe/feed use group `Bifrost:Line1` and edge `recipe-edge`. The observer for the value assertions passes `--ndata-values <file>`.
- The govern step (populate the registry so muninn feed can provenance-verify) is the spine gate's happy-path govern, condensed — NO reject legs, NO spec conformance leg.
- Heimdall writing `Recipe/Rpm` fires the sim transfer; wait for BOTH `[BRIDGE] APPLY ... ok=true` AND `[SIM] transfer Line1/Mixer1.Rpm = 1500` before feed#2.

- [ ] **Step 1: Write the gate script**

Create `bifrost/scripts/run-yggdrasil-full-loop-gate.sh` with the content below.

```bash
#!/usr/bin/env bash
# YGGDRASIL FULL-LOOP GATE: prove a governed + authorized command changes the northbound UNS
# observation (observe -> command -> observe). Composes BOTH axes on ONE broker + ONE sim:
#
#   ../mimir/target/mimir.jar       — model the equipment type (northbound, design-time)
#   gates/target/bifrost-gates.jar  — govern (schema ① + provenance ③)
#   sim/target/bifrost-sim.jar      — embedded OPC-UA sim (Recipe/* setpoints + Line1/Mixer1 PV;
#                                     internal setpoint->PV transfer)
#   heimdall/target/bifrost-heimdall.jar — southbound write-boundary daemon (② authz -> OPC write)
#   ../muninn/target/muninn.jar     — northbound feeder/observer (NBIRTH/NDATA over Sparkplug B)
#   docker-compose.yml (hivemq-ce)  — the MQTT broker
#
# Assertions:
#   L1 OBSERVE#1 : muninn NDATA Rpm == 1535.0 (initial governed observation).
#   L2 COMMAND   : authorized NCMD Recipe/Rpm=1500 -> [BRIDGE] APPLY ok=true, witnessed by
#                  [SIM] SET and [SIM] transfer Line1/Mixer1.Rpm=1500.
#   L3 OBSERVE#2 : muninn NDATA Rpm == 1500.0  <-- THE closed loop.
#   L4 rogue     : NCMD Recipe/Secret -> [BRIDGE] DENY, never APPLY (deny-by-default).
#   L5 d-i-d     : NCMD Recipe/Rpm=9999 -> [BRIDGE] DENY above-max, no new APPLY;
#                  OBSERVE#3 still Rpm == 1500.0 (a denied command does NOT move the UNS).
#
# Run from anywhere (needs Docker Desktop running + host port 1883 free):
#   bash scripts/run-yggdrasil-full-loop-gate.sh
#   # expect: [GATE] PASS run-yggdrasil-full-loop-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

WORK="build/loopgate"

GATES_JAR="gates/target/bifrost-gates.jar"
SIM_JAR="sim/target/bifrost-sim.jar"
HEIMDALL_JAR="heimdall/target/bifrost-heimdall.jar"
MIMIR_JAR="../mimir/target/mimir.jar"
MUNINN_JAR="../muninn/target/muninn.jar"

ENDPOINT="opc.tcp://localhost:48400"
NS_URI="urn:bifrost:opcua:sim"
TYPE="MixerType"
REF="Line1-Mixer"
VER="1.0.0"
GROUP="Bifrost:Line1"
EDGE="recipe-edge"
INSTANCE="Line1/Mixer1"
MQTT="tcp://localhost:1883"
RPM_NODE="ns=2;s=Recipe/Rpm"
SECRET_NODE="ns=2;s=Recipe/Secret"

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- sim log tail ---";    tail -40 "$WORK/sim.log"    2>/dev/null || true
  echo "--- bridge log tail ---"; tail -40 "$WORK/bridge.log" 2>/dev/null || true
  for f in "$WORK"/feed-*.log "$WORK"/observe-*.log; do
    [ -f "$f" ] && { echo "--- $(basename "$f") tail ---"; tail -20 "$f" 2>/dev/null; } || true
  done
  exit 1
}

kill_by_mainclass() {  # $1=substring of the jps -lm main-class/jar line
  { jps -lm 2>/dev/null | grep -i "$1" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

SIM_PID=""; BRIDGE_PID=""; OBS_PID=""; COMPOSE_WIN=""
cleanup() {
  [ -n "$SIM_PID" ]    && taskkill //F //T //PID "$SIM_PID"    >/dev/null 2>&1 || true
  [ -n "$BRIDGE_PID" ] && taskkill //F //T //PID "$BRIDGE_PID" >/dev/null 2>&1 || true
  [ -n "$OBS_PID" ]    && taskkill //F //T //PID "$OBS_PID"    >/dev/null 2>&1 || true
  kill_by_mainclass "bifrost-sim.jar" || true
  kill_by_mainclass "bifrost-heimdall.jar" || true
  kill_by_mainclass "muninn.jar" || true
  [ -n "$COMPOSE_WIN" ] && docker compose -f "$COMPOSE_WIN" stop hivemq-ce >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
echo "[GATE] step 1: preflight + build (5 jars)"
command -v docker >/dev/null 2>&1 || { echo "[GATE] FAIL: docker not found on PATH"; exit 1; }

if [ ! -f "$GATES_JAR" ] || [ ! -f "$SIM_JAR" ] || [ ! -f "$HEIMDALL_JAR" ]; then
  mvn -q -pl core,sim,gates,heimdall install
fi
[ -f "$GATES_JAR" ]    || fail "$GATES_JAR missing after build"
[ -f "$SIM_JAR" ]      || fail "$SIM_JAR missing after build"
[ -f "$HEIMDALL_JAR" ] || fail "$HEIMDALL_JAR missing after build"
[ -f "$MIMIR_JAR" ]  || ( cd ../mimir  && mvn -q package )
[ -f "$MUNINN_JAR" ] || ( cd ../muninn && mvn -q package )
[ -f "$MIMIR_JAR" ]  || fail "$MIMIR_JAR missing after build"
[ -f "$MUNINN_JAR" ] || fail "$MUNINN_JAR missing after build"

kill_by_mainclass "bifrost-sim.jar" || true
kill_by_mainclass "bifrost-heimdall.jar" || true
kill_by_mainclass "muninn.jar" || true
docker rm -f bifrost-hivemq-ce muninn-hivemq-ce >/dev/null 2>&1 || true

GATES_JAR_WIN="$(cygpath -m "$(pwd)/$GATES_JAR")"
SIM_JAR_WIN="$(cygpath -m "$(pwd)/$SIM_JAR")"
HEIMDALL_JAR_WIN="$(cygpath -m "$(pwd)/$HEIMDALL_JAR")"
MIMIR_JAR_WIN="$(cygpath -m "$(pwd)/$MIMIR_JAR")"
MUNINN_JAR_WIN="$(cygpath -m "$(pwd)/$MUNINN_JAR")"
COMPOSE_WIN="$(cygpath -m "$(pwd)/docker-compose.yml")"
POLICY_PATH="$(cygpath -m "$(pwd)/heimdall/registry/policy.json")"
[ -f "$(pwd)/heimdall/registry/policy.json" ] || fail "heimdall/registry/policy.json fixture missing"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start ONE HiveMQ CE broker + sim"
docker compose -f "$COMPOSE_WIN" up -d hivemq-ce >/dev/null 2>&1 || fail "failed to start hivemq-ce"
ok=0
for i in $(seq 1 30); do
  bash -c "echo > /dev/tcp/localhost/1883" >/dev/null 2>&1 && { ok=1; break; }
  sleep 2
done
[ "$ok" = "1" ] || { docker compose -f "$COMPOSE_WIN" logs --tail 40 hivemq-ce 2>/dev/null || true; fail "HiveMQ CE did not open :1883"; }
echo "[GATE] HiveMQ CE up on :1883"

rm -rf "$WORK"
mkdir -p "$WORK/registry" "$WORK/srcrepo" "$WORK/out"

: > "$WORK/sim.log"
java -jar "$SIM_JAR_WIN" > "$WORK/sim.log" 2>&1 &
SIM_PID=$!
ok=0
for i in $(seq 1 30); do
  grep -q "OPC-UA sim listening" "$WORK/sim.log" 2>/dev/null && { ok=1; break; }
  sleep 1
done
[ "$ok" = "1" ] || fail "OPC-UA sim did not start (pid $SIM_PID)"
echo "[GATE] OPC-UA sim listening (pid $SIM_PID)"

REGISTRY_WIN="$(cygpath -m "$(pwd)/$WORK/registry")"

# ---------------------------------------------------------------------------
# Observer helpers (mirror the spine gate).
wait_observer_ready() {  # $1=observe log
  local t=0
  while [ "$t" -lt 20 ]; do
    grep -q "\[OBSERVE\] subscribed" "$1" 2>/dev/null && return 0
    sleep 0.5; t=$((t + 1))
  done
  return 1
}
poll_ndata() {  # $1=birth file  $2=ndata file
  local t=0
  while [ "$t" -lt 40 ]; do
    if [ -s "$1" ] && [ -s "$2" ]; then return 0; fi
    sleep 0.5; t=$((t + 1))
  done
  return 1
}
# Run one observe#N + feed#N cycle; capture the Rpm value line into $WORK/out/rpm-$1.txt
# $1 = run label (1|2|3) ; $2..= extra feed args (e.g. --inject-bogus) [none here]
observe_and_feed() {
  local tag="$1"; shift
  local blog="$WORK/out/birth-$tag.bin" ndlog="$WORK/out/ndata-$tag.txt" vlog="$WORK/out/values-$tag.txt"
  : > "$WORK/observe-$tag.log"
  java -jar "$MUNINN_JAR_WIN" observe "$MQTT" "$GROUP" \
    "$(cygpath -m "$(pwd)/$blog")" "$(cygpath -m "$(pwd)/$ndlog")" \
    --expect-ndata 4 --timeout-ms 20000 --ndata-values "$(cygpath -m "$(pwd)/$vlog")" \
    > "$WORK/observe-$tag.log" 2>&1 &
  OBS_PID=$!
  wait_observer_ready "$WORK/observe-$tag.log" || fail "$tag: observer never printed '[OBSERVE] subscribed'"
  set +e
  java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VER" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
    "$MQTT" "$GROUP" "$EDGE" "$@" > "$WORK/feed-$tag.log" 2>&1
  local code=$?
  set -e
  [ "$code" -eq 0 ] || fail "$tag: feed returned $code — expected 0"
  poll_ndata "$blog" "$ndlog" || fail "$tag: observer never captured birth+ndata"
  OBS_PID=""
}

# ---------------------------------------------------------------------------
echo "[GATE] step 3: govern the MODEL (schema ① + provenance ③) to populate the registry"
set +e
java -jar "$MIMIR_JAR_WIN" derive "$ENDPOINT" "$NS_URI" "$TYPE" "$REF" "$VER" "$(cygpath -m "$(pwd)/$WORK/def.json")"
[ $? -eq 0 ] || { set -e; fail "mimir derive returned non-zero"; }
java -jar "$GATES_JAR_WIN" schema "$REGISTRY_WIN" "$(cygpath -m "$(pwd)/$WORK/def.json")" --promote
[ $? -eq 0 ] || { set -e; fail "schema gate rejected the derive"; }
set -e
[ -f "$WORK/registry/udt/$REF/$VER.json" ] || fail "schema gate did not promote udt/$REF/$VER.json"

cp "$WORK/registry/udt/$REF/$VER.json" "$WORK/srcrepo/$REF-$VER.json"
SRCREPO_WIN="$(cygpath -m "$(pwd)/$WORK/srcrepo")"
git -C "$SRCREPO_WIN" init -q
git -C "$SRCREPO_WIN" config core.autocrlf false
git -C "$SRCREPO_WIN" config user.email gate@local
git -C "$SRCREPO_WIN" config user.name gate
git -C "$SRCREPO_WIN" add "$REF-$VER.json"
git -C "$SRCREPO_WIN" commit -qm seed
set +e
java -jar "$GATES_JAR_WIN" provenance publish "$REGISTRY_WIN" "$SRCREPO_WIN" "$REF-$VER.json" "$REF" "$VER"
[ $? -eq 0 ] || { set -e; fail "provenance publish returned non-zero"; }
set -e
[ -f "$WORK/registry/recipe/$REF/$VER/recipe-setpoints.yaml" ] || fail "provenance publish did not mint the recipe"
echo "[GATE] registry populated (udt + recipe) for $REF@$VER"

# ---------------------------------------------------------------------------
echo "[GATE] step 4: start the Heimdall daemon (southbound write boundary)"
export MQTT_URL="$MQTT"
export OPCUA_URL="$ENDPOINT"
export SPB_GROUP="$GROUP"
export SPB_EDGE="$EDGE"
export POLICY_PATH="$POLICY_PATH"
: > "$WORK/bridge.log"
java -jar "$HEIMDALL_JAR_WIN" > "$WORK/bridge.log" 2>&1 &
BRIDGE_PID=$!
ok=0
for i in $(seq 1 45); do
  grep -q "\[BRIDGE\] ready" "$WORK/bridge.log" 2>/dev/null && { ok=1; break; }
  sleep 2
done
[ "$ok" = "1" ] || fail "Heimdall daemon did not reach '[BRIDGE] ready' (pid $BRIDGE_PID)"
echo "[GATE] Heimdall ready (pid $BRIDGE_PID)"

pub() {  # $1=nodeId $2=value $3=dataType
  MQTT_URL="$MQTT" SPB_GROUP="$GROUP" SPB_EDGE="$EDGE" \
    java -cp "$HEIMDALL_JAR_WIN" dev.krillin.bifrost.heimdall.RogueNcmd "$1" "$2" "$3" >>"$WORK/pub.log" 2>&1
}
apply_count() { grep -c "\[BRIDGE\] APPLY cmd=$1" "$WORK/bridge.log" 2>/dev/null || echo 0; }

# ---------------------------------------------------------------------------
echo "[GATE] ===== L1 OBSERVE#1 (before): NDATA Rpm == 1535.0 ====="
observe_and_feed 1
grep -q "^Rpm=1535" "$WORK/out/values-1.txt" || fail "L1: NDATA Rpm != 1535 (got: $(grep '^Rpm=' "$WORK/out/values-1.txt" || echo none))"
echo "[GATE] L1 OK: initial UNS observation Rpm=1535.0"

# ---------------------------------------------------------------------------
echo "[GATE] ===== L2 COMMAND: authorized NCMD Recipe/Rpm=1500 -> APPLY + transfer ====="
pub "$RPM_NODE" 1500 Double
ok=0
for i in $(seq 1 10); do
  grep -q "\[BRIDGE\] APPLY cmd=$RPM_NODE ok=true" "$WORK/bridge.log" 2>/dev/null && { ok=1; break; }
  sleep 2
done
[ "$ok" = "1" ] || fail "L2: '[BRIDGE] APPLY cmd=$RPM_NODE ok=true' never observed"
ok=0
for i in $(seq 1 10); do
  grep -q "\[SIM\] transfer Line1/Mixer1.Rpm = 1500" "$WORK/sim.log" 2>/dev/null && { ok=1; break; }
  sleep 1
done
[ "$ok" = "1" ] || fail "L2: '[SIM] transfer Line1/Mixer1.Rpm = 1500' never observed (transfer not wired?)"
echo "[GATE] L2 OK: authorized command applied + transferred to the instance PV"

# ---------------------------------------------------------------------------
echo "[GATE] ===== L3 OBSERVE#2 (after): NDATA Rpm == 1500.0 — THE CLOSED LOOP ====="
observe_and_feed 2
grep -q "^Rpm=1500" "$WORK/out/values-2.txt" || fail "L3: NDATA Rpm != 1500 after command (got: $(grep '^Rpm=' "$WORK/out/values-2.txt" || echo none))"
echo "[GATE] L3 OK: the governed+authorized command changed the UNS observation 1535 -> 1500"

# ---------------------------------------------------------------------------
echo "[GATE] ===== L4 rogue deny-by-default: Recipe/Secret -> DENY, never APPLY ====="
pub "$SECRET_NODE" 1.0 Double
sleep 3
grep -q "\[BRIDGE\] DENY cmd=$SECRET_NODE" "$WORK/bridge.log" || fail "L4: bridge did not DENY the rogue $SECRET_NODE"
! grep -q "\[BRIDGE\] APPLY cmd=$SECRET_NODE" "$WORK/bridge.log" || fail "L4: bridge APPLIED a deny-by-default node"
echo "[GATE] L4 OK: rogue node denied, never applied"

# ---------------------------------------------------------------------------
echo "[GATE] ===== L5 defense-in-depth: Recipe/Rpm=9999 -> DENY above-max, UNS unchanged ====="
APPLY_BEFORE=$(apply_count "$RPM_NODE")
pub "$RPM_NODE" 9999 Double
sleep 3
grep -qE "\[BRIDGE\] DENY cmd=$RPM_NODE .*above-max" "$WORK/bridge.log" || fail "L5: bridge did not DENY Rpm=9999 as above-max"
APPLY_AFTER=$(apply_count "$RPM_NODE")
[ "$APPLY_AFTER" = "$APPLY_BEFORE" ] || fail "L5: an APPLY for $RPM_NODE appeared after the out-of-range rogue (before=$APPLY_BEFORE after=$APPLY_AFTER)"
observe_and_feed 3
grep -q "^Rpm=1500" "$WORK/out/values-3.txt" || fail "L5: UNS Rpm changed after a DENIED command (got: $(grep '^Rpm=' "$WORK/out/values-3.txt" || echo none))"
echo "[GATE] L5 OK: out-of-range command denied; UNS observation still Rpm=1500.0"

# ---------------------------------------------------------------------------
echo "[GATE] step 9: teardown"
[ -n "$SIM_PID" ]    && taskkill //F //T //PID "$SIM_PID"    >/dev/null 2>&1 || true
[ -n "$BRIDGE_PID" ] && taskkill //F //T //PID "$BRIDGE_PID" >/dev/null 2>&1 || true
kill_by_mainclass "bifrost-sim.jar" || true
kill_by_mainclass "bifrost-heimdall.jar" || true
kill_by_mainclass "muninn.jar" || true
docker compose -f "$COMPOSE_WIN" stop hivemq-ce >/dev/null 2>&1 || true

echo ""
echo "[GATE] PASS run-yggdrasil-full-loop-gate.sh"
exit 0
```

- [ ] **Step 2: Make the script executable + commit (bifrost)**

```bash
cd bifrost
chmod +x scripts/run-yggdrasil-full-loop-gate.sh
git add scripts/run-yggdrasil-full-loop-gate.sh
git commit -m "test(gate): run-yggdrasil-full-loop-gate — observe->command->observe closed loop

Composes Heimdall (southbound authz->write) + sim transfer + Muninn (northbound feed/observe)
on one broker: L1 NDATA Rpm=1535 -> L2 authorized NCMD 1500 (APPLY+transfer) -> L3 NDATA Rpm=1500
(closed loop) + L4 rogue DENY + L5 above-max DENY with UNS unchanged."
```

### Task 5: Controller-direct final verification (the #1 rule — never trust a subagent PASS)

**No files.** The controller runs every done-bit personally.

- [ ] **Step 1: `mvn install` green in bifrost**

Run (from `bifrost/`): `mvn -q install`
Expected: BUILD SUCCESS, all modules incl. `MixerTypeNodeTest` (4 methods).

- [ ] **Step 2: `mvn install` green in muninn**

Run (from `muninn/`): `mvn -q install`
Expected: BUILD SUCCESS incl. `NdataValueLineTest` (2 methods).

- [ ] **Step 3: Run the full-loop gate (the headline done-bit)**

Run (from `bifrost/`): `bash scripts/run-yggdrasil-full-loop-gate.sh`
Expected: `[GATE] PASS run-yggdrasil-full-loop-gate.sh` with L1–L5 all printed OK.

- [ ] **Step 4: No-regression — re-run the three existing gates**

Run:
- `bash scripts/run-yggdrasil-spine-gate.sh` → `[GATE] PASS`
- `bash scripts/run-ncmd-runtime-gate.sh` → `[GATE] PASS`
- (from `muninn/`) `bash scripts/run-muninn-gate.sh` → `[GATE] PASS`

- [ ] **Step 5: Update memory + report**

Update `yggdrasil-governance-spine.md` with the full-loop DONE state (controller-verified), keeping all repos LOCAL/unpushed. Report results with evidence (gate output), then confirm the Ep5-blog next step remains Eisen-gated.

---

## Notes / risks

- **One-broker discipline:** the gate `docker rm -f`s both `bifrost-hivemq-ce` and `muninn-hivemq-ce` in preflight, then brings up ONE via bifrost's compose. Do not run the spine/muninn gate concurrently.
- **Group-id colon:** legal MQTT topic char; ncmd gate already proves it against HiveMQ CE.
- **Value formatting:** `metric.getValue()` for a Double renders `1500.0` (Java `Double.toString`), so the gate greps the `^Rpm=1500` / `^Rpm=1535` prefix (tolerant of the trailing `.0`). If a future derived def makes Rpm an integral type, revisit the grep.
- **Ordering determinism:** observe#N + feed#N are sequential and one-shot; the command step blocks on both the bridge APPLY line and the sim transfer line before feed#2, so no before/after race.
- **Heimdall unchanged:** the daemon, policy fixture, and RogueNcmd are reused as-is; the ncmd gate re-run guards them.
