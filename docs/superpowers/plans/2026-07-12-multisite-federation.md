# Multi-site Federation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove enterprise (multi-site) governance on one machine — two per-site brokers/edges consuming one federated Bifrost authority — by recombining existing primitives (T1 template conformance, T7 git anchor, per-site Heimdall) plus a small amount of honestly-enumerated new code.

**Architecture:** All *code* lands in **bifrost** (the governance product); the **lab** holds only this plan + the reproduction doc (design journal, same split as T5/T6). The build adds: (1) OPC-UA sim endpoint parameterization so two sims run on distinct ports; (2) a `federation audit` gate CLI that aggregates N sites' activation ledgers into one cross-site view (F6); (3) a second broker service in compose; (4) `scripts/run-federation-gate.sh` staging an enterprise git registry + two site mirror-clones + the F1–F6 assertions. F1/F5/F6 are pure-CLI and always run; the runtime legs (F2/F3/F4, two brokers + sims + Heimdalls) run only when Docker is up, exactly like the existing anchored gate's AN8 leg.

**Tech Stack:** Java 17 (records, `mvn -pl … -am install`), Eclipse Milo 1.0.0 (embedded OPC-UA sim), HiveMQ CE (Docker), Tahu (Sparkplug), Ed25519 (JDK built-in, T5), Git (T7 anchor via `git show HEAD`), Bash gate scripts (Git-Bash on Windows, `cygpath -m`).

**Spec:** `docs/superpowers/specs/2026-07-12-multisite-federation-design.md` (this repo). Read it before starting — especially §1 (honest T7-residual framing: federation **relocates** the residual into the enterprise trust domain; it does **not** cryptographically close it — the PoC's "cannot rewrite" is topological) and §5 (the enumerated new code).

**Honest scope reminder (do not over-build):** No real PLCs, no HA cluster, no load test, no true off-box remote. Two sim-sites on localhost. The gate output must *state* that F5's un-rewritability is topological, not proven.

**Repo paths in this plan:** all `bifrost/…` paths are under `C:\Users\Eisen\Desktop\Labs\[iiot]\bifrost`. All `lab/…` paths are under `C:\Users\Eisen\Desktop\Labs\[iiot]\sparkplug-governance-lab`.

---

## File Structure

**New files:**
- `bifrost/gates/src/main/java/dev/krillin/bifrost/gates/FederationAudit.java` — pure cross-site aggregator (no IO), unit-tested.
- `bifrost/gates/src/main/java/dev/krillin/bifrost/gates/FederationGate.java` — `federation audit` CLI (IO: reads each site's `ActivationLedger`, calls `FederationAudit`, prints).
- `bifrost/gates/src/test/java/dev/krillin/bifrost/gates/FederationAuditTest.java` — aggregation logic.
- `bifrost/gates/src/test/java/dev/krillin/bifrost/gates/FederationGateTest.java` — CLI arg-parsing / usage exit codes.
- `bifrost/sim/src/test/java/dev/krillin/bifrost/sim/EmbeddedMiloSimConfigTest.java` — sim port/host resolution (no server startup).
- `bifrost/scripts/run-federation-gate.sh` — the F1–F6 federation gate.
- `lab/docs/reproduce/multisite-federation.md` — reproduction deep-dive.

**Modified files:**
- `bifrost/sim/src/main/java/dev/krillin/bifrost/sim/EmbeddedMiloSim.java` — parameterize bind port/host (keep `48400`/`localhost` defaults).
- `bifrost/sim/src/main/java/dev/krillin/bifrost/sim/SimMain.java` — read `SIM_BIND_PORT`/`SIM_BIND_HOST` env; print the actual port.
- `bifrost/gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java` — add `federation` subcommand dispatch + usage string.
- `bifrost/docker-compose.yml` — add a second `hivemq-ce-b` service on `:1884`.
- `lab/docs/reproduce/README.md` — add the federation experiment to the index/matrix.

**Why these boundaries:** `FederationAudit` (pure) is split from `FederationGate` (IO) so the aggregation logic is unit-testable with hand-built records, and the CLI stays a thin adapter — matching how `TemplateGate`/`ActivateGate` keep logic in `core` and IO in the gate. The sim change is additive (new ctor + delegating no-arg) so every existing caller and gate script is byte-for-byte unaffected on the default port.

---

## Chunk 1: Sim endpoint parameterization + second broker

The 2-site topology needs two OPC-UA sims on distinct endpoints and two brokers. The sim currently hardcodes `BIND_PORT = 48400` / `localhost`; make both configurable **without** changing the default or the node model.

### Task 1: Parameterize the OPC-UA sim endpoint

**Files:**
- Modify: `bifrost/sim/src/main/java/dev/krillin/bifrost/sim/EmbeddedMiloSim.java`
- Modify: `bifrost/sim/src/main/java/dev/krillin/bifrost/sim/SimMain.java`
- Test: `bifrost/sim/src/test/java/dev/krillin/bifrost/sim/EmbeddedMiloSimConfigTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `EmbeddedMiloSimConfigTest.java`. It must NOT start the server (Milo bind is integration-level) — it only checks endpoint config resolution: the parameterized ctor stores port/host, the no-arg ctor keeps the `48400`/`localhost` defaults, and `SimMain.resolvePort`/`resolveHost` read env with those defaults.

```java
package dev.krillin.bifrost.sim;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbeddedMiloSimConfigTest {

    @Test void defaultCtor_keepsLegacyEndpoint() {
        EmbeddedMiloSim sim = new EmbeddedMiloSim();
        assertEquals(48400, sim.bindPort());
        assertEquals("localhost", sim.bindHost());
    }

    @Test void paramCtor_overridesEndpoint() {
        EmbeddedMiloSim sim = new EmbeddedMiloSim(48401, "localhost");
        assertEquals(48401, sim.bindPort());
        assertEquals("localhost", sim.bindHost());
    }

    @Test void simMain_resolvesPortFromEnv_defaulting48400() {
        assertEquals(48400, SimMain.resolvePort(Map.of()));
        assertEquals(48401, SimMain.resolvePort(Map.of("SIM_BIND_PORT", "48401")));
    }

    @Test void simMain_resolvesHostFromEnv_defaultingLocalhost() {
        assertEquals("localhost", SimMain.resolveHost(Map.of()));
        assertEquals("127.0.0.1", SimMain.resolveHost(Map.of("SIM_BIND_HOST", "127.0.0.1")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bifrost && mvn -q -pl sim -am test -Dtest=EmbeddedMiloSimConfigTest`
Expected: FAIL — compile error (`bindPort()`/`bindHost()`/parameterized ctor / `SimMain.resolvePort` do not exist).

- [ ] **Step 3: Implement — `EmbeddedMiloSim` ctor + fields**

In `EmbeddedMiloSim.java`: replace the `static final int BIND_PORT = 48400;` constant usage with instance fields + constructors. Keep the `NAMESPACE_URI` constant. Keep a `DEFAULT_BIND_PORT` for `SimMain`'s log/back-compat.

```java
    static final String NAMESPACE_URI = "urn:bifrost:opcua:sim";
    static final int DEFAULT_BIND_PORT = 48400;
    static final String DEFAULT_BIND_HOST = "localhost";

    private final int bindPort;
    private final String bindHost;
    private OpcUaServer server;
    private SimNamespace namespace;

    EmbeddedMiloSim() { this(DEFAULT_BIND_PORT, DEFAULT_BIND_HOST); }

    EmbeddedMiloSim(int bindPort, String bindHost) {
        this.bindPort = bindPort;
        this.bindHost = bindHost;
    }

    int bindPort() { return bindPort; }
    String bindHost() { return bindHost; }
```

Then in `start()`, use the fields (host used for both bind address AND advertised hostname — Milo re-resolves the advertised endpoint after discovery, so they must match, per the class javadoc):

```java
        EndpointConfig endpointConfig = EndpointConfig.newBuilder()
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .setBindAddress(bindHost)
                .setBindPort(bindPort)
                .setHostname(bindHost)
                .setPath("")
                .setSecurityPolicy(SecurityPolicy.None)
                .setSecurityMode(MessageSecurityMode.None)
                .addTokenPolicies(new UserTokenPolicy("anonymous", UserTokenType.Anonymous, null, null, null))
                .build();
```

Update the class javadoc's hardcoded `48400`/`localhost` sentences to say "configurable, default `opc.tcp://localhost:48400`". **Do not** change any `newNodeId(...)` identifier — the node model (`ns=2;s=Recipe/Rpm`, etc.) is endpoint-independent.

- [ ] **Step 4: Implement — `SimMain` env resolution**

Rewrite `SimMain.java` to resolve from env with static helpers (so they're unit-testable), pass to the ctor, and print the actual port:

```java
package dev.krillin.bifrost.sim;

import java.util.Map;

/**
 * Standalone process entry point for the bifrost-local runtime gate: starts the embedded Milo
 * OPC-UA server (endpoint configurable via SIM_BIND_PORT / SIM_BIND_HOST env, default
 * opc.tcp://localhost:48400), prints the ready line the gate waits on, then blocks forever.
 *
 * <p>Run: {@code java -jar bifrost-sim.jar}  (or {@code SIM_BIND_PORT=48401 java -jar bifrost-sim.jar})
 */
public final class SimMain {

    public static void main(String[] args) throws Exception {
        int port = resolvePort(System.getenv());
        String host = resolveHost(System.getenv());
        EmbeddedMiloSim sim = new EmbeddedMiloSim(port, host).start();
        Runtime.getRuntime().addShutdownHook(new Thread(sim::close));

        // The gate waits for the substring "OPC-UA sim listening" — keep this line stable.
        System.out.println("OPC-UA sim listening on opc.tcp://" + host + ":" + port);

        Thread.currentThread().join();
    }

    static int resolvePort(Map<String, String> env) {
        String v = env.get("SIM_BIND_PORT");
        return (v == null || v.isBlank()) ? EmbeddedMiloSim.DEFAULT_BIND_PORT : Integer.parseInt(v.trim());
    }

    static String resolveHost(Map<String, String> env) {
        String v = env.get("SIM_BIND_HOST");
        return (v == null || v.isBlank()) ? EmbeddedMiloSim.DEFAULT_BIND_HOST : v.trim();
    }

    private SimMain() {}
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd bifrost && mvn -q -pl sim -am test -Dtest=EmbeddedMiloSimConfigTest`
Expected: PASS (4/4).

- [ ] **Step 6: Regression — the default-port sim still builds and the existing gate is unaffected**

Run: `cd bifrost && mvn -q -pl sim -am install -DskipTests`
Expected: BUILD SUCCESS, `sim/target/bifrost-sim.jar` present. (No behavior change on the default port — the anchored gate's AN8 leg still starts the sim identically.)

- [ ] **Step 7: Commit**

```bash
cd bifrost && git add sim/ && git commit -m "feat(sim): parameterize OPC-UA endpoint (SIM_BIND_PORT/SIM_BIND_HOST, default :48400) for multi-site"
```

### Task 2: Second broker in compose

**Files:**
- Modify: `bifrost/docker-compose.yml`

- [ ] **Step 1: Add the second service**

Append a `hivemq-ce-b` service on `:1884` (site B), mirroring `hivemq-ce` (site A on `:1883`). Update the header comment.

```yaml
# Local bifrost runtime-gate brokers.
#   docker compose up -d hivemq-ce     → site A MQTT on tcp://localhost:1883
#   docker compose up -d hivemq-ce-b   → site B MQTT on tcp://localhost:1884  (multi-site federation gate)
#
# ⚠️ HiveMQ CE 2026.x is secure-by-default: without a security extension it refuses ALL MQTT
#    connections. For local development we enable the bundled allow-all extension via
#    HIVEMQ_ALLOW_ALL_CLIENTS=true.
services:
  hivemq-ce:
    image: hivemq/hivemq-ce:latest
    container_name: bifrost-hivemq-ce
    ports:
      - "1883:1883"     # MQTT — site A
    environment:
      HIVEMQ_ALLOW_ALL_CLIENTS: "true"
    restart: unless-stopped

  hivemq-ce-b:
    image: hivemq/hivemq-ce:latest
    container_name: bifrost-hivemq-ce-b
    ports:
      - "1884:1883"     # MQTT — site B (container listens on 1883, published to host 1884)
    environment:
      HIVEMQ_ALLOW_ALL_CLIENTS: "true"
    restart: unless-stopped
```

- [ ] **Step 2: Verify compose is valid**

Run: `cd bifrost && docker compose config >/dev/null && echo COMPOSE_OK`
Expected: `COMPOSE_OK` (if Docker is installed; if not, skip — the gate script tolerates no-docker).

- [ ] **Step 3: Commit**

```bash
cd bifrost && git add docker-compose.yml && git commit -m "feat(compose): add site-B broker hivemq-ce-b on :1884 for multi-site federation"
```

---

## Chunk 2: Federation audit CLI (F6)

A read-only aggregator: given N sites (name + registry dir) and a target, read each site's activation ledger and print one cross-site view (who activated what, where, when). Pure logic in `FederationAudit`; IO in `FederationGate`.

### Task 3: `FederationAudit` pure aggregator

**Files:**
- Create: `bifrost/gates/src/main/java/dev/krillin/bifrost/gates/FederationAudit.java`
- Test: `bifrost/gates/src/test/java/dev/krillin/bifrost/gates/FederationAuditTest.java`

Reference record shapes (already exist):
- `ActivationEvent(String target, String kind, String ref, String version, String contentSha256, String activatedBy, String approvedBy, long activatedAt, String priorVersion, String action)`
- `LedgerEntry(ActivationEvent event, String prevHash, String entryHash, String activatorSig, String approverSig)` — use `LedgerEntry.unsigned(event, prev, hash)` in tests.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.gates;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.bifrost.core.activation.ActivationEvent;
import dev.krillin.bifrost.core.activation.LedgerEntry;
import org.junit.jupiter.api.Test;

class FederationAuditTest {

    private static LedgerEntry ev(String site, String version, String by, long at, String action) {
        ActivationEvent e = new ActivationEvent("Line1", "recipe", "mix-recipe", version,
                "sha-" + version, by, "bob", at, null, action);
        return LedgerEntry.unsigned(e, "PREV", "HASH-" + site + "-" + version);
    }

    @Test void merge_producesPerSiteActiveVersion_andTotalEvents() {
        Map<String, List<LedgerEntry>> perSite = new LinkedHashMap<>();
        perSite.put("busan", List.of(ev("busan", "1.0.0", "alice", 100, "ACTIVATE"),
                                     ev("busan", "1.1.0", "alice", 200, "ACTIVATE")));
        perSite.put("ulsan", List.of(ev("ulsan", "1.0.0", "carol", 150, "ACTIVATE")));

        FederationAudit.CrossSiteView v = FederationAudit.merge("Line1", perSite);

        assertEquals(2, v.sites().size());
        assertEquals("1.1.0", v.sites().get("busan").activeVersion());  // last ACTIVATE wins
        assertEquals("1.0.0", v.sites().get("ulsan").activeVersion());
        assertEquals(3, v.totalEvents());
    }

    @Test void merge_rollbackTailMakesActiveVersionThePriorTarget() {
        Map<String, List<LedgerEntry>> perSite = new LinkedHashMap<>();
        perSite.put("busan", List.of(ev("busan", "1.0.0", "alice", 100, "ACTIVATE"),
                                     ev("busan", "1.1.0", "alice", 200, "ACTIVATE"),
                                     ev("busan", "1.0.0", "alice", 300, "ROLLBACK")));
        FederationAudit.CrossSiteView v = FederationAudit.merge("Line1", perSite);
        assertEquals("1.0.0", v.sites().get("busan").activeVersion());  // rollback tail => version field of last entry
        assertEquals(3, v.sites().get("busan").eventCount());
    }

    @Test void merge_emptySiteReportsNoneActive() {
        Map<String, List<LedgerEntry>> perSite = new LinkedHashMap<>();
        perSite.put("busan", List.of());
        FederationAudit.CrossSiteView v = FederationAudit.merge("Line1", perSite);
        assertNull(v.sites().get("busan").activeVersion());
        assertEquals(0, v.totalEvents());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bifrost && mvn -q -pl gates -am test -Dtest=FederationAuditTest`
Expected: FAIL — `FederationAudit` does not exist.

- [ ] **Step 3: Implement `FederationAudit`**

Active version = the `version` field of the last ledger entry (ACTIVATE advances it, ROLLBACK sets it to the prior target — the ledger already records the resulting version in `event.version()`, so "last entry's version" is correct for both). Keep it pure and small.

```java
package dev.krillin.bifrost.gates;

import java.util.*;
import dev.krillin.bifrost.core.activation.LedgerEntry;

/** Pure cross-site aggregation of per-site activation ledgers into one federated audit view (F6).
 *  No IO — {@link FederationGate} reads the ledgers and calls {@link #merge}. */
public final class FederationAudit {

    /** One site's rolled-up view: its currently-active version (null if the ledger is empty) and event count. */
    public record SiteView(String site, String activeVersion, int eventCount) {}

    /** The federated view across all sites for one target. */
    public record CrossSiteView(String target, Map<String, SiteView> sites, int totalEvents) {}

    private FederationAudit() {}

    /** @param perSite insertion-ordered site-name -> that site's ledger history for {@code target}. */
    public static CrossSiteView merge(String target, Map<String, List<LedgerEntry>> perSite) {
        Map<String, SiteView> views = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<LedgerEntry>> e : perSite.entrySet()) {
            List<LedgerEntry> hist = e.getValue();
            total += hist.size();
            String active = hist.isEmpty() ? null : hist.get(hist.size() - 1).event().version();
            views.put(e.getKey(), new SiteView(e.getKey(), active, hist.size()));
        }
        return new CrossSiteView(target, views, total);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bifrost && mvn -q -pl gates -am test -Dtest=FederationAuditTest`
Expected: PASS (3/3).

- [ ] **Step 5: Commit**

```bash
cd bifrost && git add gates/src/main/java/dev/krillin/bifrost/gates/FederationAudit.java gates/src/test/java/dev/krillin/bifrost/gates/FederationAuditTest.java && git commit -m "feat(gates): FederationAudit — pure cross-site activation-ledger aggregator (F6)"
```

### Task 4: `FederationGate` CLI + dispatch

**Files:**
- Create: `bifrost/gates/src/main/java/dev/krillin/bifrost/gates/FederationGate.java`
- Modify: `bifrost/gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java`
- Test: `bifrost/gates/src/test/java/dev/krillin/bifrost/gates/FederationGateTest.java`

CLI shape: `gates federation audit <target> --site <name>=<registryDir> [--site <name>=<registryDir> ...]`
Exit codes: 0 = printed the audit (read-only always succeeds if args parse), 2 = usage/error. (No "fail" exit — audit reports, it doesn't gate.)

- [ ] **Step 1: Write the failing test**

Unit-test only the thin CLI contract (arg parsing / usage). The real read+aggregate across two on-disk registries is proven end-to-end by the gate script's F6 (Chunk 3); duplicating a full ledger-on-disk fixture here would couple the test to `ActivationService`/artifact staging for no extra coverage over `FederationAuditTest`.

```java
package dev.krillin.bifrost.gates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FederationGateTest {

    @Test void noArgs_returnsTwo() {
        assertEquals(2, FederationGate.run(new String[]{}));
    }

    @Test void unknownSubcommand_returnsTwo() {
        assertEquals(2, FederationGate.run(new String[]{"bogus", "Line1"}));
    }

    @Test void auditWithoutSites_returnsTwo() {
        assertEquals(2, FederationGate.run(new String[]{"audit", "Line1"}));
    }

    @Test void auditWithMalformedSite_returnsTwo() {
        assertEquals(2, FederationGate.run(new String[]{"audit", "Line1", "--site", "noequalssign"}));
    }

    @Test void auditEmptyRegistry_printsNoneActive_returnsZero(@TempDir Path d) throws Exception {
        // an empty registry has no ledger for the target -> history() is empty -> active=none, exit 0.
        Files.createDirectories(d.resolve("busan"));
        int rc = FederationGate.run(new String[]{"audit", "Line1", "--site", "busan=" + d.resolve("busan")});
        assertEquals(0, rc);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bifrost && mvn -q -pl gates -am test -Dtest=FederationGateTest`
Expected: FAIL — `FederationGate` does not exist.

- [ ] **Step 3: Implement `FederationGate`**

```java
package dev.krillin.bifrost.gates;

import java.nio.file.Path;
import java.util.*;
import dev.krillin.bifrost.core.activation.ActivationLedger;
import dev.krillin.bifrost.core.activation.LedgerEntry;

/** Federation gate. Subcommand:
 *   audit &lt;target&gt; --site &lt;name&gt;=&lt;registryDir&gt; [--site ...]   (0 printed / 2 usage)
 * Read-only: aggregates each site's activation ledger for the target into one cross-site view (F6). */
public final class FederationGate {

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (args.length == 0 || !"audit".equals(args[0])) {
            System.err.println("Usage: federation audit <target> --site <name>=<registryDir> [--site ...]");
            return 2;
        }
        String target = null;
        LinkedHashMap<String, Path> sites = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if ("--site".equals(args[i])) {
                String spec = (++i < args.length) ? args[i] : null;
                if (spec == null || !spec.contains("=")) {
                    System.err.println("[GATE] error: --site expects <name>=<registryDir>");
                    return 2;
                }
                String[] nv = spec.split("=", 2);
                sites.put(nv[0], Path.of(nv[1]));
            } else if (target == null) {
                target = args[i];
            } else {
                System.err.println("[GATE] error: unexpected arg: " + args[i]);
                return 2;
            }
        }
        if (target == null || sites.isEmpty()) {
            System.err.println("Usage: federation audit <target> --site <name>=<registryDir> [--site ...]");
            return 2;
        }
        try {
            Map<String, List<LedgerEntry>> perSite = new LinkedHashMap<>();
            for (Map.Entry<String, Path> s : sites.entrySet()) {
                perSite.put(s.getKey(), new ActivationLedger(s.getValue()).history(target));
            }
            FederationAudit.CrossSiteView v = FederationAudit.merge(target, perSite);
            System.out.println("[GATE] federation-audit target=" + v.target()
                    + " sites=" + v.sites().size() + " totalEvents=" + v.totalEvents());
            for (FederationAudit.SiteView sv : v.sites().values()) {
                System.out.println("  site=" + sv.site()
                        + " active=" + (sv.activeVersion() == null ? "none" : sv.activeVersion())
                        + " events=" + sv.eventCount());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("[GATE] error: " + e.getMessage());
            return 2;
        }
    }
}
```

- [ ] **Step 4: Wire into `GatesCli`**

In `GatesCli.run`, add a `federation` case and extend the usage string. The usage lines:

```java
            System.err.println("Usage: gates <schema|spec|template|adapt-template|policy|provenance|activate|active|activation-log|activation|identity|federation> <args...>");
```

and the switch case (alongside `identity`):

```java
            case "federation":
                return FederationGate.run(rest);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd bifrost && mvn -q -pl gates -am test -Dtest=FederationGateTest`
Expected: PASS (5/5).

- [ ] **Step 6: Full gates module test (no regression)**

Run: `cd bifrost && mvn -q -pl gates -am test`
Expected: BUILD SUCCESS — all existing gate tests still green (PolicyGateTest, etc.).

- [ ] **Step 7: Commit**

```bash
cd bifrost && git add gates/ && git commit -m "feat(gates): federation audit CLI (F6) — cross-site activation-ledger view; wired into GatesCli"
```

---

## Chunk 3: Federation gate script (F1–F6)

`scripts/run-federation-gate.sh` — model precisely on `scripts/run-anchored-activation-gate.sh` (same JAR build / `cygpath -m` / python-mutation / broker harness / `taskkill` cleanup). It stages an **enterprise** git registry + enterprise anchor repo, **two site mirror-clones**, federates identity trust down, then runs the assertions. Pure-CLI legs (F1, F5, F6) always run; runtime legs (F2, F3, F4) run only under Docker.

### Task 5: Write `run-federation-gate.sh`

**Files:**
- Create: `bifrost/scripts/run-federation-gate.sh`
- Uses existing fixtures: `bifrost/scripts/fixtures/activation/*` (spec-mix-recipe-{1.0.0,1.1.0}.json, udt-Line1-Mixer.json, conformance-recipe.json, policy.json) and a site UDT def with `conformsTo` for F1 (see Step 2).

- [ ] **Step 1: Header, harness, enterprise + two-site staging**

Write the script skeleton (header documenting F1–F6 + the honest F5-is-topological note, `set -euo pipefail`, `cd "$(dirname "$0")/.."`, python check, `fail`/`kill_by_mainclass`/`cleanup`+trap exactly as the anchored gate). Then:

- **Build:** `mvn -q -pl core,gates,heimdall,sim -am install -DskipTests`; resolve `GATES_JAR_WIN`, `HEIMDALL_JAR_WIN`, `SIM_JAR_WIN`, `COMPOSE_WIN` via `cygpath -m`.
- **Enterprise registry (authority):** a git repo `build/fed-gate/enterprise` containing the governed templates + policy + `identity/authorized-keys.jsonl` + `identity/activation-policy.json` + the recipe spec artifacts. `git init`, add, commit (this is the mirror source).
- **Identity federation (down):** `gates identity keygen alice --out …` and `bob` into the enterprise `authorized-keys.jsonl`; seed `activation-policy.json` (deny-by-default, alice=activate/bob=approve on Line1/recipe/mix-recipe) — copy the AN-gate block verbatim.
- **Two site mirror-clones:** `git clone <enterprise> build/fed-gate/site-busan` and `…/site-ulsan` (full clone = local-first, §3.3). Each site's registry = its clone dir.
- **Enterprise anchor:** a **separate** git repo `build/fed-gate/enterprise-anchor` (NOT inside any site clone — the topological "different trust domain"). Sites activate with `--anchor-store git --anchor-dir <enterprise-anchor>`.

- [ ] **Step 2: F1 — enterprise template governs both sites (pure CLI, always)**

Add to the enterprise registry a UDT template `udt/Mixer/1.0.0.json` and, per site, a conforming site def + a NON-conforming one. Assert:
- `gates template <siteRegWin> <conformingSiteDef>` → exit 0, `[GATE] PASS`.
- `gates template <siteRegWin> <violatingSiteDef>` → exit 1, `[GATE] FAIL`.

Use a site def whose `conformsTo="Mixer@1.0.0"`; the conforming one tightens a range, the violating one widens past the enterprise envelope. (Reuse `fixtures/activation/udt-Line1-Mixer.json` shape as the template; author two tiny site defs inline in `build/fed-gate/…` via heredoc.)

```bash
echo "[FED] ===== F1: enterprise template governs both sites (site ⊨ enterprise) ====="
for site in busan ulsan; do
  reg_win="$(cygpath -m "$(pwd)/build/fed-gate/site-$site")"
  gates template "$reg_win" "build/fed-gate/site-$site-def-ok.json"   >"$WORK/f1-$site-ok.txt"  2>&1 \
    && grep -q "PASS" "$WORK/f1-$site-ok.txt" || fail "F1 $site conforming def did not PASS"
  set +e; gates template "$reg_win" "build/fed-gate/site-$site-def-bad.json" >"$WORK/f1-$site-bad.txt" 2>&1; rc=$?; set -e
  [ "$rc" -eq 1 ] || { cat "$WORK/f1-$site-bad.txt"; fail "F1 $site non-conforming def expected exit 1"; }
done
echo "[FED] F1 => PASS (both sites conform; non-conforming rejected)"
```

- [ ] **Step 3: F5 — cross-domain anchor rollback detection (pure CLI, always)**

Per site, do two signed activations to the **enterprise anchor**, snapshot the seq-0 ledger+head, activate seq1, then co-rollback the site's local ledger+head to seq0 — the enterprise anchor still witnesses seq1. Assert `verify-anchored --anchor-store git --anchor-dir <enterprise-anchor>` → exit 1 + `identity.anchor.rollback`. This is AN3 re-run **across a site boundary** (anchor in the enterprise domain, not the site registry). Copy the AN3 mechanics; the only change is `--anchor-dir` points at the enterprise anchor and the ledger lives in the site clone.

**Print the honest note** (spec §1/§4-F5): `echo "[FED] NOTE: F5 un-rewritability is TOPOLOGICAL (enterprise anchor is a separate repo the site never rewrites), not cryptographic — production needs a protected off-box remote (out of scope, as in T7)."`

- [ ] **Step 4: F6 — federated audit (pure CLI, always)**

After F5's activations, run the new CLI across both sites and assert it reports each site's active version + a nonzero total:

```bash
echo "[FED] ===== F6: enterprise federated audit across both sites ====="
BUSAN_WIN="$(cygpath -m "$(pwd)/build/fed-gate/site-busan")"
ULSAN_WIN="$(cygpath -m "$(pwd)/build/fed-gate/site-ulsan")"
gates federation audit Line1 --site busan="$BUSAN_WIN" --site ulsan="$ULSAN_WIN" >"$WORK/f6.txt" 2>&1 \
  || fail "F6 federation audit returned nonzero"
grep -q "site=busan" "$WORK/f6.txt" && grep -q "site=ulsan" "$WORK/f6.txt" || { cat "$WORK/f6.txt"; fail "F6 audit missing a site"; }
grep -q "sites=2" "$WORK/f6.txt" || { cat "$WORK/f6.txt"; fail "F6 audit did not report 2 sites"; }
echo "[FED] F6 => PASS (cross-site audit view)"
```

- [ ] **Step 5: F2/F3/F4 — runtime legs (Docker-gated, skippable)**

Guard with `if [ -n "${SKIP_RUNTIME:-}" ] || ! command -v docker …; then echo skipped; else …`. Inside:
- Start `hivemq-ce` (:1883, site busan) and `hivemq-ce-b` (:1884, site ulsan); wait on both ports (the AN8 `/dev/tcp` probe loop, once per port).
- Start two sims: `SIM_BIND_PORT=48400 java -jar sim.jar` (busan) and `SIM_BIND_PORT=48401 java -jar sim.jar` (ulsan); wait for each "OPC-UA sim listening" line in its own log.
- **F3** — activate different versions per site (busan→1.1.0, ulsan→1.0.0) under the federated policy, start each site's Heimdall (busan: `MQTT_URL=tcp://localhost:1883 OPCUA_URL=opc.tcp://localhost:48400 SPB_GROUP=Bifrost:busan ACTIVATION_PATH=<busan clone> ACTIVATION_TARGET=Line1 REGISTRY_PATH/CONFORMANCE_PATH/POLICY_PATH` from the busan clone; ulsan: `:1884`/`:48401`/`Bifrost:ulsan`/ulsan clone). Assert each edge log shows `[BRIDGE] activation bound` with the site's own version and that a rogue command at ulsan is denied independently (reuse the full-loop gate's NCMD authz assertion pattern).
- **F2** — enterprise updates policy → `git -C <enterprise> commit`; `git -C <site clone> pull`; restart that site's Heimdall; assert the new policy is in force (a command that was allowed is now denied, or vice-versa). **Say "restart," not "bind"** — Heimdall reads once at process start.
- **F4** — kill the git link (or just don't pull) and assert the site's Heimdall + broker + sim keep serving from the local clone (observe→command→observe still works offline); on "reconnect," `git fetch` reconciles.

Provide the exact env-block per site modeled on the AN8 Heimdall invocation (lines 365–370 of `run-anchored-activation-gate.sh`). Keep each assertion's wait-loop bounded (20×2s) with a `fail` + log-tail on timeout.

- [ ] **Step 6: Footer**

```bash
echo ""
echo "[FED] GATE PASS (F1 F5 F6${RUNTIME_RAN:+ +F2 F3 F4})"
exit 0
```

- [ ] **Step 7: Controller runs the gate (pure-CLI leg first)**

> **NOTE (controller-direct verification — [[working-style]]):** the CONTROLLER runs this, never a subagent's PASS claim.

Run: `cd bifrost && SKIP_RUNTIME=1 bash scripts/run-federation-gate.sh`
Expected: `[FED] GATE PASS (F1 F5 F6)` exit 0.

Then the full run (Docker up): `cd bifrost && bash scripts/run-federation-gate.sh`
Expected: `[FED] GATE PASS (F1 F5 F6 +F2 F3 F4)` exit 0.

- [ ] **Step 8: Commit**

```bash
cd bifrost && git add scripts/run-federation-gate.sh && git commit -m "test(gate): run-federation-gate — F1 site⊨enterprise / F5 cross-domain anchor rollback / F6 federated audit (+F2/F3/F4 runtime legs under Docker)"
```

### Task 6: No-regression sweep (controller-direct)

- [ ] **Step 1: Full build + all module tests**

Run: `cd bifrost && mvn -q install`
Expected: BUILD SUCCESS; note the per-module test counts (core / heimdall / gates / sim) for the memory record.

- [ ] **Step 2: Re-run the adjacent gates to prove nothing regressed**

The sim change touches the runtime harness; the CLI change touches `GatesCli`. Controller runs:
- `bash scripts/run-anchored-activation-gate.sh` (SKIP_AN8 ok) → `[ANCHORED] GATE PASS`
- `bash scripts/run-identity-gate.sh` → PASS
- `bash scripts/run-command-authz-gate.sh` → PASS
- `bash scripts/run-composable-conformance-gate.sh` → PASS

Expected: all PASS. (If any fails, STOP — regression, not "close enough.")

---

## Chunk 4: Reproduction doc (lab)

### Task 7: Federation reproduction deep-dive

**Files:**
- Create: `lab/docs/reproduce/multisite-federation.md`
- Modify: `lab/docs/reproduce/README.md`

- [ ] **Step 1: Write the deep-dive**

Follow the existing `lab/docs/reproduce/anchored-activation.md` structure: what it proves (enterprise multi-site governance + the federation shape of T7's anchor), exact commands (`SKIP_RUNTIME=1 bash scripts/run-federation-gate.sh` and the full run), the honest F5-is-topological caveat, and the **captured real output** (paste the controller's actual gate log into `lab/docs/reproduce/outputs/federation-gate.log`). Do NOT claim any assertion that wasn't actually run — mirror the honesty discipline already applied to I7/AN8.

- [ ] **Step 2: Index it**

Add a row to `lab/docs/reproduce/README.md`'s experiment index/matrix for the federation gate (F1/F5/F6 always; F2/F3/F4 Docker-gated).

- [ ] **Step 3: Commit (lab repo, feat branch)**

```bash
cd "sparkplug-governance-lab" && git add docs/reproduce/ && git commit -m "docs(reproduce): multi-site federation gate — F1/F5/F6 (+F2/F3/F4) reproduction + captured output"
```

---

## Done criteria

- [ ] `mvn install` green in bifrost; new module test counts recorded.
- [ ] `run-federation-gate.sh` PASSES both `SKIP_RUNTIME=1` and full (Docker) — **controller-run**, output captured.
- [ ] 4 no-regression gates PASS (anchored, identity, command-authz, composable-conformance).
- [ ] Reproduction doc + captured log committed to lab.
- [ ] Everything on feat branches; **merge/push Eisen-gated** (bifrost: a new `feat/multisite-federation` branch; lab: `feat/multisite-federation-spec`). No direct main commits.

## Open decisions to surface to Eisen before/at execution

1. **Build go/no-go.** This plan is the design deliverable; per the standing agreement the actual build is a separate decision. Confirm before Task 1.
2. **bifrost branch name** for the code (suggest `feat/multisite-federation`), created off `main` — not committed to main.
3. **How honest to make F5 in the demo** — this plan prints the "topological, not cryptographic" note in the gate output. Confirm that's the desired framing (vs. adding a real protected-remote leg, which the spec scoped OUT).
