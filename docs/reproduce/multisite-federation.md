# Reproduction — Multi-site federation gate

**What it proves:** enterprise (multi-site) governance on one machine. Two per-site brokers + edges consume
**one** federated Bifrost authority, built by recombining existing primitives (T1 template conformance,
T7 git anchor, per-site Heimdall). This is the PoC-stage demonstration of the "per-site-federated scope"
the governed model claims — the loop actually runs across a *site boundary*, not a single line.

> **This doc lives with the federation spec/plan** (`docs/superpowers/specs/2026-07-12-multisite-federation-design.md`,
> `docs/superpowers/plans/2026-07-12-multisite-federation.md`). The rest of the reproduction package
> (master `README.md`, other deep-dives) currently sits on a separate lab branch; when the branches are
> reconciled, add the row below to that master index.

---

## Topology (what the gate stages)

All on one machine, under `bifrost/build/fed-gate/`:

- **enterprise/** — the governed git registry = **the authority** (templates + command policy + identity
  trust `authorized-keys.jsonl` + T6 `activation-policy.json` + recipe artifacts). `git init` + commit.
- **enterprise-anchor/{busan,ulsan}/** — the enterprise **off-box anchor domain**, one git anchor repo per
  site (separate from the site clones). Per-site because a T7 anchor's `seq` is monotonic *per target* and
  both sites activate the same target `Line1`.
- **site-busan/**, **site-ulsan/** — full **git mirror-clones** of the enterprise registry (local-first;
  each survives a WAN outage and reconciles with `git pull`/`fetch`).

Identity trust (`authorized-keys.jsonl` + `activation-policy.json`) federates **down** with the mirror;
each site's activation ledger is **per-site** and anchored **up** to its enterprise anchor.

---

## Prerequisites

- The `bifrost` repo checked out next to this lab (`../bifrost` from the lab, or wherever your Yggdrasil
  product tree lives). The gate is a bifrost script — **the lab consumes it** (verify-then-trust).
- JDK 17+, Maven, Git (Git-Bash on Windows — the gate uses `cygpath -m`), Python (test-mutation helpers).
- **Docker Desktop running** for the runtime legs (F2/F3/F4). Without Docker they are skipped cleanly and
  the pure-CLI legs (F1/F5/F6) still run.

---

## Run it

From the `bifrost` repo root:

```bash
# pure-CLI legs only (F1 F5 F6) — fast, no Docker needed
SKIP_RUNTIME=1 bash scripts/run-federation-gate.sh

# full run (adds F2 F3 F4: two brokers + two sims + two Heimdalls) — needs Docker
bash scripts/run-federation-gate.sh
```

Expected final line:

```
[FED] GATE PASS (F1 F5 F6 +F2 F3 F4)     # full run
[FED] GATE PASS (F1 F5 F6)               # SKIP_RUNTIME=1
```

The full captured output of a real controller run is in
[`outputs/federation-gate.log`](outputs/federation-gate.log).

---

## The six assertions

| # | Claim | How it's proven | Always runs? |
|---|-------|-----------------|:---:|
| **F1** | Enterprise template governs both sites | Each site's *conforming* specialization (Rpm tightened to 0–2500 within the 0–3000 envelope) passes `gates template` (site ⊨ enterprise, T1); a *non-conforming* one (Rpm widened to 4000) is **rejected** with `template.range.exceeds-envelope` (exit 1). | ✅ |
| **F5** | Cross-domain anchor rollback is caught | A site insider co-rolls-back busan's local ledger+head to `seq0`; the **enterprise** anchor (a separate repo) still witnesses `seq1` → `gates identity verify-anchored --anchor-store git` reports `identity.anchor.rollback` (exit 1). | ✅ |
| **F6** | Enterprise federated audit | `gates federation audit Line1 --site busan=… --site ulsan=…` aggregates both ledgers into one view: `busan active=1.0.0 (1 event)`, `ulsan active=1.1.0 (2 events)`. | ✅ |
| **F3** | Per-site independent activation + enforcement | Two Heimdalls each bind **their own** activated version (`busan@1.0.0`, `ulsan@1.1.0`); a rogue command at one site is denied in *its* log and never appears in the other's (physical isolation on separate brokers/groups). | Docker |
| **F2** | Governance propagation | Enterprise revokes busan's Rpm authorization and commits; busan `git pull`s; on Heimdall **restart** the same Rpm command that previously **APPLYed** is now **DENYed**. | Docker |
| **F4** | Local-first (WAN outage) | The enterprise repo is moved away (link cut): ulsan's `git fetch` fails, yet its broker+sim+Heimdall keep enforcing a fresh command locally; restoring the repo lets `fetch` reconcile. | Docker |

---

## Honest scope — read this (do not overstate F5)

F5 is the existing single-site anchored-gate assertion (`AN3`) **re-run with the anchor relocated to the
enterprise trust domain**. That relocation is the correct *architecture* for T7's residual, but on one
machine the enterprise anchor is still a *local* git repo: the "cannot rewrite" property is **topological**
(a separate repo the site's rollback script never touches), **not cryptographic**. A machine-owning insider
could still rewrite it. True closure needs the enterprise anchor to be a genuinely protected off-box remote
(push-only, protected refs) — **out of scope, exactly the same honest residual as T7.** The gate prints this
caveat on every run; the reproduction is honest that federation **relocates** the residual, it does not
**close** it.

Also deliberately out of scope (this is a governance PoC, not deployment-scale): real PLCs, HA broker
cluster, aggregate load, a real WAN. Two sim-sites on localhost.

---

## What each leg actually exercises (new vs reused)

- **Reused verbatim** (the point — federation is recombination, not a new tower): `gates template` (T1),
  `gates activate`/`verify-anchored` + `GitAnchorStore` (T5/T7), per-site Heimdall bind + edge authz, the
  embedded OPC-UA sim, HiveMQ CE.
- **New for federation** (small, honestly enumerated): the OPC-UA sim's endpoint is now configurable
  (`SIM_BIND_PORT`/`SIM_BIND_HOST`) so two sims run at once; a second broker `hivemq-ce-b:1884`; the
  `gates federation audit` CLI (F6); and the gate scripts themselves
  (`scripts/run-federation-gate.sh` + `scripts/_federation-runtime.sh`).
