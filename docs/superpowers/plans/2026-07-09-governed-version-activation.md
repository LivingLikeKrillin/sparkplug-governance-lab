# Governed Version-Activation (T3) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "which governed recipe version is active at an edge" a governed, four-eyes-approved, audited, content-verified, reversible EVENT — and make Heimdall bind to that active version at startup (closing the loop) with an edge-provenance sha256 check.

**Architecture:** A new pure `core.activation` package owns the immutable event, the append-only JSONL ledger, an `ArtifactResolver` port (recipe adapter over the existing `MasterSpecStore`/`spec/`), and an `ActivationService` (fail-closed: artifact-resolvable + four-eyes SoD + rollback-guard). A `gates` CLI leg drives it. Heimdall's `loadConformance` resolves the active recipe version from the ledger and **verifies the spec bytes' sha256 before parsing** (verify-then-trust), fail-closed on absent pointer / absent file / content mismatch.

**Tech Stack:** Java 17, Jackson (`core.schema.JsonMapperFactory`), JUnit 5, Maven. Branch `feat/yggdrasil-spine` (commit locally, do NOT push). Package root `dev.krillin.bifrost`.

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-09-governed-version-activation-design.md`

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `core/.../conformance/MasterSpecStore.java` | add a pure `file(reg,ref,ver)` path accessor; refactor `load` to use it | Modify |
| `core/.../activation/Sha256.java` | tiny `hex(byte[])` util (shared by resolver + Heimdall) | Create |
| `core/.../activation/ActivationEvent.java` | immutable governed event (JSONL line) | Create |
| `core/.../activation/ActivationLedger.java` | append-only JSONL: `append`/`active`/`history` | Create |
| `core/.../activation/ArtifactResolver.java` | port `Optional<ResolvedArtifact> resolve(kind,ref,ver)` + `ResolvedArtifact` | Create |
| `core/.../activation/RecipeArtifactResolver.java` | recipe adapter over `MasterSpecStore` `spec/` (bytes+sha, parse-validate) | Create |
| `core/.../activation/ActivationService.java` | governance logic (fail-closed) + `ActivationRequest`/`ActivationVerdict` | Create |
| `gates/.../ActivateGate.java` | `activate`/`active`/`activation-log` CLI legs | Create |
| `gates/.../GatesCli.java` | dispatch + usage | Modify |
| `heimdall/.../NcmdOpcUaBridgeMain.java` | `Config` gains `activationPath`/`activationTarget`; `loadConformance` edge-provenance bind | Modify |
| `scripts/fixtures/activation/*` | recipe-mode conformance policy + 2 MasterSpec versions | Create |
| `scripts/run-activation-gate.sh` | A1 audit / A2 SoD / A3a refuse / A3b edge-provenance / A4 flip / A5 rollback | Create |

**Existing shapes (verified against code):**
- `MasterSpec(String specRef, String version, String site, String equipmentRef, String equipmentVersion, List<Setpoint> setpoints)` · `Setpoint(String member, String type, double value)` (in `core.schema`).
- `MasterSpecStore.load(Path registryDir, String ref, String version) → Optional<MasterSpec>` reads `spec/<ref>/<version>.json` (already per-version).
- `ConformancePolicy(policyRef,version,equipmentRef,equipmentVersion,Dial dial,List<CrossConstraint>,List<NodeBinding>)`; `Dial(String mode, String activeRecipeRef, String activeRecipeVersion, Double recipeTolerance)`.
- `Violation(String rule, String detail)` (in `core.schema`); verdicts model on `ConformanceVerdict(boolean ok, List<Violation>)`.
- `JsonMapperFactory.create()` → Jackson `ObjectMapper` (records round-trip; `.readValue(byte[]|File|String, Class)`, `.writeValueAsString(obj)`).
- `NcmdOpcUaBridgeMain.Config` (package-private record) + `resolve(Function<String,String> getenv)` + `env(getenv,key,dflt)`; `loadConformance(Config)`; `Conformance(UdtDefinition def, ConformancePolicy policy, MasterSpec recipe)`.
- `GatesCli.run` dispatches `switch(args[0]) → XxxGate.run(rest)`.
- Recipe-mode conformance is an ADMISSIBILITY gate (`ConformanceEvaluator`): NCMD value must satisfy `|value−target| ≤ |target|·tol`, else `conformance.recipe.deviation`. The recipe supplies no value.

---

## Chunk 1: core.activation (governed event + ledger + resolver + service)

### Task 1: `MasterSpecStore` path accessor (spec §6 prerequisite)

**Files:** Modify `core/src/main/java/dev/krillin/bifrost/core/conformance/MasterSpecStore.java`. Test: `core/src/test/java/dev/krillin/bifrost/core/conformance/MasterSpecStoreTest.java`.

`load(reg,ref,ver)` already resolves a specific version — CONFIRM by reading the file. T3 additionally needs the **path** (to read raw bytes for hashing). Add a pure `file(...)` helper and refactor `load` to use it (DRY, no behavior change).

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.conformance;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterSpecStoreTest {
    @Test void fileResolvesPerVersionPath(@TempDir Path reg) throws Exception {
        Path f = new MasterSpecStore().file(reg, "mix-recipe", "1.0.0");
        assertEquals(reg.resolve("spec").resolve("mix-recipe").resolve("1.0.0.json"), f);
    }
    @Test void loadRoundTripsAtThatPath(@TempDir Path reg) throws Exception {
        MasterSpec spec = new MasterSpec("mix-recipe","1.0.0","Line1","Line1-Mixer","1.0.0",
            List.of(new Setpoint("Rpm","Double",1500)));
        Path f = new MasterSpecStore().file(reg, "mix-recipe", "1.0.0");
        Files.createDirectories(f.getParent());
        JsonMapperFactory.create().writeValue(f.toFile(), spec);
        assertEquals(spec, new MasterSpecStore().load(reg, "mix-recipe", "1.0.0").orElseThrow());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=MasterSpecStoreTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (`file` missing).

- [ ] **Step 3: Implement** — add the helper and refactor `load` to use it:

```java
/** Pure path of the pinned recipe artifact: <registryDir>/spec/<ref>/<version>.json. */
public Path file(Path registryDir, String ref, String version) {
    return registryDir.resolve("spec").resolve(ref).resolve(version + ".json");
}

public Optional<MasterSpec> load(Path registryDir, String ref, String version) throws IOException {
    Path f = file(registryDir, ref, version);
    if (!Files.isRegularFile(f)) return Optional.empty();
    return Optional.of(mapper.readValue(f.toFile(), MasterSpec.class));
}
```
(Add `import java.nio.file.Path;` if not present — it is.)

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/conformance/MasterSpecStore.java core/src/test/java/dev/krillin/bifrost/core/conformance/MasterSpecStoreTest.java` ; `git commit -m "refactor(core): MasterSpecStore.file() path accessor (T3 activation prerequisite; load unchanged)"`.

### Task 2: `Sha256` util + `ActivationEvent` record

**Files:** Create `core/.../activation/Sha256.java`, `ActivationEvent.java`. Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationEventTest.java`.

- [ ] **Step 1: Write the failing test** (JSONL round-trip + deterministic hash):

```java
package dev.krillin.bifrost.core.activation;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import org.junit.jupiter.api.Test;

class ActivationEventTest {
    @Test void jsonRoundTrips() throws Exception {
        ObjectMapper m = JsonMapperFactory.create();
        ActivationEvent e = new ActivationEvent("Line1","recipe","mix-recipe","1.1.0",
            "abc123","alice","bob",1720000000000L,"1.0.0","ACTIVATE");
        String line = m.writeValueAsString(e);
        assertFalse(line.contains("\n"));                       // single JSONL line
        assertEquals(e, m.readValue(line, ActivationEvent.class));
    }
    @Test void sha256IsStableHex() {
        assertEquals(Sha256.hex("hello".getBytes()),
                     "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=ActivationEventTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement**

```java
// Sha256.java
package dev.krillin.bifrost.core.activation;
import java.security.MessageDigest;
/** Lowercase-hex SHA-256 — the one content-hash discipline shared by the activation resolver and the edge check. */
public final class Sha256 {
    private Sha256() {}
    public static String hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder(d.length * 2);
            for (byte x : d) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```
```java
// ActivationEvent.java
package dev.krillin.bifrost.core.activation;
/** One immutable, audited activation act. Appended verbatim as a JSONL line to the ledger.
 *  contentSha256 = sha256 of the exact runtime bytes activated (spec/<ref>/<version>.json) — the
 *  four-eyes-attested seal the edge re-checks. action = ACTIVATE | ROLLBACK. */
public record ActivationEvent(String target, String kind, String ref, String version,
                              String contentSha256, String activatedBy, String approvedBy,
                              long activatedAt, String priorVersion, String action) {}
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/activation/Sha256.java core/src/main/java/dev/krillin/bifrost/core/activation/ActivationEvent.java core/src/test/java/dev/krillin/bifrost/core/activation/ActivationEventTest.java` ; `git commit -m "feat(core): activation — ActivationEvent (governed JSONL event) + Sha256 util"`.

### Task 3: `ActivationLedger` (append-only JSONL)

**Files:** Create `core/.../activation/ActivationLedger.java`. Test: `ActivationLedgerTest.java`.

- [ ] **Step 1: Write the failing test** (append/active last-wins/multi-artifact separation/history order):

```java
package dev.krillin.bifrost.core.activation;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivationLedgerTest {
    static ActivationEvent ev(String ref,String ver,String action){
        return new ActivationEvent("Line1","recipe",ref,ver,"sha","alice","bob",1L,null,action);
    }
    @Test void appendActiveHistory(@TempDir Path reg) throws Exception {
        ActivationLedger led = new ActivationLedger(reg);
        assertTrue(led.active("Line1","recipe","mix-recipe").isEmpty());      // none yet
        led.append(ev("mix-recipe","1.0.0","ACTIVATE"));
        led.append(ev("other","5.0.0","ACTIVATE"));                          // different ref
        led.append(ev("mix-recipe","1.1.0","ACTIVATE"));
        assertEquals("1.1.0", led.active("Line1","recipe","mix-recipe").orElseThrow().version()); // last wins
        assertEquals("5.0.0", led.active("Line1","recipe","other").orElseThrow().version());       // separated
        assertEquals(3, led.history("Line1").size());                        // full ordered trail
        assertEquals("1.0.0", led.history("Line1").get(0).version());
    }
    @Test void missingTargetIsEmpty(@TempDir Path reg) throws Exception {
        assertTrue(new ActivationLedger(reg).history("Nope").isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=ActivationLedgerTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement**

```java
package dev.krillin.bifrost.core.activation;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Append-only JSONL audit ledger at registry/activation/<target>.jsonl. The LAST event per (kind,ref)
 *  is the current active pointer. Single control-plane writer (no concurrent-writer coordination). */
public final class ActivationLedger {
    private final Path root;
    private final ObjectMapper mapper = JsonMapperFactory.create();
    public ActivationLedger(Path registryRoot) { this.root = registryRoot; }

    private Path file(String target) { return root.resolve("activation").resolve(target + ".jsonl"); }

    public void append(ActivationEvent e) throws IOException {
        Path f = file(e.target());
        Files.createDirectories(f.getParent());
        Files.writeString(f, mapper.writeValueAsString(e) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public List<ActivationEvent> history(String target) throws IOException {
        Path f = file(target);
        if (!Files.isRegularFile(f)) return List.of();
        List<ActivationEvent> out = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
            if (!line.isBlank()) out.add(mapper.readValue(line, ActivationEvent.class));
        }
        return out;
    }

    public Optional<ActivationEvent> active(String target, String kind, String ref) throws IOException {
        ActivationEvent found = null;
        for (ActivationEvent e : history(target)) {
            if (e.kind().equals(kind) && e.ref().equals(ref)) found = e;   // last match wins
        }
        return Optional.ofNullable(found);
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationLedger.java core/src/test/java/dev/krillin/bifrost/core/activation/ActivationLedgerTest.java` ; `git commit -m "feat(core): ActivationLedger — append-only JSONL, last-event-per-(kind,ref) active pointer"`.

### Task 4: `ArtifactResolver` port + `RecipeArtifactResolver`

**Files:** Create `core/.../activation/ArtifactResolver.java`, `RecipeArtifactResolver.java`. Test: `RecipeArtifactResolverTest.java`.

- [ ] **Step 1: Write the failing test** (resolve+hash a real spec file; refuse absent; refuse invalid JSON):

```java
package dev.krillin.bifrost.core.activation;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import dev.krillin.bifrost.core.conformance.MasterSpecStore;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecipeArtifactResolverTest {
    @Test void resolvesBytesAndSha(@TempDir Path reg) throws Exception {
        MasterSpec spec = new MasterSpec("mix-recipe","1.0.0","Line1","Line1-Mixer","1.0.0",
            List.of(new Setpoint("Rpm","Double",1500)));
        Path f = new MasterSpecStore().file(reg,"mix-recipe","1.0.0");
        Files.createDirectories(f.getParent());
        JsonMapperFactory.create().writeValue(f.toFile(), spec);
        var r = new RecipeArtifactResolver(reg).resolve("recipe","mix-recipe","1.0.0").orElseThrow();
        assertEquals(f, r.path());
        assertEquals(Sha256.hex(Files.readAllBytes(f)), r.sha256());
    }
    @Test void absentVersionIsEmpty(@TempDir Path reg) {
        assertTrue(new RecipeArtifactResolver(reg).resolve("recipe","mix-recipe","9.9.9").isEmpty());
    }
    @Test void nonRecipeKindIsEmpty(@TempDir Path reg) {
        assertTrue(new RecipeArtifactResolver(reg).resolve("equipment","x","1.0.0").isEmpty());
    }
    @Test void invalidJsonIsEmpty(@TempDir Path reg) throws Exception {
        Path f = new MasterSpecStore().file(reg,"broken","1.0.0");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{ not valid master spec");
        assertTrue(new RecipeArtifactResolver(reg).resolve("recipe","broken","1.0.0").isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=RecipeArtifactResolverTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement**

```java
// ArtifactResolver.java
package dev.krillin.bifrost.core.activation;
import java.nio.file.Path;
import java.util.Optional;
/** Resolves a governed runtime artifact (kind,ref,version) to its on-disk bytes' identity. Empty ⇒
 *  unresolvable (absent or invalid) ⇒ activation refuses. Decouples ActivationService from any one store. */
public interface ArtifactResolver {
    Optional<ResolvedArtifact> resolve(String kind, String ref, String version);
    record ResolvedArtifact(Path path, String sha256) {}
}
```
```java
// RecipeArtifactResolver.java
package dev.krillin.bifrost.core.activation;
import dev.krillin.bifrost.core.conformance.MasterSpecStore;
import java.nio.file.*;
import java.util.Optional;
/** recipe kind → the runtime MasterSpec at spec/<ref>/<version>.json (the store Heimdall actually binds).
 *  Refuses (empty) if the file is absent or does not parse as a MasterSpec; else returns path + sha256 of
 *  the exact file bytes. Non-recipe kinds are not yet resolvable (return empty — honest, recipe is demoed). */
public final class RecipeArtifactResolver implements ArtifactResolver {
    private final Path registryDir;
    private final MasterSpecStore store = new MasterSpecStore();
    public RecipeArtifactResolver(Path registryDir) { this.registryDir = registryDir; }

    public Optional<ResolvedArtifact> resolve(String kind, String ref, String version) {
        if (!"recipe".equals(kind)) return Optional.empty();
        try {
            Path f = store.file(registryDir, ref, version);
            if (!Files.isRegularFile(f)) return Optional.empty();
            if (store.load(registryDir, ref, version).isEmpty()) return Optional.empty(); // parse-validate
            return Optional.of(new ResolvedArtifact(f, Sha256.hex(Files.readAllBytes(f))));
        } catch (Exception e) { return Optional.empty(); }   // invalid/unreadable ⇒ unresolvable (fail-closed)
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/activation/ArtifactResolver.java core/src/main/java/dev/krillin/bifrost/core/activation/RecipeArtifactResolver.java core/src/test/java/dev/krillin/bifrost/core/activation/RecipeArtifactResolverTest.java` ; `git commit -m "feat(core): ArtifactResolver port + RecipeArtifactResolver (spec/ MasterSpec bytes+sha, parse-validated)"`.

### Task 5: `ActivationService` (governance logic, fail-closed)

**Files:** Create `core/.../activation/ActivationRequest.java`, `ActivationVerdict.java`, `ActivationService.java`. Test: `ActivationServiceTest.java`.

Order of checks (spec §4.4): artifact-resolvable → SoD (missing, self) → rollback-guard → priorVersion → append. Inject `java.time.Clock` for deterministic `activatedAt`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.krillin.bifrost.core.activation;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import dev.krillin.bifrost.core.conformance.MasterSpecStore;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ActivationServiceTest {
    Path reg;
    ActivationService svc;
    ActivationLedger led;
    @BeforeEach void setup(@TempDir Path dir) throws Exception {
        reg = dir;
        writeSpec("mix-recipe","1.0.0",1500);
        writeSpec("mix-recipe","1.1.0",1600);
        led = new ActivationLedger(reg);
        svc = new ActivationService(new RecipeArtifactResolver(reg), led,
                Clock.fixed(Instant.ofEpochMilli(42L), ZoneOffset.UTC));
    }
    void writeSpec(String ref,String ver,double rpm) throws Exception {
        MasterSpec s = new MasterSpec(ref,ver,"Line1","Line1-Mixer","1.0.0",List.of(new Setpoint("Rpm","Double",rpm)));
        Path f = new MasterSpecStore().file(reg,ref,ver); Files.createDirectories(f.getParent());
        JsonMapperFactory.create().writeValue(f.toFile(), s);
    }
    ActivationRequest req(String ver,String by,String appr,boolean rb){
        return new ActivationRequest("Line1","recipe","mix-recipe",ver,by,appr,rb);
    }
    @Test void happyPathAppendsSealedEvent() throws Exception {
        var v = svc.activate(req("1.0.0","alice","bob",false));
        assertTrue(v.ok());
        assertEquals("ACTIVATE", v.event().action());
        assertEquals(42L, v.event().activatedAt());
        assertNull(v.event().priorVersion());
        assertEquals(new RecipeArtifactResolver(reg).resolve("recipe","mix-recipe","1.0.0").orElseThrow().sha256(),
                     v.event().contentSha256());                       // sealed with the real bytes' hash
        assertEquals("1.0.0", led.active("Line1","recipe","mix-recipe").orElseThrow().version());
    }
    @Test void priorVersionComputed() throws Exception {
        svc.activate(req("1.0.0","alice","bob",false));
        var v = svc.activate(req("1.1.0","alice","bob",false));
        assertEquals("1.0.0", v.event().priorVersion());
    }
    @Test void unresolvedArtifactRefused() throws Exception {
        var v = svc.activate(req("9.9.9","alice","bob",false));
        assertFalse(v.ok());
        assertTrue(hasRule(v,"activation.artifact.unresolved"));
        assertTrue(led.history("Line1").isEmpty());                    // ledger untouched
    }
    @Test void missingApprovalRefused() throws Exception {
        assertTrue(hasRule(svc.activate(req("1.0.0","alice","  ",false)),"activation.approval.missing"));
    }
    @Test void selfApprovalRefused() throws Exception {
        assertTrue(hasRule(svc.activate(req("1.0.0","alice","alice",false)),"activation.approval.self"));
    }
    @Test void rollbackToUnknownVersionRefused() throws Exception {
        svc.activate(req("1.0.0","alice","bob",false));
        assertTrue(hasRule(svc.activate(req("1.1.0","alice","bob",true)),"activation.rollback.unknown-version"));
    }
    @Test void rollbackToKnownVersionRecordsReversal() throws Exception {
        svc.activate(req("1.0.0","alice","bob",false));
        svc.activate(req("1.1.0","alice","bob",false));
        var v = svc.activate(req("1.0.0","alice","bob",true));
        assertTrue(v.ok());
        assertEquals("ROLLBACK", v.event().action());
        assertEquals("1.0.0", led.active("Line1","recipe","mix-recipe").orElseThrow().version());
    }
    static boolean hasRule(ActivationVerdict v,String rule){
        return !v.ok() && v.violations().stream().anyMatch(x -> x.rule().equals(rule));
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core test -Dtest=ActivationServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement**

```java
// ActivationRequest.java
package dev.krillin.bifrost.core.activation;
public record ActivationRequest(String target, String kind, String ref, String version,
                                String by, String approvedBy, boolean rollback) {}
```
```java
// ActivationVerdict.java
package dev.krillin.bifrost.core.activation;
import java.util.List;
import dev.krillin.bifrost.core.schema.Violation;
/** ok ⇒ event is the appended ActivationEvent, violations empty. refused ⇒ event null, ledger untouched. */
public record ActivationVerdict(boolean ok, ActivationEvent event, List<Violation> violations) {}
```
```java
// ActivationService.java
package dev.krillin.bifrost.core.activation;
import java.time.Clock;
import java.util.*;
import dev.krillin.bifrost.core.schema.Violation;

/** Governs the activation act: only resolvable+content-sealed bytes, four-eyes SoD, guarded rollback.
 *  Fail-closed — any check fails ⇒ refuse, ledger untouched. */
public final class ActivationService {
    private final ArtifactResolver resolver;
    private final ActivationLedger ledger;
    private final Clock clock;
    public ActivationService(ArtifactResolver resolver, ActivationLedger ledger, Clock clock) {
        this.resolver = resolver; this.ledger = ledger; this.clock = clock;
    }

    public ActivationVerdict activate(ActivationRequest r) {
        try {
            // 1. artifact resolvable + content hash (the seal)
            var resolved = resolver.resolve(r.kind(), r.ref(), r.version());
            if (resolved.isEmpty()) return refuse("activation.artifact.unresolved",
                    r.kind() + " " + r.ref() + "@" + r.version() + " is not a resolvable governed artifact");
            // 2. four-eyes SoD
            if (r.approvedBy() == null || r.approvedBy().isBlank())
                return refuse("activation.approval.missing", "activation requires a distinct approver (--approved-by)");
            if (r.approvedBy().equals(r.by()))
                return refuse("activation.approval.self", "approver '" + r.by() + "' must differ from the activator (four-eyes)");
            // 3. rollback guard
            if (r.rollback() && !versionInHistory(r))
                return refuse("activation.rollback.unknown-version",
                        "cannot rollback to " + r.version() + " — never activated on target " + r.target());
            // 4. prior active
            String prior = ledger.active(r.target(), r.kind(), r.ref()).map(ActivationEvent::version).orElse(null);
            // 5. append the sealed event
            ActivationEvent e = new ActivationEvent(r.target(), r.kind(), r.ref(), r.version(),
                    resolved.get().sha256(), r.by(), r.approvedBy(), clock.millis(), prior,
                    r.rollback() ? "ROLLBACK" : "ACTIVATE");
            ledger.append(e);
            return new ActivationVerdict(true, e, List.of());
        } catch (Exception ex) {
            return refuse("activation.error", ex.getMessage());
        }
    }

    private boolean versionInHistory(ActivationRequest r) throws Exception {
        for (ActivationEvent e : ledger.history(r.target()))
            if (e.kind().equals(r.kind()) && e.ref().equals(r.ref()) && e.version().equals(r.version())) return true;
        return false;
    }
    private static ActivationVerdict refuse(String rule, String detail) {
        return new ActivationVerdict(false, null, List.of(new Violation(rule, detail)));
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationRequest.java core/src/main/java/dev/krillin/bifrost/core/activation/ActivationVerdict.java core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceTest.java` ; `git commit -m "feat(core): ActivationService — fail-closed artifact-seal + four-eyes SoD + guarded rollback"`.

---

## Chunk 2: gates activation CLI

### Task 6: `ActivateGate` + `GatesCli` legs

**Files:** Create `gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java`. Modify `gates/.../GatesCli.java`. Test: `gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateTest.java`.

Three subcommands routed from `GatesCli`: `activate` / `active` / `activation-log`. The `activate` arg parse is a self-contained positional + flag scan (`--by`/`--approved-by` each take a value; `--rollback` is a bare flag) — the same order-agnostic style as `core.schema.RecipePublish`'s `--kind` scan, but no dependency on it.

- [ ] **Step 1: Write the failing test** (`@TempDir`, real spec files + ledger):

```java
package dev.krillin.bifrost.gates;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import dev.krillin.bifrost.core.conformance.MasterSpecStore;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ActivateGateTest {
    Path reg;
    @BeforeEach void setup(@TempDir Path dir) throws Exception {
        reg = dir;
        MasterSpec s = new MasterSpec("mix-recipe","1.0.0","Line1","Line1-Mixer","1.0.0",
            List.of(new Setpoint("Rpm","Double",1500)));
        Path f = new MasterSpecStore().file(reg,"mix-recipe","1.0.0"); Files.createDirectories(f.getParent());
        JsonMapperFactory.create().writeValue(f.toFile(), s);
    }
    int run(String... a){ return ActivateGate.run(a); }

    @Test void activateThenActive() {
        assertEquals(0, run("activate", reg.toString(), "Line1","recipe","mix-recipe","1.0.0","--by","alice","--approved-by","bob"));
        assertEquals(0, run("active", reg.toString(), "Line1","recipe","mix-recipe"));
    }
    @Test void selfApprovalRefused() {
        assertEquals(1, run("activate", reg.toString(), "Line1","recipe","mix-recipe","1.0.0","--by","alice","--approved-by","alice"));
    }
    @Test void missingApprovalRefused() {
        assertEquals(1, run("activate", reg.toString(), "Line1","recipe","mix-recipe","1.0.0","--by","alice"));
    }
    @Test void unresolvedRefused() {
        assertEquals(1, run("activate", reg.toString(), "Line1","recipe","mix-recipe","9.9.9","--by","alice","--approved-by","bob"));
    }
    @Test void usageErrors() {
        assertEquals(2, run("activate", reg.toString(), "Line1"));       // too few args
        assertEquals(2, run());                                          // no subcommand
    }
    @Test void logListsHistory() {
        run("activate", reg.toString(), "Line1","recipe","mix-recipe","1.0.0","--by","alice","--approved-by","bob");
        assertEquals(0, run("activation-log", reg.toString(), "Line1"));
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core,gates test -Dtest=ActivateGateTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement `ActivateGate`**

```java
package dev.krillin.bifrost.gates;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import dev.krillin.bifrost.core.activation.*;
import dev.krillin.bifrost.core.schema.Violation;

/** Activation ③-adjacent gate. Subcommands:
 *   activate <reg> <target> <kind> <ref> <version> --by <p> --approved-by <p> [--rollback]  (0 ok / 1 refused / 2 usage)
 *   active   <reg> <target> <kind> <ref>                                                    (prints active version+sha or none)
 *   activation-log <reg> <target>                                                           (prints the audit trail) */
public final class ActivateGate {
    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (args.length == 0) { usage(); return 2; }
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (sub) {
                case "activate": return activate(rest);
                case "active": return active(rest);
                case "activation-log": return log(rest);
                default: usage(); return 2;
            }
        } catch (Exception e) { System.err.println("[GATE] error: " + e.getMessage()); return 2; }
    }

    private static int activate(String[] a) throws Exception {
        String by = null, approvedBy = null; boolean rollback = false;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--by" -> by = (++i < a.length) ? a[i] : null;
                case "--approved-by" -> approvedBy = (++i < a.length) ? a[i] : null;
                case "--rollback" -> rollback = true;
                default -> pos.add(a[i]);
            }
        }
        if (pos.size() < 5) { System.err.println("Usage: activate <reg> <target> <kind> <ref> <version> --by <p> --approved-by <p> [--rollback]"); return 2; }
        Path reg = Path.of(pos.get(0));
        ActivationService svc = new ActivationService(new RecipeArtifactResolver(reg), new ActivationLedger(reg), Clock.systemUTC());
        ActivationVerdict v = svc.activate(new ActivationRequest(pos.get(1), pos.get(2), pos.get(3), pos.get(4), by, approvedBy, rollback));
        if (v.ok()) {
            ActivationEvent e = v.event();
            System.out.println("[GATE] activated target=" + e.target() + " kind=" + e.kind() + " ref=" + e.ref()
                + " version=" + e.version() + " action=" + e.action() + " by=" + e.activatedBy()
                + " approvedBy=" + e.approvedBy() + " sha256=" + e.contentSha256());
            return 0;
        }
        System.out.println("[GATE] REFUSED:");
        for (Violation viol : v.violations()) System.out.println("  - [" + viol.rule() + "] " + viol.detail());
        return 1;
    }

    private static int active(String[] a) throws Exception {
        if (a.length < 4) { System.err.println("Usage: active <reg> <target> <kind> <ref>"); return 2; }
        Path reg = Path.of(a[0]);
        Optional<ActivationEvent> e = new ActivationLedger(reg).active(a[1], a[2], a[3]);
        if (e.isEmpty()) { System.out.println("[GATE] active target=" + a[1] + " " + a[2] + "/" + a[3] + " => none"); return 0; }
        System.out.println("[GATE] active target=" + a[1] + " " + a[2] + "/" + a[3] + " => version=" + e.get().version()
            + " sha256=" + e.get().contentSha256() + " (by " + e.get().activatedBy() + ", approvedBy " + e.get().approvedBy() + ")");
        return 0;
    }

    private static int log(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("Usage: activation-log <reg> <target>"); return 2; }
        List<ActivationEvent> hist = new ActivationLedger(Path.of(a[0])).history(a[1]);
        System.out.println("[GATE] activation-log target=" + a[1] + " events=" + hist.size());
        for (ActivationEvent e : hist)
            System.out.println("  " + e.action() + " " + e.kind() + "/" + e.ref() + "@" + e.version()
                + " by=" + e.activatedBy() + " approvedBy=" + e.approvedBy() + " prior=" + e.priorVersion() + " sha256=" + e.contentSha256());
        return 0;
    }

    private static void usage() { System.err.println("Usage: <activate|active|activation-log> ..."); }
}
```
Add to `GatesCli.run` switch: `case "activate": case "active": case "activation-log": return ActivateGate.run(args);` — NOTE: pass **`args`** (not `rest`) so `ActivateGate` sees the subcommand as `args[0]`. Update the usage string to `<schema|spec|template|adapt-template|policy|provenance|activate|active|activation-log>`.

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git add gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateTest.java` ; `git commit -m "feat(gates): activate|active|activation-log CLI legs over ActivationService"`.

---

## Chunk 3: Heimdall edge-provenance binding (closing the loop)

### Task 7: `Config` gains activation env

**Files:** Modify `heimdall/.../NcmdOpcUaBridgeMain.java`. Test: extend/`heimdall/src/test/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMainConfigTest.java` (create if absent).

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.heimdall;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NcmdOpcUaBridgeMainConfigTest {
    @Test void activationEnvResolved() {
        var cfg = NcmdOpcUaBridgeMain.resolve(Map.of(
            "ACTIVATION_TARGET","Line1", "ACTIVATION_PATH","/reg")::get);
        assertEquals("Line1", cfg.activationTarget());
        assertEquals("/reg", cfg.activationPath());
    }
    @Test void activationDefaultsNull() {
        var cfg = NcmdOpcUaBridgeMain.resolve(Map.<String,String>of()::get);
        assertNull(cfg.activationTarget());
        assertNull(cfg.activationPath());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core,heimdall test -Dtest=NcmdOpcUaBridgeMainConfigTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (compile: no `activationTarget()`).

- [ ] **Step 3: Implement** — extend the `Config` record + `resolve`:

```java
record Config(String broker, String opcua, String group, String edge, String policyPath,
              String registryPath, String conformancePath, String activationPath, String activationTarget) {}

static Config resolve(Function<String, String> getenv) {
    String broker = env(getenv, "MQTT_URL", "tcp://localhost:1883");
    String opcua = env(getenv, "OPCUA_URL", "opc.tcp://localhost:48400");
    String group = env(getenv, "SPB_GROUP", "Bifrost:Line1");
    String edge = env(getenv, "SPB_EDGE", "recipe-edge");
    String policyPath = env(getenv, "POLICY_PATH", "registry/policy.json");
    String registryPath = env(getenv, "REGISTRY_PATH", "registry");
    String conformancePath = env(getenv, "CONFORMANCE_PATH", null);
    String activationPath = env(getenv, "ACTIVATION_PATH", null);
    String activationTarget = env(getenv, "ACTIVATION_TARGET", null);
    return new Config(broker, opcua, group, edge, policyPath, registryPath, conformancePath, activationPath, activationTarget);
}
```
(Every existing `new Config(...)` call site must gain the two args — there is one, in `resolve`.)

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(heimdall): Config carries ACTIVATION_PATH/ACTIVATION_TARGET (null ⇒ legacy dial)"`.

### Task 8: `loadConformance` edge-provenance bind (verify-then-trust)

**Files:** Modify `heimdall/.../NcmdOpcUaBridgeMain.java` (`loadConformance`). Test: `heimdall/src/test/java/dev/krillin/bifrost/heimdall/LoadConformanceActivationTest.java`.

When `activationTarget` is set and the policy is recipe-mode, resolve the active recipe **version** from the ledger, verify the spec bytes' sha256 **before** parsing, fail-closed on absent pointer / absent file / mismatch. When unset → unchanged dial behavior.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.heimdall;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.List;
import dev.krillin.bifrost.core.activation.*;
import dev.krillin.bifrost.core.conformance.*;
import dev.krillin.bifrost.core.schema.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LoadConformanceActivationTest {
    Path reg, confFile;
    @BeforeEach void setup(@TempDir Path dir) throws Exception {
        reg = dir.resolve("registry"); Files.createDirectories(reg);
        // equipment def the policy references
        Path udt = reg.resolve("udt").resolve("Line1-Mixer").resolve("1.0.0.json"); Files.createDirectories(udt.getParent());
        JsonMapperFactory.create().writeValue(udt.toFile(),
            new UdtDefinition("Line1-Mixer", SemVer.parse("1.0.0"),
                List.of(new Member("Rpm","Double","corp:rpm", new Range(0,3000))), List.of(), null));
        writeSpec("mix-recipe","1.0.0",1500);
        writeSpec("mix-recipe","1.1.0",1600);
        // recipe-mode conformance policy, dial version is deliberately WRONG (0.0.0) to prove the ledger overrides it
        ConformancePolicy cp = new ConformancePolicy("Line1-pol","1.0.0","Line1-Mixer","1.0.0",
            new ConformancePolicy.Dial("recipe","mix-recipe","0.0.0",0.0), List.of(), List.of());
        confFile = dir.resolve("conformance.json");
        JsonMapperFactory.create().writeValue(confFile.toFile(), cp);
    }
    void writeSpec(String ref,String ver,double rpm) throws Exception {
        MasterSpec s = new MasterSpec(ref,ver,"Line1","Line1-Mixer","1.0.0",List.of(new Setpoint("Rpm","Double",rpm)));
        Path f = new MasterSpecStore().file(reg,ref,ver); Files.createDirectories(f.getParent());
        JsonMapperFactory.create().writeValue(f.toFile(), s);
    }
    void activate(String ver) throws Exception {
        new ActivationService(new RecipeArtifactResolver(reg), new ActivationLedger(reg), Clock.systemUTC())
            .activate(new ActivationRequest("Line1","recipe","mix-recipe",ver,"alice","bob",false));
    }
    NcmdOpcUaBridgeMain.Config cfg() {
        return new NcmdOpcUaBridgeMain.Config("tcp://x","opc.tcp://x","g","e","p",
            reg.toString(), confFile.toString(), reg.toString(), "Line1");
    }

    @Test void bindsLedgerActiveVersionNotDial() throws Exception {
        activate("1.1.0");                                              // ledger says 1.1.0 (dial says 0.0.0)
        var conf = NcmdOpcUaBridgeMain.loadConformance(cfg());
        assertEquals(1600.0, conf.recipe().setpoints().get(0).value()); // bound 1.1.0, not the dial's 0.0.0
    }
    @Test void noActivePointerFailsClosed() {
        var ex = assertThrows(Exception.class, () -> NcmdOpcUaBridgeMain.loadConformance(cfg()));
        assertTrue(ex.getMessage().contains("activation.edge.no-active-pointer"));
    }
    @Test void tamperedBytesFailClosed() throws Exception {
        activate("1.0.0");
        Path f = new MasterSpecStore().file(reg,"mix-recipe","1.0.0");
        Files.writeString(f, Files.readString(f) + " ");               // one-byte tamper after activation
        var ex = assertThrows(Exception.class, () -> NcmdOpcUaBridgeMain.loadConformance(cfg()));
        assertTrue(ex.getMessage().contains("activation.edge.content-mismatch"));
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -q -pl core,heimdall test -Dtest=LoadConformanceActivationTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement** — replace the recipe-loading block in `loadConformance`. Current code:
```java
MasterSpec recipe = null;
if (cp.dial() != null && "recipe".equals(cp.dial().mode())) {
    recipe = new MasterSpecStore()
            .load(registryDir, cp.dial().activeRecipeRef(), cp.dial().activeRecipeVersion())
            .orElseThrow(() -> new IllegalStateException("conformance active recipe not in registry: "
                    + cp.dial().activeRecipeRef() + "@" + cp.dial().activeRecipeVersion()));
}
```
Replace with:
```java
MasterSpec recipe = null;
if (cp.dial() != null && "recipe".equals(cp.dial().mode())) {
    String ref = cp.dial().activeRecipeRef();
    if (config.activationTarget() != null && !config.activationTarget().isBlank()) {
        // governed activation: the ledger's active pointer (not the dial) picks the version; verify-then-trust.
        java.nio.file.Path ledgerDir = (config.activationPath() != null && !config.activationPath().isBlank())
                ? java.nio.file.Path.of(config.activationPath()) : registryDir;
        var active = new dev.krillin.bifrost.core.activation.ActivationLedger(ledgerDir)
                .active(config.activationTarget(), "recipe", ref)
                .orElseThrow(() -> new IllegalStateException("activation.edge.no-active-pointer: no active recipe for target "
                        + config.activationTarget() + " ref " + ref));
        java.nio.file.Path specFile = new MasterSpecStore().file(registryDir, ref, active.version());
        if (!java.nio.file.Files.isRegularFile(specFile))
            throw new IllegalStateException("activation.edge.artifact-missing: " + specFile);
        byte[] bytes = java.nio.file.Files.readAllBytes(specFile);      // verify BEFORE parse
        String sha = dev.krillin.bifrost.core.activation.Sha256.hex(bytes);
        if (!sha.equals(active.contentSha256()))
            throw new IllegalStateException("activation.edge.content-mismatch: ref " + ref + "@" + active.version()
                    + " edge sha " + sha + " != approved " + active.contentSha256());
        recipe = cmapper.readValue(bytes, MasterSpec.class);
        System.out.println("[BRIDGE] activation bound " + ref + "@" + active.version()
                + " (approved by " + active.approvedBy() + ", sha256 " + sha + ")");
    } else {
        recipe = new MasterSpecStore()
                .load(registryDir, ref, cp.dial().activeRecipeVersion())
                .orElseThrow(() -> new IllegalStateException("conformance active recipe not in registry: "
                        + ref + "@" + cp.dial().activeRecipeVersion()));
    }
}
```
(`cmapper` is the existing `ObjectMapper` in `loadConformance`. Keep the existing `[BRIDGE] conformance loaded …` line after this block.)

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(heimdall): edge-provenance recipe bind — ledger active version, verify sha256 before parse, fail-closed"`.

---

## Chunk 4: fixtures + killer gate + controller verification

### Task 9: fixtures + `run-activation-gate.sh`

**Files:** Create under `scripts/fixtures/activation/`: `conformance-recipe.json` (recipe-mode `ConformancePolicy` for `Line1-Mixer@1.0.0`, dial `mode:"recipe"`, `activeRecipeRef:"mix-recipe"`, `activeRecipeVersion:"0.0.0"` [deliberately unused — ledger overrides], `recipeTolerance:0`), `udt-Line1-Mixer.json` (the equipment def the policy references, `Rpm` Double range [0,3000]), `spec-mix-recipe-1.0.0.json` (`MasterSpec` setpoint `Rpm=1500`), `spec-mix-recipe-1.1.0.json` (`Rpm=1600`), and the ACL `policy.json` (reuse the composable gate's weld/mixer authz shape — allow the `Rpm` write for the `Bifrost:Line1`/`recipe-edge` identity). Create `scripts/run-activation-gate.sh`.

**Model the runtime harness on `scripts/run-composable-conformance-gate.sh`** (docker HiveMQ broker, `start_bridge`, `wait_deny`/APPLY helpers, `cygpath -m` path discipline, jps-based kill). Extend `start_bridge` to also export `ACTIVATION_PATH` + `ACTIVATION_TARGET=Line1`.

- [ ] **Step 1: Write the gate script** — assertions:
  - **A1 activate + audit (pure CLI, no broker):** stage the registry (`udt/`, `spec/mix-recipe/1.0.0.json`); `gates activate $WORK/reg Line1 recipe mix-recipe 1.0.0 --by alice --approved-by bob` → exit 0; `gates active $WORK/reg Line1 recipe mix-recipe` → grep `version=1.0.0`; `gates activation-log $WORK/reg Line1` → grep `ACTIVATE recipe/mix-recipe@1.0.0`.
  - **A2 SoD refuse:** `… --by alice --approved-by alice` → exit 1 + grep `activation.approval.self`; `… --by alice` (no approver) → exit 1 + grep `activation.approval.missing`.
  - **A3a activate-time refuse:** `gates activate … mix-recipe 9.9.9 --by alice --approved-by bob` → exit 1 + grep `activation.artifact.unresolved`; assert `activation-log` count unchanged.
  - **A3b edge-provenance (broker up):** with `1.0.0` active, corrupt `spec/mix-recipe/1.0.0.json` (append a byte), launch Heimdall with `ACTIVATION_TARGET=Line1` → assert the bridge log contains `activation.edge.content-mismatch` and NEVER prints `[BRIDGE] ready`; kill it; restore the file afterward. **Do NOT reuse the standard `start_bridge` helper here** — it `fail()`s the whole gate when `[BRIDGE] ready` doesn't appear, but A3b expects exactly that (the bridge throws on the mismatch and exits before `ready`). Use a bespoke launcher: background `java -jar heimdall.jar` with the activation env, poll its log for `activation.edge.content-mismatch` (success) with a timeout, assert `ready` is absent, then `kill`.
  - **A4 runtime flip (broker up, the closed loop):** restore `1.0.0` active; `start_bridge` → send NCMD `Rpm=1500` → APPLY (ok), NCMD `Rpm=1600` → DENY `conformance.recipe.deviation`. Stop bridge. Stage `spec/mix-recipe/1.1.0.json`; `gates activate … mix-recipe 1.1.0 --by alice --approved-by bob`; restart bridge → NCMD `Rpm=1600` → APPLY, NCMD `Rpm=1500` → DENY. (Active version flips which value is admitted.)
  - **A5 rollback:** `gates activate … mix-recipe 1.0.0 --rollback --by alice --approved-by bob` → exit 0; `active` → `version=1.0.0`; `activation-log` shows `ACTIVATE(1.0.0) → ACTIVATE(1.1.0) → ROLLBACK(1.0.0)` in order; restart bridge → NCMD `Rpm=1500` → APPLY again.
  - End: `echo "[GATE] PASS run-activation-gate.sh"; exit 0`. `fail()` tails the relevant log.

- [ ] **Step 2: Syntax check** — `bash -n scripts/run-activation-gate.sh` → OK; `chmod +x`.

- [ ] **Step 3: Build + RUN to green** — the implementer must actually run it (`mvn -q -pl core,gates,heimdall,sim -am install -DskipTests` first if jars are stale) and iterate until it prints `[GATE] PASS`. Debug fixture values / paths until A1–A5 all hold. (Docker Desktop required for the broker, like the composable gate.)

- [ ] **Step 4: Commit** — `git add scripts/run-activation-gate.sh scripts/fixtures/activation` ; `git commit -m "test(gate): run-activation-gate — A1 audit / A2 SoD / A3 activate+edge provenance / A4 runtime flip / A5 rollback"`.

### Task 10: controller-direct final verification (the #1 rule)

> The controller (not a subagent) personally runs every command below and reads the real output. A subagent PASS is NOT trusted.

- [ ] **Step 1:** controller runs `mvn -q install` at bifrost root → BUILD SUCCESS (core + gates + heimdall + sim; all new activation tests green).
- [ ] **Step 2:** controller runs `bash scripts/run-activation-gate.sh` → `[GATE] PASS` with A1–A5 all observed.
- [ ] **Step 3: No-regression (controller-run):** `run-spec-gate.sh`, `run-ncmd-runtime-gate.sh`, `run-template-conformance-gate.sh`, `run-yggdrasil-spine-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-composable-conformance-gate.sh` → all `[GATE] PASS`. (Full-loop + composable share the broker harness — run them after the activation gate has released the broker.)
- [ ] **Step 4:** update memory (T3 DONE, controller-verified: audited activation binding + edge-provenance + SoD, runtime loop closed via Heimdall; roadmap → T4/T5). Report with evidence. All local/unpushed.

---

## Notes / risks

- **Verify-then-trust ordering is load-bearing:** the edge check hashes the raw file bytes BEFORE `readValue` parses them, so a validity-breaking tamper surfaces as `activation.edge.content-mismatch`, not a parse error (spec §4.6). Do not reorder.
- **`ref` addresses the `spec/` store** (the dial's `activeRecipeRef`), NOT `RecipeDefinitionStore`/`recipe/`. The activation seal is event-anchored (approval attests the bytes), deliberately distinct from the git-anchored provenance-publish (spec §9).
- **Back-compat:** with `ACTIVATION_TARGET` unset, `loadConformance` is byte-for-byte the old dial behavior — the full-loop + composable gates must stay green (they set no activation env).
- **A4 needs the broker** (docker HiveMQ), like the composable gate. A1/A2/A3a are pure-CLI and need no broker.
- **`recipeTolerance:0`** in the fixture makes the admissibility flip unambiguous (`1600` vs `1500` deviates for any tol < ~0.067).
- **No push** — all commits local on `feat/yggdrasil-spine`.
```
