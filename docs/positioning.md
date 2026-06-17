# Field validation & positioning

An honest, source-cited assessment of where this lab sits against real
industrial-IoT practice: which problems it targets that are genuinely felt,
where existing tools already overlap, and what it deliberately does **not** do.

This document is written to be **falsifiable, not promotional**. It was built
from an adversarial multi-source review (claims that failed verification were
dropped, and thin-evidence legs are flagged below). Where a claim rests on a
vendor's own docs, that is noted — vendor docs are authoritative for what a
product *does*, but their problem statements are self-serving.

---

## 1. The problems are real — not invented

The lab targets four governance problems. Three are independently corroborated;
one is acknowledged by the Sparkplug standard itself.

### Command (NCMD) authorization — the strongest validation

The Sparkplug B specification **explicitly defers** command security to
application designers and marks its entire security chapter as *non-normative*
("guidance only"). The only broker-level control it defines is an MQTT **topic
ACL at node granularity** (e.g. `Subscribe: spBv1.0/G1/NCMD/E1`). But the NCMD
topic `spBv1.0/{group}/NCMD/{edge}` **carries no command identity** — the
command name and value live in the protobuf payload. So a topic ACL can gate
*node-level reachability* ("may this identity command this node at all?") but
**cannot** authorize an individual command or value range.

This is not the lab's invention: it is an **acknowledged gap in the standard**,
now an open upstream feature issue — [eclipse-sparkplug/sparkplug#600
"Add support for authorization to CMD messages"](https://github.com/eclipse-sparkplug/sparkplug/issues/600).
The lab's contribution here (deny-by-default per-command/per-value authorization,
edge-enforced, projected from one policy source) is written up in
[ADR-0011](adr/ADR-0011-command-authorization.en.md) and proposed upstream in
[`upstream/sparkplug-600-ncmd-authorization.md`](upstream/sparkplug-600-ncmd-authorization.md).

### OPC UA → Sparkplug semantic loss is structural

OPC UA's address-space and information modeling is structurally richer than
Sparkplug's flat metric/UDT model; a faithful mapping cannot preserve all
references, methods, and type semantics, so **loss is real**. The lab's response
is to make that loss a *first-class, enumerated output* (a per-member loss
ledger with verbatim side-channel preservation) rather than hide it — see
[ADR-0010](adr/ADR-0010-opcua-udt-mapping.en.md).

> **Guardrail:** the OPC UA ↔ Sparkplug asymmetry is *structural*, not a clean
> "N-vs-M data-type ratio." We make **no numeric type-count claim** (a specific
> "~60 vs ~18 types" framing was checked and rejected as inaccurate).

### Schema evolution & runtime drift

Existing UNS platforms validate *structural* message conformance, but
**versioned compatibility** (FORWARD/BACKWARD/FULL, in the Confluent
Schema-Registry sense) and **runtime drift detection** against a registry of
record are not standard UNS tooling. The lab implements both
([ADR-0007](adr/ADR-0007-schema-registry-gate.en.md),
[ADR-0012](adr/ADR-0012-runtime-drift-detection.en.md)).

> **Honesty note:** the *tooling gap* is established, but we did not find a named
> practitioner directly calling *versioned schema-evolution* "the pain." This is
> the thinnest leg of the problem-validation case — it is inferred from the
> tooling gap plus the Confluent-world precedent, not from a direct field quote.

---

## 2. Landscape — what is covered, what is not

Honest counter-evidence included: parts of this space are already served.

| Tool / tier | Covers | Does **not** cover |
|---|---|---|
| **Broker tier** — HiveMQ [Sparkplug-Aware Extension](https://github.com/hivemq/hivemq-sparkplug-aware-extension), [EMQX](https://docs.emqx.com/en/emqx/latest/data-integration/sparkplug.html) | Transport/retention only: persists NBIRTH/DBIRTH as retained topics, fixes NDEATH timestamps, resolves aliases | Schema validation/evolution, command authorization, OPC UA mapping, drift detection — **no payload governance** |
| **United Manufacturing Hub** ([data contracts](https://umh.docs.umh.app/docs/datacontracts/historian/)) | A real payload-schema **compatibility gate** (validate-and-drop against a JSON Schema), fixed ISA-95 naming | **Versioned** semantic evolution (FORWARD/BACKWARD/FULL); also rejects Sparkplug B for its UNS model |
| **HighByte Intelligence Hub** ([4.3 notes](https://www.highbyte.com/resources/release-notes/version-4-3), [namespaces](https://www.highbyte.com/intelligence-hub/namespaces)) | Imports OPC UA NodeSets as models; centralizes UNS-structure governance | Schema-evolution/compatibility, an OPC UA **loss ledger**, NCMD write-authorization, runtime drift |

**The honest read:** the broker tier *structurally cannot* do per-command
authorization with native MQTT/ACLs — that premise holds. But UMH and HighByte
each already cover **one** of the lab's primitives. So the lab's defensible
differentiation is **the integrated set plus explicit accounting** —
versioned compatibility vocabulary **+** per-command deny-by-default **+** an
OPC UA loss ledger **+** drift-closure as one coherent governance loop — **not**
the novelty of any single primitive in isolation. Claims of "first to do X"
for any one primitive would not survive scrutiny; the combination is the claim.

---

## 3. What this lab deliberately does **not** do (scope, not naivety)

The lab is single-broker, single-node, with a JSON-file registry, at-least-once
delivery, and no sequence reordering. These are **deliberate, documented scoping
choices** that isolate the *governance* questions (semantic / data-contract)
from the *infrastructure* questions (HA / throughput / delivery semantics).

The production gap is concrete and quantifiable. HiveMQ's own documentation
establishes that production deployments require **multi-node HA clusters**
("no single point of failure… indispensable for high availability";
"highly recommend HiveMQ clusters for your production IoT deployments") — so a
single broker has, by definition, **no failover**. A production system would
additionally need the (spec-non-normative but operationally mandatory) security
layer (TLS, authN/Z), a real schema registry rather than a JSON file, and
ordering/exactly-once guarantees. None of these is attempted here, and the lab
says so.

**No production track record.** This is a personal proof-of-concept. It has not
run in production and makes no such claim. Its value is *characterizing real
governance problems and enforcing answers as working, tested code* — not
operational maturity.

---

## 4. Calibrated verdict

- **"Niche" — rebutted.** The four targeted problems are documented industrial
  pain. The strongest leg: command authorization is left *non-normative by the
  Sparkplug specification itself* (security deferred to application designers),
  and the gap is independently corroborated by an open upstream issue (#600,
  contributor-filed). The domain is under-tooled, not irrelevant.
- **"Small-scale / PoC" — reframed.** The single-broker / JSON-registry limits
  are deliberate scoping against a quantifiable production gap, stated plainly —
  not naivety. (This reframing only holds *because* the limits are explicit.)
- **"No operational track record" — stands.** True and unrebuttable for a
  personal PoC. The only honest way to move it is real-world engagement, which
  is why the NCMD-authorization work is being taken upstream (§1, #600).

## 5. Where the evidence is thin (so you don't over-claim)

- **No quantitative adoption figures** survived verification. We argue
  *relevance*; we do **not** quantify Sparkplug/UNS growth or claim a trajectory.
- **Versioned schema-evolution as named practitioner pain** is inferred, not
  directly quoted (see §1 honesty note).
- **Ignition + MQTT Engine/Transmission and Cirrus Link modules** were not
  verified for per-command authorization or UDT versioning — a known hole in the
  landscape comparison above.
- The upstream issue #600 is, as of this writing, an early title-only stub; its
  weight as "upstream-acknowledged remediation" is real but should not be
  overstated.

## Sources

Primary (normative / product docs): Eclipse Sparkplug 3.0 specification
(Operational Behavior ch. 5, Security ch. 7); eclipse-sparkplug/sparkplug#600;
HiveMQ Sparkplug-Aware Extension; HiveMQ cluster documentation; EMQX Sparkplug
integration; UMH historian data contracts; HighByte 4.3 release notes /
namespaces. Secondary (vendor/practitioner blogs, lower weight): EMQX & HiveMQ
protocol comparisons; A. Hulshout, "The Unified Namespace — the remaining
questions." Full URLs are linked inline above.
