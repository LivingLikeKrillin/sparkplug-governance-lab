# ADR-0011 — NCMD command authorization / layered policy-as-code (English translation)

- Status: **Accepted**
- Date: 2026-06-11
- Relations: reuses the fail-closed CLI pattern of the schema gate (ADR-0007); divides identity responsibility with edge-id uniqueness (ADR-0006). Code: `src/main/java/dev/krillin/sparkplug/acl/`, demo `CommandAclDemo.java`. *(Original Korean: [ADR-0011-command-authorization.md](ADR-0011-command-authorization.md))*

## Context

As specified, NCMD is **wide open**. Any client that can publish to `spBv1.0/{group}/NCMD/{edge}` can issue commands, and a naive edge node executes whatever arrives, unauthenticated and unauthorized. Extended to OT writeback (setpoints etc.) this means "anyone can write any value to equipment" — a safety and governance hole. This relates directly to the question raised in [eclipse-sparkplug/sparkplug#600](https://github.com/eclipse-sparkplug/sparkplug/issues/600) (command authorization, including what role a Sparkplug Aware MQTT Server could play).

## Decision

**Key observation:** the NCMD topic `spBv1.0/{group}/NCMD/{edge}` carries **no command name**. Rebirth, Reboot, and Setpoint all arrive on the same topic; command identity lives in the payload metrics. Consequences:

- A standard broker **topic ACL is structurally incapable of per-command authorization** — it can only enforce *node-level reachability* (who may command this node at all), all-or-nothing.
- Per-command and per-value authorization requires payload visibility, which exists at the **edge** (or in a broker that decodes Sparkplug payloads, i.e. a Sparkplug Aware server — at a cost).
- The two enforcement layers therefore cannot substitute for each other; they answer different questions.

![Two enforcement layers](../img/adr-0011-enforcement-layers.svg)

We project a **single policy source** (`registry/command-policy.json`, deny-by-default) onto two enforcement points plus one CI gate:

1. **Edge layer = payload → command & value.** `CommandAuthorizer` (pure logic) checks every received command *before* execution against the policy — command allowlist + value range (inclusive min/max) + type match + target match, **first-match, deny-by-default (fail-closed)**: null values, non-numeric values where a range is declared, and type mismatches all DENY. `GuardedEdgeNode` executes only ALLOW decisions.
2. **Broker layer = identity → node reachability.** `BrokerAclProjector` (pure logic) renders the policy into a portable broker-ACL artifact (principal → PUBLISH permission on NCMD topics; the policy's `*` becomes MQTT's single-level wildcard `+`). *This is an artifact for broker configuration — not live enforcement in this PoC.*
3. **CI gate.** `CommandPolicyGate` (a sibling of `SchemaGate`) lints the policy shift-left: default must be deny, no duplicate rule ids, no meaningless constraints, flags over-broad `*` grants, rejects unknown fields. Violations exit non-zero; parse/IO errors exit 2 (**fail-closed** — an unreadable policy never passes).

## Consequences

- Command authorization is actually enforced outside the protocol: one policy source projects consistently to edge runtime, broker configuration, and CI. The live demo proves the structural point: a value-range violation (`Rpm=99999`, correct topic, correct command name) **cannot be blocked by any broker topic ACL** and is denied at the edge.
- **Limitations / honesty (PoC):** the broker ACL side is projection + validation only (no live RBAC wired up — actual enforcement is the broker's responsibility). Publisher **identity is the broker/MQTT-auth layer's responsibility**; the edge is principal-independent (no re-authentication, no cryptographic command signing — signing is production hardening). Note: the `principal` fields in `command-policy.json` exist **only** to generate broker ACL entries — the edge authorizer never matches on principal. Audit goes to structured console logs only. Single policy file, PoC scale. Discrete allowed-value sets (`oneOf`) are out of scope for v1 (numeric min/max suffices).
- Same policy-as-code + fail-closed CLI shape as the data-contract gate (ADR-0007) — data-contract governance and command governance are isomorphic. The command permission matrix sits on top of the stable node identity guaranteed by ADR-0006.

## Links

- Code: [`src/main/java/dev/krillin/sparkplug/acl/`](../../src/main/java/dev/krillin/sparkplug/acl/)
- Shell: [`src/main/java/dev/krillin/sparkplug/GuardedEdgeNode.java`](../../src/main/java/dev/krillin/sparkplug/GuardedEdgeNode.java)
- CI gate: [`src/main/java/dev/krillin/sparkplug/acl/CommandPolicyGate.java`](../../src/main/java/dev/krillin/sparkplug/acl/CommandPolicyGate.java)
- Demo: [`src/main/java/dev/krillin/sparkplug/CommandAclDemo.java`](../../src/main/java/dev/krillin/sparkplug/CommandAclDemo.java)
- Policy: [`registry/command-policy.json`](../../registry/command-policy.json)
- Related: ADR-0007 (schema gate), ADR-0006 (edge-id uniqueness), [namespace-standard §7](../namespace-standard.md) *(Korean)*
