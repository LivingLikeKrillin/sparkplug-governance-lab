# Signed Activation — Identity (T5) — Design

**Status:** approved for planning (2026-07-11)
**Repo:** `bifrost` (Java 17, Maven multi-module: core/gates/heimdall/sim), new branch `feat/t5-identity` off `main` (spec/plan live in the lab repo `sparkplug-governance-lab` R&D journal, branch `feat/t5-identity`; code lives in `bifrost`).
**Builds on:** T3 governed version-activation (`core.activation`; four-eyes SoD recorded as two string principals; `contentSha256` seal). T4 lineage/record-of-record (`registry/activation/<target>.jsonl` is a SHA-256 hash chain of `LedgerEntry{event, prevHash, entryHash}`; `LedgerChain` is the single canonical-preimage hash impl; Heimdall fail-closes on a broken chain before binding). See `2026-07-09-governed-version-activation-design.md`, `2026-07-09-lineage-record-of-record-design.md`.

---

## 1. Problem

T4 made the activation history **tamper-EVIDENT**: any retroactive edit / delete / reorder / mid-truncation of past events breaks the hash chain, and Heimdall refuses to bind a broken chain. But T4 was explicit (its §9) that a hash chain alone is **self-attested, not authentic**, and leaves two gaps:

1. **Full re-chain from genesis.** The `entryHash` values are unauthenticated SHA-256. An attacker with write access to the ledger can edit any past event and **recompute every downstream `entryHash`** — the chain re-validates structurally, and nothing external says the recomputed history is forged. The chain proves "not altered since the control plane wrote it," but there is no proof of *who* wrote it or that they were authorized. `activatedBy`/`approvedBy` are plain strings — anyone with file access can write any name.

2. **Tail-truncation.** Dropping the last N events leaves a prefix that is a perfectly valid chain (each remaining link still verifies, genesis still holds). There is no anchor to the head, so "the last recorded activation" can be silently rewound.

**T5 closes both cryptographically.** Each activation event is **dual-signed** by the activator's and approver's registered Ed25519 keys (closing full re-chain — a forger cannot produce valid signatures over recomputed hashes), and the ledger tail is anchored by a **signed head** carrying a monotonic sequence (closing tail-truncation — the recorded tail must match the signed head). Consistent with the T3/T4 thesis that activation governs *what actually runs*, Heimdall **re-verifies signatures and head before binding** and fails closed.

This turns T4's "self-attested chain integrity" into **authenticated, non-repudiable history**: signed by identified individuals, anchored at the head.

## 2. Scope decisions (agreed in brainstorming)

- **Thesis = signed activation (entry signatures + signed head).** T5 proves *authenticity / non-repudiation* — who cryptographically attested each activation, and that the head has not been rewound. (Rejected as the thesis: "identity-first authorization" and "external-anchor-first freshness" — see §9 for what each defers.)
- **Dual signature (activator AND approver).** Each `LedgerEntry` carries two Ed25519 signatures; verification requires both valid, both keys registered, and the two keys **distinct** — a cryptographic upgrade of T3's `activatedBy≠approvedBy` four-eyes SoD. (Rejected: approver-only single signature — leaves SoD only half-cryptographic.)
- **Trust anchor = plaintext `authorized-keys` registry file.** `registry/identity/authorized-keys.jsonl` maps `principal → ed25519 publicKey`. Signature verification = "this pubkey is registered for this principal." The registry's own bootstrap/distribution/revocation (PKI/OIDC/CRL) is **out of scope**, honestly named (§9). (Rejected: trust-on-first-use — weak identity bootstrap, low anti-tamper value.)
- **NO authorization/role check.** T5 verifies WHO signed (authenticity), **not** whether that principal is *permitted* to activate this target (authorization/RBAC scope). Role/scope enforcement is the natural next thread, deliberately deferred (§9). This keeps the thesis focused on non-repudiation.
- **Signed head = local file `registry/identity/<target>.head`** carrying `{seq, tailEntryHash, signedBy, sig}`. Catches tail-truncation (recorded tail ≠ signed head). It does **NOT** catch a **ledger+head simultaneous rollback** — that needs an *external* anchor (git commit / notary / TPM counter), stated as the honest residual gap (§9). This corrects T4 §9's slightly optimistic "a signed head closes tail-truncation" — a *local* signed head closes single-sided truncation; the fully-external anchor is a later thread.
- **Sign over T4's `entryHash` (Approach A).** The signature covers the existing `entryHash`, which already commits to event fields + prevHash + the full prior history. So `activatorSig = sign(entryHash, activatorPriv)` transitively commits to everything, and **T4's structural chain (`LedgerChain`) is untouched** — signatures are an authenticity envelope layered on top. (Rejected: a distinct signing preimage — duplicates T4's canonical-preimage drift hazard for no gain; and signing raw event JSON — reintroduces the serialization-stability problem `LedgerChain` already solved.)
- **Crypto primitive = JDK built-in Ed25519** (`java.security`, JDK 15+; project is 17). **No new `core` dependency** (no BouncyCastle). Serialization: base64 — public key = X.509 `SubjectPublicKeyInfo`, private key = PKCS8, signature = raw 64-byte Ed25519. (Rejected: RSA/ECDSA — larger, more footguns; a crypto library — unnecessary weight for one primitive the JDK ships.)
- **Signing keys supplied as file paths on the CLI** (`--by-key`, `--approved-by-key`). HSM / true signer-host separation is out of scope; the demo runs both keys on one host (§9). (Rejected as the thesis mechanism: pre-signed approval tokens — closer to real duty separation but adds flow/test complexity beyond T5's authenticity thesis.)
- **Backward compatible.** Unsigned (T3/T4) ledgers still parse and still verify structurally. Signing is opt-in via a nullable signer seam; `verify-chain` keeps its T4 structural-only semantics; a new `verify-signed` leg does the full authenticated check.

### 2.1 What each gap needs, and where T5 draws the line

| Gap (from T4 §9) | Closed by | Mechanism in T5 |
|---|---|---|
| Full re-chain from genesis | Entry signatures | Forger recomputes `entryHash` but cannot forge Ed25519 signatures over them without a registered private key |
| Tail-truncation (single-sided) | Signed head | Recorded ledger tail must equal `head.tailEntryHash`; `head.seq` must match entry count; head signature must be valid + signer registered |
| Ledger **+ head** simultaneous rollback | **NOT closed** (needs external anchor) | Deferred: git-anchored / notarized / monotonic-counter head — future thread (§9) |
| Authorization (is signer *permitted*?) | **Not in scope** | T5 proves authenticity, not permission — role/scope is the next thread (§9) |

## 3. Architecture

Additive. New `core.identity` package holds the crypto primitive, the trust anchor, the signed head, and the full verifier. `core.activation` gains signatures on `LedgerEntry` and a nullable signer seam on the writer. `LedgerChain` (T4) is **unchanged** — structural verification stays exactly as shipped; signatures wrap it.

```
gates identity keygen alice --out keys/                       [T5: key material]
   └─ Ed25519Keys.generate() → writes alice.key (PKCS8 b64), alice.pub (X.509 b64)
        prints authorized-keys line {"principal":"alice","publicKey":"<b64>"}

gates activate Line1 recipe mix-recipe 1.1.0 \
      --by alice --approved-by bob \
      --by-key keys/alice.key --approved-by-key keys/bob.key   [T5: dual-signed activation]
   └─ ActivationService.activate(req, LedgerSigner)            [T3/T4 checks preserved]
        · four-eyes string SoD (alice≠bob)         [T3]
        · resolve + contentSha256 seal             [T3]
        · build ActivationEvent                    [T3, field order preserved]
        └─ ActivationLedger.append(event, signer): [T4 chains; T5 signs + anchors head]
             prevHash = tail.entryHash (GENESIS if empty)        [T4]
             entryHash = LedgerChain.entryHash(event, prevHash)  [T4, unchanged]
             signer.sign(entryHash):                             [T5]
                activatorSig = Ed25519.sign(entryHash, alice.key)
                approverSig  = Ed25519.sign(entryHash, bob.key)
                assert alice.pub ≠ bob.pub          [cryptographic four-eyes]
             write LedgerEntry{event, prevHash, entryHash, activatorSig, approverSig}
                → registry/activation/Line1.jsonl                [T4 line + 2 sig fields]
             seq = prevHead.seq + 1 (0 if none)                  [T5 head]
             headSig = Ed25519.sign(target|seq|entryHash, bob.key)   [approver anchors head]
             write SignedHead{Line1, seq, entryHash, "bob", headSig}
                → registry/identity/Line1.head

gates identity verify-signed <registry> Line1                   [T5: audit plane]
   └─ SignedLedgerVerifier.verify(Line1)
        1. LedgerChain.verify(history)                 [T4 structural — reused verbatim]
        2. per entry: both sigs valid vs AuthorizedKeys, keys distinct
        3. head: tailEntryHash==last.entryHash, seq==count-1, sig valid + signer registered
        exit 0 intact / 1 broken (first failure + reason) / 2 usage|no-such-target

Heimdall (re)start, ACTIVATION_TARGET=Line1                     [T5: edge enforcement]
   └─ SignedLedgerVerifier.verify(Line1) intact?
        ── broken ─► FAIL-CLOSED activation.edge.signed-ledger-broken (no bind)
        └─ intact ─► active(...) ─► contentSha256 recheck [T3] ─► bind
```

## 4. Components

### 4.1 `core.identity.Ed25519Keys` — pure crypto util (no I/O)
Static methods over JDK `java.security`:
- `KeyPair generate()` — `KeyPairGenerator.getInstance("Ed25519")`.
- `String publicKeyB64(PublicKey)` / `PublicKey publicKey(String b64)` — X.509 `SubjectPublicKeyInfo` ↔ base64 (`X509EncodedKeySpec`).
- `String privateKeyB64(PrivateKey)` / `PrivateKey privateKey(String b64)` — PKCS8 ↔ base64 (`PKCS8EncodedKeySpec`).
- `String sign(byte[] msg, PrivateKey)` → base64 raw signature (`Signature.getInstance("Ed25519")`).
- `boolean verify(byte[] msg, String sigB64, PublicKey)` — false on any exception (fail-closed).

The signed message is always `entryHash.getBytes(UTF_8)` (entry sigs) or the head preimage bytes (head sig) — never re-derived elsewhere.

### 4.2 `core.identity.AuthorizedKeys` — the trust anchor
Loads `registry/identity/authorized-keys.jsonl`, one JSON object per line `{"principal": "...", "publicKey": "<X.509 b64>"}`.
- `static AuthorizedKeys load(Path registryRoot)` — reads `registryRoot/identity/authorized-keys.jsonl`; absent file → empty set (fail-closed: nobody is authorized).
- `Optional<PublicKey> forPrincipal(String principal)` — empty if unregistered.
- Duplicate principal lines: **last wins is rejected** — a duplicate principal with a *different* key is a load error (ambiguous identity, fail-closed). Duplicate identical lines are tolerated.

### 4.3 `core.identity.SignedHead` (record) + `SignedHeadStore`
`record SignedHead(String target, long seq, String tailEntryHash, String signedBy, String sig)`.
- Head preimage (canonical, delimiter-joined like `LedgerChain`): `target ␟ seq ␟ tailEntryHash` — signed by the approver.
- `SignedHeadStore(Path registryRoot)`: `Optional<SignedHead> read(String target)` (file `identity/<target>.head`, absent → empty), `void write(SignedHead)`.
- `seq` is monotonic per target; the writer sets `seq = prevHead.map(seq)+1` else 0. `seq` must equal (entry count − 1) at verify time — this is what makes a dropped tail entry detectable.

### 4.4 `core.activation.LedgerEntry` — extended (backward compatible)
`record LedgerEntry(ActivationEvent event, String prevHash, String entryHash, String activatorSig, String approverSig)`.
- `activatorSig`/`approverSig` are **nullable** — a legacy T4 line (no sig fields) deserializes with both null = "unsigned entry." Backward-compat mechanism: the two fields are simply *absent* from a legacy 3-field JSON line, and Jackson passes absent record components as `null` to the canonical constructor. (This does not rely on `FAIL_ON_UNKNOWN_PROPERTIES` being disabled — `JsonMapperFactory.create()` does not disable it; nothing here is an *unknown* property, the new fields are just missing.)
- `entryHash` is still `LedgerChain.entryHash(event, prevHash)` — **signatures do not enter the hash**, so T4's `LedgerChain.verify` is unaffected and legacy chains still verify structurally.

### 4.5 `core.activation.LedgerSigner` — the nullable writer seam (port)
Interface: `Signatures sign(String entryHash)` returning `record Signatures(String activatorSig, String approverSig)`, plus `String signHead(String preimage)` and `String approverPrincipal()`.
- **Construction binds each key file to its claimed principal (the crux of the four-eyes fix).** The gate builds the concrete signer from `(activatorPrincipal, activatorKeyFile, approverPrincipal, approverKeyFile, AuthorizedKeys)`. At construction the signer, for **each** side, signs a fixed probe with the loaded private key and verifies that signature against `AuthorizedKeys.forPrincipal(principal)`; a miss → **fail-closed at activate** (`identity.key.principal-mismatch`), ledger untouched. This is what makes a private key file provably belong to its named principal *without* recomputing a public key from a PKCS8 private key (JDK 17 has no clean path for that). The registered public keys used for four-eyes therefore come from `AuthorizedKeys`, not from the key files.
- **Cryptographic four-eyes = `AuthorizedKeys.forPrincipal(activatedBy) ≠ AuthorizedKeys.forPrincipal(approvedBy)`** (throws `identity.four-eyes.same-key` → fail-closed at activate). With the `AuthorizedKeys.load` dup-principal-diff-key rule (§4.2) two *distinct* principals normally have distinct keys, so this fires only in the pathological case where two principals registered the *same* pubkey. The everyday "same key file for both" attack is caught earlier by `identity.key.principal-mismatch` (the approver key file won't verify against the approver principal's registered key). Both are activate-time refusals — see test I4.
- `ActivationLedger.append(ActivationEvent e, LedgerSigner signer)`: when `signer == null` → **exact T4 behavior** (write unsigned `LedgerEntry`, no head). When non-null → compute entryHash, `signer.sign(entryHash)`, write the signed line, then advance + write the signed head. (The principal-binding and four-eyes checks already ran at signer construction / activate time, before any write.)
- T4's `append(ActivationEvent)` remains as `append(e, null)` for source compatibility.
- **Ordering / crash-consistency:** ledger line is appended *before* the head is written. If the process dies between the two, the head lags the tail by one entry — detected by `verify-signed` fail-closed (the verifier checks `tail-mismatch` *before* `seq-mismatch`, so the reported code is `identity.head.tail-mismatch`; both are head faults, both fail closed), never a silent pass. §7 documents this as a recovery-by-re-sign path, not data loss.

### 4.6 `core.activation.ActivationService` — signer injection
`activate(ActivationRequest, LedgerSigner)`. All T3 checks unchanged and run **before** any signing. `LedgerSigner` is built by the caller (gate) from the two key files + `AuthorizedKeys`. `null` signer → unsigned activation (preserves every existing T3/T4 test and the legacy CLI form).

### 4.7 `core.identity.SignedLedgerVerifier` — the full authenticated check
Returns a **parallel `SignedVerdict`** record (`intact/brokenIndex/rule`) rather than reusing T4's `ChainVerdict` — the reason-code set here is the richer `identity.*` family below, and keeping a distinct type avoids overloading `ChainVerdict.rule` (which today carries only `ledger.chain.*` codes). `verify(String target)`:
1. `LedgerChain.verify(history)` — structural (T4). Reused verbatim; first structural break returns immediately.
2. Per entry: `activatorSig`/`approverSig` present; `Ed25519.verify(entryHash, sig, AuthorizedKeys.forPrincipal(event.activatedBy()/approvedBy()))`; the two resolved keys distinct. Any miss → broken with a reason code (`identity.sig.missing` / `identity.sig.invalid` / `identity.key.unregistered` / `identity.four-eyes.same-key`).
3. Head: present; `head.tailEntryHash == last.entryHash`; `head.seq == history.size()-1`; head sig valid vs `AuthorizedKeys.forPrincipal(head.signedBy())`. Miss → `identity.head.missing` / `identity.head.tail-mismatch` / `identity.head.seq-mismatch` / `identity.head.sig-invalid`. **`head.signedBy` is only required to be a registered principal — it is NOT required to equal the tail entry's `approvedBy`** (the writer signs the head with the approver's key by convention (§7), but verification does not bind the two; under the acknowledged ledger+head co-rollback gap of §9, requiring equality would add no real strength).
- Fail-closed throughout; a fully-unsigned legacy ledger yields `identity.sig.missing` at index 0 (see §5 for how Heimdall's require-signed switch treats that).

## 5. Heimdall edge enforcement
`NcmdOpcUaBridgeMain` currently, when `ACTIVATION_TARGET` is set, calls `ledger.verifyChain(target)` and fails closed on a break, then binds the active pointer with a `contentSha256` recheck. T5 replaces the `verifyChain` call with `SignedLedgerVerifier.verify(target)` (which internally *still* runs `LedgerChain.verify` first, so structural breaks are still caught with T4's semantics) and fails closed on any signature/head break: `activation.edge.signed-ledger-broken`.
- `registry/identity/` is derived from the registry root already in `Config` (no new env var).
- **Require-signed switch:** a new `Config` flag `REQUIRE_SIGNED_ACTIVATION` whose **global default is OFF**. When off, Heimdall falls back to T4 structural `verifyChain` only — so the existing T3/T4 gates (`run-activation-gate.sh` and `run-lineage-gate.sh`, both of which start Heimdall with `ACTIVATION_TARGET=Line1` against an *unsigned* ledger) keep passing with **no changes to those scripts**. Only the T5 gate (`run-identity-gate.sh`) sets `REQUIRE_SIGNED_ACTIVATION=on`; with it on, `SignedLedgerVerifier.verify` runs and an unsigned ledger fails closed (`identity.sig.missing`). (Default-off is the *only* setting under which the "no changes to existing scripts" claim in §8 holds — do not default this on.)

## 6. Gate CLI (`gates` / `ActivateGate` / `GatesCli`)
- `identity keygen <principal> --out <dir>` — generate an Ed25519 keypair; write `<principal>.key` (PKCS8 b64, `0600`-intent) and `<principal>.pub` (X.509 b64); print the `authorized-keys.jsonl` line to stdout. Exit 0 / 2 usage.
- `activate ... --by-key <path> --approved-by-key <path>` — extends the existing `activate` leg. When both key flags present → build a `LedgerSigner` from `(--by principal, --by-key file, --approved-by principal, --approved-by-key file, AuthorizedKeys.load(reg))`; the signer's constructor runs the key-file↔principal binding check and the four-eyes check (§4.5), both fail-closed at activate before any write → dual-signed activation + head write. When absent → T4 unsigned activation (unchanged). If only one key flag present → usage error (2).
- `identity verify-signed <reg> <target>` — `SignedLedgerVerifier`; exit 0 intact / 1 broken (prints index + reason) / 2 usage|no-such-target. Placed under the **`identity`** subcommand (alongside `keygen`) specifically so **T4's `activation verify-chain` is left completely untouched** (structural-only). The two verifications are distinct legs on distinct subcommands.

## 7. Honest engineering notes (in the spec, not hidden)
- **append writes line then head (non-atomic).** A crash between them leaves the head one entry behind the tail — `verify-signed` fails closed (reported as `identity.head.tail-mismatch`, since the verifier compares tail hash before seq); recovery is re-signing the head for the current tail (a `gates identity reseal-head` convenience leg MAY be added; not required for the thesis). No silent acceptance, no data loss (the ledger line is the record; the head is a re-derivable anchor).
- **`AuthorizedKeys.load` reads the whole file per verify** (small registry, matches T4's whole-file reads). Not cached across calls; O(n) is fine at this scale.
- **Head signed by the approver** (the accountable party for "this is the current tail"). Signing it by the activator instead would be equally valid; approver chosen to match "approval attests the state."
- **Key files are plaintext on disk** in the demo; `keygen` writes with restrictive-permission *intent* but real key custody (HSM, OS keystore) is out of scope (§9).

## 8. Testing — `scripts/run-identity-gate.sh` (controller-run)
`mvn -q -pl gates -am package` once, then drive the CLI against a temp registry seeded with `keygen`. Upfront tool check (java + the `python` byte-tamper helper pattern from `run-lineage-gate.sh`).

| Case | Scenario | Expect |
|---|---|---|
| I1 | keygen alice/bob → dual-signed activate → `verify-signed` | INTACT, exit 0 |
| I2 | flip one byte of `activatorSig` in a line → `verify-signed` | BROKEN `identity.sig.invalid`, exit 1 |
| I3 | sign with a key whose principal is NOT in `authorized-keys` → `verify-signed` | BROKEN `identity.key.unregistered`, exit 1 |
| I4a | `activate --by alice --approved-by alice` (same principal) | REFUSED at activate, T3 string SoD `activation.approval.self`, exit 1 |
| I4b | distinct principals `--by alice --approved-by bob` but the approver key file is actually alice's key | REFUSED at activate, `identity.key.principal-mismatch` (approver key doesn't verify against bob's registered key), exit 1 |
| I4c | two principals that registered the *same* pubkey, used as activator+approver | REFUSED at activate, `identity.four-eyes.same-key`, exit 1 |
| I5 | delete the last ledger line, leave the head → `verify-signed` | BROKEN `identity.head.tail-mismatch`, exit 1 |
| I6 | edit a past event + recompute ALL `entryHash` (structural re-chain), do NOT re-sign → `verify-signed` | structural `LedgerChain.verify` passes, but `identity.sig.invalid` at the edited index (sig over old hash), exit 1 |
| I7 | Heimdall start against a broken signed ledger (reuse I2 or I5 fixture), `REQUIRE_SIGNED_ACTIVATION=on` | fail-closed `activation.edge.signed-ledger-broken`, no bind |

Plus JUnit at the unit layer: `Ed25519KeysTest` (sign/verify round-trip, tamper→false, cross-key→false), `AuthorizedKeysTest` (lookup, absent→empty, dup-principal-diff-key→error), `SignedHeadStoreTest`, `SignedLedgerVerifierTest` (each reason code), and `ActivationServiceTest`/`ActivationLedgerTest` additions (signed append writes head; null signer = unchanged T4 behavior). No-regression: `run-lineage-gate.sh`, `run-activation-gate.sh`, `run-yggdrasil-full-loop-gate.sh`, `run-template-conformance-gate.sh` all still green + full `mvn install`.

## 9. Honest limitations (do not oversell)
- **Ledger + head simultaneous rollback is NOT detected.** A signed head anchors the tail against single-sided truncation, but an attacker who rewinds *both* the ledger and the head (re-signing the head at the lower seq, if they hold a registered key) leaves no local trace. Truly closing this needs an **external, append-only anchor for the head** — git commit, a notary/transparency log, or a hardware monotonic counter — which is a distinct future thread. This is the honest correction to T4 §9's "a signed head closes tail-truncation": a *local* signed head closes *single-sided* truncation only.
- **Authenticity, not authorization.** T5 proves *who cryptographically signed* an activation (non-repudiation), **not** that the signer was *permitted* to activate this target/kind/ref. Role/scope/attribute-based authorization (the "is alice allowed to activate Line1?" question) is deliberately out of scope and is the natural next thread — Bifrost-as-IAM's authorization plane.
- **`authorized-keys` bootstrap/distribution/revocation is out-of-band and manual.** No PKI, no OIDC, no CRL/expiry — registering a key is editing a file; revoking is deleting a line. Key rotation and expiry are not modeled. A compromised registered key is trusted until its line is removed.
- **Key custody is out of scope.** Keys are plaintext files on the same host in the demo; no HSM, no OS keystore, no true signer-host separation. `--by-key`/`--approved-by-key` both resolve locally, so the demo's "four-eyes" is two key files, not two isolated humans. Pre-signed approval tokens (real duty separation) were considered and deferred.
- **A malicious *registered* signer is trusted.** The threat model is tamper/forgery by an unregistered party; an authorized principal who signs a bad activation is held accountable via non-repudiation, not prevented. (This closes T4 §9's delimiter-injection concern for the authenticity dimension — a forging writer cannot produce valid signatures — while accepting that a registered writer is trusted.)
- **Per-target head, per-target chain** — no cross-target global ordering or single global signed log (matches T4's one-file-per-target layout; a global transparency log is out of scope / YAGNI).
- **Scope is the activation ledger (`spec/` runtime governance).** Unifying the `spec/` activation seal with the git-anchored `recipe/` provenance manifest (the other long-standing thread) remains future work, unchanged from T3/T4.

## 10. Why this matters (portfolio framing)
Turns T4's tamper-*evident* record-of-record into an **authenticated, non-repudiable** one: every activation is cryptographically signed by identified individuals under four-eyes SoD, and the head is anchored so the "current active version" cannot be silently rewound. This is the identity seam of Bifrost-as-IAM for the OT governance boundary — the last planned T. It stops honestly short of *authorization* (who is permitted) and *external head anchoring* (ledger+head co-rollback), both named as the next threads rather than oversold.
