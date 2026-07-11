# T6 Activation Authorization Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deny-by-default authorization plane over the activation act — a policy that says which authenticated principal may **activate** and which may **approve** which `(target, kind, ref)` — enforced at the pre-deploy gate and re-verified at the Heimdall edge, completing the "Bifrost = IAM" story (T5 authN → T6 authZ).

**Architecture:** Additive to `core.activation`. A small, pure authorization unit mirroring the shipped `core.acl.CommandAuthorizer` (first-match, deny-by-default, `null`/`*` wildcard) — `ActivationPolicy`/`ActivationRule`/`ActivationAuthorizer` reading `registry/identity/activation-policy.json`. `ActivationService.activate` gains the canonical 3-arg signed form `activate(r, signer, policy)` (the old 2-arg signed overload is removed — there is deliberately no signed path that skips authZ); the unsigned `activate(r)` keeps no authZ (authZ presupposes authN). Heimdall re-checks the bound event's activator/approver against the current policy in `REQUIRE_SIGNED_ACTIVATION` mode. maker-checker: activator needs `ACTIVATE`, approver needs `APPROVE` — pairing 1:1 with T5's dual signature.

**Tech Stack:** Java 17, Maven multi-module (core/gates/heimdall/sim), JUnit 5, Jackson (`JsonMapperFactory`), bash+python gate scripts. Builds on merged T5 (`core.identity`, signed `ActivationLedger`/`ActivationService`).

**Spec:** `sparkplug-governance-lab/docs/superpowers/specs/2026-07-11-activation-authorization-design.md`

---

## Conventions (read once)

- **Repo:** all code in `bifrost`. Branch `feat/t6-activation-authz` off `main` (Task 0). Plan/spec in the `sparkplug-governance-lab` sibling repo.
- **Build one module:** `mvn -q -pl core -am test`, `-pl gates -am test`, `-pl heimdall -am test`. Full: `mvn -q install`. One test: add `-Dtest=Name` (+ `-Dsurefire.failIfNoSpecifiedTests=false` when the `-am` reactor pulls in a module without that test).
- **Package:** `dev.krillin.bifrost.core.activation` (existing — T6 authZ lives with the activation act it governs).
- **Mirror precisely:** `core.acl.CommandAuthorizer` (first-match, deny-by-default), `core.acl.Decision` (allow/deny factories), `core.acl.Rule.fieldMatches` (`rule == null || "*".equals(rule) || rule.equals(actual)`), and `core.identity.AuthorizedKeys.load` (absent-file → fail-closed empty, coded `IllegalStateException` on malformed).
- **Commit trailer:** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. One commit per task.
- **Reason codes:** gate = `activation.authz.denied`; policy load = `activation.authz.policy.default-not-deny` / `activation.authz.policy.read-error`; edge = `activation.edge.authz-denied`.
- **Policy file:** `registry/identity/activation-policy.json` — co-located with T5's `authorized-keys.jsonl`.

---

## File Structure

**New (`core/src/main/java/dev/krillin/bifrost/core/activation/`):**
- `ActivationAction.java` — enum `ACTIVATE`/`APPROVE`, lowercase JSON.
- `ActivationRule.java` — record `(id, principal, action, target, kind, ref)` + `matches(...)`.
- `ActivationPolicy.java` — record `(version, rules, default)` + `denyAll()` factory.
- `AuthzDecision.java` — record `(allowed, ruleId, reason)` + `allow`/`deny`.
- `ActivationPolicyStore.java` — load `registry/identity/activation-policy.json`; absent → deny-all; malformed → coded fail-closed.
- `ActivationAuthorizer.java` — pure first-match deny-by-default evaluator.

**Modified:**
- `core/.../activation/ActivationService.java` — 3-arg `activate(r, signer, policy)`; remove 2-arg; authZ block.
- `gates/.../gates/ActivateGate.java` — load policy, pass to 3-arg (signed path).
- `gates/.../gates/IdentityGate.java` — `authorize` subcommand.
- `heimdall/.../heimdall/NcmdOpcUaBridgeMain.java` — edge authZ re-check + audit line.
- `scripts/run-identity-gate.sh` — seed `activation-policy.json` (REQUIRED — else T5 gate breaks).

**Migrated tests:** `ActivationServiceSignedTest` (3 signed calls → 3-arg + policy).
**New tests:** `ActivationAuthorizerTest`, `ActivationPolicyStoreTest`, `ActivationPolicyTest`, `IdentityGateAuthorizeTest`, extended `RequireSignedActivationTest`; `scripts/run-activation-authz-gate.sh`.

---

## Chunk 1: Policy model + authorizer + store (pure core)

Self-contained: the value types, the pure evaluator, and the loader. No wiring into the service/gate/edge yet. Fully unit-tested first.

### Task 0: Branch

**Files:** none (git only).

- [ ] **Step 1:** Create the branch off `main`.

Run:
```bash
cd "C:/Users/Eisen/Desktop/Labs/[iiot]/bifrost"
git checkout main && git checkout -b feat/t6-activation-authz
```
Expected: `Switched to a new branch 'feat/t6-activation-authz'`.

- [ ] **Step 2:** Confirm the baseline builds green.

Run: `mvn -q install`
Expected: BUILD SUCCESS (core 174 · heimdall 35 · gates 58 · sim 7 — the merged-T5 baseline).

---

### Task 1: Value types — `ActivationAction`, `ActivationRule`, `ActivationPolicy`, `AuthzDecision`

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationAction.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationRule.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationPolicy.java`
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/AuthzDecision.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationPolicyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.activation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivationPolicyTest {
    private final ObjectMapper mapper = JsonMapperFactory.create();

    @Test void rule_wildcard_and_exact_matching() {
        // exact rule
        ActivationRule exact = new ActivationRule("r1", "alice", ActivationAction.ACTIVATE, "Line1", "recipe", "mix");
        assertTrue(exact.matches("alice", ActivationAction.ACTIVATE, "Line1", "recipe", "mix"));
        assertFalse(exact.matches("bob",   ActivationAction.ACTIVATE, "Line1", "recipe", "mix"), "principal differs");
        assertFalse(exact.matches("alice", ActivationAction.APPROVE,  "Line1", "recipe", "mix"), "action differs");
        assertFalse(exact.matches("alice", ActivationAction.ACTIVATE, "Line2", "recipe", "mix"), "target differs");
        // wildcard rule (null and "*" both match any)
        ActivationRule wild = new ActivationRule("r2", "alice", ActivationAction.ACTIVATE, "*", null, "*");
        assertTrue(wild.matches("alice", ActivationAction.ACTIVATE, "AnyLine", "anyKind", "anyRef"));
        assertFalse(wild.matches("carol", ActivationAction.ACTIVATE, "AnyLine", "anyKind", "anyRef"), "principal not wild");
    }

    @Test void action_serializes_lowercase() throws Exception {
        assertEquals("\"activate\"", mapper.writeValueAsString(ActivationAction.ACTIVATE));
        assertEquals("\"approve\"",  mapper.writeValueAsString(ActivationAction.APPROVE));
        assertEquals(ActivationAction.APPROVE, mapper.readValue("\"approve\"", ActivationAction.class));
    }

    @Test void policy_round_trips_with_default_deny_key() throws Exception {
        String json = "{\"version\":\"1\",\"default\":\"deny\",\"rules\":["
                + "{\"id\":\"r1\",\"principal\":\"alice\",\"action\":\"activate\",\"target\":\"Line1\",\"kind\":\"recipe\",\"ref\":\"mix\"}]}";
        ActivationPolicy p = mapper.readValue(json, ActivationPolicy.class);
        assertEquals("deny", p.defaultEffect());
        assertEquals(1, p.rules().size());
        assertEquals(ActivationAction.ACTIVATE, p.rules().get(0).action());
    }

    @Test void deny_all_factory_has_no_rules() {
        assertTrue(ActivationPolicy.denyAll().rules().isEmpty());
        assertEquals("deny", ActivationPolicy.denyAll().defaultEffect());
    }

    @Test void decision_factories() {
        assertTrue(AuthzDecision.allow("r1").allowed());
        assertEquals("r1", AuthzDecision.allow("r1").ruleId());
        assertFalse(AuthzDecision.deny("nope").allowed());
        assertNull(AuthzDecision.deny("nope").ruleId());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=ActivationPolicyTest`
Expected: FAIL — types missing (compile error).

- [ ] **Step 3: Write the implementations**

`ActivationAction.java`:
```java
package dev.krillin.bifrost.core.activation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The two authorizable activation roles, serialized lowercase in the policy JSON. */
public enum ActivationAction {
    ACTIVATE, APPROVE;

    @JsonValue public String json() { return name().toLowerCase(); }

    @JsonCreator public static ActivationAction from(String s) {
        return ActivationAction.valueOf(s.trim().toUpperCase());
    }
}
```

`ActivationRule.java`:
```java
package dev.krillin.bifrost.core.activation;

/** One deny-by-default authorization rule (implicit allow candidate): principal P may perform ACTION on
 *  resource (target,kind,ref). A null or "*" field matches any value; otherwise an exact match is required
 *  (identical to core.acl.Rule.fieldMatches). */
public record ActivationRule(String id, String principal, ActivationAction action,
                             String target, String kind, String ref) {

    public boolean matches(String principal, ActivationAction action, String target, String kind, String ref) {
        return this.action == action
                && field(this.principal, principal)
                && field(this.target, target)
                && field(this.kind, kind)
                && field(this.ref, ref);
    }

    private static boolean field(String rule, String actual) {
        return rule == null || "*".equals(rule) || rule.equals(actual);
    }
}
```

`ActivationPolicy.java`:
```java
package dev.krillin.bifrost.core.activation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Deny-by-default policy-as-code for the activation act (mirrors core.acl.CommandPolicy). {@code default}
 *  must be "deny"; an empty/absent rule list denies everything. */
public record ActivationPolicy(String version, List<ActivationRule> rules,
                               @JsonProperty("default") String defaultEffect) {

    /** The fail-closed policy used when no policy file is present: no rules, deny everything. */
    public static ActivationPolicy denyAll() {
        return new ActivationPolicy("(none)", List.of(), "deny");
    }
}
```

`AuthzDecision.java`:
```java
package dev.krillin.bifrost.core.activation;

/** Authorization result. {@code allowed} => {@code ruleId} identifies the matching rule; on deny, ruleId is
 *  null and {@code reason} explains. Mirrors core.acl.Decision. */
public record AuthzDecision(boolean allowed, String ruleId, String reason) {
    public static AuthzDecision allow(String ruleId) { return new AuthzDecision(true, ruleId, "allow"); }
    public static AuthzDecision deny(String reason)  { return new AuthzDecision(false, null, reason); }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=ActivationPolicyTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationAction.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/ActivationRule.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/ActivationPolicy.java \
        core/src/main/java/dev/krillin/bifrost/core/activation/AuthzDecision.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationPolicyTest.java
git commit -m "feat(core): activation authz value types — Action/Rule/Policy/Decision (deny-by-default, lowercase-JSON action)"
```

---

### Task 2: `ActivationAuthorizer` — pure first-match deny-by-default

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationAuthorizer.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationAuthorizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.activation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.krillin.bifrost.core.activation.ActivationAction.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivationAuthorizerTest {
    private final ActivationAuthorizer authz = new ActivationAuthorizer();

    private static ActivationPolicy policy(ActivationRule... rules) {
        return new ActivationPolicy("1", List.of(rules), "deny");
    }
    private static ActivationRule rule(String id, String p, ActivationAction a, String t, String k, String r) {
        return new ActivationRule(id, p, a, t, k, r);
    }

    @Test void first_matching_rule_allows_with_ruleId() {
        ActivationPolicy p = policy(rule("r-act", "alice", ACTIVATE, "Line1", "recipe", "mix"));
        AuthzDecision d = authz.authorize(p, "alice", ACTIVATE, "Line1", "recipe", "mix");
        assertTrue(d.allowed());
        assertEquals("r-act", d.ruleId());
    }

    @Test void no_matching_rule_denies_by_default() {
        ActivationPolicy p = policy(rule("r-act", "alice", ACTIVATE, "Line1", "recipe", "mix"));
        // wrong action (alice has ACTIVATE not APPROVE)
        assertFalse(authz.authorize(p, "alice", APPROVE, "Line1", "recipe", "mix").allowed());
        // wrong principal
        assertFalse(authz.authorize(p, "carol", ACTIVATE, "Line1", "recipe", "mix").allowed());
        // wrong resource
        assertFalse(authz.authorize(p, "alice", ACTIVATE, "Line2", "recipe", "mix").allowed());
    }

    @Test void empty_policy_denies_everything() {
        AuthzDecision d = authz.authorize(ActivationPolicy.denyAll(), "alice", ACTIVATE, "Line1", "recipe", "mix");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("deny-by-default"));
    }

    @Test void activate_and_approve_are_isolated() {
        ActivationPolicy p = policy(
                rule("r-act", "alice", ACTIVATE, "Line1", "recipe", "mix"),
                rule("r-app", "bob",   APPROVE,  "Line1", "recipe", "mix"));
        assertTrue(authz.authorize(p, "alice", ACTIVATE, "Line1", "recipe", "mix").allowed());
        assertTrue(authz.authorize(p, "bob",   APPROVE,  "Line1", "recipe", "mix").allowed());
        assertFalse(authz.authorize(p, "alice", APPROVE,  "Line1", "recipe", "mix").allowed(), "alice cannot approve");
        assertFalse(authz.authorize(p, "bob",   ACTIVATE, "Line1", "recipe", "mix").allowed(), "bob cannot activate");
    }

    @Test void wildcard_rule_matches_any_resource() {
        ActivationPolicy p = policy(rule("r-star", "alice", ACTIVATE, "*", "*", "*"));
        assertTrue(authz.authorize(p, "alice", ACTIVATE, "AnyLine", "recipe", "anyRef").allowed());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=ActivationAuthorizerTest`
Expected: FAIL — `ActivationAuthorizer` missing.

- [ ] **Step 3: Write the implementation**

```java
package dev.krillin.bifrost.core.activation;

/** Design-time & edge decision engine for the activation act. First-match, deny-by-default (fail-closed),
 *  pure (no I/O) — the exact discipline of core.acl.CommandAuthorizer, over the activation resource shape. */
public final class ActivationAuthorizer {

    public AuthzDecision authorize(ActivationPolicy policy, String principal, ActivationAction action,
                                   String target, String kind, String ref) {
        for (ActivationRule r : policy.rules()) {
            if (r.matches(principal, action, target, kind, ref)) return AuthzDecision.allow(r.id());
        }
        return AuthzDecision.deny("no-matching-rule (deny-by-default)");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=ActivationAuthorizerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationAuthorizer.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationAuthorizerTest.java
git commit -m "feat(core): ActivationAuthorizer — first-match, deny-by-default (mirrors acl.CommandAuthorizer)"
```

---

### Task 3: `ActivationPolicyStore` — load, absent→deny-all, malformed→coded

**Files:**
- Create: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationPolicyStore.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationPolicyStoreTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.core.activation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivationPolicyStoreTest {

    private static void writePolicy(Path root, String json) throws Exception {
        Path f = root.resolve("identity").resolve("activation-policy.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json);
    }

    @Test void absent_file_is_deny_all(@TempDir Path root) {
        ActivationPolicy p = ActivationPolicyStore.load(root);
        assertTrue(p.rules().isEmpty());
        assertEquals("deny", p.defaultEffect());
    }

    @Test void loads_rules(@TempDir Path root) throws Exception {
        writePolicy(root, "{\"version\":\"1\",\"default\":\"deny\",\"rules\":["
                + "{\"id\":\"r1\",\"principal\":\"alice\",\"action\":\"activate\",\"target\":\"Line1\",\"kind\":\"recipe\",\"ref\":\"mix\"}]}");
        ActivationPolicy p = ActivationPolicyStore.load(root);
        assertEquals(1, p.rules().size());
        assertEquals("alice", p.rules().get(0).principal());
    }

    @Test void default_not_deny_is_a_coded_error(@TempDir Path root) throws Exception {
        writePolicy(root, "{\"version\":\"1\",\"default\":\"allow\",\"rules\":[]}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ActivationPolicyStore.load(root));
        assertTrue(ex.getMessage().startsWith("activation.authz.policy.default-not-deny"), ex.getMessage());
    }

    @Test void malformed_json_is_a_coded_error(@TempDir Path root) throws Exception {
        writePolicy(root, "this is not json");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ActivationPolicyStore.load(root));
        assertTrue(ex.getMessage().startsWith("activation.authz.policy.read-error"), ex.getMessage());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=ActivationPolicyStoreTest`
Expected: FAIL — `ActivationPolicyStore` missing.

- [ ] **Step 3: Write the implementation**

```java
package dev.krillin.bifrost.core.activation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.bifrost.core.schema.JsonMapperFactory;
import java.io.IOException;
import java.nio.file.*;

/** Loads registry/identity/activation-policy.json (co-located with the T5 identity trust anchor). Fail-closed:
 *  an ABSENT file is deny-all (a policy with no rules), a malformed file or a default other than "deny" is a
 *  coded IllegalStateException. Whole-file read per load, not cached (small policy; matches T4/T5 reads).
 *  Bootstrap/change-control of the policy are out-of-band (spec §7). */
public final class ActivationPolicyStore {
    private ActivationPolicyStore() {}

    public static ActivationPolicy load(Path registryRoot) {
        Path f = registryRoot.resolve("identity").resolve("activation-policy.json");
        if (!Files.isRegularFile(f)) return ActivationPolicy.denyAll();
        ObjectMapper mapper = JsonMapperFactory.create();
        ActivationPolicy p;
        try {
            p = mapper.readValue(Files.readString(f), ActivationPolicy.class);
        } catch (IOException e) {
            throw new IllegalStateException("activation.authz.policy.read-error: " + f, e);
        }
        if (!"deny".equals(p.defaultEffect()))
            throw new IllegalStateException("activation.authz.policy.default-not-deny: " + f
                    + " (default must be \"deny\")");
        return p;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=ActivationPolicyStoreTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationPolicyStore.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationPolicyStoreTest.java
git commit -m "feat(core): ActivationPolicyStore — load registry/identity/activation-policy.json, absent=deny-all, coded fail-closed"
```

---

## Chunk 2: Service wiring + gate CLI

Wire authZ into `ActivationService` (3-arg signed form, remove the 2-arg), the `activate` gate, and add the `identity authorize` audit leg.

### Task 4: `ActivationService.activate(r, signer, policy)` — authZ, remove 2-arg

**Files:**
- Modify: `core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java`
- Modify (migrate): `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceSignedTest.java`
- Test: `core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceAuthzTest.java`

> **The 2-arg `activate(r, signer)` is removed.** Its only callers are `ActivationServiceSignedTest` (3 sites: lines ~36, 58, 80) and `ActivateGate:57` (migrated in Task 5). `ActivationServiceTest` and `LoadConformanceActivationTest` use the 1-arg `activate(r)` (unsigned) — unchanged.

- [ ] **Step 1: Write the failing test** (new authZ behavior)

```java
package dev.krillin.bifrost.core.activation;

import dev.krillin.bifrost.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPair;
import java.time.Clock;
import java.util.List;
import static dev.krillin.bifrost.core.activation.ActivationAction.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivationServiceAuthzTest {

    private ArtifactResolver okResolver() {
        return (kind, ref, version) -> java.util.Optional.of(
                new ArtifactResolver.ResolvedArtifact(Path.of("x"), "shaX"));
    }

    /** Registers alice+bob and returns a signer that names them. */
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

    private static ActivationPolicy policy(ActivationRule... rules) {
        return new ActivationPolicy("1", List.of(rules), "deny");
    }
    private ActivationRule r(String id, String p, ActivationAction a) {
        return new ActivationRule(id, p, a, "Line1", "recipe", "mix");
    }
    private ActivationRequest req() { return new ActivationRequest("Line1","recipe","mix","1.0.0","alice","bob",false); }

    @Test void authorized_activator_and_approver_are_admitted(@TempDir Path root, @TempDir Path keys) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        ActivationPolicy p = policy(r("r-act","alice",ACTIVATE), r("r-app","bob",APPROVE));
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC())
                .activate(req(), signer(root, keys), p);
        assertTrue(v.ok(), v.violations().toString());
        assertEquals(1, ledger.history("Line1").size());
    }

    @Test void unauthorized_activator_is_denied_ledger_untouched(@TempDir Path root, @TempDir Path keys) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        // only bob→APPROVE; alice has no ACTIVATE rule
        ActivationPolicy p = policy(r("r-app","bob",APPROVE));
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC())
                .activate(req(), signer(root, keys), p);
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("activation.authz.denied")), v.violations().toString());
        assertTrue(ledger.history("Line1").isEmpty(), "refused => ledger untouched");
    }

    @Test void unauthorized_approver_is_denied(@TempDir Path root, @TempDir Path keys) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        // only alice→ACTIVATE; bob has no APPROVE rule
        ActivationPolicy p = policy(r("r-act","alice",ACTIVATE));
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC())
                .activate(req(), signer(root, keys), p);
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("activation.authz.denied")));
    }

    @Test void deny_all_policy_denies_signed_activation(@TempDir Path root, @TempDir Path keys) throws Exception {
        ActivationLedger ledger = new ActivationLedger(root);
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC())
                .activate(req(), signer(root, keys), ActivationPolicy.denyAll());
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(x -> x.rule().equals("activation.authz.denied")));
    }

    @Test void unsigned_activate_has_no_authz(@TempDir Path root) throws Exception {
        // 1-arg path: no signer, no authZ — succeeds even with no policy
        ActivationLedger ledger = new ActivationLedger(root);
        ActivationVerdict v = new ActivationService(okResolver(), ledger, Clock.systemUTC()).activate(req());
        assertTrue(v.ok());
        assertNull(ledger.history("Line1").get(0).activatorSig());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl core -am test -Dtest=ActivationServiceAuthzTest`
Expected: FAIL — no `activate(r, signer, policy)` overload.

- [ ] **Step 3: Modify `ActivationService`**

Replace the two `activate` methods with:
```java
public ActivationVerdict activate(ActivationRequest r) { return activate(r, null, null); }

public ActivationVerdict activate(ActivationRequest r, LedgerSigner signer, ActivationPolicy policy) {
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
        if (signer != null) {                                   // T5 identity checks
            var idv = signer.preflight();
            if (!idv.isEmpty()) return new ActivationVerdict(false, null, idv);
            if (!signer.activatorPrincipal().equals(r.by()) || !signer.approverPrincipal().equals(r.approvedBy()))
                return refuse("identity.signer.principal-mismatch",
                        "signing keys (" + signer.activatorPrincipal() + "/" + signer.approverPrincipal()
                        + ") must match the named activator/approver (" + r.by() + "/" + r.approvedBy() + ")");
            // T6 authZ (deny-by-default) — only on the signed path (authZ presupposes authN).
            ActivationPolicy p = (policy != null) ? policy : ActivationPolicy.denyAll();
            ActivationAuthorizer authz = new ActivationAuthorizer();
            AuthzDecision act = authz.authorize(p, r.by(), ActivationAction.ACTIVATE, r.target(), r.kind(), r.ref());
            if (!act.allowed())
                return refuse("activation.authz.denied", "activator '" + r.by() + "' not permitted to ACTIVATE "
                        + r.target() + "/" + r.kind() + "/" + r.ref() + " [" + act.reason() + "]");
            AuthzDecision app = authz.authorize(p, r.approvedBy(), ActivationAction.APPROVE, r.target(), r.kind(), r.ref());
            if (!app.allowed())
                return refuse("activation.authz.denied", "approver '" + r.approvedBy() + "' not permitted to APPROVE "
                        + r.target() + "/" + r.kind() + "/" + r.ref() + " [" + app.reason() + "]");
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

- [ ] **Step 4: Migrate `ActivationServiceSignedTest`** (the 3 signed calls → 3-arg with an allow-all policy)

At the top of the class add a helper:
```java
private static final ActivationPolicy ALLOW_ALL = new ActivationPolicy("t", java.util.List.of(
        new ActivationRule("a","*", ActivationAction.ACTIVATE, "*","*","*"),
        new ActivationRule("b","*", ActivationAction.APPROVE,  "*","*","*")), "deny");
```
Then change the two signed happy-path calls (`svc.activate(req, signer(...))` at ~line 36, and the mismatch/carol tests at ~58/80 that pass a signer) to append `, ALLOW_ALL`. Concretely:
- line ~36: `svc.activate(new ActivationRequest(...), signer(root, keys, "alice", alice, "bob", bob), ALLOW_ALL)`
- line ~58: `.activate(new ActivationRequest(...), bad, ALLOW_ALL)`
- line ~80: `.activate(new ActivationRequest(...), carolSigner, ALLOW_ALL)`
The 1-arg call at ~90 (`null_signer_is_unchanged_t3_behavior`) stays unchanged.
Run `grep -n "activate(" core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceSignedTest.java` and confirm every call that passes a signer now passes `ALLOW_ALL` too. (These tests assert on preflight/identity refusals which fire BEFORE authZ, so ALLOW_ALL keeps them green.)

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q -pl core -am test -Dtest=ActivationServiceAuthzTest,ActivationServiceSignedTest,ActivationServiceTest,ActivationLedgerSignedTest`
Expected: PASS (new authz test + migrated signed test + unchanged T3/T5 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/dev/krillin/bifrost/core/activation/ActivationService.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceAuthzTest.java \
        core/src/test/java/dev/krillin/bifrost/core/activation/ActivationServiceSignedTest.java
git commit -m "feat(core): ActivationService.activate(r, signer, policy) — deny-by-default authZ on the signed path; drop the authZ-skipping 2-arg overload"
```

---

### Task 5: `ActivateGate` — load policy, pass to the 3-arg signed activate

**Files:**
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java`
- Test: `gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateSignedTest.java` (add one authz case)

- [ ] **Step 1: Write the failing test** (a signed activate against a deny-all registry is refused)

Add to `ActivateGateSignedTest`:
```java
    @Test void signed_activate_denied_by_absent_policy(@TempDir Path root) throws Exception {
        // Stage a registry with authorized keys but NO activation-policy.json (deny-all), plus a resolvable
        // recipe artifact — reuse the same fixture staging the existing ActivateGateTest uses (MasterSpecStore
        // layout under spec/mix-recipe/1.0.0.json + authorized-keys via keygen). See ActivateGateTest for the
        // exact staging; then:
        // int code = ActivateGate.run(new String[]{"activate", regWin, "Line1","recipe","mix-recipe","1.0.0",
        //      "--by","alice","--approved-by","bob","--by-key",aliceKey,"--approved-by-key",bobKey});
        // assertEquals(1, code);  // REFUSED activation.authz.denied (deny-all)
        // NOTE: this happy-path staging is heavy; the authoritative coverage is run-activation-authz-gate.sh (AZ4).
        // Keep this test only if the fixture is readily reusable from ActivateGateTest; otherwise rely on the gate.
    }
```

> **Executor guidance:** the signed-activate happy/deny paths need a resolvable `MasterSpec` artifact whose exact layout is defined by `RecipeArtifactResolver`/`MasterSpecStore` (see `ActivateGateTest`). That fixture is heavy. **Default action: DELETE the `signed_activate_denied_by_absent_policy` stub entirely — do NOT commit an empty/comment-only @Test whose name implies coverage it lacks.** The authoritative gate-level authZ coverage is `run-activation-authz-gate.sh` (Task 9, AZ1–AZ4). For this task, the REQUIRED code change (below) is small; if reusing `ActivateGateTest`'s fixture is clean, add the deny-all assertion above — otherwise delete this stub and rely on the gate script.

- [ ] **Step 2: Modify `ActivateGate.activate`** — load the policy and pass it on the signed path

Change the signed branch:
```java
if (byKey != null) {
    dev.krillin.bifrost.core.activation.LedgerSigner signer =
            dev.krillin.bifrost.core.identity.KeyFileLedgerSigner.create(
                    by, Path.of(byKey), approvedBy, Path.of(approvedByKey),
                    dev.krillin.bifrost.core.identity.AuthorizedKeys.load(reg));
    dev.krillin.bifrost.core.activation.ActivationPolicy policy =
            dev.krillin.bifrost.core.activation.ActivationPolicyStore.load(reg);
    v = svc.activate(req, signer, policy);
} else {
    v = svc.activate(req);   // unsigned: no authZ
}
```
(The `[GATE] REFUSED:` printer already renders `activation.authz.denied` violations — no change there.)

- [ ] **Step 3: Run to verify** existing gate tests stay green

Run: `mvn -q -pl gates -am test -Dtest=ActivateGateTest,ActivateGateSignedTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (existing 9 `ActivateGateTest` unchanged — they use unsigned `activate`; `ActivateGateSignedTest` parse test unchanged).

- [ ] **Step 4: Commit**

```bash
git add gates/src/main/java/dev/krillin/bifrost/gates/ActivateGate.java \
        gates/src/test/java/dev/krillin/bifrost/gates/ActivateGateSignedTest.java
git commit -m "feat(gates): activate loads activation-policy.json and enforces authZ on the signed path"
```

---

### Task 6: `IdentityGate authorize` — audit-plane CLI

**Files:**
- Modify: `gates/src/main/java/dev/krillin/bifrost/gates/IdentityGate.java`
- Test: `gates/src/test/java/dev/krillin/bifrost/gates/IdentityGateAuthorizeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.krillin.bifrost.gates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityGateAuthorizeTest {

    private void writePolicy(Path root) throws Exception {
        Path f = root.resolve("identity").resolve("activation-policy.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"version\":\"1\",\"default\":\"deny\",\"rules\":["
                + "{\"id\":\"r-act\",\"principal\":\"alice\",\"action\":\"activate\",\"target\":\"Line1\",\"kind\":\"recipe\",\"ref\":\"mix\"}]}");
    }

    @Test void allow_case_exits_0(@TempDir Path root) throws Exception {
        writePolicy(root);
        assertEquals(0, IdentityGate.run(new String[]{"authorize", root.toString(), "alice", "activate", "Line1", "recipe", "mix"}));
    }

    @Test void deny_case_exits_1(@TempDir Path root) throws Exception {
        writePolicy(root);
        assertEquals(1, IdentityGate.run(new String[]{"authorize", root.toString(), "alice", "approve", "Line1", "recipe", "mix"}));
        assertEquals(1, IdentityGate.run(new String[]{"authorize", root.toString(), "carol", "activate", "Line1", "recipe", "mix"}));
    }

    @Test void absent_policy_denies(@TempDir Path root) {
        assertEquals(1, IdentityGate.run(new String[]{"authorize", root.toString(), "alice", "activate", "Line1", "recipe", "mix"}));
    }

    @Test void usage_error_exits_2(@TempDir Path root) {
        assertEquals(2, IdentityGate.run(new String[]{"authorize", root.toString(), "alice", "activate"}));
        assertEquals(2, IdentityGate.run(new String[]{"authorize", root.toString(), "alice", "bogus-action", "Line1", "recipe", "mix"}));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl gates -am test -Dtest=IdentityGateAuthorizeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `authorize` not handled.

- [ ] **Step 3: Add the `authorize` leg to `IdentityGate`**

Add to the `run(...)` switch: `case "authorize": return authorize(Arrays.copyOfRange(args, 1, args.length));`. Implement:
```java
private static int authorize(String[] a) throws Exception {
    if (a.length < 6) { System.err.println("Usage: identity authorize <reg> <principal> <activate|approve> <target> <kind> <ref>"); return 2; }
    dev.krillin.bifrost.core.activation.ActivationAction action;
    try { action = dev.krillin.bifrost.core.activation.ActivationAction.from(a[2]); }
    catch (Exception e) { System.err.println("[GATE] authorize: action must be activate|approve, got: " + a[2]); return 2; }
    var policy = dev.krillin.bifrost.core.activation.ActivationPolicyStore.load(Path.of(a[0]));
    var d = new dev.krillin.bifrost.core.activation.ActivationAuthorizer()
            .authorize(policy, a[1], action, a[3], a[4], a[5]);
    if (d.allowed()) {
        System.out.println("[GATE] authorize " + a[1] + " " + action.json() + " " + a[3] + "/" + a[4] + "/" + a[5]
                + " => ALLOW rule=" + d.ruleId());
        return 0;
    }
    System.out.println("[GATE] authorize " + a[1] + " " + action.json() + " " + a[3] + "/" + a[4] + "/" + a[5]
            + " => DENY (" + d.reason() + ")");
    return 1;
}
```
Update the `identity` usage string to include `authorize` — this is `IdentityGate.usage()` (`identity <keygen|verify-signed>` -> `identity <keygen|verify-signed|authorize>`), the only usage string, easy to miss as it sits outside the diff snippet.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl gates -am test -Dtest=IdentityGateAuthorizeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add gates/src/main/java/dev/krillin/bifrost/gates/IdentityGate.java \
        gates/src/test/java/dev/krillin/bifrost/gates/IdentityGateAuthorizeTest.java
git commit -m "feat(gates): identity authorize — audit-plane authZ check (0 allow / 1 deny / 2 usage)"
```

---

## Chunk 3: Heimdall edge + gates + no-regression

### Task 7: Heimdall edge authZ re-check

**Files:**
- Modify: `heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java`
- Test: `heimdall/src/test/java/dev/krillin/bifrost/heimdall/RequireSignedActivationTest.java` (extend)

> **Placement:** in `loadConformance`, AFTER `var active = ledger.active(...).orElseThrow(...)` (which has `activatedBy`/`approvedBy`) and BEFORE the `specFile`/content-hash block. Gate on `config.requireSignedActivation()` (authZ presupposes authN). `target = config.activationTarget()`, `ref` is the in-scope recipe ref.

- [ ] **Step 1: Write the failing test** — extend `RequireSignedActivationTest` with an extracted, broker-free edge-authZ method

Add a package-private static helper to `NcmdOpcUaBridgeMain` and test it directly (mirrors how `assertLedgerTrustworthy` is unit-tested):
```java
// NcmdOpcUaBridgeMain — new helper
/** Fail-closed activation authZ re-check at the edge (only meaningful with an authenticated subject, i.e.
 *  requireSigned). Throws activation.edge.authz-denied on a deny; prints an audit line on pass. No-op when
 *  requireSigned is false (authZ presupposes authN). */
static void assertActivationAuthorized(java.nio.file.Path ledgerDir, String target, String kind, String ref,
                                       String activatedBy, String approvedBy, boolean requireSigned) {
    if (!requireSigned) return;
    var policy = dev.krillin.bifrost.core.activation.ActivationPolicyStore.load(ledgerDir);
    var authz = new dev.krillin.bifrost.core.activation.ActivationAuthorizer();
    var a = authz.authorize(policy, activatedBy, dev.krillin.bifrost.core.activation.ActivationAction.ACTIVATE, target, kind, ref);
    if (!a.allowed())
        throw new IllegalStateException("activation.edge.authz-denied: " + activatedBy + " activate [" + a.reason() + "]");
    var p = authz.authorize(policy, approvedBy, dev.krillin.bifrost.core.activation.ActivationAction.APPROVE, target, kind, ref);
    if (!p.allowed())
        throw new IllegalStateException("activation.edge.authz-denied: " + approvedBy + " approve [" + p.reason() + "]");
    System.out.println("[BRIDGE] activation authz = ok (by " + activatedBy + "/" + approvedBy + ")");
}
```
Test (append to `RequireSignedActivationTest`):
```java
    private void writePolicy(Path root, boolean grantAlice) throws Exception {
        Path f = root.resolve("identity").resolve("activation-policy.json");
        Files.createDirectories(f.getParent());
        String rules = "{\"id\":\"r-app\",\"principal\":\"bob\",\"action\":\"approve\",\"target\":\"Line1\",\"kind\":\"recipe\",\"ref\":\"mix\"}"
                + (grantAlice ? ",{\"id\":\"r-act\",\"principal\":\"alice\",\"action\":\"activate\",\"target\":\"Line1\",\"kind\":\"recipe\",\"ref\":\"mix\"}" : "");
        Files.writeString(f, "{\"version\":\"1\",\"default\":\"deny\",\"rules\":[" + rules + "]}");
    }

    @Test void edge_authz_allows_when_both_permitted(@TempDir Path root) throws Exception {
        writePolicy(root, true);
        assertDoesNotThrow(() -> NcmdOpcUaBridgeMain.assertActivationAuthorized(root, "Line1","recipe","mix","alice","bob", true));
    }

    @Test void edge_authz_denies_when_activator_revoked(@TempDir Path root) throws Exception {
        writePolicy(root, false);   // alice's ACTIVATE removed
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NcmdOpcUaBridgeMain.assertActivationAuthorized(root, "Line1","recipe","mix","alice","bob", true));
        assertTrue(ex.getMessage().contains("activation.edge.authz-denied"), ex.getMessage());
    }

    @Test void edge_authz_is_noop_when_require_signed_off(@TempDir Path root) throws Exception {
        // no policy at all; require-signed off => skip (authZ presupposes authN)
        assertDoesNotThrow(() -> NcmdOpcUaBridgeMain.assertActivationAuthorized(root, "Line1","recipe","mix","alice","bob", false));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl heimdall -am test -Dtest=RequireSignedActivationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `assertActivationAuthorized` missing.

- [ ] **Step 3: Add the helper + call it in `loadConformance`**

Add the helper method above. Then, right after the `var active = ledger.active(...).orElseThrow(...)` line in `loadConformance`, insert:
```java
assertActivationAuthorized(ledgerDir, config.activationTarget(), "recipe", ref,
        active.activatedBy(), active.approvedBy(), config.requireSignedActivation());
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl heimdall -am test -Dtest=RequireSignedActivationTest -Dsurefire.failIfNoSpecifiedTests=false` then `mvn -q -pl heimdall -am test`
Expected: PASS (new 3 + existing heimdall tests unchanged — they run require-signed off, so the edge authZ is a no-op).

- [ ] **Step 5: Commit**

```bash
git add heimdall/src/main/java/dev/krillin/bifrost/heimdall/NcmdOpcUaBridgeMain.java \
        heimdall/src/test/java/dev/krillin/bifrost/heimdall/RequireSignedActivationTest.java
git commit -m "feat(heimdall): edge activation-authZ re-check (require-signed mode) — fail-closed activation.edge.authz-denied + audit line"
```

---

### Task 8: `run-identity-gate.sh` — seed the activation policy (REQUIRED backward-compat fix)

**Files:**
- Modify: `scripts/run-identity-gate.sh`

> **Why REQUIRED:** T6's gate-time deny-by-default fires on every signed activation. `run-identity-gate.sh` performs legitimate signed activations (alice/bob) against registries with no `activation-policy.json` — under T6 those are refused `activation.authz.denied`, breaking I1/I2/I5/I6/I7. Seeding a policy that grants alice→ACTIVATE and bob→APPROVE on `(Line1, recipe, mix-recipe)` restores them. I3/I4a–c refuse earlier (preflight/four-eyes), so they are unaffected.

- [ ] **Step 1: Add policy seeding to `stage_reg()`**

In `stage_reg()` (right after the `cp "$AKF" "$reg/identity/authorized-keys.jsonl"` line), write the policy:
```bash
  cat > "$reg/identity/activation-policy.json" <<'JSON'
{"version":"1","default":"deny","rules":[
  {"id":"r-activate","principal":"alice","action":"activate","target":"Line1","kind":"recipe","ref":"mix-recipe"},
  {"id":"r-approve","principal":"bob","action":"approve","target":"Line1","kind":"recipe","ref":"mix-recipe"}
]}
JSON
```

- [ ] **Step 2: Add the same policy to the I7 registry**

In the I7 block (after `cp "$AKF" "$I7REG/identity/authorized-keys.jsonl"`), write the identical `activation-policy.json` into `$I7REG/identity/`.

- [ ] **Step 3: Run the T5 gate to confirm it still passes under T6**

Run: `bash scripts/run-identity-gate.sh`
Expected: `[IDENTITY] GATE PASS (I1-I6 +I7)` (or `(I1-I6)` if no docker). **CONTROLLER-VERIFY.**

- [ ] **Step 4: Commit**

```bash
git add scripts/run-identity-gate.sh
git commit -m "test(gate): run-identity-gate — seed activation-policy.json so T5 signed activations pass T6 authZ (deny-by-default)"
```

---

### Task 9: `run-activation-authz-gate.sh` — the T6 end-to-end gate (AZ1–AZ7)

**Files:**
- Create: `scripts/run-activation-authz-gate.sh`

> **Model on `run-identity-gate.sh`** (reuse its scaffolding: `set -euo pipefail`, `WORK`, `fail()`, docker/python checks, `REG_WIN`/cygpath, keygen + `authorized-keys.jsonl` staging, fixture staging, `sactivate` helper, broker harness for AZ6/AZ7). **keygen + register `carol` too** (for AZ2/AZ3, so the denial is the authZ layer, not principal-mismatch). Seed an `activation-policy.json` granting `alice→ACTIVATE`, `bob→APPROVE` on `(Line1,recipe,mix-recipe)` in the authorized registries; use a fresh registry per case.

- [ ] **Step 1: Write the script** covering:

| Case | Setup | Assert |
|---|---|---|
| AZ1 | authorized alice(ACTIVATE)+bob(APPROVE), signed activate | exit 0, INTACT via `identity verify-signed` |
| AZ2 | policy grants only bob→APPROVE; signed activate `--by carol --approved-by bob` (carol registered, keyed) | `[GATE] REFUSED` `activation.authz.denied`, exit 1, ledger empty |
| AZ3 | policy grants only alice→ACTIVATE; signed activate `--by alice --approved-by carol` | `activation.authz.denied`, exit 1 |
| AZ4 | **stage a registry WITHOUT `activation-policy.json`** (deny-all) — do NOT seed this one; authorized-keyed signed activate | `activation.authz.denied`, exit 1 |
| AZ5 | `gates identity authorize` allow case (alice activate) exit 0 + deny case (alice approve) exit 1 | both codes |
| AZ6 | broker: AZ1 activation, then rewrite policy removing alice's ACTIVATE, restart Heimdall `REQUIRE_SIGNED_ACTIVATION=on` | log has `activation.edge.authz-denied`, never `[BRIDGE] activation bound` |
| AZ7 | broker: authorized policy + signed ledger, `REQUIRE_SIGNED_ACTIVATION=on` | binds; log has `[BRIDGE] activation authz = ok` |

- **AZ6/AZ7 need a FULL conformance registry** (udt + conformance + spec + trust anchor + `activation-policy.json`), NOT the lean CLI registry of AZ1-AZ5 — copy `run-identity-gate.sh`'s I7 staging block (its `$I7REG` conformance-trio staging) verbatim as the template, then add `activation-policy.json`.
- Gate AZ6/AZ7 on `command -v docker` (skip-if-absent, honest label `(AZ1-AZ5${AZ_BROKER_RAN:+ +AZ6-AZ7})`).
- For AZ2/AZ3, keygen `carol` and register her (so preflight passes and the denial is specifically the authZ layer, not principal-mismatch).
- Final line: `echo "[AUTHZ] GATE PASS (AZ1-AZ5${AZ_BROKER_RAN:+ +AZ6-AZ7})"`.

- [ ] **Step 2: Make executable + run**

Run:
```bash
chmod +x scripts/run-activation-authz-gate.sh
bash scripts/run-activation-authz-gate.sh
```
Expected: `[AUTHZ] GATE PASS (AZ1-AZ5 +AZ6-AZ7)` (or without the broker suffix). **CONTROLLER-VERIFY** (do not trust a subagent PASS).

- [ ] **Step 3: Commit**

```bash
git add scripts/run-activation-authz-gate.sh
git commit -m "test(gate): run-activation-authz — AZ1 allow / AZ2 activator-deny / AZ3 approver-deny / AZ4 deny-all / AZ5 audit-cli / AZ6 edge-revoke / AZ7 edge-ok"
```

---

### Task 10: Full no-regression + build

**Files:** none (verification only).

- [ ] **Step 1: Full build + all unit tests**

Run: `mvn -q install`
Expected: BUILD SUCCESS — core (baseline 174 + T6 ~19 new), heimdall (35 + 3), gates (58 + ~5), sim 7. All T3/T4/T5 tests unchanged.

- [ ] **Step 2: Re-run the prior gates (controller-run)**

Run (each, expect its PASS line + exit 0):
```bash
bash scripts/run-activation-gate.sh          # T3 A1-A5 (unsigned, require-signed off → no authZ)
bash scripts/run-lineage-gate.sh             # T4 LN1-LN4
bash scripts/run-identity-gate.sh            # T5 I1-I7 — now with the seeded policy (Task 8)
bash scripts/run-activation-authz-gate.sh    # T6 AZ1-AZ7
bash scripts/run-template-conformance-gate.sh
bash scripts/run-yggdrasil-full-loop-gate.sh
```
Expected: all print their PASS and exit 0. **CONTROLLER-VERIFY all + `mvn install` personally.**

- [ ] **Step 3: Docs (optional)** — if `README.md` enumerates the gate suite, add `run-activation-authz-gate.sh` + note the `identity authorize` leg and the T6 authZ lifecycle line. Commit if changed.

---

## Done criteria

- [ ] `mvn install` green; all T3/T4/T5 tests unchanged and passing.
- [ ] `run-activation-authz-gate.sh` → `GATE PASS (AZ1-AZ7)` (controller-verified).
- [ ] `run-identity-gate.sh` still PASS with the seeded policy; `run-activation-gate` / `run-lineage-gate` / template / full-loop all still PASS.
- [ ] Deny-by-default holds: absent policy denies every signed activation; unsigned `activate(r)` carries no authZ.
- [ ] maker-checker enforced: activator needs ACTIVATE, approver needs APPROVE, both at the gate and (require-signed) at the edge.
- [ ] Spec §7 honest limits hold in code (no roles/ABAC; policy plaintext; authZ presupposes authN; revocation bind-fresh).

## Handoff to the executor

Follow subagent-driven-development (fresh subagent per task/chunk, two-stage review) if available, else executing-plans. **Before Task 4/5, read the real `ActivationServiceSignedTest` and `ActivateGateTest` fixtures** to migrate the signed calls and (optionally) stage the gate test accurately. The controller runs all gates + `mvn install` personally (working-style: never trust a subagent PASS).
