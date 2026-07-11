# Reproduce the PoC — the full loop running under Yggdrasil governance

This lab is a **PoC stage**: it shows the whole governance loop *actually running* under an external
**Bifrost / Yggdrasil** authority it consumes (verify-then-trust). This guide lets anyone reproduce
every experiment the PoC and the blog cite, and get the **same result values**.

- The runnable code + gate scripts live in **[bifrost](https://github.com/yggdrasil-iiot/bifrost)**
  (core / gates / heimdall / sim). The lab consumes bifrost's governed contracts.
- Captured evidence for every experiment below is in [`outputs/`](outputs/) — your run should match.
- Every result here was **controller-run** (executed and captured directly, not asserted second-hand),
  2026-07-12, on `bifrost@main` + PR #7 (see the command-authz note).

---

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 17 | build + run all modules |
| Maven | 3.9+ | build (`mvn install`) |
| git | any | clone + the provenance / tamper-evidence gates |
| Docker Desktop | any | the **edge gates** (HiveMQ CE broker on host port 1883) |
| Python | 3.x | the byte-tamper helpers in the T4/T5/T7 gates |

The **pure-CLI gates need no Docker**. The **edge gates** (marked 🐳) start HiveMQ CE via
`docker-compose.yml` and need host port `1883` free.

## Setup

```bash
# 1. clone both repos side by side
git clone https://github.com/yggdrasil-iiot/bifrost.git
git clone https://github.com/LivingLikeKrillin/sparkplug-governance-lab.git

# 2. build bifrost (the governance product + gate CLIs + sim)
cd bifrost
mvn install            # Java 17 · core 223 / heimdall 42 / gates 68 / sim 7 · BUILD SUCCESS

# 3. (edge gates only) make sure Docker Desktop is up and :1883 is free
docker info >/dev/null && echo "docker ready"
```

Every gate is a `scripts/run-*-gate.sh` under `bifrost/`; run each from the `bifrost/` directory.
Exit `0` = the whole gate passed; the script prints `... GATE PASS`.

> **command-authz needs bifrost PR #7.** `run-command-authz-gate` was silently broken on `main` since
> `a98e3cf` migrated value ranges out of `policy.json` into the governed conformance model (the shipped
> policy became type-only, but `PolicyGate` lint-3 still demanded `min`/`max`). **PR #7**
> (`fix/policy-lint-stale-range-constraint`) fixes lint-3 — check it out (or wait for merge) before
> running that one gate. All other gates reproduce on plain `main`.

---

## The headline — the full loop 🐳

**What it proves:** a governed + authorized command actually changes the plant, the change is observed
back through the UNS, and rogue / out-of-range commands are denied — *observe → command → observe*,
all under Bifrost governance (Mímir models → Bifrost governs → Heimdall enforces at the OPC-UA edge →
sim → Muninn → UNS).

```bash
cd bifrost
bash scripts/run-yggdrasil-full-loop-gate.sh
```

**Expected result** (evidence: [`outputs/full-loop.log`](outputs/full-loop.log)):

```
[MIMIR]   Rpm : Double range=[0.0, 3000.0]
[GATE] L1 OK: initial UNS observation Rpm=1535.0
[GATE] L2 OK: authorized command applied + transferred to the instance PV      (NCMD Recipe/Rpm=1500 -> APPLY)
[GATE] L3 OK: the governed+authorized command changed the UNS observation 1535 -> 1500
[GATE] L4 OK: rogue node denied, never applied                                 (Recipe/Secret -> DENY)
[GATE] L5 OK: out-of-range command denied; UNS observation still Rpm=1500.0     (Recipe/Rpm=9999 -> DENY above-max)
[GATE] PASS run-yggdrasil-full-loop-gate.sh
```

---

## The three governance bills

| Bill | Gate | 🐳 | Proves | Evidence |
|---|---|:--:|---|---|
| ① data definition | `run-schema-gate.sh` |  | UDT schema compat: compatible proposal → exit 0, breaking → exit 1 | [log](outputs/run-schema-gate.log) |
| ② command authorization | `run-command-authz-gate.sh` (needs PR #7) |  | deny-by-default policy accepted, `default:"allow"` rejected (lint-1) | [log](outputs/run-command-authz-gate.log) |
| ② command authz (runtime) | `run-ncmd-runtime-gate.sh` | 🐳 | Heimdall re-authorizes NCMD at the edge, deny-by-default | [log](outputs/run-ncmd-runtime-gate.log) |
| ③ data lineage | `run-provenance-gate.sh` |  | git-anchored SHA-256 manifest: verify accept, tamper → reject | [log](outputs/run-provenance-gate.log) |

```bash
bash scripts/run-schema-gate.sh        # ① accept OK (0) / reject OK (1) => PASS
bash scripts/run-command-authz-gate.sh # ② good policy 0 / default:allow 1 => PASS   (bifrost PR #7)
bash scripts/run-provenance-gate.sh    # ③ verify accept / tamper detected => PASS
```

## Conformance (the process-spec model)

| Gate | 🐳 | Proves | Evidence |
|---|:--:|---|---|
| `run-spec-gate.sh` |  | `MasterSpec` conformance: conformant → 0, out-of-range → 1 | [log](outputs/run-spec-gate.log) |
| `run-template-conformance-gate.sh` |  | **site ⊨ enterprise** + a ports-&-adapters core proven standard-agnostic (Ignition / CFIHOS / AAS adapt ≡ native) | [log](outputs/run-template-conformance-gate.log) |
| `run-composable-conformance-gate.sh` | 🐳 | **ONE** `ConformancePolicy` evaluated at BOTH design-time (gate) AND runtime (Heimdall) — the migrated Mixer Rpm range | [log](outputs/run-composable-conformance-gate.log) |

## The activation lifecycle (record-of-what-ran, progressively hardened)

| Tier | Gate | 🐳 | Proves | Cases | Evidence |
|---|---|:--:|---|---|---|
| T3 activation | `run-activation-gate.sh` | 🐳 | four-eyes SoD + content seal + audited rollback + edge bind | A1–A5 | [log](outputs/run-activation-gate.log) |
| T4 lineage | `run-lineage-gate.sh` | 🐳 | hash-chained ledger; edit/delete/reorder detectable; edge fail-closes | LN1–LN4 | [log](outputs/run-lineage-gate.log) |
| T5 identity | `run-identity-gate.sh` | 🐳 | dual Ed25519 signatures + signed head; full-re-chain / tail-truncation caught | I1–I6 (`SKIP_I7=1` skips the broker leg) | [log](outputs/run-identity-gate.log) |
| T6 authZ | `run-activation-authz-gate.sh` | 🐳 | deny-by-default maker-checker; edge revocation bind-fresh | AZ1–AZ7 | [log](outputs/run-activation-authz-gate.log) |
| T7 anchored | `run-anchored-activation-gate.sh` | 🐳 | four-eyes head + external anchor; lone-reanchor & co-rollback caught | AN1–AN7 (`SKIP_AN8=1` skips the broker leg) | [log](outputs/run-anchored-activation-gate.log) |

```bash
SKIP_I7=1 SKIP_AN8=1 bash scripts/run-activation-gate.sh          # A1–A5   => PASS
SKIP_I7=1            bash scripts/run-identity-gate.sh            # I1–I6   => PASS
                    bash scripts/run-lineage-gate.sh            # LN1–LN4 => PASS
                    bash scripts/run-activation-authz-gate.sh   # AZ1–AZ7 => PASS
           SKIP_AN8=1 bash scripts/run-anchored-activation-gate.sh # AN1–AN7 => PASS
```

## The spine

| Gate | 🐳 | Proves | Evidence |
|---|:--:|---|---|
| `run-yggdrasil-spine-gate.sh` | 🐳 | Mímir → Bifrost → Muninn northbound spine, **zero shared code** (composed only over the wire contract) | [log](outputs/run-yggdrasil-spine-gate.log) |

## Measurements (mechanism test-verified; figures are ADR worked examples)

The loss and payload figures the blog cites are **worked examples** in the ADRs; the underlying
**mechanism** is verified by unit tests (run in this lab, not bifrost):

```bash
cd sparkplug-governance-lab
mvn test -Dtest=LossLedgerTest,UaDataTypeMapperTest      # BUILD SUCCESS
```

- **loss ledger** (`svg/loss-ledger.svg`, ADR-0010): the 9-member example = **8 clean / 1 side-channel
  preserved / 0 type-identity lost**. `LossLedgerTest` verifies the `CLEAN / PRECISION_LOSS /
  TYPE_IDENTITY_LOSS / SIDE_CHANNEL_REQUIRED` classification and the side-channel preservation
  (`ua_ticks` / `ua_statuscode`). Evidence: [`outputs/lab-measurements.log`](outputs/lab-measurements.log).
- **NBIRTH payload** (`svg/nbirth-size.svg`, ADR-0008): schema/data separation = **328 B inline vs
  162 B thin (schemaRef), ~166 B / birth saved**. `UaDataTypeMapperTest` verifies the type mapping and
  loss-class the thin representation relies on.

---

## Full result matrix (controller-run 2026-07-12)

| # | Experiment | 🐳 | Result |
|---|---|:--:|---|
| 1 | full-loop (headline) | 🐳 | ✅ PASS — 1535 → (auth NCMD 1500) → 1500 · rogue + above-max DENY |
| 2 | schema ① |  | ✅ PASS |
| 3 | command-authz ② (PR #7) |  | ✅ PASS |
| 4 | ncmd-runtime ② | 🐳 | ✅ PASS |
| 5 | provenance ③ |  | ✅ PASS |
| 6 | spec conformance |  | ✅ PASS |
| 7 | template conformance (site ⊨ enterprise) |  | ✅ PASS |
| 8 | composable conformance | 🐳 | ✅ PASS |
| 9 | activation T3 | 🐳 | ✅ PASS (A1–A5) |
| 10 | lineage T4 | 🐳 | ✅ PASS (LN1–LN4) |
| 11 | identity T5 | 🐳 | ✅ PASS (I1–I6) |
| 12 | activation-authz T6 | 🐳 | ✅ PASS (AZ1–AZ7) |
| 13 | anchored T7 | 🐳 | ✅ PASS (AN1–AN7) |
| 14 | spine | 🐳 | ✅ PASS |
| — | measurements (loss / nbirth) |  | ✅ mechanism test-verified; figures = ADR examples |

Honest scope (unchanged from the product's own README): single broker / edge / instance / localhost;
the sim's transfer is instant setpoint = PV (a governance loop, not process physics).
