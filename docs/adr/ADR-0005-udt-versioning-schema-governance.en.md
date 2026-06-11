# ADR-0005 — UDT (Template) versioning / schema governance (English translation)

- Status: **Accepted**
- Date: 2026-06-03
- Evidence: direct experiment (`src/.../UdtDemo.java`), Tahu 1.0.14 / HiveMQ CE. *(Original Korean: [ADR-0005-udt-versioning-schema-governance.md](ADR-0005-udt-versioning-schema-governance.md))*

## Context

UDTs (Sparkplug Templates) are the **data model** of a UNS. Definitions and instances flow in NBIRTH, and there is a `version` field. The core governance question: *how are UDT definitions governed and versioned across an enterprise, multiple sites, multiple vendors?*

## Experiment (measured)

`UdtDemo` publishes a "Motor" UDT:

- **v1.0:** def {Rpm: Double, Running: Boolean} + parameter Location; instance Motors/Motor1 {Rpm=1500, Running=true}.
- **v2.0:** same def with **member Temperature: Double added**, version="2.0"; instance carries Temperature=65.4.

Host decodes both versions fine — definition/ref/version/parameters/members all preserved.

## Findings

1. **`version` is a free-form string — the protocol enforces nothing.** "1.0"/"2.0" is convention only; it carries no compatibility semantics.
2. **No schema registry.** The only source of a definition is the NBIRTH payload. A consumer that missed the birth, or was built against v1, has **no authority to validate against**.
3. **Member add/remove/type-change is unconstrained.** Between v1→v2 the broker/protocol does none of (a) compatibility checking, (b) migration, (c) rejection → consumers assuming v1 can **break silently**.
4. **Ignition UDTs ↔ raw Sparkplug Templates are separate models** → dual-definition consistency (drift) risk.

## Decision (governance — enforced outside the protocol)

- Govern UDT definitions in an **external schema registry with review** (ownership, approval, history). NBIRTH `_types_/` is *wire truth*, not *source of truth*.
- **Version policy = SemVer, enforced by governance:** member add = minor (backward compatible); member remove / type change = major (breaking). **Breaking changes get a new `templateRef`** (e.g. `Motor` → `Motor2`) to protect v1 consumers.
- **Consumers compare the received version's major against their expectation**; unknown majors are rejected/alerted.
- CI gates definition changes for compatibility (additive-only within a major).

## Consequences

- The governance burden lives **outside the protocol** (Sparkplug will not stop you) → the registry, CI, and policy documents are the actual controls.
- The namespace standard (§5) must codify UDT ownership, versioning, and `templateRef` conventions.
- Directly relevant to OPC UA → UDT mapping: this versioning convention is the backbone of mapping governance (see ADR-0010).

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/UdtDemo.java`](../../src/main/java/dev/krillin/sparkplug/UdtDemo.java)
- Implemented as a working enforcement mechanism in ADR-0007
