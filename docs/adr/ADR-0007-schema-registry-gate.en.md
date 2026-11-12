# ADR-0007 — Schema registry gate / data-contract enforcement (English translation)

- Status: **Accepted**
- Date: 2026-06-09
- Relations: implements ADR-0005 (the decision) as a **working enforcement mechanism**. Registry value types remain in `src/main/java/yggdrasil/sparkplug/schema/`; the runnable historical gate and demo are in `legacy/pre-bifrost/`, with package `yggdrasil.sparkplug.legacy`. *(Original Korean: [ADR-0007-schema-registry-gate.md](ADR-0007-schema-registry-gate.md))*

## Context

ADR-0005 decided "govern UDTs with an external registry + SemVer + a CI gate", but the existing code (`UdtDemo`) only demonstrated the *gap* — schemas silently replaced without any check. This ADR enforces that decision in running code.

## Decision

1. **The registry is the source of truth.** Data contracts live at `registry/udt/<ref>/<semver>.json`. NBIRTH `_types_` is wire truth (extracted via `TemplateAdapter` for comparison).
2. **Policy-as-code.** Compatibility mode in `registry/policy.json` (Confluent vocabulary: FORWARD/BACKWARD/FULL/NONE). **UNS default = FORWARD** — producers evolve the model and many consumers lag, so guarantee "old consumers can read new data". Member add = OK; remove/type-change = breaking → new `templateRef` + major bump (ADR-0005's `Motor` → `Motor2`).
3. **Shift-left gate.** The `SchemaGate` CLI compares a proposed definition against the registered one and **exits non-zero on breaking changes**, blocking CI/deployment (it never drops OT data at runtime).
4. **Fail-closed.** A missing/corrupt policy.json never passes (exit 2).

## Consequences

- Governance is *actually* enforced in CI, outside the protocol (an exit code, not a document).
- Aligned with the Sparkplug 4.0 direction: **#608 Definition messages** (schema↔data separation) move toward specifying an external schema authority — this registry is a PoC of that role. #607 payload properties extend to metadata governance as well.
- Limits (PoC): single disk registry, single major line, no automated instance migration. Runtime drift detection is out of scope here (that is ADR-0012).

## Links

- Code (registry value types, still here): [`src/main/java/yggdrasil/sparkplug/schema/`](../../src/main/java/yggdrasil/sparkplug/schema/)
- Gate (extracted to bifrost in `0b9b1ae`): bifrost [`gates/SchemaGate.java`](https://github.com/yggdrasil-iiot/bifrost/blob/main/gates/src/main/java/yggdrasil/bifrost/gates/SchemaGate.java) · [`core/schema/CompatibilityChecker.java`](https://github.com/yggdrasil-iiot/bifrost/blob/main/core/src/main/java/yggdrasil/bifrost/core/schema/CompatibilityChecker.java)
- Demo: standalone legacy copy [`SchemaGateDemo.java`](../../legacy/pre-bifrost/src/main/java/yggdrasil/sparkplug/legacy/SchemaGateDemo.java) — how to run it: [README › Running](../../README.md#running)
- Registry: [`registry/`](../../registry/)
- Prior: ADR-0005
