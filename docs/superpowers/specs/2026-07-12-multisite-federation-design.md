# Multi-site federation — per-site brokers under one federated governance authority (Design)

**Status:** design approved via brainstorming dialogue (Eisen, 2026-07-12) — ready for spec review
**Track:** Yggdrasil-IIOT, follow-on that raises the PoC from single-line to **enterprise (multi-site) governance**
**Builds on (all already exist):** T1 site ⊨ enterprise template conformance · git-anchored provenance · per-site Heimdall edge · T7 anchored activation (`GitAnchorStore`) · the per-site-federated scope the model already claims

---

## 1. Problem — the honest single-site limit

The PoC today runs one line: one broker, one edge, one Heimdall, one governed registry. Two honest gaps
follow:

1. **"Enterprise governance" is claimed but only shown for a single line.** The model asserts a
   *per-site-federated scope* (an enterprise authority governing many sites), but the demonstration never
   spans more than one site — so "enterprise" is asserted, not proven.
2. **T7's anchor residual is still open.** T7 made rollback *evident* but its honest residual was: a
   `FileAnchorStore`/local git repo is **on-box** — a co-rollback that also rewrites the local anchor is
   undetectable; the anchor needs an **off-box** witness (protected remote / TPM) to be real.

Multi-site federation closes both — and does so by **recombining primitives that already exist**, not by
building a new tower.

## 2. Thesis

**A single enterprise governance authority; per-site brokers + edges that consume it; local-first.**

- **Broker-per-site is the correct OT topology** (WAN autonomy, latency, blast-radius isolation, IEC-62443
  zoning). Each site runs its own broker + Heimdall + a **local mirror** of its governed scope, and keeps
  running when the site↔enterprise link drops.
- **The enterprise layer is the natural off-box anchor witness** for each site's activation ledger →
  **federation closes T7's residual**: a site insider who rolls back the site's local ledger + head +
  local anchor cannot rewrite the enterprise anchor (outside the site's control), which still witnesses the
  higher `seq` → the co-rollback is caught at the next bind / on enterprise audit.

So multi-site is not "just scale" — it is a **security-completing** architectural evolution.

## 3. The three settled design axes

### 3.1 Authority model — central authority + per-site local cache (A)

Bifrost stays the **single registry-of-record authority** (thesis unchanged). The enterprise governs and
publishes the templates + policy; each site holds a **local git mirror** of its governed scope and enforces
it with its own Heimdall. Activation is **per-site** (each site activates its own versions in its own
ledger, under enterprise policy). Rejected: per-site autonomous authorities that reconcile — that breaks the
single-registry-of-record invariant and makes cross-site consistency a distributed-consensus problem.

### 3.2 What federates

| Object | How it federates |
|---|---|
| **Templates** (equipment / spec models) | enterprise owns the standard template; each site specializes; governed by **site ⊨ enterprise (T1)** |
| **Policy** (command + activation authZ) | enterprise governs; each site enforces its **scoped slice** (rules are keyed by `target group/edge`, so per-site scoping is natural) |
| **Activation ledger** | **per-site** (each site activates its own versions, T6 authZ under enterprise policy) + **federated up** for enterprise audit and the T7 off-box anchor |

### 3.3 Transport + local-first

- **Governance down:** each site's registry is a **git mirror clone** of the enterprise governed registry;
  sites `git pull`. A full local clone ⇒ the site survives a WAN outage and reconciles on reconnect
  (`git fetch`).
- **Data / audit up:** each site's Muninn feeds the enterprise UNS (northbound data); each site's activation
  ledger + anchor **federate up** to the enterprise (audit + off-box witness).
- **The enterprise anchor** is a git AnchorStore repo owned by the enterprise; a site may **append** its
  anchor records but cannot **rewrite** the enterprise anchor's history — that is the off-box property.

## 4. What the PoC demonstrates — F1–F6

Two sites (A, B) + one enterprise, realized on one machine (§6). A federation gate script asserts:

- **F1 — enterprise template governs both sites.** Enterprise publishes template `v1.0.0`; A and B each pull
  it, specialize, and their specializations pass **site ⊨ enterprise (T1)**; a non-conforming site
  specialization is **rejected**.
- **F2 — governance propagation.** Enterprise updates the template/policy → both sites `git pull` → the
  change takes effect at each site's **next Heimdall bind**.
- **F3 — per-site independent activation + enforcement.** A activates version X (its own ledger, T6 authZ)
  and A's Heimdall enforces X; B is independent. A command at A is governed by A's slice; a rogue at B is
  denied **independently**.
- **F4 — local-first (WAN outage).** Cut A↔enterprise → A keeps observing / commanding / enforcing on its
  local mirror + broker + Heimdall; on reconnect it reconciles (`git fetch`, ledger federates up).
- **F5 — cross-site anchor rollback detection (the T7 closer).** A site-A insider rolls back A's local
  ledger + head + local anchor to an older snapshot; the **enterprise anchor** (outside A's control) still
  witnesses the higher `seq` → A's next bind / an enterprise audit reports `identity.anchor.rollback`.
- **F6 — enterprise federated audit.** The enterprise aggregates both sites' activation ledgers into a
  single cross-site audit view (who activated what, where, when).

Together these prove **enterprise (not single-line) governance + per-site autonomy / local-first + the
T7-closing off-box anchor**.

## 5. Components

Additive; most of this is **wiring existing pieces**, not new code.

- **bifrost — unchanged.** `core` / `gates` / `heimdall` / `GitAnchorStore` (T7) / the T1 template gate are
  reused verbatim.
- **Enterprise layer (new setup, ~no new core code):**
  - the **central governed registry** = a git repo (templates + policy) — the authority.
  - the **enterprise anchor** = a git AnchorStore repo the sites append to but cannot rewrite.
- **Site A, Site B (each):** own **broker** (e.g. `:1883` / `:1884`), own **Heimdall**, own **git-mirror
  clone** of the enterprise registry, own **activation ledger** anchored to the **enterprise** anchor.
- **Small new code:** a `federation audit` gate CLI that aggregates the two sites' activation ledgers into
  one cross-site view (F6). Everything else is wiring + the federation gate script.

## 6. Build shape (one machine, honest 2-sim-site)

- Two broker instances (two HiveMQ CE containers on `:1883` / `:1884`, or two compose stacks), two Heimdall
  daemons, two OPC-UA sims (different endpoints) — the honest 2-site topology on localhost.
- A `run-federation-gate.sh` staging: an enterprise git registry + enterprise anchor repo; two site clones;
  two sites' Heimdall/broker/sim; then the F1–F6 assertions. Controller-run + added to the reproduction
  package.
- **WAN outage (F4)** is simulated by cutting the site's git-sync / stopping its enterprise link — the site's
  local stack keeps running.

## 7. Honest scope (still out — deliberately)

- **Real PLCs, HA broker cluster, aggregate load, real WAN** remain out of scope: two sim-sites on one
  machine. This proves the **federation governance mechanics** + the **T7 cross-site anchor**, not
  deployment-scale operation. (HA is a broker-choice matter — HiveMQ CE is single-node; a real cluster would
  use EMQX / HiveMQ Enterprise; that is deployment hardening, not the governance thesis.)
- The enterprise anchor's real off-box protection (a genuinely separate host / protected remote / signed
  tags) is *demonstrated by topology* (the enterprise repo is outside the site's write control in the PoC)
  but production would pin it to a protected remote — same honest residual as T7, now one level up.

## 8. Why this is the right next bar-raiser

It is the **one** extension that raises the PoC from "single line" to "enterprise governance" **without
diluting the thesis** (it is governance, not control or deployment-scale), it **reuses** T1 + T7 +
git-provenance rather than adding a tower, and it **closes T7's honest residual** as a side effect. The
alternative bar-raisers (process physics, HA cluster, real PLC) either sit outside the governance thesis or
are pure deployment hardening.
