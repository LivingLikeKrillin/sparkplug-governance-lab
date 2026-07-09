# Tamper-Evident Activation Ledger (T4 Lineage / Record-of-Record) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the T3 activation ledger tamper-evident by hash-chaining every event, verify the chain in a gate CLI, and fail-closed at the Heimdall edge if the active pointer's ledger chain is broken.

**Architecture:** A new `LedgerChain` (single canonical-preimage SHA-256 impl, pure, no I/O) + a `LedgerEntry(event, prevHash, entryHash)` persistence wrapper (the T3 `ActivationEvent` is unchanged). `ActivationLedger.append` chains on write (tail's `entryHash` → new `prevHash`), `history` returns `List<LedgerEntry>`, and `verifyChain` walks the chain. A new `gates activation verify-chain` leg drives verification; Heimdall re-verifies the chain before binding the active version. Hash-chain only — tail-truncation and full re-chain are deferred to T5 (signed head); the `spec/`-seal unification is out of scope.

**Tech Stack:** Java 17, Maven multi-module (core/gates/heimdall/sim), JUnit 5, Jackson (JSONL), existing `core.activation` package.

**Spec:** `docs/superpowers/specs/2026-07-09-lineage-record-of-record-design.md` (repo: `sparkplug-governance-lab`). Code repo: `bifrost`, branch `feat/t4-lineage`.

**Conventions (match existing code):**
- Package `dev.krillin.bifrost.core.activation`; tests mirror under `core/src/test/...`.
- `Sha256.hex(byte[])` is the shared hash primitive (already exists).
- Commit after each task with a `feat(core|gates|heimdall)`/`test(gate)` prefix + `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Build/verify command from repo root: `mvn -q -pl <module> -am test` for a module; `mvn install` for the full reactor.

---

## Chunk 1: Core chain + ledger

### Task 1: `LedgerChain` + `LedgerEntry` + `ChainVerdict` (the crypto foundation)

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/LedgerEntry.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/ChainVerdict.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/LedgerChain.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/LedgerChainTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.activation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LedgerChainTest {

    private static ActivationEvent ev(String version, String approvedBy, String prior) {
        return new ActivationEvent("Line1", "recipe", "mix-recipe", version,
                "sha-" + version, "alice", approvedBy, 1000L, prior, "ACTIVATE");
    }

    /** Build a well-formed chain of N entries from the given events. */
    private static List<LedgerEntry> chain(ActivationEvent... events) {
        List<LedgerEntry> out = new ArrayList<>();
        String prev = LedgerChain.GENESIS;
        for (ActivationEvent e : events) {
            String h = LedgerChain.entryHash(e, prev);
            out.add(new LedgerEntry(e, prev, h));
            prev = h;
        }
        return out;
    }

    @Test void genesis_is_64_hex_zeros() {
        assertEquals(64, LedgerChain.GENESIS.length());
        assertTrue(LedgerChain.GENESIS.chars().allMatch(c -> c == '0'));
    }

    @Test void entryHash_is_deterministic_and_field_sensitive() {
        ActivationEvent e = ev("1.0.0", "bob", null);
        assertEquals(LedgerChain.entryHash(e, LedgerChain.GENESIS),
                     LedgerChain.entryHash(e, LedgerChain.GENESIS), "same inputs => same hash");
        assertNotEquals(LedgerChain.entryHash(e, LedgerChain.GENESIS),
                        LedgerChain.entryHash(ev("1.0.0", "mallory", null), LedgerChain.GENESIS),
                        "changed approvedBy => different hash");
        assertNotEquals(LedgerChain.entryHash(e, LedgerChain.GENESIS),
                        LedgerChain.entryHash(e, "ffff"), "changed prevHash => different hash");
    }

    @Test void null_priorVersion_does_not_collide_with_literal_null_string() {
        // an event with priorVersion == null must not hash the same as priorVersion == "null"
        assertNotEquals(LedgerChain.entryHash(ev("1.0.0", "bob", null), LedgerChain.GENESIS),
                        LedgerChain.entryHash(ev("1.0.0", "bob", "null"), LedgerChain.GENESIS));
    }

    @Test void verify_intact_chain() {
        assertTrue(LedgerChain.verify(chain(ev("1.0.0","bob",null), ev("1.1.0","bob","1.0.0"))).intact());
        assertTrue(LedgerChain.verify(List.of()).intact(), "empty chain is vacuously intact");
    }

    @Test void verify_detects_edited_event_content() {
        List<LedgerEntry> c = new ArrayList<>(chain(ev("1.0.0","bob",null), ev("1.1.0","bob","1.0.0")));
        // tamper entry 0's event (approvedBy) but keep its stored entryHash => self-hash mismatch
        LedgerEntry orig = c.get(0);
        c.set(0, new LedgerEntry(ev("1.0.0","mallory",null), orig.prevHash(), orig.entryHash()));
        ChainVerdict v = LedgerChain.verify(c);
        assertFalse(v.intact());
        assertEquals(0, v.brokenIndex());
        assertEquals("ledger.chain.entry-hash-mismatch", v.rule());
    }

    @Test void verify_detects_edited_prevHash_in_place() {
        List<LedgerEntry> c = new ArrayList<>(chain(ev("1.0.0","bob",null), ev("1.1.0","bob","1.0.0")));
        LedgerEntry e1 = c.get(1);
        // change stored prevHash but keep stored entryHash => self-hash mismatch (prevHash is in the preimage)
        c.set(1, new LedgerEntry(e1.event(), "deadbeef", e1.entryHash()));
        ChainVerdict v = LedgerChain.verify(c);
        assertFalse(v.intact());
        assertEquals("ledger.chain.entry-hash-mismatch", v.rule());
    }

    @Test void verify_detects_deleted_middle_entry() {
        List<LedgerEntry> c = new ArrayList<>(chain(
                ev("1.0.0","bob",null), ev("1.1.0","bob","1.0.0"), ev("1.2.0","bob","1.1.0")));
        c.remove(1); // delete the middle entry; entry-2 now follows entry-0 but points at entry-1's hash
        ChainVerdict v = LedgerChain.verify(c);
        assertFalse(v.intact());
        assertEquals(1, v.brokenIndex());
        assertEquals("ledger.chain.prev-link-broken", v.rule());
    }

    @Test void verify_detects_reordered_middle_entries() {
        List<LedgerEntry> c = new ArrayList<>(chain(
                ev("1.0.0","bob",null), ev("1.1.0","bob","1.0.0"), ev("1.2.0","bob","1.1.0")));
        LedgerEntry a = c.get(1); c.set(1, c.get(2)); c.set(2, a); // swap entries 1 and 2
        ChainVerdict v = LedgerChain.verify(c);
        assertFalse(v.intact());
        assertEquals("ledger.chain.prev-link-broken", v.rule());
    }

    @Test void verify_detects_broken_genesis() {
        List<LedgerEntry> c = new ArrayList<>(chain(ev("1.0.0","bob",null)));
        LedgerEntry g = c.get(0);
        // rebuild the first entry's self-hash against a non-genesis prev, so entry-hash passes but genesis fails
        String badPrev = "1".repeat(64);
        c.set(0, new LedgerEntry(g.event(), badPrev, LedgerChain.entryHash(g.event(), badPrev)));
        ChainVerdict v = LedgerChain.verify(c);
        assertFalse(v.intact());
        assertEquals(0, v.brokenIndex());
        assertEquals("ledger.chain.genesis-broken", v.rule());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl core test -Dtest=LedgerChainTest`
Expected: FAIL — `LedgerChain`, `LedgerEntry`, `ChainVerdict` do not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

`LedgerEntry.java`:
```java
package dev.krillin.bifrost.core.activation;

/** The persisted unit: a T3 ActivationEvent plus its hash-chain links. Serialized as one JSONL line
 *  {"event":{…},"prevHash":"…","entryHash":"…"}. prevHash = the prior entry's entryHash (GENESIS for the
 *  first entry). entryHash = LedgerChain.entryHash(event, prevHash) — this entry's identity. */
public record LedgerEntry(ActivationEvent event, String prevHash, String entryHash) {}
```

`ChainVerdict.java`:
```java
package dev.krillin.bifrost.core.activation;

/** Result of verifying a ledger's hash chain. intact => brokenIndex and rule are null; on a break,
 *  the FIRST break's zero-based index and rule slug. */
public record ChainVerdict(boolean intact, Integer brokenIndex, String rule) {
    // NOTE: the "all good" factory is whole(), NOT intact() — a record auto-generates the accessor
    // intact(), so a static intact() factory is an override-equivalent name clash and will NOT compile.
    public static ChainVerdict whole()  { return new ChainVerdict(true, null, null); }
    public static ChainVerdict broken(int index, String rule) { return new ChainVerdict(false, index, rule); }
}
```

`LedgerChain.java`:
```java
package dev.krillin.bifrost.core.activation;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** The single hash-chain implementation shared by the ledger writer and every verifier (gate CLI,
 *  Heimdall edge). Pure, no I/O. An entry's hash commits to the event's business fields AND the prior
 *  entry's hash, so any retroactive edit/delete/reorder of history breaks the chain. The canonical
 *  preimage is an explicit, ordered, delimiter-joined field concatenation (NOT JSON) so the writer and
 *  every verifier hash byte-for-byte identical input regardless of serialization stability. */
public final class LedgerChain {
    private LedgerChain() {}

    /** The genesis predecessor — the first entry's prevHash. 64 hex zeros. */
    public static final String GENESIS = "0".repeat(64);

    private static final char SEP = '\u001f';                    // ASCII Unit Separator — cannot occur in field values
    private static final String NULL_SENTINEL = "\u0000null\u0000"; // distinct from a literal "null" string value

    /** Deterministic, serialization-independent hash preimage: event fields in fixed order, then prevHash. */
    static String preimage(ActivationEvent e, String prevHash) {
        return f(e.target()) + SEP + f(e.kind()) + SEP + f(e.ref()) + SEP + f(e.version()) + SEP
             + f(e.contentSha256()) + SEP + f(e.activatedBy()) + SEP + f(e.approvedBy()) + SEP
             + e.activatedAt() + SEP + f(e.priorVersion()) + SEP + f(e.action()) + SEP + f(prevHash);
    }

    private static String f(String v) { return v == null ? NULL_SENTINEL : v; }

    /** This entry's identity hash over (event fields + prevHash), UTF-8 encoded. */
    public static String entryHash(ActivationEvent e, String prevHash) {
        return Sha256.hex(preimage(e, prevHash).getBytes(StandardCharsets.UTF_8));
    }

    /** Walk the entries in append order; return the FIRST break, or intact. Check order per §4.2 of the spec:
     *  genesis (i==0) → entry self-hash → prev-link (i>0). */
    public static ChainVerdict verify(List<LedgerEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry en = entries.get(i);
            if (i == 0 && !GENESIS.equals(en.prevHash()))
                return ChainVerdict.broken(0, "ledger.chain.genesis-broken");
            if (!entryHash(en.event(), en.prevHash()).equals(en.entryHash()))
                return ChainVerdict.broken(i, "ledger.chain.entry-hash-mismatch");
            if (i > 0 && !entries.get(i - 1).entryHash().equals(en.prevHash()))
                return ChainVerdict.broken(i, "ledger.chain.prev-link-broken");
        }
        return ChainVerdict.whole();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl core test -Dtest=LedgerChainTest`
Expected: PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/LedgerChain.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/LedgerEntry.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/ChainVerdict.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/LedgerChainTest.java
git commit -m "feat(core): LedgerChain — canonical-preimage SHA-256 hash chain over the activation ledger (LedgerEntry + ChainVerdict)"
```

---

### Task 2: `ActivationLedger` chains on write + `verifyChain` (+ fix the two callers)

**Files:**
- Modify: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationLedger.java`
- Modify: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java:39-43` (`versionInHistory` loop)
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java:log(...)` (project `.event()`)
- Modify (tests to new shape): `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationLedgerTest.java`, and any assertion in `ActivationServiceTest.java` that reads `history(...)` as `List<ActivationEvent>`.
- Test (new cases): add to `ActivationLedgerTest.java`.

- [ ] **Step 1: Write the failing test** (append to `ActivationLedgerTest.java`)

```java
    @Test void append_chains_entries(@TempDir java.nio.file.Path reg) throws Exception {
        ActivationLedger ledger = new ActivationLedger(reg);
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.0.0","shaA","alice","bob",1L,null,"ACTIVATE"));
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.1.0","shaB","alice","bob",2L,"1.0.0","ACTIVATE"));
        java.util.List<LedgerEntry> h = ledger.history("Line1");
        assertEquals(2, h.size());
        assertEquals(LedgerChain.GENESIS, h.get(0).prevHash());
        assertEquals(h.get(0).entryHash(), h.get(1).prevHash(), "2nd entry links to 1st");
        assertTrue(ledger.verifyChain("Line1").intact());
    }

    @Test void verifyChain_detects_out_of_band_edit(@TempDir java.nio.file.Path reg) throws Exception {
        ActivationLedger ledger = new ActivationLedger(reg);
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.0.0","shaA","alice","bob",1L,null,"ACTIVATE"));
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.1.0","shaB","alice","bob",2L,"1.0.0","ACTIVATE"));
        // out-of-band tamper: replace "bob" with "eve" on the first line of the raw JSONL file
        java.nio.file.Path f = reg.resolve("activation").resolve("Line1.jsonl");
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
        lines.set(0, lines.get(0).replace("\"bob\"", "\"eve\""));
        java.nio.file.Files.write(f, lines);
        ChainVerdict v = ledger.verifyChain("Line1");
        assertFalse(v.intact());
        assertEquals(0, v.brokenIndex());
        assertEquals("ledger.chain.entry-hash-mismatch", v.rule());
    }

    @Test void active_still_returns_last_match_projected_to_event(@TempDir java.nio.file.Path reg) throws Exception {
        ActivationLedger ledger = new ActivationLedger(reg);
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.0.0","shaA","alice","bob",1L,null,"ACTIVATE"));
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.1.0","shaB","alice","bob",2L,"1.0.0","ACTIVATE"));
        assertEquals("1.1.0", ledger.active("Line1","recipe","mix").orElseThrow().version());
    }
```

Also UPDATE the existing `ActivationLedgerTest` assertions that were written against `List<ActivationEvent>` (e.g. `history("Line1").get(0).version()` → `history("Line1").get(0).event().version()`).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl core test -Dtest=ActivationLedgerTest`
Expected: FAIL — `verifyChain` undefined and `history` still returns `List<ActivationEvent>` (compile error on `.event()` / `LedgerEntry`).

- [ ] **Step 3: Write minimal implementation**

Replace `ActivationLedger.java` body (keep package, imports add `java.util.List`):
```java
    public void append(ActivationEvent e) throws IOException {
        Path f = file(e.target());
        Files.createDirectories(f.getParent());
        String prevHash = tailEntryHash(f);
        LedgerEntry entry = new LedgerEntry(e, prevHash, LedgerChain.entryHash(e, prevHash));
        Files.writeString(f, mapper.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The prevHash for the next append = the last entry's entryHash (GENESIS if the ledger is empty). O(1)-ish:
     *  reads the file tail only; does NOT re-verify the whole chain on every append (see spec §7). */
    private String tailEntryHash(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return LedgerChain.GENESIS;
        String last = null;
        for (String line : Files.readAllLines(f)) if (!line.isBlank()) last = line;
        return last == null ? LedgerChain.GENESIS : mapper.readValue(last, LedgerEntry.class).entryHash();
    }

    public List<LedgerEntry> history(String target) throws IOException {
        Path f = file(target);
        if (!Files.isRegularFile(f)) return List.of();
        List<LedgerEntry> out = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
            if (!line.isBlank()) out.add(mapper.readValue(line, LedgerEntry.class));
        }
        return out;
    }

    public Optional<ActivationEvent> active(String target, String kind, String ref) throws IOException {
        ActivationEvent found = null;
        for (LedgerEntry en : history(target)) {
            ActivationEvent e = en.event();
            if (e.kind().equals(kind) && e.ref().equals(ref)) found = e;   // last match wins
        }
        return Optional.ofNullable(found);
    }

    public ChainVerdict verifyChain(String target) throws IOException {
        return LedgerChain.verify(history(target));
    }
```

Fix `ActivationService.versionInHistory` (the only other core caller of `history`):
```java
    private boolean versionInHistory(ActivationRequest r) throws Exception {
        for (LedgerEntry en : ledger.history(r.target())) {
            ActivationEvent e = en.event();
            if (e.kind().equals(r.kind()) && e.ref().equals(r.ref()) && e.version().equals(r.version())) return true;
        }
        return false;
    }
```
(Add `import` for `LedgerEntry` if not covered by an existing wildcard; the package is the same, so no import is needed.)

Fix `ActivateGate.log` (the gates caller of `history`):
```java
    private static int log(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("Usage: activation-log <reg> <target>"); return 2; }
        List<LedgerEntry> hist = new ActivationLedger(Path.of(a[0])).history(a[1]);
        System.out.println("[GATE] activation-log target=" + a[1] + " events=" + hist.size());
        for (LedgerEntry en : hist) {
            ActivationEvent e = en.event();
            System.out.println("  " + e.action() + " " + e.kind() + "/" + e.ref() + "@" + e.version()
                + " by=" + e.activatedBy() + " approvedBy=" + e.approvedBy() + " prior=" + e.priorVersion()
                + " sha256=" + e.contentSha256() + " entryHash=" + en.entryHash());
        }
        return 0;
    }
```
(`ActivateGate` already imports `dev.krillin.bifrost.core.activation.*`, so `LedgerEntry` resolves.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl core -am test -Dtest=ActivationLedgerTest,ActivationServiceTest,LedgerChainTest`
Then compile gates: `mvn -q -pl gates -am test -Dtest=ActivateGateTest` (existing gate tests still green).
Expected: PASS. If any pre-existing test asserted on the flat-`ActivationEvent` JSONL line shape, update it to go through `LedgerEntry` (read via `ledger.history(...).get(i).event()`), not by parsing the raw line as `ActivationEvent`.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationLedger.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java \
        gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationLedgerTest.java
git commit -m "feat(core): ActivationLedger chains on write + verifyChain; history returns LedgerEntry (callers projected to .event())"
```

---

## Chunk 2: Gate CLI + Heimdall edge + integration gate

### Task 3: `gates activation verify-chain` CLI leg

**Files:**
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java` (new `case "activation"`)
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java` (route `verify-chain`)
- Test: `gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateTest.java` (new cases; create the file if absent)

- [ ] **Step 1: Write the failing test**

```java
    @Test void verify_chain_intact_exit0(@TempDir java.nio.file.Path reg) throws Exception {
        // seed two chained events directly via the ledger
        var ledger = new dev.krillin.bifrost.core.activation.ActivationLedger(reg);
        ledger.append(new dev.krillin.bifrost.core.activation.ActivationEvent("Line1","recipe","mix","1.0.0","shaA","alice","bob",1L,null,"ACTIVATE"));
        ledger.append(new dev.krillin.bifrost.core.activation.ActivationEvent("Line1","recipe","mix","1.1.0","shaB","alice","bob",2L,"1.0.0","ACTIVATE"));
        assertEquals(0, ActivateGate.run(new String[]{"activation","verify-chain", reg.toString(), "Line1"}));
        // and via the top-level dispatcher
        assertEquals(0, GatesCli.run(new String[]{"activation","verify-chain", reg.toString(), "Line1"}));
    }

    @Test void verify_chain_tampered_exit1(@TempDir java.nio.file.Path reg) throws Exception {
        var ledger = new dev.krillin.bifrost.core.activation.ActivationLedger(reg);
        ledger.append(new dev.krillin.bifrost.core.activation.ActivationEvent("Line1","recipe","mix","1.0.0","shaA","alice","bob",1L,null,"ACTIVATE"));
        java.nio.file.Path f = reg.resolve("activation").resolve("Line1.jsonl");
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
        lines.set(0, lines.get(0).replace("\"bob\"","\"eve\""));
        java.nio.file.Files.write(f, lines);
        assertEquals(1, ActivateGate.run(new String[]{"activation","verify-chain", reg.toString(), "Line1"}));
    }

    @Test void verify_chain_no_such_target_exit2(@TempDir java.nio.file.Path reg) throws Exception {
        assertEquals(2, ActivateGate.run(new String[]{"activation","verify-chain", reg.toString(), "Nope"}));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl gates -am test -Dtest=ActivateGateTest`
Expected: FAIL — the `activation` subcommand routes nowhere / returns 2 for the intact case.

- [ ] **Step 3: Write minimal implementation**

`GatesCli` — add to the switch (and to both usage strings):
```java
            case "activation":
                return ActivateGate.run(args);
```
(Update both `GatesCli` usage mentions — the `System.err` `Usage:` string and the class javadoc — to include `activation`. Also update `ActivateGate.usage()`'s `<activate|active|activation-log>` string to `<activate|active|activation-log|activation>` for consistency. Cosmetic but keeps the help text truthful.)

`ActivateGate.run` — add the `activation` case and the handler:
```java
                case "activation": return activation(rest);
```
```java
    private static int activation(String[] a) throws Exception {
        if (a.length < 3 || !"verify-chain".equals(a[0])) {
            System.err.println("Usage: activation verify-chain <reg> <target>");
            return 2;
        }
        Path reg = Path.of(a[1]);
        String target = a[2];
        ActivationLedger ledger = new ActivationLedger(reg);
        java.util.List<LedgerEntry> hist = ledger.history(target);
        if (hist.isEmpty()) { System.err.println("[GATE] verify-chain: no such target ledger: " + target); return 2; }
        ChainVerdict v = ledger.verifyChain(target);
        if (v.intact()) {
            System.out.println("[GATE] verify-chain target=" + target + " entries=" + hist.size() + " => INTACT");
            return 0;
        }
        System.out.println("[GATE] verify-chain target=" + target + " entries=" + hist.size()
                + " => BROKEN at index=" + v.brokenIndex() + " rule=" + v.rule());
        return 1;
    }
```
Note: `ActivateGate.run` receives the FULL `args` from `GatesCli` (matching how `activate`/`active`/`activation-log` are dispatched). So inside `run`, `sub = args[0]` = `"activation"`, and `rest` = `["verify-chain", reg, target]`. The handler indexes `a[0]="verify-chain"`, `a[1]=reg`, `a[2]=target`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl gates -am test -Dtest=ActivateGateTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java \
        gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java \
        gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateTest.java
git commit -m "feat(gates): activation verify-chain CLI leg — 0 intact / 1 tamper / 2 no-such-target"
```

---

### Task 4: Heimdall edge enforcement — verify-chain before bind

**Files:**
- Modify: `heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java` (activation branch of `loadConformance`, ~lines 80-96)
- Test: `heimdall/src/test/java/dev/krillin/bifrost/heimdall/LoadConformanceActivationTest.java` (extend)

- [ ] **Step 1: Write the failing test** (add a broken-chain case; mirror the existing content-mismatch test's setup)

Reuse the existing test's helpers verbatim — the `@BeforeEach setup(@TempDir Path dir)` already builds the registry (udt + `spec/mix-recipe/{1.0.0,1.1.0}.json` + recipe-mode `conformance.json`) and sets the `reg` field; `activate(String ver)` appends an ACTIVATE via `ActivationService`; `cfg()` returns a `Config` with `activationPath=reg`, `activationTarget="Line1"`; invocation is the package-private `NcmdOpcUaBridgeMain.loadConformance(cfg())`. The ledger file is therefore at `reg/activation/Line1.jsonl`. Add exactly this test (no new helpers):

```java
    @Test void brokenLedgerChainFailsClosed() throws Exception {
        activate("1.0.0");
        // out-of-band tamper: editing approvedBy on entry-0's raw JSONL line breaks its self-hash
        // (approvedBy "bob" occurs once), so verifyChain fails BEFORE the active pointer is read.
        Path f = reg.resolve("activation").resolve("Line1.jsonl");
        List<String> lines = Files.readAllLines(f);
        lines.set(0, lines.get(0).replace("\"bob\"", "\"eve\""));
        Files.write(f, lines);
        var ex = assertThrows(Exception.class, () -> NcmdOpcUaBridgeMain.loadConformance(cfg()));
        assertTrue(ex.getMessage().contains("activation.edge.ledger-chain-broken"), ex.getMessage());
    }
```
(`Path`, `List`, `Files`, `assertThrows`, `assertTrue` are already imported in `LoadConformanceActivationTest`. This mirrors the existing `tamperedBytesFailClosed` test structure exactly, swapping the spec-byte tamper for a ledger-line tamper and the expected rule to `ledger-chain-broken`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl heimdall -am test -Dtest=LoadConformanceActivationTest`
Expected: FAIL — no chain check yet, so it proceeds to bind (or throws a different error).

- [ ] **Step 3: Write minimal implementation**

In `loadConformance`, the activation branch currently constructs the ledger inline inside the `.active(...)` call. Refactor to build the ledger once, verify the chain first, then read the active pointer:
```java
                dev.krillin.bifrost.core.activation.ActivationLedger ledger =
                        new dev.krillin.bifrost.core.activation.ActivationLedger(ledgerDir);
                dev.krillin.bifrost.core.activation.ChainVerdict chain = ledger.verifyChain(config.activationTarget());
                if (!chain.intact())
                    throw new IllegalStateException("activation.edge.ledger-chain-broken: target "
                            + config.activationTarget() + " index " + chain.brokenIndex() + " rule " + chain.rule());
                var active = ledger
                        .active(config.activationTarget(), "recipe", ref)
                        .orElseThrow(() -> new IllegalStateException("activation.edge.no-active-pointer: no active recipe for target "
                                + config.activationTarget() + " ref " + ref));
```
(The rest of the branch — `specFile`, byte read, sha compare, parse, `[BRIDGE] activation bound` — is UNCHANGED. Only the ledger is now built once and chain-verified before `.active(...)`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl heimdall -am test -Dtest=LoadConformanceActivationTest`
Expected: PASS (broken chain → throws `ledger-chain-broken`; existing happy-path/content-mismatch cases still pass).

- [ ] **Step 5: Commit**

```bash
git add heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java \
        heimdall/src/test/java/dev/krillin/bifrost/heimdall/LoadConformanceActivationTest.java
git commit -m "feat(heimdall): fail-closed on broken activation-ledger chain before binding the active version"
```

---

### Task 5: `run-lineage-gate.sh` (LN1–LN4) + activation-gate no-regression

**Files:**
- Create: `scripts/run-lineage-gate.sh`
- Reference: `scripts/run-activation-gate.sh` (copy its harness: `cygpath -m` path discipline, jar path, recipe-mode policy + `spec/mix-recipe/{1.0.0,1.1.0}.json` fixtures, Heimdall invocation, NCMD apply/deny helper).

- [ ] **Step 1: Author the gate script**

Model it on `run-activation-gate.sh`. Structure:
```bash
#!/usr/bin/env bash
set -euo pipefail
# ... same preamble as run-activation-gate.sh: resolve JAR, REG dir, cygpath -m, register
#     spec/mix-recipe/1.0.0.json (Rpm=1500) and 1.1.0.json (Rpm=1600), recipe-mode policy, recipeTolerance:0 ...
GATES() { java -jar "$GATES_JAR" "$@"; }

# LN1 — chain intact + audited
GATES activate  "$REG" Line1 recipe mix-recipe 1.0.0 --by alice --approved-by bob
GATES activate  "$REG" Line1 recipe mix-recipe 1.1.0 --by alice --approved-by bob
GATES activation verify-chain "$REG" Line1   # expect exit 0, prints INTACT entries=2
GATES activation-log "$REG" Line1            # expect both entries with entryHash/prevHash chained
echo "[LINEAGE] LN1 chain intact => PASS"

# LN2 — edit a past event detected
LEDGER="$REG/activation/Line1.jsonl"
cp "$LEDGER" "$LEDGER.bak"
# tamper the first line: approvedBy bob -> mallory (portable in-place edit, no sed -i quirks)
python - "$LEDGER" <<'PY'
import sys
p=sys.argv[1]; ls=open(p,encoding='utf-8').read().splitlines()
ls[0]=ls[0].replace('"bob"','"mallory"',1)
open(p,'w',encoding='utf-8').write("\n".join(ls)+"\n")
PY
set +e; GATES activation verify-chain "$REG" Line1; rc=$?; set -e
[ "$rc" -eq 1 ] || { echo "[LINEAGE] LN2 expected exit 1, got $rc"; exit 1; }
echo "[LINEAGE] LN2 edit-past detected (entry-hash-mismatch) => PASS"
cp "$LEDGER.bak" "$LEDGER"    # restore intact chain

# LN3 — delete a middle event detected (need >=3 entries)
GATES activate "$REG" Line1 recipe mix-recipe 1.0.0 --rollback --by alice --approved-by bob  # 3rd entry
cp "$LEDGER" "$LEDGER.bak"
python - "$LEDGER" <<'PY'
import sys
p=sys.argv[1]; ls=open(p,encoding='utf-8').read().splitlines()
del ls[1]                      # remove the middle entry
open(p,'w',encoding='utf-8').write("\n".join(ls)+"\n")
PY
set +e; GATES activation verify-chain "$REG" Line1; rc=$?; set -e
[ "$rc" -eq 1 ] || { echo "[LINEAGE] LN3 expected exit 1, got $rc"; exit 1; }
echo "[LINEAGE] LN3 delete-middle detected (prev-link-broken) => PASS"
cp "$LEDGER.bak" "$LEDGER"

# LN4 — edge enforcement: intact => Heimdall binds+APPLY; tampered => fail-closed, no bind
#   (a) intact chain, 1.1.0 active (Rpm=1600): start Heimdall ACTIVATION_TARGET=Line1, authorized NCMD Rpm=1600 => APPLY
#       -- reuse the exact Heimdall boot + RogueNcmd/apply helper from run-activation-gate.sh A4
#   (b) tamper the ledger (edit a past line), restart Heimdall => log shows activation.edge.ledger-chain-broken,
#       never "[BRIDGE] ready" / "[BRIDGE] activation bound"; assert the bridge refused to start-bind.
echo "[LINEAGE] LN4 edge enforcement => PASS"

echo "[LINEAGE] GATE PASS (LN1-LN4)"
```
Fill (a)/(b) using the *identical* Heimdall-launch and NCMD-apply/deny plumbing that `run-activation-gate.sh` A4 already contains (do not invent a new launcher). For (b), grep the Heimdall stdout/stderr for `activation.edge.ledger-chain-broken` AND assert absence of `[BRIDGE] activation bound`.

- [ ] **Step 2: Make executable + run**

Run: `chmod +x scripts/run-lineage-gate.sh && bash scripts/run-lineage-gate.sh`
Expected: prints `[LINEAGE] GATE PASS (LN1-LN4)`, exit 0.

- [ ] **Step 3: No-regression — activation gate on the new line shape**

Run: `bash scripts/run-activation-gate.sh`
Expected: `[GATE] ... PASS` (A1–A5). If any A-assertion greps the raw ledger for a flat field that moved under `event`, adjust the grep to match the nested `{"event":{...}}` line (the field substrings still appear, so most greps survive unchanged).

- [ ] **Step 4: Commit**

```bash
git add scripts/run-lineage-gate.sh
git commit -m "test(gate): run-lineage-gate — LN1 intact / LN2 edit-past / LN3 delete-middle / LN4 edge fail-closed"
```

---

## Final controller verification (NOT a subagent step — the controller runs these personally, per the #1 rule)

- [ ] `mvn install` from repo root → BUILD SUCCESS, all modules, 0 failures.
- [ ] `bash scripts/run-lineage-gate.sh` → `[LINEAGE] GATE PASS (LN1-LN4)`.
- [ ] No-regression, each run personally: `run-activation-gate.sh` (A1–A5), `run-spec-gate.sh`, `run-ncmd-runtime-gate.sh`, `run-template-conformance-gate.sh`, `run-yggdrasil-spine-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-composable-conformance-gate.sh`.
- [ ] All commits on `feat/t4-lineage` (bifrost). Do NOT push/merge — Eisen-gated.

## Honest-limitations checklist (must remain true in the final code; do not oversell)
- Hash-chain catches edit/delete/reorder/mid-truncation; **tail-truncation and full re-chain are NOT caught** (no external/signed head) — T5.
- Integrity is **self-attested** (no signature) — T5.
- `append` trusts the tail entryHash (O(1)); pre-existing breaks surface at verify/bind, not append.
- Per-target independent chains; single control-plane writer.
- Scope is the activation ledger only; `spec/`-seal ↔ git-anchored `recipe/` provenance unification is out of scope.
