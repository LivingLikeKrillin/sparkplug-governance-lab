# Upstream design note — NCMD/DCMD authorization (Sparkplug #600)

A concrete, deny-by-default design for per-command authorization, written as a
contribution toward [eclipse-sparkplug/sparkplug#600 "Add support for
authorization to CMD messages"](https://github.com/eclipse-sparkplug/sparkplug/issues/600)
and the spec team's open question there ("how could a Sparkplug Aware MQTT
Server play into this"). Backed by a working prototype:
[ADR-0011](../adr/ADR-0011-command-authorization.en.md) ·
[runnable demo](../../README.md#running) (`CommandAclDemo`).

> Scope: this is a PoC-scale design note, not a spec patch. Numbers and limits
> are stated honestly; the goal is to give the working group something concrete
> to react to.

## The structural problem

`spBv1.0/{group}/NCMD/{edge}` is the **same topic** whether the payload metric
is `Node Control/Rebirth`, `Node Control/Reboot`, or a setpoint write to `Rpm`.
Command identity and value live in the **protobuf payload**, not the topic. The
spec's only broker-level lever is a topic ACL at node granularity, which can
therefore answer only *"may this identity command this node at all?"* —
all-or-nothing. **Per-command and per-value authorization** ("writes to `Rpm`
are allowed, but only within 0–3000") requires **payload visibility**, which
exists at the edge, or in an Aware server that decodes NCMD payloads.

The two enforcement points **compose** rather than compete:

1. **Edge-enforced** (prototyped here): one deny-by-default policy file projected
   three ways — an edge-side authorizer (fail-closed: command allowlist + value
   range + type), a broker-ACL artifact for node-level reachability, and a CI
   lint gate.
2. **Aware-server-enforced:** the broker decodes NCMD payloads and enforces
   per-command rules centrally — stronger central control, at the cost of
   payload decoding on the publish path and policy distribution to the broker.

## The case a topic ACL structurally cannot block

The prototype's demo includes it: **correct topic, allowed command name,
out-of-range value** (`Rpm = 99999`). A node-level topic ACL passes it (right
topic, permitted identity); only a payload-aware authorizer denies it. This is
the concrete reason node-level ACLs are necessary but not sufficient.

## Proposed model (deny-by-default, single source of truth)

```
command-policy.json  (deny-by-default)
        │
        ├─ projected → edge authorizer   : allowlist + value range + type (fail-closed)
        ├─ projected → broker ACL artifact: identity → node reachability
        └─ checked   → CI lint gate       : policy is well-formed & deny-by-default
```

- **Deny-by-default:** absent an explicit allow rule, a command is rejected.
- **One policy, three projections:** the same file drives edge enforcement,
  broker reachability, and a CI check, so the three never drift apart.
- **Per-command + per-value:** rules bind to the payload metric name, type, and
  value range — the granularity topic ACLs cannot express.

## Spec-level implication for 4.0

If per-command authorization should be enforceable by **non-aware**
infrastructure (plain MQTT brokers + topic ACLs), then **command identity would
need to surface outside the protobuf payload** — at the topic level or in an
envelope. That is a message-design consideration for Sparkplug 4.0, independent
of whether enforcement lands at the edge or in an Aware server.

## Honest scope / limits

PoC scale: single broker, single node, value/type/allowlist rules only (no
rate-limiting, no multi-tenant policy composition, no signed policy
distribution). The design demonstrates the *enforcement-point structure and the
deny-by-default discipline*, not a production authorization service.

---

## Draft follow-up comment for #600

A tightened version of the above, ending in a **binary ask** (easier for the WG
to answer than an open-ended offer). Paste/adapt as a follow-up comment:

> Following up on the prototype above — if it's useful to the spec team, I can
> take this further in whichever form fits your process best:
>
> **(a)** a short design-note PR capturing the two enforcement points
> (edge-enforced vs Aware-server-enforced) and the deny-by-default policy model,
> for the Commands/Security chapters; **or**
> **(b)** a brief write-up of just the spec-level implication — that
> enforcing per-command authorization on *non-aware* infrastructure would
> require command identity to surface outside the protobuf payload (topic or
> envelope), as a 4.0 message-design consideration.
>
> Happy to do either (or neither) — just let me know which is actually useful.
