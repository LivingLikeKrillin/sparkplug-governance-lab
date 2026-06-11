# ADR-0010 — OPC UA information model → Sparkplug UDT mapping governance (English translation)

- Status: **Accepted (PoC)**
- Date: 2026-06-10
- Relations: reuses the ADR-0007 registry as the UDT authority and the ADR-0008 (#608/#607/#603) Definition/thin codec as the wire; consistent with ADR-0005 (UDT SemVer); cross-referenced from the namespace standard §2 (ISA-95) / §5 (UDT). Code `src/.../opcua/`, demo `OpcUaUdtBridgeDemo.java`. *(Original Korean: [ADR-0010-opcua-udt-mapping.md](ADR-0010-opcua-udt-mapping.md))*

## Context

For an enterprise UNS to carry OT semantics first-class, the OPC UA **information model** (ObjectType subtype inheritance + `HasInterface` multiple inheritance + NodeIds + EURange/EngineeringUnits + 32-bit StatusCodes + 100 ns/1601 DateTime) must be mapped onto Sparkplug **UDTs** (flat templates without inheritance, edge-local integer aliases, ms/1970 DateTime, 3-state quality). The two type systems differ in expressive power, so this mapping is **not lossless**. Most Sparkplug/UNS practitioners come from the IT/MQTT side and never touch the OPC UA type system — this ADR governs that hardest OT↔IT seam with **working code plus an explicit loss ledger**.

The PoC demonstrates the full round trip live: Milo browse → `OpcUaTypeMapper` → `UdtDefinition` + `LossLedger` → ADR-0008 #608-style retained Definition + thin NDATA → consumer typed view + side-channel recovery (demo exit 0).

## Decision

### 1. The loss boundary is a first-class output (not lossless)

- The DataType mapping (`UaDataTypeMapper`) assigns each member a **LossClass** (CLEAN / PRECISION_LOSS / TYPE_IDENTITY_LOSS / SIDE_CHANNEL_REQUIRED), aggregated by a `LossLedger`. The demo prints a per-member ledger plus a summary (e.g. "9 members: 8 clean / 1 side-channel preserved / 0 type-identity lost") — honesty as a visible artifact.
- **Lossless truth is preserved verbatim in side-channel properties:**
  - `ua_statuscode` (UInt32) — the original 32-bit StatusCode. Even an Uncertain that the quality projection (#603-style) flattens to GOOD is recoverable here from the severity bits. Demo: `ua_statuscode=0x40000000 severity=Uncertain (quality projection=GOOD; the truth is in ua_statuscode)`.
  - `ua_ticks` (Int64) — the original 100 ns/1601 ticks of DateTime members. Sparkplug ms/1970 loses 10,000× precision plus the epoch, so sequence-of-events / sub-ms fidelity is recovered from these ticks.
- The side-channel is built by a new pure `OpcUaThinCodec` as an extended PropertySet **without touching** the ADR-0008 `ThinCodec` (its signatures/tests unchanged).

### 2. Flattening rules (`TypeFlattener` — the core governance logic)

Deterministic member order = ① target's own members (declaration order) → ② supertype chain's own members (upward) → ③ each interface in `HasInterface` order (including interface supertype chains). This order is the **alias input** (ADR-0008's `alias = i + 1`).

- **Subtype override = most-derived wins:** if the same member name exists on both the derived and a supertype, the most derived declaration's type/engineering info is adopted (position fixed), with both OWN and SUPERTYPE recorded in provenance. **Not a conflict.**
- **Interface dedup:** two interfaces declaring the same member with the same type → a single `FlatMember` (provenance = both INTERFACEs). **On a type conflict**, a `Conflict` is surfaced with a **deterministic fallback** (first occurrence adopted, conflict recorded — never dropped).
- **ObjectType-own wins:** when an ObjectType member collides with an interface member of the same name, own wins (if OWN/SUPERTYPE is involved it is an override, not a conflict).

### 3. Alias = flattening order

Aliases are `i + 1` in the deterministic flattening order (isomorphic to ADR-0008's `DefinitionCodec.aliasOf`, round-trip verified). An alias is an edge-local within-BIRTH identifier, not a portable NodeId. Alias stability is a governance policy (defending consumers that cache across a missed rebirth + stable historian keys), not a correctness invariant.

### 4. The OT-side implementation of the #608/#607/#603 convergence

- #608 Definition ≅ how OPC UA separates TypeDefinition from instances → the ADR-0007 registry is the authority, the retained Definition is the wire truth.
- #607 engUnit/engLow/engHigh ↔ OPC UA `EngineeringUnits` (EUInformation) / `EURange`.
- #603 quality (GOOD/STALE/BAD) ↔ a **lossy projection** of StatusCode severity (see limits below).

## Known limits / out of scope (stated honestly)

- **Diamond-inheritance traversal limit (OUT OF SCOPE, documented):** `TypeFlattener` has no visited-set. If a node is reachable via two interface paths (a diamond), it is **traversed twice** → duplicated provenance / possible spurious Conflicts. OPC UA type graphs are acyclic, so no infinite loops/crashes. It does not occur in the PoC model, and the general fix (per-node visited-set + provenance merge) is out of PoC scope.
- **Multi-server alias namespacing = design only (out of scope):** aggregating multiple OPC UA servers under one edge makes each server emit the same aliases, violating edge-scope uniqueness. The registry must namespace alias spaces per source. Needs two servers → recorded as design only.
- **BIRTH-time definition freeze gap:** the whole ObjectType is browsed and members frozen at BIRTH time. Address spaces are dynamic — members appearing after BIRTH require redeclaration via rebirth (= worsens BIRTH storms). This PoC does a one-shot browse→freeze only.
- **How common is interface multiple inheritance in practice:** OPC UA Part 3 defines **single-inheritance semantics only** for ObjectTypes — multiple inheritance happens **only via `HasInterface` (+`HasAddIn`)** (introduced in 1.04). The Machinery (OPC 40001) / DI (OPC 10000-100) nameplates (`IVendorNameplateType`/`ITagNameplateType`) are all interface-based → **interface flattening is effectively table stakes on information-model-rich servers**. Conversely, flat tag servers (Kepware-style) have no ObjectTypes at all, so ObjectType→UDT mapping is moot there → out of scope.
- **Honesty of the Uncertain→GOOD projection:** generic Uncertain projects to GOOD in quality (a loss); only `UncertainLastUsableValue`-class codes map to STALE. **Uncertain ≢ STALE** (treating them as equivalent makes a historian misread an on-time value as stale). The lossless truth is always in `ua_statuscode`, and the demo proves it by decoding the severity bits.
- Other non-goals: flat tag-server folder browsing, RBE ↔ OPC UA subscription impedance (polling only), single-seq serialization across multiple servers, ModellingRule enforcement (metadata preserved only), nested Structures → nested UDTs, all 25 built-in DataTypes (11 representative ones cover all four LossClasses), error handling/reconnect/HA.

## Consequences

- The OPC UA type system (inheritance, interface multiple-inheritance, NodeIds, EURange, StatusCode) → UDT mapping exists as **working code with a loss ledger** — OT information-model governance as proof rather than design. Direct reuse of ADR-0007 (authority) and ADR-0008 (#608/#607/#603) keeps this work consistent with the rest of the ADR series.
- The pure core (`UaDataTypeMapper`/`TypeFlattener`/`OpcUaTypeMapper`/`LossLedger`/`OpcUaThinCodec`) is TDD-verified without a server; the live shells (`OpcUaBrowser`/`OpcUaInstanceReader`/demo) exercise the same code live — conflict cases that fixtures can't produce are still strongly covered by unit tests.
- All limits documented (diamond traversal, multi-server aliases, BIRTH freeze, Uncertain projection) — making the loss boundary explicit, never hiding it, is the design principle.

## Links

- Code (pure + shells): [`src/main/java/dev/krillin/sparkplug/opcua/`](../../src/main/java/dev/krillin/sparkplug/opcua/)
- Demo: [`src/main/java/dev/krillin/sparkplug/OpcUaUdtBridgeDemo.java`](../../src/main/java/dev/krillin/sparkplug/OpcUaUdtBridgeDemo.java)
- Simulator: [`opcua-sim-server.py`](../../opcua-sim-server.py)
- Prior: ADR-0005 (UDT SemVer), ADR-0007 (registry gate), ADR-0008 (#608/#607/#603), [namespace-standard §2/§5](../namespace-standard.md) *(Korean)*
