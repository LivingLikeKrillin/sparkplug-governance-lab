# T5 Signed Activation (Identity) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each governed activation event cryptographically dual-signed (activator + approver Ed25519) and anchor the ledger tail with a signed head, so full-re-chain and tail-truncation — the two gaps T4's hash chain leaves open — are closed, and Heimdall fail-closes on any signature/head break before binding.

**Architecture:** Additive to `core.activation`. A new `core.identity` package holds the crypto primitive (`Ed25519Keys`), the trust anchor (`AuthorizedKeys`), the signed head (`SignedHead`/`SignedHeadStore`), and the full verifier (`SignedLedgerVerifier`). Signatures cover T4's existing `entryHash`, so `LedgerChain` (structural verification) is untouched — signatures are an authenticity envelope on top. Signing is opt-in via a nullable `LedgerSigner` seam: `null` = exact T3/T4 unsigned behavior (existing tests/gates preserved). Heimdall gains a default-OFF `REQUIRE_SIGNED_ACTIVATION` switch that, when on, swaps the T4 structural check for the full signed check.

**Tech Stack:** Java 17, Maven multi-module (core/gates/heimdall/sim), JUnit 5, Jackson (`JsonMapperFactory`), JDK built-in Ed25519 (`java.security`, no new dependency), bash+python gate scripts.

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-11-identity-signed-activation-design.md`

---

## Conventions (read once)

- **Repo:** all code is in `bifrost` (this repo). Branch: `feat/t5-identity` off `main` (create in Task 0). Plan/spec are in the `sparkplug-governance-lab` sibling repo.
- **Build one module:** `mvn -q -pl core -am test` (core), `-pl gates -am test`, `-pl heimdall -am test`. Full: `mvn -q install`.
- **Run one test:** `mvn -q -pl core -am test -Dtest=Ed25519KeysTest`.
- **Package base:** `dev.krillin.bifrost.core.identity` (new) and `dev.krillin.bifrost.core.activation` (existing).
- **Commit discipline:** one commit per task (TDD: test → impl → green → commit). Commit message trailer:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- **Reason-code families:** writer/activate-time = `identity.key.principal-mismatch`, `identity.four-eyes.same-key`. Verifier = `identity.sig.missing|invalid`, `identity.key.unregistered`, `identity.four-eyes.same-key`, `identity.head.missing|tail-mismatch|seq-mismatch|sig-invalid`.
- **Serialization:** base64 — public key = X.509 `SubjectPublicKeyInfo` (`getEncoded()`), private key = PKCS8 (`getEncoded()`), signature = raw Ed25519 bytes. Use `java.util.Base64` (getEncoder/getDecoder, standard alphabet).

---

## File Structure

**New (`core/src/main/java/dev/krillin/bifrost/core/identity/`):**
- `Ed25519Keys.java` — pure crypto util (keygen, base64↔key, sign, verify). No I/O.
- `AuthorizedKeys.java` + `AuthorizedKey.java` (record) — trust anchor loaded from `registry/identity/authorized-keys.jsonl`.
- `SignedHead.java` (record) + `SignedHeadStore.java` — `registry/identity/<target>.head` read/write + canonical head preimage.
- `SignedVerdict.java` (record) — parallel to `ChainVerdict`, richer `identity.*` rule set.
- `SignedLedgerVerifier.java` — structural (reuses `LedgerChain.verify`) + per-entry signatures + head.
- `IdentityException.java` — checked domain exception carrying `(rule, detail)` for activate-time refusals.

**New (`core/src/main/java/dev/krillin/bifrost/core/activation/`):**
- `LedgerSigner.java` (interface) + `KeyFileLedgerSigner.java` (concrete, key-file backed).
- `Signatures.java` (record: activatorSig, approverSig).

**Modified:**
- `core/.../activation/LedgerEntry.java` — add nullable `activatorSig`, `approverSig`.
- `core/.../activation/ActivationLedger.java` — `append(event, signer)` overload; write signed line + head.
- `core/.../activation/ActivationService.java` — `activate(request, signer)` overload; signer preflight fail-closed.
- `gates/.../gates/ActivateGate.java` — `identity keygen`, `activate --by-key/--approved-by-key`, `activation verify-signed`.
- `gates/.../gates/GatesCli.java` — route `identity` subcommand.
- `heimdall/.../heimdall/NcmdOpcUaBridgeMain.java` — `REQUIRE_SIGNED_ACTIVATION` switch; use `SignedLedgerVerifier` when on.

**New test/script:**
- Unit tests mirroring each new class under `core/src/test/.../identity/` and `.../activation/`.
- `scripts/run-identity-gate.sh` — I1–I7 end-to-end gate.

---

## Chunk 1: Crypto primitive + trust anchor

Self-contained: `Ed25519Keys`, `AuthorizedKey`/`AuthorizedKeys`. No dependency on activation. Fully unit-tested before anything consumes them.

### Task 0: Branch

**Files:** none (git only).

- [ ] **Step 1:** Create the feature branch off `main`.

Run:
```bash
cd "C:/Users/Eisen/Desktop/Labs/[iiot]/bifrost"
git checkout main && git checkout -b feat/t5-identity
```
Expected: `Switched to a new branch 'feat/t5-identity'`.

- [ ] **Step 2:** Confirm baseline builds green (guards against a dirty starting point).

Run: `mvn -q install`
Expected: BUILD SUCCESS (core/heimdall/gates/sim + tests).

---

### Task 1: `Ed25519Keys` — pure crypto util

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/Ed25519Keys.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/identity/Ed25519KeysTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.identity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import static org.junit.jupiter.api.Assertions.*;

class Ed25519KeysTest {
    private static final byte[] MSG = "the exact bytes".getBytes(StandardCharsets.UTF_8);

    @Test void sign_then_verify_roundtrips() {
        KeyPair kp = Ed25519Keys.generate();
        String sig = Ed25519Keys.sign(MSG, kp.getPrivate());
        assertTrue(Ed25519Keys.verify(MSG, sig, kp.getPublic()));
    }

    @Test void verify_rejects_tampered_message() {
        KeyPair kp = Ed25519Keys.generate();
        String sig = Ed25519Keys.sign(MSG, kp.getPrivate());
        assertFalse(Ed25519Keys.verify("other bytes".getBytes(StandardCharsets.UTF_8), sig, kp.getPublic()));
    }

    @Test void verify_rejects_wrong_key() {
        KeyPair a = Ed25519Keys.generate();
        KeyPair b = Ed25519Keys.generate();
        String sig = Ed25519Keys.sign(MSG, a.getPrivate());
        assertFalse(Ed25519Keys.verify(MSG, sig, b.getPublic()));
    }

    @Test void verify_returns_false_on_garbage_signature_not_throw() {
        KeyPair kp = Ed25519Keys.generate();
        assertFalse(Ed25519Keys.verify(MSG, "not-base64-!!!", kp.getPublic()));
        assertFalse(Ed25519Keys.verify(MSG, "", kp.getPublic()));
    }

    @Test void public_key_base64_roundtrips() {
        KeyPair kp = Ed25519Keys.generate();
        String b64 = Ed25519Keys.publicKeyB64(kp.getPublic());
        PublicKey back = Ed25519Keys.publicKey(b64);
        String sig = Ed25519Keys.sign(MSG, kp.getPrivate());
        assertTrue(Ed25519Keys.verify(MSG, sig, back), "reloaded pubkey verifies a sig from its pair");
    }

    @Test void private_key_base64_roundtrips() {
        KeyPair kp = Ed25519Keys.generate();
        String b64 = Ed25519Keys.privateKeyB64(kp.getPrivate());
        PrivateKey back = Ed25519Keys.privateKey(b64);
        String sig = Ed25519Keys.sign(MSG, back);
        assertTrue(Ed25519Keys.verify(MSG, sig, kp.getPublic()), "reloaded privkey signs verifiably");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=Ed25519KeysTest`
Expected: FAIL — `Ed25519Keys` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

```java
package dev.krillin.bifrost.core.identity;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Pure Ed25519 crypto — the one signing/verification discipline shared by the ledger writer, the
 *  verifier (gate + Heimdall edge), and keygen. JDK built-in ("Ed25519", JEP 339, JDK 15+); no external
 *  dependency. Serialization is base64: public key = X.509 SubjectPublicKeyInfo, private key = PKCS8,
 *  signature = raw 64-byte Ed25519. verify() NEVER throws — any malformed input is a false (fail-closed). */
public final class Ed25519Keys {
    private Ed25519Keys() {}

    private static final String ALG = "Ed25519";

    public static KeyPair generate() {
        try { return KeyPairGenerator.getInstance(ALG).generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Ed25519 unavailable", e); }
    }

    public static String sign(byte[] msg, PrivateKey key) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initSign(key);
            s.update(msg);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("sign failed", e); }
    }

    /** True iff sigB64 is a valid Ed25519 signature of msg under key. False on ANY error (fail-closed). */
    public static boolean verify(byte[] msg, String sigB64, PublicKey key) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initVerify(key);
            s.update(msg);
            return s.verify(Base64.getDecoder().decode(sigB64));
        } catch (RuntimeException | GeneralSecurityException e) { return false; }
    }

    public static String publicKeyB64(PublicKey k)  { return Base64.getEncoder().encodeToString(k.getEncoded()); }
    public static String privateKeyB64(PrivateKey k) { return Base64.getEncoder().encodeToString(k.getEncoded()); }

    public static PublicKey publicKey(String b64) {
        try {
            return KeyFactory.getInstance(ALG)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | RuntimeException e) { throw new IllegalArgumentException("bad public key", e); }
    }

    public static PrivateKey privateKey(String b64) {
        try {
            return KeyFactory.getInstance(ALG)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | RuntimeException e) { throw new IllegalArgumentException("bad private key", e); }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=Ed25519KeysTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/identity/Ed25519Keys.java \
        core/src/test/java/dev/krillin/bifrost/core/identity/Ed25519KeysTest.java
git commit -m "feat(core): Ed25519Keys — JDK-built-in sign/verify + base64 key I/O (T5 identity)"
```

---

### Task 2: `AuthorizedKey` record + `AuthorizedKeys` trust anchor

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/AuthorizedKey.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/AuthorizedKeys.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/identity/AuthorizedKeysTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizedKeysTest {

    private static void writeKeys(Path root, String... lines) throws Exception {
        Path f = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, List.of(lines));
    }

    private static String line(String principal, KeyPair kp) {
        return "{\"principal\":\"" + principal + "\",\"publicKey\":\"" + Ed25519Keys.publicKeyB64(kp.getPublic()) + "\"}";
    }

    @Test void absent_file_authorizes_nobody(@TempDir Path root) throws Exception {
        AuthorizedKeys ak = AuthorizedKeys.load(root);
        assertTrue(ak.forPrincipal("alice").isEmpty());
    }

    @Test void registered_principal_resolves_to_its_pubkey(@TempDir Path root) throws Exception {
        KeyPair alice = Ed25519Keys.generate();
        writeKeys(root, line("alice", alice));
        AuthorizedKeys ak = AuthorizedKeys.load(root);
        assertTrue(ak.forPrincipal("alice").isPresent());
        assertTrue(ak.forPrincipal("bob").isEmpty());
        // resolved key verifies a sig made by alice's private key
        String sig = Ed25519Keys.sign("m".getBytes(), alice.getPrivate());
        assertTrue(Ed25519Keys.verify("m".getBytes(), sig, ak.forPrincipal("alice").get()));
    }

    @Test void duplicate_principal_same_key_is_tolerated(@TempDir Path root) throws Exception {
        KeyPair alice = Ed25519Keys.generate();
        writeKeys(root, line("alice", alice), line("alice", alice));
        assertTrue(AuthorizedKeys.load(root).forPrincipal("alice").isPresent());
    }

    @Test void duplicate_principal_different_key_is_a_load_error(@TempDir Path root) throws Exception {
        writeKeys(root, line("alice", Ed25519Keys.generate()), line("alice", Ed25519Keys.generate()));
        assertThrows(IllegalStateException.class, () -> AuthorizedKeys.load(root));
    }

    @Test void blank_lines_are_ignored(@TempDir Path root) throws Exception {
        KeyPair alice = Ed25519Keys.generate();
        writeKeys(root, line("alice", alice), "", "  ");
        assertTrue(AuthorizedKeys.load(root).forPrincipal("alice").isPresent());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=AuthorizedKeysTest`
Expected: FAIL — `AuthorizedKeys` / `AuthorizedKey` do not exist.

- [ ] **Step 3: Write the implementations**

`AuthorizedKey.java`:
```java
package dev.krillin.bifrost.core.identity;

/** One line of registry/identity/authorized-keys.jsonl: a principal and its X.509-b64 Ed25519 public key. */
public record AuthorizedKey(String principal, String publicKey) {}
```

`AuthorizedKeys.java`:
```java
package dev.krillin.bifrost.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import java.io.IOException;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.*;

/** The trust anchor: principal -> Ed25519 public key, loaded from registry/identity/authorized-keys.jsonl
 *  (one {@link AuthorizedKey} JSON object per line). Fail-closed: an absent file authorizes nobody, and an
 *  unregistered principal resolves to empty. A duplicate principal line with a DIFFERENT key is a load
 *  error (ambiguous identity) — an identical duplicate is tolerated. Whole-file read per load (small
 *  registry, matches T4's ledger reads); not cached. Bootstrap/distribution/revocation are out-of-band
 *  (spec §9) — registering a key is adding a line, revoking is deleting one. */
public final class AuthorizedKeys {
    private final Map<String, PublicKey> byPrincipal;
    private AuthorizedKeys(Map<String, PublicKey> m) { this.byPrincipal = m; }

    public Optional<PublicKey> forPrincipal(String principal) {
        return Optional.ofNullable(byPrincipal.get(principal));
    }

    public static AuthorizedKeys load(Path registryRoot) {
        Path f = registryRoot.resolve("identity").resolve("authorized-keys.jsonl");
        if (!Files.isRegularFile(f)) return new AuthorizedKeys(Map.of());
        ObjectMapper mapper = JsonMapperFactory.create();
        Map<String, String> b64ByPrincipal = new HashMap<>();
        Map<String, PublicKey> keys = new HashMap<>();
        try {
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) continue;
                AuthorizedKey ak = mapper.readValue(line, AuthorizedKey.class);
                String prev = b64ByPrincipal.putIfAbsent(ak.principal(), ak.publicKey());
                if (prev != null && !prev.equals(ak.publicKey()))
                    throw new IllegalStateException("identity.authorized-keys.duplicate-principal-different-key: "
                            + ak.principal());
                keys.putIfAbsent(ak.principal(), Ed25519Keys.publicKey(ak.publicKey()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("identity.authorized-keys.read-error: " + f, e);
        }
        return new AuthorizedKeys(Map.copyOf(keys));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=AuthorizedKeysTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/identity/AuthorizedKey.java \
        core/src/main/java/dev/krillin/bifrost/core/identity/AuthorizedKeys.java \
        core/src/test/java/dev/krillin/bifrost/core/identity/AuthorizedKeysTest.java
git commit -m "feat(core): AuthorizedKeys trust anchor — principal->pubkey from registry, fail-closed, dup-key rejected"
```

---

## Chunk 2: Signed head + signed ledger entries (writer path)

Extends `LedgerEntry`, adds `SignedHead`/`SignedHeadStore`, `Signatures`, the `LedgerSigner` seam, and wires signing into `ActivationLedger.append` and `ActivationService.activate` — with the null-signer path proving exact T4 behavior is preserved.

### Task 3: `SignedHead` record + `SignedHeadStore`

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/SignedHead.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/SignedHeadStore.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/identity/SignedHeadStoreTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class SignedHeadStoreTest {

    @Test void absent_head_reads_empty(@TempDir Path root) throws Exception {
        assertTrue(new SignedHeadStore(root).read("Line1").isEmpty());
    }

    @Test void write_then_read_roundtrips(@TempDir Path root) throws Exception {
        SignedHeadStore store = new SignedHeadStore(root);
        SignedHead h = new SignedHead("Line1", 0L, "abc123", "bob", "sigB64");
        store.write(h);
        Optional<SignedHead> back = store.read("Line1");
        assertTrue(back.isPresent());
        assertEquals(h, back.get());
    }

    @Test void head_preimage_is_stable_and_field_ordered() {
        String p = SignedHeadStore.preimage("Line1", 3L, "deadbeef");
        assertEquals("Line1\u001F3\u001Fdeadbeef", p);   // fields joined by U+001F Unit Separator
    }

    @Test void write_overwrites_prior_head(@TempDir Path root) throws Exception {
        SignedHeadStore store = new SignedHeadStore(root);
        store.write(new SignedHead("Line1", 0L, "h0", "bob", "s0"));
        store.write(new SignedHead("Line1", 1L, "h1", "bob", "s1"));
        assertEquals(1L, store.read("Line1").get().seq());
        assertEquals("h1", store.read("Line1").get().tailEntryHash());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=SignedHeadStoreTest`
Expected: FAIL — types missing.

- [ ] **Step 3: Write the implementations**

`SignedHead.java`:
```java
package dev.krillin.bifrost.core.identity;

/** The signed anchor for a target's ledger tail. seq is monotonic per target and MUST equal
 *  (entryCount - 1) at verify time — this is what makes tail-truncation detectable. sig is the approver's
 *  Ed25519 signature over {@link SignedHeadStore#preimage}. Persisted as one JSON line at
 *  registry/identity/<target>.head. */
public record SignedHead(String target, long seq, String tailEntryHash, String signedBy, String sig) {}
```

`SignedHeadStore.java`:
```java
package dev.krillin.bifrost.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;

/** Reads/writes registry/identity/<target>.head. The head preimage is a canonical, delimiter-joined
 *  field concat (NOT JSON) — target ␟ seq ␟ tailEntryHash — so the writer and every verifier sign/verify
 *  byte-for-byte identical input (same discipline as LedgerChain.preimage). */
public final class SignedHeadStore {
    private static final char SEP = '\u001F';   // U+001F Unit Separator
    private final Path root;
    private final ObjectMapper mapper = JsonMapperFactory.create();

    public SignedHeadStore(Path registryRoot) { this.root = registryRoot; }

    private Path file(String target) { return root.resolve("identity").resolve(target + ".head"); }

    /** The signed bytes' textual preimage. Kept identical between writer and verifier. */
    public static String preimage(String target, long seq, String tailEntryHash) {
        return target + SEP + seq + SEP + tailEntryHash;
    }

    public Optional<SignedHead> read(String target) throws IOException {
        Path f = file(target);
        if (!Files.isRegularFile(f)) return Optional.empty();
        String content = Files.readString(f).strip();
        if (content.isEmpty()) return Optional.empty();
        return Optional.of(mapper.readValue(content, SignedHead.class));
    }

    public void write(SignedHead head) throws IOException {
        Path f = file(head.target());
        Files.createDirectories(f.getParent());
        Files.writeString(f, mapper.writeValueAsString(head) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=SignedHeadStoreTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/identity/SignedHead.java \
        core/src/main/java/dev/krillin/bifrost/core/identity/SignedHeadStore.java \
        core/src/test/java/dev/krillin/bifrost/core/identity/SignedHeadStoreTest.java
git commit -m "feat(core): SignedHead + SignedHeadStore — canonical head preimage, per-target .head file"
```

---

### Task 4: Extend `LedgerEntry` with nullable signatures (backward compatible)

**Files:**
- Modify: `core/src/main/java/dev/krillin/bifrost/core/activation/LedgerEntry.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/LedgerEntrySerdeTest.java`

> **Ripple note:** adding two record components changes the canonical constructor. Every `new LedgerEntry(e, prev, hash)` call site now needs two more args. Known call sites: `LedgerChainTest` (helper `chain()`), and any test that builds entries directly. Do NOT change `LedgerChain`/`ActivationLedger` reads (they use accessors, unaffected). Fix call sites in Step 3.

- [ ] **Step 1: Write the failing test** (proves legacy 3-field line deserializes with null sigs, and a signed line roundtrips)

```java
package dev.krillin.bifrost.core.activation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LedgerEntrySerdeTest {
    private final ObjectMapper mapper = JsonMapperFactory.create();

    private static ActivationEvent ev() {
        return new ActivationEvent("Line1","recipe","mix","1.0.0","sha","alice","bob",1000L,null,"ACTIVATE");
    }

    @Test void legacy_three_field_line_deserializes_with_null_sigs() throws Exception {
        // A T4 line has no sig fields at all.
        String legacy = "{\"event\":{\"target\":\"Line1\",\"kind\":\"recipe\",\"ref\":\"mix\",\"version\":\"1.0.0\","
                + "\"contentSha256\":\"sha\",\"activatedBy\":\"alice\",\"approvedBy\":\"bob\",\"activatedAt\":1000,"
                + "\"priorVersion\":null,\"action\":\"ACTIVATE\"},\"prevHash\":\""
                + LedgerChain.GENESIS + "\",\"entryHash\":\"deadbeef\"}";
        LedgerEntry e = mapper.readValue(legacy, LedgerEntry.class);
        assertNull(e.activatorSig());
        assertNull(e.approverSig());
        assertEquals("deadbeef", e.entryHash());
    }

    @Test void signed_entry_roundtrips_through_json() throws Exception {
        LedgerEntry signed = new LedgerEntry(ev(), LedgerChain.GENESIS, "hash1", "aSig", "bSig");
        LedgerEntry back = mapper.readValue(mapper.writeValueAsString(signed), LedgerEntry.class);
        assertEquals("aSig", back.activatorSig());
        assertEquals("bSig", back.approverSig());
    }

    @Test void entryHash_ignores_signatures() {
        // signatures are NOT in the hash preimage -> LedgerChain unaffected
        ActivationEvent e = ev();
        assertEquals(LedgerChain.entryHash(e, LedgerChain.GENESIS),
                     new LedgerEntry(e, LedgerChain.GENESIS,
                             LedgerChain.entryHash(e, LedgerChain.GENESIS), "x", "y").entryHash());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=LedgerEntrySerdeTest`
Expected: FAIL — `LedgerEntry` has no 5-arg constructor / accessors.

- [ ] **Step 3: Modify `LedgerEntry` and fix ripple call sites**

New `LedgerEntry.java`:
```java
package dev.krillin.bifrost.core.activation;

/** The persisted unit: a T3 ActivationEvent plus its hash-chain links, plus (T5) two Ed25519 signatures
 *  over entryHash. Serialized as one JSONL line
 *  {"event":{…},"prevHash":"…","entryHash":"…","activatorSig":"…","approverSig":"…"}.
 *  prevHash = the prior entry's entryHash (GENESIS for the first). entryHash = LedgerChain.entryHash(event,
 *  prevHash) — signatures are NOT in the hash, so T4 structural verification is unaffected and legacy
 *  (unsigned) lines still verify. activatorSig/approverSig are NULL on a legacy T4 line (fields absent). */
public record LedgerEntry(ActivationEvent event, String prevHash, String entryHash,
                          String activatorSig, String approverSig) {

    /** T4-compatibility factory for unsigned entries (both signatures null). */
    public static LedgerEntry unsigned(ActivationEvent event, String prevHash, String entryHash) {
        return new LedgerEntry(event, prevHash, entryHash, null, null);
    }
}
```

Then fix the ripple in `LedgerChainTest.chain()` — change:
```java
out.add(new LedgerEntry(e, prev, h));
```
to:
```java
out.add(LedgerEntry.unsigned(e, prev, h));
```
and the three other `new LedgerEntry(...)` sites in `LedgerChainTest` — at **lines 56, 66, 95** (`verify_detects_edited_event_content`, `verify_detects_edited_prevHash_in_place`, `verify_detects_broken_genesis`) — to `LedgerEntry.unsigned(...)`. Confirm with `grep -rn "new LedgerEntry(" core/src/test` (repo-wide this is the ONLY test that constructs `LedgerEntry` directly; `ActivationLedgerTest`/`ActivationServiceTest`/Heimdall use `.append(...)` or accessors, so they compile unchanged against the 5-arg record).

- [ ] **Step 4: Run to verify it passes** (new test + the fixed T4 chain test)

Run: `mvn -q -pl core -am test -Dtest=LedgerEntrySerdeTest,LedgerChainTest`
Expected: PASS (both).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/LedgerEntry.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/LedgerEntrySerdeTest.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/LedgerChainTest.java
git commit -m "feat(core): LedgerEntry carries nullable activator/approver sigs; unsigned() factory keeps T4 lines valid"
```

---

### Task 5: `Signatures` record + `LedgerSigner` seam + `KeyFileLedgerSigner`

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/Signatures.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/LedgerSigner.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/IdentityException.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/KeyFileLedgerSigner.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/identity/KeyFileLedgerSignerTest.java`

> **Design:** `LedgerSigner` (in `core.activation`, so `ActivationLedger` can reference it without a cycle) exposes `preflight()` (fail-closed principal-binding + four-eyes checks → list of `Violation`), `sign(entryHash)`, `signHead(preimage)`, `approverPrincipal()`. `KeyFileLedgerSigner` (in `core.identity`) is the concrete impl holding the two private keys + principals + `AuthorizedKeys`. `preflight` returns violations (not exceptions) so `ActivationService` reports them the same way as T3's string SoD.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.identity;

import dev.krillin.bifrost.core.activation.LedgerSigner;
import dev.krillin.bifrost.core.activation.Signatures;
import dev.krillin.bifrost.core.schema.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KeyFileLedgerSignerTest {

    private Path writeKey(Path dir, String name, java.security.PrivateKey k) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, Ed25519Keys.privateKeyB64(k));
        return f;
    }

    private void authorize(Path root, String principal, KeyPair kp) throws Exception {
        Path f = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(f.getParent());
        String line = "{\"principal\":\"" + principal + "\",\"publicKey\":\""
                + Ed25519Keys.publicKeyB64(kp.getPublic()) + "\"}\n";
        Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test void preflight_clean_for_two_distinct_registered_principals(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        authorize(root, "alice", alice); authorize(root, "bob", bob);
        LedgerSigner s = KeyFileLedgerSigner.create("alice", writeKey(keys,"a",alice.getPrivate()),
                "bob", writeKey(keys,"b",bob.getPrivate()), AuthorizedKeys.load(root));
        assertTrue(s.preflight().isEmpty());
        // sigs verify under the registered keys
        Signatures sig = s.sign("hash1");
        assertTrue(Ed25519Keys.verify("hash1".getBytes(StandardCharsets.UTF_8), sig.activatorSig(), alice.getPublic()));
        assertTrue(Ed25519Keys.verify("hash1".getBytes(StandardCharsets.UTF_8), sig.approverSig(), bob.getPublic()));
    }

    @Test void preflight_flags_key_file_not_matching_its_principal(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        authorize(root, "alice", alice); authorize(root, "bob", bob);
        // approver claims "bob" but hands alice's key file -> principal-mismatch
        LedgerSigner s = KeyFileLedgerSigner.create("alice", writeKey(keys,"a",alice.getPrivate()),
                "bob", writeKey(keys,"b2",alice.getPrivate()), AuthorizedKeys.load(root));
        List<Violation> v = s.preflight();
        assertFalse(v.isEmpty());
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("identity.key.principal-mismatch")));
    }

    @Test void preflight_flags_unregistered_principal(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        authorize(root, "alice", alice); // bob NOT authorized
        LedgerSigner s = KeyFileLedgerSigner.create("alice", writeKey(keys,"a",alice.getPrivate()),
                "bob", writeKey(keys,"b",bob.getPrivate()), AuthorizedKeys.load(root));
        // an UNREGISTERED principal folds into principal-mismatch at WRITE time (spec §4.5);
        // identity.key.unregistered is the VERIFIER-side code, exercised in Chunk 3.
        assertTrue(s.preflight().stream().anyMatch(x -> x.rule().equals("identity.key.principal-mismatch")));
    }

    @Test void preflight_flags_two_principals_sharing_one_pubkey(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair shared = Ed25519Keys.generate();
        authorize(root, "alice", shared); authorize(root, "bob", shared);
        LedgerSigner s = KeyFileLedgerSigner.create("alice", writeKey(keys,"a",shared.getPrivate()),
                "bob", writeKey(keys,"b",shared.getPrivate()), AuthorizedKeys.load(root));
        assertTrue(s.preflight().stream().anyMatch(x -> x.rule().equals("identity.four-eyes.same-key")));
    }

    @Test void signHead_is_verifiable_by_approver_key(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        authorize(root, "alice", alice); authorize(root, "bob", bob);
        LedgerSigner s = KeyFileLedgerSigner.create("alice", writeKey(keys,"a",alice.getPrivate()),
                "bob", writeKey(keys,"b",bob.getPrivate()), AuthorizedKeys.load(root));
        String preimage = "Line1\u001F0\u001Fhash1";
        assertEquals("bob", s.approverPrincipal());
        assertTrue(Ed25519Keys.verify(preimage.getBytes(StandardCharsets.UTF_8), s.signHead(preimage), bob.getPublic()));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=KeyFileLedgerSignerTest`
Expected: FAIL — types missing.

- [ ] **Step 3: Write the implementations**

`Signatures.java`:
```java
package dev.krillin.bifrost.core.activation;

/** The two Ed25519 signatures over a LedgerEntry's entryHash (base64). */
public record Signatures(String activatorSig, String approverSig) {}
```

`LedgerSigner.java`:
```java
package dev.krillin.bifrost.core.activation;

import dev.krillin.bifrost.core.schema.Violation;
import java.util.List;

/** The writer's signing seam (nullable in ActivationLedger.append / ActivationService.activate — null =
 *  exact T4 unsigned behavior). preflight() runs the fail-closed identity checks (key-file↔principal
 *  binding and cryptographic four-eyes) and returns them as Violations so ActivationService reports them
 *  exactly like T3's string SoD. sign()/signHead() produce the entry and head signatures. */
public interface LedgerSigner {
    List<Violation> preflight();
    Signatures sign(String entryHash);
    String signHead(String headPreimage);
    String approverPrincipal();
}
```

`IdentityException.java`:
```java
package dev.krillin.bifrost.core.identity;

/** A fail-closed identity fault carrying a reason-code rule slug (identity.*). */
public final class IdentityException extends RuntimeException {
    private final String rule;
    public IdentityException(String rule, String detail) { super(detail); this.rule = rule; }
    public String rule() { return rule; }
}
```

`KeyFileLedgerSigner.java`:
```java
package dev.krillin.bifrost.core.identity;

import dev.krillin.bifrost.core.activation.LedgerSigner;
import dev.krillin.bifrost.core.activation.Signatures;
import dev.krillin.bifrost.core.schema.Violation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

/** LedgerSigner backed by two private-key FILES (base64 PKCS8) + the AuthorizedKeys trust anchor.
 *  Because a public key cannot be cleanly recomputed from a PKCS8 private key in JDK 17, we bind each key
 *  FILE to its claimed principal by signing a fixed probe and verifying it against the principal's
 *  REGISTERED public key. The registered pubkeys are also what the four-eyes distinctness check compares. */
public final class KeyFileLedgerSigner implements LedgerSigner {
    private static final byte[] PROBE = "bifrost-identity-probe".getBytes(StandardCharsets.UTF_8);

    private final String activatorPrincipal, approverPrincipal;
    private final PrivateKey activatorKey, approverKey;
    private final AuthorizedKeys authorized;

    private KeyFileLedgerSigner(String ap, PrivateKey ak, String pp, PrivateKey pk, AuthorizedKeys auth) {
        this.activatorPrincipal = ap; this.activatorKey = ak;
        this.approverPrincipal = pp;  this.approverKey = pk; this.authorized = auth;
    }

    public static KeyFileLedgerSigner create(String activatorPrincipal, Path activatorKeyFile,
                                             String approverPrincipal, Path approverKeyFile,
                                             AuthorizedKeys authorized) {
        return new KeyFileLedgerSigner(activatorPrincipal, readKey(activatorKeyFile),
                approverPrincipal, readKey(approverKeyFile), authorized);
    }

    private static PrivateKey readKey(Path f) {
        try { return Ed25519Keys.privateKey(Files.readString(f).strip()); }
        catch (IOException e) { throw new IdentityException("identity.key.unreadable", "cannot read key file " + f); }
    }

    @Override public List<Violation> preflight() {
        List<Violation> v = new ArrayList<>();
        Optional<PublicKey> aReg = bindsToPrincipal(activatorPrincipal, activatorKey, v);
        Optional<PublicKey> pReg = bindsToPrincipal(approverPrincipal, approverKey, v);
        // cryptographic four-eyes: the two registered pubkeys must differ (only reachable if both bound)
        if (aReg.isPresent() && pReg.isPresent()
                && Arrays.equals(aReg.get().getEncoded(), pReg.get().getEncoded()))
            v.add(new Violation("identity.four-eyes.same-key",
                    "activator '" + activatorPrincipal + "' and approver '" + approverPrincipal
                    + "' resolve to the same registered key"));
        return v;
    }

    /** The key file signs a probe that verifies under the principal's REGISTERED pubkey; else a violation. */
    private Optional<PublicKey> bindsToPrincipal(String principal, PrivateKey key, List<Violation> sink) {
        Optional<PublicKey> reg = authorized.forPrincipal(principal);
        if (reg.isEmpty() || !Ed25519Keys.verify(PROBE, Ed25519Keys.sign(PROBE, key), reg.get())) {
            sink.add(new Violation("identity.key.principal-mismatch",
                    "key file for '" + principal + "' does not match its registered public key (or principal not registered)"));
            return Optional.empty();
        }
        return reg;
    }

    @Override public Signatures sign(String entryHash) {
        byte[] msg = entryHash.getBytes(StandardCharsets.UTF_8);
        return new Signatures(Ed25519Keys.sign(msg, activatorKey), Ed25519Keys.sign(msg, approverKey));
    }

    @Override public String signHead(String headPreimage) {
        return Ed25519Keys.sign(headPreimage.getBytes(StandardCharsets.UTF_8), approverKey);
    }

    @Override public String approverPrincipal() { return approverPrincipal; }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=KeyFileLedgerSignerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/Signatures.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/LedgerSigner.java \
        core/src/main/java/dev/krillin/bifrost/core/identity/IdentityException.java \
        core/src/main/java/dev/krillin/bifrost/core/identity/KeyFileLedgerSigner.java \
        core/src/test/java/dev/krillin/bifrost/core/identity/KeyFileLedgerSignerTest.java
git commit -m "feat(core): LedgerSigner seam + KeyFileLedgerSigner — probe-verify principal binding + crypto four-eyes"
```

---

### Task 6: `ActivationLedger.append(event, signer)` — signed line + head

**Files:**
- Modify: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationLedger.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationLedgerSignedTest.java`

> **Preserve T4:** keep `append(ActivationEvent e)` as a delegate to `append(e, null)`. The `tailEntryHash`/`history`/`active`/`verifyChain` methods are unchanged. `LedgerEntry.unsigned(...)` is used on the null path.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.activation;

import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ActivationLedgerSignedTest {

    private static ActivationEvent ev(String v, String prior) {
        return new ActivationEvent("Line1","recipe","mix",v,"sha-"+v,"alice","bob",1000L,prior,"ACTIVATE");
    }

    private LedgerSigner signer(Path root, Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\"alice\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(alice.getPublic())+"\"}\n"
          + "{\"principal\":\"bob\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(bob.getPublic())+"\"}\n");
        Path a = keys.resolve("a"), b = keys.resolve("b");
        Files.writeString(a, Ed25519Keys.privateKeyB64(alice.getPrivate()));
        Files.writeString(b, Ed25519Keys.privateKeyB64(bob.getPrivate()));
        return KeyFileLedgerSigner.create("alice", a, "bob", b, AuthorizedKeys.load(root));
    }

    @Test void null_signer_writes_unsigned_entry_and_no_head(@TempDir Path root) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        ledger.append(ev("1.0.0", null), null);
        List<LedgerEntry> h = ledger.history("Line1");
        assertEquals(1, h.size());
        assertNull(h.get(0).activatorSig());
        assertTrue(new SignedHeadStore(root).read("Line1").isEmpty(), "no head on the unsigned path");
    }

    @Test void t4_append_still_works(@TempDir Path root) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        ledger.append(ev("1.0.0", null));   // legacy single-arg
        assertEquals(1, ledger.history("Line1").size());
    }

    @Test void signed_append_writes_two_sigs_and_advances_head(@TempDir Path root, @TempDir Path keys) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        LedgerSigner s = signer(root, keys);
        ledger.append(ev("1.0.0", null), s);
        ledger.append(ev("1.1.0", "1.0.0"), s);
        List<LedgerEntry> h = ledger.history("Line1");
        assertEquals(2, h.size());
        assertNotNull(h.get(1).activatorSig());
        assertNotNull(h.get(1).approverSig());
        SignedHead head = new SignedHeadStore(root).read("Line1").orElseThrow();
        assertEquals(1L, head.seq(), "seq == entryCount-1");
        assertEquals(h.get(1).entryHash(), head.tailEntryHash());
        assertEquals("bob", head.signedBy());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=ActivationLedgerSignedTest`
Expected: FAIL — no `append(event, signer)` overload.

- [ ] **Step 3: Modify `ActivationLedger`**

Add imports and replace the `append` method. Add a `SignedHeadStore` field built from the same root:
```java
// add field
private final dev.krillin.bifrost.core.identity.SignedHeadStore heads;
// in constructor:
public ActivationLedger(Path registryRoot) {
    this.root = registryRoot;
    this.heads = new dev.krillin.bifrost.core.identity.SignedHeadStore(registryRoot);
}

/** T4-compatible unsigned append. */
public void append(ActivationEvent e) throws IOException { append(e, null); }

/** T5: when signer != null, dual-sign the entry over entryHash and advance the signed head; when null,
 *  exact T4 behavior (unsigned line, no head). The ledger line is written BEFORE the head — a crash
 *  between them leaves head.seq one behind, caught fail-closed by SignedLedgerVerifier (spec §7). */
public void append(ActivationEvent e, dev.krillin.bifrost.core.activation.LedgerSigner signer) throws IOException {
    Path f = file(e.target());
    Files.createDirectories(f.getParent());
    String prevHash = tailEntryHash(f);
    String entryHash = LedgerChain.entryHash(e, prevHash);
    LedgerEntry entry = (signer == null)
            ? LedgerEntry.unsigned(e, prevHash, entryHash)
            : signedEntry(e, prevHash, entryHash, signer);
    Files.writeString(f, mapper.writeValueAsString(entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    if (signer != null) advanceHead(e.target(), entryHash, signer);
}

private LedgerEntry signedEntry(ActivationEvent e, String prevHash, String entryHash,
                                dev.krillin.bifrost.core.activation.LedgerSigner signer) {
    Signatures sig = signer.sign(entryHash);
    return new LedgerEntry(e, prevHash, entryHash, sig.activatorSig(), sig.approverSig());
}

private void advanceHead(String target, String tailEntryHash,
                         dev.krillin.bifrost.core.activation.LedgerSigner signer) throws IOException {
    long seq = heads.read(target).map(h -> h.seq() + 1).orElse(0L);
    String preimage = dev.krillin.bifrost.core.identity.SignedHeadStore.preimage(target, seq, tailEntryHash);
    heads.write(new dev.krillin.bifrost.core.identity.SignedHead(
            target, seq, tailEntryHash, signer.approverPrincipal(), signer.signHead(preimage)));
}
```

> **Note:** if the earlier `ActivationLedger` had a one-line constructor `public ActivationLedger(Path registryRoot) { this.root = registryRoot; }`, replace it with the two-line version above.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=ActivationLedgerSignedTest,ActivationLedgerTest`
Expected: PASS (new + existing T4 ledger test).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationLedger.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationLedgerSignedTest.java
git commit -m "feat(core): ActivationLedger.append(event, signer) — signed line + advancing signed head; null=T4"
```

---

### Task 7: `ActivationService.activate(request, signer)` — preflight fail-closed

**Files:**
- Modify: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceSignedTest.java`

> **Preserve T4:** keep `activate(ActivationRequest r)` delegating to `activate(r, null)`. All T3 checks (resolve, string SoD, rollback) run first and unchanged; the signer `preflight()` runs after them and before the ledger write, returning refuse verdicts with the `identity.*` rules.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.activation;

import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPair;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class ActivationServiceSignedTest {

    /** Minimal resolver: any (kind,ref,version) resolves. NOTE the REAL interface is
     *  ArtifactResolver.ResolvedArtifact(Path path, String sha256) — NOT Resolved(byte[],String). */
    private ArtifactResolver okResolver() {
        return (kind, ref, version) ->
                java.util.Optional.of(new ArtifactResolver.ResolvedArtifact(java.nio.file.Path.of("x"), "shaX"));
    }

    private KeyFileLedgerSigner signer(Path root, Path keys, String aP, KeyPair a, String pP, KeyPair p) throws Exception {
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\""+aP+"\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(a.getPublic())+"\"}\n"
          + "{\"principal\":\""+pP+"\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(p.getPublic())+"\"}\n");
        Path af = keys.resolve("a"), pf = keys.resolve("p");
        Files.writeString(af, Ed25519Keys.privateKeyB64(a.getPrivate()));
        Files.writeString(pf, Ed25519Keys.privateKeyB64(p.getPrivate()));
        return KeyFileLedgerSigner.create(aP, af, pP, pf, AuthorizedKeys.load(root));
    }

    @Test void signed_activation_writes_a_signed_ledger(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        ActivationLedger ledger = new ActivationLedger(root);
        ActivationService svc = new ActivationService(okResolver(), ledger, Clock.systemUTC());
        ActivationVerdict v = svc.activate(
                new ActivationRequest("Line1","recipe","mix","1.0.0","alice","bob",false),
                signer(root, keys, "alice", alice, "bob", bob));
        assertTrue(v.ok(), v.violations().toString());
        assertNotNull(ledger.history("Line1").get(0).activatorSig());
    }

    @Test void principal_mismatch_refuses_and_leaves_ledger_untouched(@TempDir Path root, @TempDir Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        // approver claims "bob" but the key file is alice's
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\"alice\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(alice.getPublic())+"\"}\n"
          + "{\"principal\":\"bob\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(bob.getPublic())+"\"}\n");
        Path af = keys.resolve("a"), pf = keys.resolve("p");
        Files.writeString(af, Ed25519Keys.privateKeyB64(alice.getPrivate()));
        Files.writeString(pf, Ed25519Keys.privateKeyB64(alice.getPrivate())); // wrong: alice's key as bob
        LedgerSigner bad = KeyFileLedgerSigner.create("alice", af, "bob", pf, AuthorizedKeys.load(root));

        ActivationLedger ledger = new ActivationLedger(root);
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC())
                .activate(new ActivationRequest("Line1","recipe","mix","1.0.0","alice","bob",false), bad);
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("identity.key.principal-mismatch")));
        assertTrue(ledger.history("Line1").isEmpty(), "refused => ledger untouched");
    }

    @Test void null_signer_is_unchanged_t3_behavior(@TempDir Path root) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC())
                .activate(new ActivationRequest("Line1","recipe","mix","1.0.0","alice","bob",false));
        assertTrue(v.ok());
        assertNull(ledger.history("Line1").get(0).activatorSig());
    }
}
```

> **`ArtifactResolver` (confirmed shape):** `Optional<ResolvedArtifact> resolve(String kind, String ref, String version)` where `record ResolvedArtifact(Path path, String sha256)`. The lambda above matches it. The service body reads `resolved.get().sha256()` (correct accessor). The behavior asserted (refuse leaves ledger untouched; signed path writes sigs) is what matters.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=ActivationServiceSignedTest`
Expected: FAIL — no `activate(request, signer)` overload.

- [ ] **Step 3: Modify `ActivationService`**

Keep the T3 method as a delegate and add the signer-aware overload:
```java
public ActivationVerdict activate(ActivationRequest r) { return activate(r, null); }

public ActivationVerdict activate(ActivationRequest r, LedgerSigner signer) {
    try {
        var resolved = resolver.resolve(r.kind(), r.ref(), r.version());
        if (resolved.isEmpty()) return refuse("activation.artifact.unresolved",
                r.kind() + " " + r.ref() + "@" + r.version() + " is not a resolvable governed artifact");
        if (r.approvedBy() == null || r.approvedBy().isBlank())
            return refuse("activation.approval.missing", "activation requires a distinct approver (--approved-by)");
        if (r.approvedBy().equals(r.by()))
            return refuse("activation.approval.self", "approver '" + r.by() + "' must differ from the activator (four-eyes)");
        if (r.rollback() && !versionInHistory(r))
            return refuse("activation.rollback.unknown-version",
                    "cannot rollback to " + r.version() + " — never activated on target " + r.target());
        if (signer != null) {                                   // T5: fail-closed identity checks
            var idv = signer.preflight();
            if (!idv.isEmpty()) return new ActivationVerdict(false, null, idv);
        }
        String prior = ledger.active(r.target(), r.kind(), r.ref()).map(ActivationEvent::version).orElse(null);
        ActivationEvent e = new ActivationEvent(r.target(), r.kind(), r.ref(), r.version(),
                resolved.get().sha256(), r.by(), r.approvedBy(), clock.millis(), prior,
                r.rollback() ? "ROLLBACK" : "ACTIVATE");
        ledger.append(e, signer);
        return new ActivationVerdict(true, e, List.of());
    } catch (Exception ex) {
        return refuse("activation.error", ex.getMessage());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=ActivationServiceSignedTest,ActivationServiceTest`
Expected: PASS (new + existing T3 service test).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceSignedTest.java
git commit -m "feat(core): ActivationService.activate(request, signer) — signer preflight fail-closed; null=T3"
```

---

## Chunk 3: Verifier + gate CLI

Full authenticated verification and the operator surface (keygen, signed activate, verify-signed).

### Task 8: `SignedVerdict` + `SignedLedgerVerifier`

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/SignedVerdict.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/identity/SignedLedgerVerifier.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/identity/SignedLedgerVerifierTest.java`

- [ ] **Step 1: Write the failing test** (covers each reason code — this is the crux of I2/I3/I5/I6 at the unit layer)

```java
package dev.krillin.bifrost.core.identity;

import dev.krillin.bifrost.core.activation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SignedLedgerVerifierTest {

    private static ActivationEvent ev(String v, String prior) {
        return new ActivationEvent("Line1","recipe","mix",v,"sha-"+v,"alice","bob",1000L,prior,"ACTIVATE");
    }

    /** Seed a 2-entry signed ledger; returns the verifier over the registry root. */
    private SignedLedgerVerifier seed(Path root, Path keys, KeyPair alice, KeyPair bob) throws Exception {
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\"alice\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(alice.getPublic())+"\"}\n"
          + "{\"principal\":\"bob\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(bob.getPublic())+"\"}\n");
        Path a = keys.resolve("a"), b = keys.resolve("b");
        Files.writeString(a, Ed25519Keys.privateKeyB64(alice.getPrivate()));
        Files.writeString(b, Ed25519Keys.privateKeyB64(bob.getPrivate()));
        LedgerSigner s = KeyFileLedgerSigner.create("alice", a, "bob", b, AuthorizedKeys.load(root));
        ActivationLedger ledger = new ActivationLedger(root);
        ledger.append(ev("1.0.0", null), s);
        ledger.append(ev("1.1.0", "1.0.0"), s);
        return SignedLedgerVerifier.forRegistry(root);
    }

    private Path ledgerFile(Path root) { return root.resolve("activation").resolve("Line1.jsonl"); }

    @Test void intact_signed_ledger_verifies(@TempDir Path root, @TempDir Path keys) throws Exception {
        SignedVerdict v = seed(root, keys, Ed25519Keys.generate(), Ed25519Keys.generate()).verify("Line1");
        assertTrue(v.intact(), v.rule());
    }

    @Test void tampered_signature_is_invalid(@TempDir Path root, @TempDir Path keys) throws Exception {
        SignedLedgerVerifier ver = seed(root, keys, Ed25519Keys.generate(), Ed25519Keys.generate());
        List<String> lines = Files.readAllLines(ledgerFile(root));
        lines.set(0, lines.get(0).replaceFirst("\"activatorSig\":\"[^\"]", "\"activatorSig\":\"A"));
        Files.write(ledgerFile(root), lines);
        SignedVerdict v = ver.verify("Line1");
        assertFalse(v.intact());
        assertEquals("identity.sig.invalid", v.rule());
    }

    @Test void unregistered_signer_is_rejected(@TempDir Path root, @TempDir Path keys) throws Exception {
        SignedLedgerVerifier ver = seed(root, keys, Ed25519Keys.generate(), Ed25519Keys.generate());
        // drop alice from authorized-keys after the fact
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        List<String> keep = new ArrayList<>();
        for (String l : Files.readAllLines(akf)) if (!l.contains("\"alice\"")) keep.add(l);
        Files.write(akf, keep);
        SignedVerdict v = SignedLedgerVerifier.forRegistry(root).verify("Line1");
        assertFalse(v.intact());
        assertEquals("identity.key.unregistered", v.rule());
    }

    @Test void tail_truncation_is_detected_by_head(@TempDir Path root, @TempDir Path keys) throws Exception {
        SignedLedgerVerifier ver = seed(root, keys, Ed25519Keys.generate(), Ed25519Keys.generate());
        List<String> lines = Files.readAllLines(ledgerFile(root));
        Files.write(ledgerFile(root), lines.subList(0, 1)); // drop the last entry, leave the head
        SignedVerdict v = ver.verify("Line1");
        assertFalse(v.intact());
        // seq (1) != size-1 (0) OR head.tailEntryHash != last.entryHash — both are head faults
        assertTrue(v.rule().equals("identity.head.seq-mismatch") || v.rule().equals("identity.head.tail-mismatch"), v.rule());
    }

    @Test void full_rechain_of_a_past_event_fails_signature(@TempDir Path root, @TempDir Path keys) throws Exception {
        SignedLedgerVerifier ver = seed(root, keys, Ed25519Keys.generate(), Ed25519Keys.generate());
        // Rewrite entry#0's event AND recompute all entryHash/prevHash so LedgerChain.verify passes,
        // but WITHOUT re-signing (attacker has no registered key). Rebuild via the same chain math.
        List<LedgerEntry> h = new ActivationLedger(root).history("Line1");
        ActivationEvent edited = ev("9.9.9", null);                 // forged past event
        String h0 = LedgerChain.entryHash(edited, LedgerChain.GENESIS);
        LedgerEntry e0 = new LedgerEntry(edited, LedgerChain.GENESIS, h0, h.get(0).activatorSig(), h.get(0).approverSig());
        ActivationEvent e1ev = h.get(1).event();
        String h1 = LedgerChain.entryHash(e1ev, h0);
        LedgerEntry e1 = new LedgerEntry(e1ev, h0, h1, h.get(1).activatorSig(), h.get(1).approverSig());
        com.fasterxml.jackson.databind.ObjectMapper m = dev.krillin.bifrost.core.schema.JsonMapperFactory.create();
        Files.write(ledgerFile(root), List.of(m.writeValueAsString(e0), m.writeValueAsString(e1)));
        SignedVerdict v = ver.verify("Line1");
        assertFalse(v.intact());
        assertEquals("identity.sig.invalid", v.rule(), "structural chain re-validates but the sig over the new hash fails");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=SignedLedgerVerifierTest`
Expected: FAIL — types missing.

- [ ] **Step 3: Write the implementations**

`SignedVerdict.java`:
```java
package dev.krillin.bifrost.core.identity;

/** Result of the full authenticated verification. intact => brokenIndex and rule null; else the FIRST
 *  break's zero-based entry index (or -1 for a head-level fault) and the identity.* rule slug. */
public record SignedVerdict(boolean intact, Integer brokenIndex, String rule) {
    public static SignedVerdict whole() { return new SignedVerdict(true, null, null); }
    public static SignedVerdict broken(int index, String rule) { return new SignedVerdict(false, index, rule); }
}
```

`SignedLedgerVerifier.java`:
```java
package dev.krillin.bifrost.core.identity;

import dev.krillin.bifrost.core.activation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;

/** The full authenticated check, layered on T4's structural chain. verify(target):
 *  1. LedgerChain.verify (structural — reused verbatim; a structural break returns first);
 *  2. per entry: both sigs present, valid vs AuthorizedKeys for the claimed activatedBy/approvedBy, and
 *     the two resolved keys distinct;
 *  3. head: present, tailEntryHash == last.entryHash, seq == size-1, sig valid vs the head's signer.
 *  Fail-closed throughout. A fully-unsigned legacy ledger yields identity.sig.missing at index 0. */
public final class SignedLedgerVerifier {
    private final ActivationLedger ledger;
    private final AuthorizedKeys authorized;
    private final SignedHeadStore heads;

    public SignedLedgerVerifier(ActivationLedger ledger, AuthorizedKeys authorized, SignedHeadStore heads) {
        this.ledger = ledger; this.authorized = authorized; this.heads = heads;
    }

    /** Convenience: build all three collaborators from one registry root (gate + Heimdall use this). */
    public static SignedLedgerVerifier forRegistry(Path registryRoot) {
        return new SignedLedgerVerifier(new ActivationLedger(registryRoot),
                AuthorizedKeys.load(registryRoot), new SignedHeadStore(registryRoot));
    }

    public SignedVerdict verify(String target) throws IOException {
        List<LedgerEntry> hist = ledger.history(target);

        // 1. structural (T4)
        ChainVerdict structural = LedgerChain.verify(hist);
        if (!structural.intact())
            return SignedVerdict.broken(structural.brokenIndex(), structural.rule());

        // 2. per-entry signatures
        for (int i = 0; i < hist.size(); i++) {
            LedgerEntry en = hist.get(i);
            ActivationEvent e = en.event();
            if (en.activatorSig() == null || en.approverSig() == null)
                return SignedVerdict.broken(i, "identity.sig.missing");
            Optional<PublicKey> aKey = authorized.forPrincipal(e.activatedBy());
            Optional<PublicKey> pKey = authorized.forPrincipal(e.approvedBy());
            if (aKey.isEmpty() || pKey.isEmpty())
                return SignedVerdict.broken(i, "identity.key.unregistered");
            byte[] msg = en.entryHash().getBytes(StandardCharsets.UTF_8);
            if (!Ed25519Keys.verify(msg, en.activatorSig(), aKey.get())
                    || !Ed25519Keys.verify(msg, en.approverSig(), pKey.get()))
                return SignedVerdict.broken(i, "identity.sig.invalid");
            if (java.util.Arrays.equals(aKey.get().getEncoded(), pKey.get().getEncoded()))
                return SignedVerdict.broken(i, "identity.four-eyes.same-key");
        }

        // 3. head (only meaningful for a non-empty ledger)
        if (!hist.isEmpty()) {
            Optional<SignedHead> maybe = heads.read(target);
            if (maybe.isEmpty()) return SignedVerdict.broken(-1, "identity.head.missing");
            SignedHead head = maybe.get();
            LedgerEntry last = hist.get(hist.size() - 1);
            if (!last.entryHash().equals(head.tailEntryHash()))
                return SignedVerdict.broken(-1, "identity.head.tail-mismatch");
            if (head.seq() != hist.size() - 1)
                return SignedVerdict.broken(-1, "identity.head.seq-mismatch");
            Optional<PublicKey> hKey = authorized.forPrincipal(head.signedBy());
            // head.signedBy need NOT equal the tail approver (spec §4.7) — only must be registered
            byte[] hp = SignedHeadStore.preimage(head.target(), head.seq(), head.tailEntryHash())
                    .getBytes(StandardCharsets.UTF_8);
            if (hKey.isEmpty() || !Ed25519Keys.verify(hp, head.sig(), hKey.get()))
                return SignedVerdict.broken(-1, "identity.head.sig-invalid");
        }
        return SignedVerdict.whole();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=SignedLedgerVerifierTest`
Expected: PASS (6 tests).

> **Deterministic tamper (applies to EVERY sig-flip in this plan — Task 8 I2, Task 11 `verify_signed_broken`, Task 12 `require_signed_on_throws...`):** `replaceFirst("\"activatorSig\":\"[^\"]", "\"activatorSig\":\"A")` is a NO-OP ~1/64 of the time (when the sig's first base64 char is already `A`). Make it deterministic: capture the first sig char and replace it with a *different* fixed char (e.g. `c0 == 'A' ? 'B' : 'A'`). Use the same deterministic helper in all three tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/identity/SignedVerdict.java \
        core/src/main/java/dev/krillin/bifrost/core/identity/SignedLedgerVerifier.java \
        core/src/test/java/dev/krillin/bifrost/core/identity/SignedLedgerVerifierTest.java
git commit -m "feat(core): SignedLedgerVerifier — structural + dual-sig + signed-head, reason-coded fail-closed"
```

---

### Task 9: Gate CLI — `identity keygen`

**Files:**
- Create: `gates/src/main/java/dev/krillin/bifrost/gates/IdentityGate.java`
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java`
- Test: `gates/src/test/java/dev/krillin/bifrost/gates/IdentityGateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.gates;

import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityGateTest {

    @Test void keygen_writes_key_and_pub_files(@TempDir Path out) {
        int code = IdentityGate.run(new String[]{"keygen", "alice", "--out", out.toString()});
        assertEquals(0, code);
        assertTrue(Files.isRegularFile(out.resolve("alice.key")));
        assertTrue(Files.isRegularFile(out.resolve("alice.pub")));
    }

    @Test void keygen_key_and_pub_are_a_valid_pair(@TempDir Path out) throws Exception {
        IdentityGate.run(new String[]{"keygen", "alice", "--out", out.toString()});
        var priv = Ed25519Keys.privateKey(Files.readString(out.resolve("alice.key")).strip());
        var pub  = Ed25519Keys.publicKey(Files.readString(out.resolve("alice.pub")).strip());
        String sig = Ed25519Keys.sign("m".getBytes(), priv);
        assertTrue(Ed25519Keys.verify("m".getBytes(), sig, pub));
    }

    @Test void keygen_missing_args_is_usage_error(@TempDir Path out) {
        assertEquals(2, IdentityGate.run(new String[]{"keygen"}));
        assertEquals(2, IdentityGate.run(new String[]{"keygen", "alice"})); // no --out
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl gates -am test -Dtest=IdentityGateTest`
Expected: FAIL — `IdentityGate` missing.

- [ ] **Step 3: Write `IdentityGate` (keygen leg) and route it**

`IdentityGate.java`:
```java
package dev.krillin.bifrost.gates;

import dev.krillin.bifrost.core.identity.Ed25519Keys;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.*;

/** Identity gate. Subcommands:
 *   keygen <principal> --out <dir>   generate an Ed25519 keypair; write <principal>.key (PKCS8 b64) and
 *                                    <principal>.pub (X.509 b64); print the authorized-keys.jsonl line.
 *   verify-signed <reg> <target>     full authenticated verification (0 intact / 1 broken / 2 usage).  (Task 11)
 */
public final class IdentityGate {
    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (args.length == 0) { usage(); return 2; }
        try {
            switch (args[0]) {
                case "keygen": return keygen(Arrays.copyOfRange(args, 1, args.length));
                default: usage(); return 2;
            }
        } catch (Exception e) { System.err.println("[GATE] error: " + e.getMessage()); return 2; }
    }

    private static int keygen(String[] a) throws Exception {
        String principal = null, out = null;
        for (int i = 0; i < a.length; i++) {
            if ("--out".equals(a[i])) out = (++i < a.length) ? a[i] : null;
            else if (principal == null) principal = a[i];
        }
        if (principal == null || out == null) {
            System.err.println("Usage: identity keygen <principal> --out <dir>"); return 2;
        }
        KeyPair kp = Ed25519Keys.generate();
        Path dir = Path.of(out);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(principal + ".key"), Ed25519Keys.privateKeyB64(kp.getPrivate()));
        Files.writeString(dir.resolve(principal + ".pub"), Ed25519Keys.publicKeyB64(kp.getPublic()));
        System.out.println("{\"principal\":\"" + principal + "\",\"publicKey\":\""
                + Ed25519Keys.publicKeyB64(kp.getPublic()) + "\"}");
        System.err.println("[GATE] keygen principal=" + principal + " -> " + dir.resolve(principal + ".key")
                + " , " + dir.resolve(principal + ".pub"));
        return 0;
    }

    private static void usage() { System.err.println("Usage: identity <keygen|verify-signed> ..."); }
}
```

In `GatesCli.run`, add a case:
```java
case "identity":
    return IdentityGate.run(rest);
```
and add `identity` to BOTH usage strings: the `run()` error line and the class-javadoc `Usage:` line.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl gates -am test -Dtest=IdentityGateTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add gates/src/main/java/dev/krillin/bifrost/gates/IdentityGate.java \
        gates/src/main/java/dev/krillin/bifrost/gates/GatesCli.java \
        gates/src/test/java/dev/krillin/bifrost/gates/IdentityGateTest.java
git commit -m "feat(gates): identity keygen — Ed25519 keypair + authorized-keys line"
```

---

### Task 10: Gate CLI — `activate --by-key/--approved-by-key` (signed activation)

**Files:**
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java`
- Test: `gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateSignedTest.java`

> **Wiring:** in `ActivateGate.activate`, parse `--by-key`/`--approved-by-key`. If both present → build `KeyFileLedgerSigner.create(by, byKey, approvedBy, approvedByKey, AuthorizedKeys.load(reg))` and call `svc.activate(req, signer)`. If neither → `svc.activate(req)` (unsigned, unchanged). If exactly one → usage error (2). The refuse-path printing (`[GATE] REFUSED:` + violations) already handles the `identity.*` rules unchanged.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.gates;

import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivateGateSignedTest {

    /** Seed a registry with a resolvable recipe artifact + two authorized keys; return the reg root. */
    private Path seedRegistry(Path root) throws Exception {
        // authorized keys via keygen
        Path keys = root.resolve("keys");
        IdentityGate.run(new String[]{"keygen","alice","--out",keys.toString()});
        IdentityGate.run(new String[]{"keygen","bob","--out",keys.toString()});
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\"alice\",\"publicKey\":\""+Files.readString(keys.resolve("alice.pub")).strip()+"\"}\n"
          + "{\"principal\":\"bob\",\"publicKey\":\""+Files.readString(keys.resolve("bob.pub")).strip()+"\"}\n");
        // a resolvable recipe artifact — the REAL layout is MasterSpecStore-backed
        // (MasterSpecStore().file(reg,"mix-recipe","1.0.0")), NOT a guessed spec/mix path.
        // Copy the exact fixture staging that ActivateGateTest / run-lineage-gate.sh use.
        return root;
    }

    @Test void signed_activate_then_verify_signed_is_intact(@TempDir Path root) throws Exception {
        // This test mirrors the existing ActivateGateTest artifact setup; adapt paths to the real fixture.
        // Left as an integration-style check primarily exercised by run-identity-gate.sh (I1).
        assertTrue(true);
    }
}
```

> **Reality check:** the true end-to-end of signed activate → verify-signed depends on a resolvable artifact whose exact on-disk shape is defined by `RecipeArtifactResolver`. Rather than duplicate that fixture brittlely in a unit test, this behavior is covered authoritatively by `run-identity-gate.sh` (I1) in Task 13. Keep this unit test minimal (arg-parsing) and confirm the fixture shape against the existing `ActivateGateTest` before expanding. **Primary parse assertions below.**

**Delete the `signed_activate_then_verify_signed_is_intact` placeholder entirely** (an `assertTrue(true)` test is a smell — do NOT commit it; the signed happy-path is covered authoritatively by `run-identity-gate.sh` I1 in Task 13). Keep ONLY the arg-parse assertion below, added once you've read `ActivateGateTest`:
```java
    @Test void only_one_key_flag_is_usage_error(@TempDir Path root) throws Exception {
        int code = ActivateGate.run(new String[]{"activate", root.toString(), "Line1","recipe","mix","1.0.0",
                "--by","alice","--approved-by","bob","--by-key","/no/such.key"});
        assertEquals(2, code, "one key flag without the other => usage error");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl gates -am test -Dtest=ActivateGateSignedTest`
Expected: FAIL — `--by-key` not parsed (currently falls into `pos`, changing arity), so the assertion on exit 2 fails or behaves wrong.

- [ ] **Step 3: Modify `ActivateGate.activate`**

Add key-flag parsing and signer construction:
```java
private static int activate(String[] a) throws Exception {
    String by = null, approvedBy = null, byKey = null, approvedByKey = null; boolean rollback = false;
    List<String> pos = new ArrayList<>();
    for (int i = 0; i < a.length; i++) {
        switch (a[i]) {
            case "--by" -> by = (++i < a.length) ? a[i] : null;
            case "--approved-by" -> approvedBy = (++i < a.length) ? a[i] : null;
            case "--by-key" -> byKey = (++i < a.length) ? a[i] : null;
            case "--approved-by-key" -> approvedByKey = (++i < a.length) ? a[i] : null;
            case "--rollback" -> rollback = true;
            default -> pos.add(a[i]);
        }
    }
    if (pos.size() < 5) { System.err.println("Usage: activate <reg> <target> <kind> <ref> <version> --by <p> --approved-by <p> [--by-key <f> --approved-by-key <f>] [--rollback]"); return 2; }
    if ((byKey == null) != (approvedByKey == null)) {
        System.err.println("Usage: --by-key and --approved-by-key must be supplied together"); return 2;
    }
    Path reg = Path.of(pos.get(0));
    ActivationService svc = new ActivationService(new RecipeArtifactResolver(reg), new ActivationLedger(reg), Clock.systemUTC());
    ActivationRequest req = new ActivationRequest(pos.get(1), pos.get(2), pos.get(3), pos.get(4), by, approvedBy, rollback);
    ActivationVerdict v;
    if (byKey != null) {
        dev.krillin.bifrost.core.activation.LedgerSigner signer =
                dev.krillin.bifrost.core.identity.KeyFileLedgerSigner.create(
                        by, Path.of(byKey), approvedBy, Path.of(approvedByKey),
                        dev.krillin.bifrost.core.identity.AuthorizedKeys.load(reg));
        v = svc.activate(req, signer);
    } else {
        v = svc.activate(req);
    }
    if (v.ok()) {
        ActivationEvent e = v.event();
        System.out.println("[GATE] activated target=" + e.target() + " kind=" + e.kind() + " ref=" + e.ref()
            + " version=" + e.version() + " action=" + e.action() + " by=" + e.activatedBy()
            + " approvedBy=" + e.approvedBy() + " sha256=" + e.contentSha256()
            + (byKey != null ? " signed=true" : ""));
        return 0;
    }
    System.out.println("[GATE] REFUSED:");
    for (Violation viol : v.violations()) System.out.println("  - [" + viol.rule() + "] " + viol.detail());
    return 1;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl gates -am test -Dtest=ActivateGateSignedTest,ActivateGateTest`
Expected: PASS (new parse test + all existing unsigned `ActivateGateTest`).

- [ ] **Step 5: Commit**

```bash
git add gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java \
        gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateSignedTest.java
git commit -m "feat(gates): activate --by-key/--approved-by-key — dual-signed activation (both-or-neither)"
```

---

### Task 11: Gate CLI — `identity verify-signed`

**Files:**
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/IdentityGate.java`
- Test: `gates/src/test/java/dev/krillin/bifrost/gates/IdentityGateVerifyTest.java`

> **Placement decision:** put `verify-signed` under `identity` (not `activation`) to keep `activation verify-chain` (T4) untouched and group identity verification with keygen. Signature: `identity verify-signed <reg> <target>` → 0 intact / 1 broken / 2 usage|no-such-target.

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.gates;

import dev.krillin.bifrost.core.activation.*;
import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPair;
import static org.junit.jupiter.api.Assertions.*;

class IdentityGateVerifyTest {

    private void seedSigned(Path root, Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\"alice\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(alice.getPublic())+"\"}\n"
          + "{\"principal\":\"bob\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(bob.getPublic())+"\"}\n");
        Path a = keys.resolve("a"), b = keys.resolve("b");
        Files.writeString(a, Ed25519Keys.privateKeyB64(alice.getPrivate()));
        Files.writeString(b, Ed25519Keys.privateKeyB64(bob.getPrivate()));
        LedgerSigner s = KeyFileLedgerSigner.create("alice", a, "bob", b, AuthorizedKeys.load(root));
        ActivationLedger ledger = new ActivationLedger(root);
        ledger.append(new ActivationEvent("Line1","recipe","mix","1.0.0","sha","alice","bob",1000L,null,"ACTIVATE"), s);
    }

    @Test void verify_signed_intact_exits_0(@TempDir Path root, @TempDir Path keys) throws Exception {
        seedSigned(root, keys);
        assertEquals(0, IdentityGate.run(new String[]{"verify-signed", root.toString(), "Line1"}));
    }

    @Test void verify_signed_broken_exits_1(@TempDir Path root, @TempDir Path keys) throws Exception {
        seedSigned(root, keys);
        Path lf = root.resolve("activation").resolve("Line1.jsonl");
        java.util.List<String> lines = Files.readAllLines(lf);
        lines.set(0, lines.get(0).replaceFirst("\"activatorSig\":\"[^\"]", "\"activatorSig\":\"A"));
        Files.write(lf, lines);
        assertEquals(1, IdentityGate.run(new String[]{"verify-signed", root.toString(), "Line1"}));
    }

    @Test void verify_signed_no_such_target_exits_2(@TempDir Path root) {
        assertEquals(2, IdentityGate.run(new String[]{"verify-signed", root.toString(), "Nope"}));
    }

    @Test void verify_signed_usage_exits_2(@TempDir Path root) {
        assertEquals(2, IdentityGate.run(new String[]{"verify-signed", root.toString()}));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl gates -am test -Dtest=IdentityGateVerifyTest`
Expected: FAIL — `verify-signed` not handled.

- [ ] **Step 3: Add the `verify-signed` leg to `IdentityGate`**

In `run`'s switch add `case "verify-signed": return verifySigned(...)`. Implement:
```java
private static int verifySigned(String[] a) throws Exception {
    if (a.length < 2) { System.err.println("Usage: identity verify-signed <reg> <target>"); return 2; }
    Path reg = Path.of(a[0]);
    String target = a[1];
    ActivationLedger ledger = new ActivationLedger(reg);
    if (ledger.history(target).isEmpty()) {
        System.err.println("[GATE] verify-signed: no such target ledger: " + target); return 2;
    }
    SignedVerdict v = SignedLedgerVerifier.forRegistry(reg).verify(target);
    if (v.intact()) {
        System.out.println("[GATE] verify-signed target=" + target + " => INTACT (signed)");
        return 0;
    }
    System.out.println("[GATE] verify-signed target=" + target + " => BROKEN at index="
            + v.brokenIndex() + " rule=" + v.rule());
    return 1;
}
```
(add imports for `ActivationLedger`, `SignedLedgerVerifier`, `SignedVerdict`; update the `identity` usage line.)

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl gates -am test -Dtest=IdentityGateVerifyTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add gates/src/main/java/dev/krillin/bifrost/gates/IdentityGate.java \
        gates/src/test/java/dev/krillin/bifrost/gates/IdentityGateVerifyTest.java
git commit -m "feat(gates): identity verify-signed — 0 intact / 1 broken / 2 usage|no-such-target"
```

---

## Chunk 4: Heimdall enforcement + end-to-end gate + no-regression

### Task 12: Heimdall `REQUIRE_SIGNED_ACTIVATION` switch

**Files:**
- Modify: `heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java`
- Test: `heimdall/src/test/java/dev/krillin/bifrost/heimdall/RequireSignedActivationTest.java`

> **Design:** add `boolean requireSignedActivation` to `Config` (parsed from env `REQUIRE_SIGNED_ACTIVATION`, default **false**). In the bind block, when the flag is **on**, replace the `ledger.verifyChain(...)` gate with `SignedLedgerVerifier.forRegistry(ledgerDir).verify(target)`; on a break throw `activation.edge.signed-ledger-broken: ... rule <r>`. When **off**, keep the exact T4 `verifyChain` path (so existing gates are unchanged). `SignedLedgerVerifier` internally runs `LedgerChain.verify` first, so structural breaks are still caught when the flag is on.

- [ ] **Step 1: Read the current Config + env plumbing**

Run:
```bash
grep -n "record Config" heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java
sed -n '30,70p' heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java
```
Expected: see the `Config` record fields and the `env(...)`/`fromEnv` builder — you'll add one boolean field + one env read.

- [ ] **Step 2: Write the failing test** (unit-level: assert the verifier is chosen and a broken signed ledger throws with the T5 code; keep it a focused test on a small extracted method)

> Prefer extracting the "verify-before-bind" decision into a small package-private static method you can test without a live broker, e.g.:
> `static void assertLedgerTrustworthy(Path ledgerDir, String target, boolean requireSigned) throws IOException` — throws `IllegalStateException` with the right code on a break, returns normally when intact. Then the bind block calls it.

```java
package dev.krillin.bifrost.heimdall;

import dev.krillin.bifrost.core.activation.*;
import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPair;
import static org.junit.jupiter.api.Assertions.*;

class RequireSignedActivationTest {

    private void seedSigned(Path root, Path keys) throws Exception {
        KeyPair alice = Ed25519Keys.generate(), bob = Ed25519Keys.generate();
        Path akf = root.resolve("identity").resolve("authorized-keys.jsonl");
        Files.createDirectories(akf.getParent());
        Files.writeString(akf,
            "{\"principal\":\"alice\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(alice.getPublic())+"\"}\n"
          + "{\"principal\":\"bob\",\"publicKey\":\""+Ed25519Keys.publicKeyB64(bob.getPublic())+"\"}\n");
        Path a=keys.resolve("a"), b=keys.resolve("b");
        Files.writeString(a, Ed25519Keys.privateKeyB64(alice.getPrivate()));
        Files.writeString(b, Ed25519Keys.privateKeyB64(bob.getPrivate()));
        LedgerSigner s = KeyFileLedgerSigner.create("alice", a, "bob", b, AuthorizedKeys.load(root));
        new ActivationLedger(root).append(
            new ActivationEvent("Line1","recipe","mix","1.0.0","sha","alice","bob",1000L,null,"ACTIVATE"), s);
    }

    @Test void require_signed_on_passes_for_intact_signed_ledger(@TempDir Path root, @TempDir Path keys) throws Exception {
        seedSigned(root, keys);
        assertDoesNotThrow(() -> NcmdOpcUaBridgeMain.assertLedgerTrustworthy(root, "Line1", true));
    }

    @Test void require_signed_on_throws_signed_code_on_broken(@TempDir Path root, @TempDir Path keys) throws Exception {
        seedSigned(root, keys);
        Path lf = root.resolve("activation").resolve("Line1.jsonl");
        var lines = Files.readAllLines(lf);
        lines.set(0, lines.get(0).replaceFirst("\"activatorSig\":\"[^\"]", "\"activatorSig\":\"A"));
        Files.write(lf, lines);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NcmdOpcUaBridgeMain.assertLedgerTrustworthy(root, "Line1", true));
        assertTrue(ex.getMessage().contains("activation.edge.signed-ledger-broken"), ex.getMessage());
    }

    @Test void require_signed_off_uses_structural_only(@TempDir Path root) throws Exception {
        // an UNSIGNED T4 ledger: intact structurally, no sigs -> must PASS when flag is off
        new ActivationLedger(root).append(
            new ActivationEvent("Line1","recipe","mix","1.0.0","sha","alice","bob",1000L,null,"ACTIVATE"));
        assertDoesNotThrow(() -> NcmdOpcUaBridgeMain.assertLedgerTrustworthy(root, "Line1", false));
    }
}
```

- [ ] **Step 3: Implement**

Add the extracted method to `NcmdOpcUaBridgeMain` and call it from the bind block:
```java
/** Fail-closed ledger trust check before binding. requireSigned=false → T4 structural chain (verifyChain);
 *  true → full SignedLedgerVerifier (structural + dual-sig + head). Throws IllegalStateException on a break. */
static void assertLedgerTrustworthy(java.nio.file.Path ledgerDir, String target, boolean requireSigned)
        throws java.io.IOException {
    if (requireSigned) {
        var v = dev.krillin.bifrost.core.identity.SignedLedgerVerifier.forRegistry(ledgerDir).verify(target);
        if (!v.intact())
            throw new IllegalStateException("activation.edge.signed-ledger-broken: target " + target
                    + " index " + v.brokenIndex() + " rule " + v.rule());
    } else {
        var chain = new dev.krillin.bifrost.core.activation.ActivationLedger(ledgerDir).verifyChain(target);
        if (!chain.intact())
            throw new IllegalStateException("activation.edge.ledger-chain-broken: target " + target
                    + " index " + chain.brokenIndex() + " rule " + chain.rule());
    }
}
```
Replace the inline `ChainVerdict chain = ledger.verifyChain(...)` + throw with:
```java
assertLedgerTrustworthy(ledgerDir, config.activationTarget(), config.requireSignedActivation());
```
Add to `Config`: field `boolean requireSignedActivation`, and in the env builder:
```java
// NB: Boolean.parseBoolean("on") is FALSE — accept on/1/true so the gate's =on and =true both work.
String rsa = env(getenv, "REQUIRE_SIGNED_ACTIVATION", "false");
boolean requireSigned = "true".equalsIgnoreCase(rsa) || "on".equalsIgnoreCase(rsa) || "1".equals(rsa);
```
threading it into the `new Config(...)` call (append the arg; update the record header). Update the `Config` javadoc/comment.

> **Ripple note (COMPILE BREAKER if missed):** appending a component to the `Config` record breaks its canonical constructor at **three existing test call sites** — fix all three (append `false` as the new last arg unless the test needs it on):
> - `heimdall/src/test/java/dev/krillin/bifrost/heimdall/LoadConformanceActivationTest.java:37` (the `cfg()` helper)
> - `heimdall/src/test/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMainDefaultsTest.java:67`
> - `heimdall/src/test/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMainDefaultsTest.java:104`
> These call `new NcmdOpcUaBridgeMain.Config(...)` directly; `resolve()`-based tests use accessors and are unaffected. Without this, `mvn -q -pl heimdall -am test` fails to COMPILE the test sources at Step 4.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl heimdall -am test -Dtest=RequireSignedActivationTest`
Expected: PASS (3 tests). Then `mvn -q -pl heimdall -am test` — all existing heimdall tests still green (they don't set the flag → default off → T4 path).

- [ ] **Step 5: Commit**

```bash
git add heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java \
        heimdall/src/test/java/dev/krillin/bifrost/heimdall/RequireSignedActivationTest.java
git commit -m "feat(heimdall): REQUIRE_SIGNED_ACTIVATION (default off) — SignedLedgerVerifier before bind; T4 path preserved"
```

---

### Task 13: End-to-end gate `run-identity-gate.sh`

**Files:**
- Create: `scripts/run-identity-gate.sh`

> **Model on `run-lineage-gate.sh`.** I1–I6 are pure CLI (no broker). I7 reuses the broker harness from `run-lineage-gate.sh`/`run-activation-gate.sh` (Docker MQTT + sim + Heimdall) but is OPTIONAL if Docker isn't available — gate on `command -v docker` and skip I7 with a printed `[IDENTITY] I7 skipped (no docker)` rather than failing, since I1–I6 already prove the crypto core. (Confirm with the executor whether to require Docker; default: skip-if-absent for I7.)

- [ ] **Step 1: Write the script**

Key structure (fill in against the real `run-lineage-gate.sh` harness — reuse `fail()`, `WORK`, registry seeding, `REG_WIN` windows-path conversion, and the python byte-tamper helper verbatim):
```bash
#!/usr/bin/env bash
# IDENTITY GATE (T5): proves each activation is dual-signed (activator+approver Ed25519) and the ledger
# tail is anchored by a signed head, so full-re-chain and tail-truncation are detected — and Heimdall
# fail-closes on a broken SIGNED ledger before binding (REQUIRE_SIGNED_ACTIVATION=on).
#
# I1 signed activate -> verify-signed INTACT (0)
# I2 tamper a signature byte -> verify-signed BROKEN identity.sig.invalid (1)
# I3 sign with a key whose principal is NOT authorized -> BROKEN identity.key.unregistered (1)
# I4a same principal --by alice --approved-by alice -> REFUSED activation.approval.self (1)
# I4b approver key file is actually alice's -> REFUSED identity.key.principal-mismatch (1)
# I4c two principals sharing one pubkey -> REFUSED identity.four-eyes.same-key (1)
# I5 delete last ledger line, keep head -> verify-signed BROKEN identity.head.tail-mismatch|seq-mismatch (1)
# I6 edit a past event + recompute all entryHash (structural re-chain), don't re-sign -> BROKEN identity.sig.invalid (1)
# I7 (broker, optional) Heimdall vs broken signed ledger, REQUIRE_SIGNED_ACTIVATION=on -> fail-closed
set -euo pipefail
cd "$(dirname "$0")/.."
# ... reuse run-lineage-gate.sh scaffolding: WORK, fail(), python check, JAR build ...

gates() { java -jar gates/target/bifrost-gates.jar "$@"; }

# seed: keygen alice/bob into $WORK/keys, build authorized-keys.jsonl, stage a resolvable recipe artifact
gates identity keygen alice --out "$KEYS_WIN" >> "$AKF"    # keygen prints the authorized-keys line to stdout
gates identity keygen bob   --out "$KEYS_WIN" >> "$AKF"
mkdir -p "$REG/identity" && mv "$AKF" "$REG/identity/authorized-keys.jsonl"

# I1
gates activate "$REG_WIN" Line1 recipe mix-recipe 1.0.0 --by alice --approved-by bob \
      --by-key "$KEYS_WIN/alice.key" --approved-by-key "$KEYS_WIN/bob.key"
gates identity verify-signed "$REG_WIN" Line1 | tee "$WORK/vs.txt"
grep -q INTACT "$WORK/vs.txt" || fail "I1 expected INTACT"

# I2..I6 as above (python byte-tamper + rc capture, restore between cases or use fresh registries)
# ...
echo "[IDENTITY] GATE PASS (I1-I6${I7_RAN:+ +I7})"   # honest label: I7 only when docker ran
```

> **Executor guidance:** build the exact I1–I6 assertions following `run-lineage-gate.sh`'s LN1–LN3 style (capture `rc` with `set +e`/`set -e` bracketing, `grep` the rule slug, `fail` on mismatch). Use a **fresh registry per destructive case** (I2/I5/I6) or restore the ledger file, mirroring LN2/LN3's restore discipline. For I3, keygen a third principal `mallory` but do NOT add her to `authorized-keys.jsonl`, then activate with `--by mallory` — expect the activate to REFUSE at `identity.key.principal-mismatch` (mallory unregistered) OR, if you bypass activate by hand-signing, `verify-signed` → `identity.key.unregistered`. Prefer the activate-time refusal path (simpler, deterministic) — **note this makes I3 assert `identity.key.principal-mismatch` (activate-time), a documented drift from spec §8-I3's `identity.key.unregistered` (verify-time), which overlaps I4b. The verifier's `identity.key.unregistered` code is then covered only by the JUnit `unregistered_signer_is_rejected` (Task 8). Acceptable; call it out in the gate comments.**

> **Executor fill-in checklist (these are NOT derivable from "reuse run-lineage-gate.sh" alone):**
> 1. **New sig-tamper helper.** `run-lineage-gate.sh` only ships `tamper_bob_to_mallory` / delete-line. I2/I6 need a base64-sig-char flip on the ledger line — a python helper that reads the line, finds `"activatorSig":"X…"`, and replaces `X` with a *different* fixed char (deterministic, per the Task 8 caveat — never flip to the same char).
> 2. **Registry/fixture staging for I1.** `activate` must resolve a real artifact — reuse the `scripts/fixtures/activation/` copy block from `run-lineage-gate.sh` (stage `spec/mix-recipe/1.0.0.json` etc. under `$REG` via `MasterSpecStore` layout) so `--by-key` activation returns exit 0.
> 3. **I5 needs TWO signed entries.** Activate twice (1.0.0 then 1.1.0, both `--by-key/--approved-by-key`) BEFORE deleting the last ledger line — otherwise there is no head/tail divergence to detect (head.seq=1, tail after delete=entry#0 → tail-mismatch).
> 4. **Define the shell vars the skeleton uses.** `run-lineage-gate.sh` defines `WORK`/`REG`/`REG_WIN` but NOT `$KEYS_WIN`/`$AKF`/`$I7_RAN` — add: `KEYS="$WORK/keys"; KEYS_WIN="$(winpath "$KEYS")"; AKF="$WORK/authorized-keys.jsonl"` (using the same path-conversion helper), and set `I7_RAN=1` only inside the I7 broker branch.

- [ ] **Step 2: Make executable + run**

Run:
```bash
chmod +x scripts/run-identity-gate.sh
bash scripts/run-identity-gate.sh
```
Expected: `[IDENTITY] GATE PASS (I1-I7)` and exit 0. (I7 may print `skipped (no docker)`.)

> **CONTROLLER VERIFICATION (per working-style):** the human controller runs this gate themselves and confirms `GATE PASS` — do not trust a subagent's report of a pass.

- [ ] **Step 3: Commit**

```bash
git add scripts/run-identity-gate.sh
git commit -m "test(gate): run-identity-gate — I1 intact / I2 sig / I3 unregistered / I4abc four-eyes / I5 tail-trunc / I6 re-chain / I7 edge"
```

---

### Task 14: Full no-regression + build

**Files:** none (verification only).

- [ ] **Step 1: Full build + all unit tests**

Run: `mvn -q install`
Expected: BUILD SUCCESS — core/heimdall/gates/sim all green, including every T3/T4 test unchanged.

- [ ] **Step 2: Re-run the prior gates (controller-run) to prove T5 didn't break T3/T4**

Run (each, expect its PASS line + exit 0):
```bash
bash scripts/run-activation-gate.sh        # A1-A5  (unsigned ledger, REQUIRE_SIGNED off by default)
bash scripts/run-lineage-gate.sh           # LN1-LN4
bash scripts/run-yggdrasil-full-loop-gate.sh
bash scripts/run-template-conformance-gate.sh
```
Expected: all four print their `GATE PASS` and exit 0. Because `REQUIRE_SIGNED_ACTIVATION` defaults **off**, the two gates that start Heimdall with `ACTIVATION_TARGET=Line1` on an unsigned ledger take the T4 structural path unchanged.

> **CONTROLLER VERIFICATION:** the human controller runs these four gates + `mvn install` themselves and confirms green.

- [ ] **Step 3: Final commit (if any doc/CLAUDE.md touch-ups) — otherwise nothing to commit**

If `README.md` documents the gate suite, add a `run-identity-gate.sh` line:
```bash
git add README.md
git commit -m "docs: list run-identity-gate (T5) in the gate suite"
```

---

## Done criteria

- [ ] `mvn install` green; all T3/T4 tests unchanged and passing.
- [ ] `run-identity-gate.sh` → `GATE PASS (I1-I7)` (controller-verified).
- [ ] `run-activation-gate.sh`, `run-lineage-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-template-conformance-gate.sh` all still PASS (controller-verified).
- [ ] `verify-chain` (T4) semantics unchanged; `verify-signed` (T5) added.
- [ ] Heimdall `REQUIRE_SIGNED_ACTIVATION` defaults off; on → `SignedLedgerVerifier` fail-closed.
- [ ] Spec §9 honest limitations hold in code (no authorization check; ledger+head co-rollback not claimed closed).

## Handoff to the executor

Follow subagent-driven-development (fresh subagent per task, two-stage review) if available, else executing-plans. **Before starting Task 5/7/10, read the real `ArtifactResolver`, `RecipeArtifactResolver`, and `ActivateGateTest` fixtures** — the plan's minimal resolver/fixtures are illustrative and must be reconciled with the actual interfaces. Controller runs all gates + `mvn install` personally (working-style: never trust a subagent PASS).
