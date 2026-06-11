# ADR-0006 — edge_node_id / MQTT client-id global uniqueness (English translation)

- Status: **Accepted**
- Date: 2026-06-03
- Evidence: direct experiment (`src/.../StolenSessionDemo.java`), HiveMQ CE. *(Original Korean: [ADR-0006-edge-node-id-uniqueness.md](ADR-0006-edge-node-id-uniqueness.md))*

## Context

Sparkplug identifies an edge by `group_id` + `edge_node_id`, and the MQTT **client-id** is usually derived from them. In a large or multi-integrator environment, what happens when the same id is issued twice?

## Experiment (measured)

Connect two EdgeNodes with the same group/edge (= same client-id):

- A connects + NBIRTH → host receives it.
- B connects with the **same client-id** → the broker disconnects A (`connection lost (32109) EOFException` = takeover).
- A's disconnect is ungraceful → **A's LWT (NDEATH) fires** → host receives NDEATH.
- B's NBIRTH → host receives NBIRTH again.
- Resulting host log: **NBIRTH(A) → NDEATH(A) → NBIRTH(B)**. If both instances stay alive and keep reconnecting, this pattern becomes **flapping (a birth/death storm)**.

## Findings

- MQTT 3.1.1 takeover semantics: a new connection with an existing client-id makes the broker **drop the old connection**. A takeover-induced drop is treated as ungraceful → **the Will (NDEATH) is published**.
- So an id collision is not a one-off accident but **state oscillation** (the device appears to die and come back repeatedly) + data attribution pollution + consumer confusion.

## Decision (governance)

- **edge_node_id (and the derived client-id) must be globally unique enterprise-wide** — enforced at provisioning time. Naming conventions (site/area/edge) prevent collisions at the source (→ namespace standard §3).
- A **central registry** manages id issuance and ownership (prevents duplicates across integrators/vendors).
- **Broker-side defense:** client-id-based ACLs + **flap detection/alerting** (frequent reconnects in a short window) to catch storms early.

## Consequences

- The provisioning/registry process becomes a governance artifact.
- Broker monitoring gains flap detection.
- Directly tied to namespace naming — id uniqueness is part of the naming convention.

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/StolenSessionDemo.java`](../../src/main/java/dev/krillin/sparkplug/StolenSessionDemo.java)
- Naming convention: [namespace-standard §3](../namespace-standard.en.md)
