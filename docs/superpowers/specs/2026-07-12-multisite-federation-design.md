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

Multi-site federation addresses (1) directly and **relocates (2) into a separate trust domain** — and does
so by **recombining primitives that already exist**, not by building a new tower.

> **Honest framing of the T7 relationship (do not overstate).** In the single-machine PoC the "enterprise"
> anchor is a *local* git repo: `GitAnchorStore` does a local `commit` and reads local `HEAD` — there is no
> push, no protected remote, no fetch-before-verify. A site insider who owns the machine could still rewrite
> it. So federation **moves the anchor witness out of the site's own registry into the enterprise trust
> domain** (a different owner/host in production), which is the correct *architecture* for closing the
> residual — but true closure still needs the enterprise anchor to be a genuinely protected off-box remote,
> which stays out of scope exactly as in T7. F5 demonstrates the federation shape + cross-domain rollback
> detection, not cryptographic un-rewritability.

## 2. Thesis

**A single enterprise governance authority; per-site brokers + edges that consume it; local-first.**

- **Broker-per-site is the correct OT topology** (WAN autonomy, latency, blast-radius isolation, IEC-62443
  zoning). Each site runs its own broker + Heimdall + a **local mirror** of its governed scope, and keeps
  running when the site↔enterprise link drops.
- **The enterprise layer is the natural off-box anchor witness** for each site's activation ledger →
  **federation relocates T7's residual into the enterprise trust domain**: a site insider who rolls back the
  site's local ledger + head + local anchor still faces the **enterprise** anchor — a different trust
  domain — which witnesses the higher `seq`, so the co-rollback is caught at the next bind / on enterprise
  audit. (In the single-machine PoC that "different domain" is a separate repo the gate script never
  rewrites; true un-rewritability needs a protected off-box remote, out of scope exactly as in T7 — see §1.)

So multi-site is not "just scale" — it is an **architecturally security-relevant** evolution: it moves the
anchor witness to where it belongs (the enterprise), rather than adding a new mechanism.

## 3. The three settled design axes

### 3.1 Authority model — central authority + per-site local cache (A)

Bifrost stays the **single registry-of-record authority for templates + policy** (thesis unchanged). The
enterprise governs and publishes those; each site holds a **local git mirror** and enforces them with its own
Heimdall. **Activation ledgers are a different governed object** — they are **per-site** (each site activates
its own versions in its own ledger, under enterprise policy), and this is *not* a contradiction of "single
authority": the enterprise owns the *rules* (templates + policy), the site owns its *events* (which authored
versions it activated), federated up for audit (§3.2). Rejected: per-site autonomous *authorities* that
reconcile *policy* — that would break the single-registry-of-record invariant and make cross-site rule
consistency a distributed-consensus problem.

### 3.2 What federates

| Object | How it federates |
|---|---|
| **Templates** (equipment / spec models) | enterprise owns the standard template; each site specializes; governed by **site ⊨ enterprise (T1)** |
| **Policy** (command + activation authZ) | enterprise governs; each site enforces **the rules keyed to its own edge** (rules carry `target group/edge`; a site simply fires the ones matching its `SPB_GROUP`/`SPB_EDGE`) |
| **Identity trust** (authorized-keys + activation-policy) | enterprise governs `authorized-keys.jsonl` (T5 trust anchor) + `activation-policy.json` (T6 deny-by-default); both federate **down** with the mirror, so a site can only activate with keys/roles the enterprise sanctioned |
| **Activation ledger** | **per-site** (each site activates its own versions, T6 authZ under enterprise policy) + **federated up** for enterprise audit and the T7 off-box anchor |

> **Scope note (confidentiality).** The PoC ships the *whole* enterprise registry to each site's mirror
> (simplest; a site reads only the slice its edge matches). That means site A can *read* site B's specs. True
> per-site confidentiality (mirror only the site's subtree) is a refinement, out of scope for the PoC — noted
> so the plan doesn't mistake "reads its slice" for "cannot see the rest."

### 3.3 Transport + local-first

- **Governance down:** each site's registry is a **full git mirror clone** of the enterprise governed
  registry (templates + policy + `authorized-keys.jsonl` + `activation-policy.json`); sites `git pull`. A
  full local clone ⇒ the site survives a WAN outage and reconciles on reconnect (`git fetch`).
- **Data / audit up:** each site's Muninn feeds the enterprise UNS (northbound data); each site's activation
  ledger + anchor records **federate up** to the enterprise (audit + off-box witness).
- **The enterprise anchor** is a git AnchorStore repo in the enterprise trust domain; a site **appends**
  anchor records (T7 `record()` enforces monotonic `seq`). In the single-machine PoC the "cannot rewrite"
  property is **topological** (a separate repo the site's rollback script never touches), *not*
  cryptographically enforced — production pins it to a protected remote (push-only, protected refs). This is
  the honest boundary from §1; the plan must not claim the PoC proves un-rewritability.
- **Single control-plane writer.** `GitAnchorStore` is documented single-writer and is not concurrency-safe
  against simultaneous commits; the federation gate script drives the two sites **sequentially**, so this
  holds. Concurrent multi-site anchor writes are out of scope.

## 4. What the PoC demonstrates — F1–F6

Two sites (A, B) + one enterprise, realized on one machine (§6). A federation gate script asserts:

- **F1 — enterprise template governs both sites.** Enterprise publishes template `v1.0.0`; A and B each pull
  it, specialize, and their specializations pass **site ⊨ enterprise (T1)**; a non-conforming site
  specialization is **rejected**.
- **F2 — governance propagation.** Enterprise updates the template/policy → both sites `git pull` → the
  change takes effect at each site's **next Heimdall (re)start**. (Heimdall reads policy/conformance/ledger/
  anchor once, at process start — there is no hot reload; the gate script restarts the site's Heimdall to
  apply the pulled change. Say "restart," not "bind," in the plan.)
- **F3 — per-site independent activation + enforcement.** A activates version X (its own ledger, T6 authZ)
  and A's Heimdall enforces X; B is independent. A command at A is governed by A's slice; a rogue at B is
  denied **independently**.
- **F4 — local-first (WAN outage).** Cut A↔enterprise → A keeps observing / commanding / enforcing on its
  local mirror + broker + Heimdall; on reconnect it reconciles (`git fetch`, ledger federates up).
- **F5 — cross-domain anchor rollback detection (the federation shape of T7).** A site-A insider rolls back
  A's local ledger + head + local anchor to an older snapshot; A's verify is pointed at the **enterprise**
  anchor (a different trust domain, a different repo) which still witnesses the higher `seq` → A's next
  Heimdall (re)start / an enterprise audit reports `identity.anchor.rollback`. This is the *federation
  architecture* T7 pointed at — the anchor lives with the enterprise, not the site's own registry. **Honest
  delta over the existing single-site AN3 test:** the novelty is *topological* (the witness is now in the
  enterprise domain across a site boundary), **not** a stronger cryptographic guarantee — un-rewritability is
  still the §1 residual. State this in the gate output so the demo doesn't over-claim.
- **F6 — enterprise federated audit.** The enterprise aggregates both sites' activation ledgers into a
  single cross-site audit view (who activated what, where, when).

Together these prove **enterprise (not single-line) governance + per-site autonomy / local-first + the
federation shape of the T7 off-box anchor** (witness relocated to the enterprise domain; un-rewritability
still the §1 residual).

## 5. Components

Additive; most of this is **wiring existing pieces**, not new code.

- **bifrost — unchanged.** `core` / `gates` / `heimdall` / `GitAnchorStore` (T7) / the T1 template gate are
  reused verbatim.
- **Enterprise layer (new setup, ~no new core code):**
  - the **central governed registry** = a git repo (templates + policy) — the authority.
  - the **enterprise anchor** = a git AnchorStore repo the sites append to but (topologically, per §3.3)
    do not rewrite.
- **Site A, Site B (each):** own **broker** (e.g. `:1883` / `:1884`), own **Heimdall**, own **git-mirror
  clone** of the enterprise registry, own **activation ledger** anchored to the **enterprise** anchor.
- **Small new code (honestly enumerated — not zero):**
  - a `federation audit` gate CLI that aggregates the two sites' activation ledgers into one cross-site
    view (F6).
  - **sim endpoint parameterization** — the OPC-UA sim currently hardcodes its bind port
    (`EmbeddedMiloSim.BIND_PORT = 48400`, `localhost`); running two sims on distinct endpoints (§6) needs a
    small change to thread a port/host through `SimMain` → `EmbeddedMiloSim` (~10 lines). Not "pure wiring."
  - the `run-federation-gate.sh` staging script itself + a second-broker compose service.
- **Identity-trust staging (no new code, but must be provisioned — F3/F5 depend on it).** The signed/anchored
  paths require, per site: an activator + approver **keypair** (`identity keygen`), those keys registered in
  the enterprise-governed `authorized-keys.jsonl`, and matching `activation-policy.json` grants (T6). The
  existing single-site `run-anchored-activation-gate.sh` already does this provisioning; the federation
  script federates it down to both sites. List it so the plan doesn't discover it mid-build.
- Everything else is wiring + the federation gate script.

## 6. Build shape (one machine, honest 2-sim-site)

- Two broker instances — add a **second `hivemq-ce` service on `:1884`** to `docker-compose.yml` (the compose
  today ships one broker); this second service is an explicit deliverable. Two Heimdall daemons, two OPC-UA
  sims on distinct endpoints (`:48400` / `:48401`, requires the §5 sim-port change) — the honest 2-site
  topology on localhost.
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
git-provenance rather than adding a tower, and it **carries T7's anchor witness into the enterprise trust
domain** — the correct architecture for the residual, honestly short of full off-box un-rewritability (§1).
The alternative bar-raisers (process physics, HA cluster, real PLC) either sit outside the governance thesis
or are pure deployment hardening.
