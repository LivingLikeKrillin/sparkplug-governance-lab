# Historical design records

These dated records trace the lab's evolution into the Yggdrasil products. They preserve technical decisions, alternatives, and limitations; they describe the implementation at each recorded stage rather than the current lab feature set. Commands and assertions in the records are verification criteria, not new execution results.

Start with the [governance-spine design](2026-07-08-yggdrasil-governance-spine-design.md) for the architectural origin: Mimir models OPC UA type spaces into definitions, Bifrost governs, and Muninn feeds governed instances to Sparkplug B.

Current implementation repositories: [Bifrost](https://github.com/yggdrasil-iiot/bifrost) (including the Heimdall write-boundary module), [Mimir](https://github.com/yggdrasil-iiot/mimir), and [Muninn](https://github.com/yggdrasil-iiot/muninn). For current reproduction instructions and dated captured evidence, use the [reproduction guide](../reproduce/README.md). Pre-extraction command/schema demos are preserved in the standalone [legacy module](../../legacy/pre-bifrost/).

| Date | Design record |
|---|---|
| 2026-07-07 | [OPA command authorization — context-conditional policy, evaluated in-JVM (WASM)](2026-07-07-opa-command-authz-design.md) |
| 2026-07-08 | [Bifrost — Governance Product (Design)](2026-07-08-bifrost-governance-product-design.md) |
| 2026-07-08 | [Yggdrasil Governance Spine — Design](2026-07-08-yggdrasil-governance-spine-design.md) |
| 2026-07-09 | [Composable Runtime Conformance — Bifrost as the IAM-like control plane for the OT governance boundary](2026-07-09-composable-runtime-conformance-design.md) |
| 2026-07-09 | [Governed Version-Activation (T3) — Design](2026-07-09-governed-version-activation-design.md) |
| 2026-07-09 | [Tamper-Evident Activation Ledger — Lineage / Record-of-Record (T4) — Design](2026-07-09-lineage-record-of-record-design.md) |
| 2026-07-09 | [site ⊨ enterprise conformance — prescriptive model governance, standard-agnostic via ports & adapters](2026-07-09-site-enterprise-conformance-design.md) |
| 2026-07-09 | [Yggdrasil Full-Loop — closing the governance loop (observe → command → observe)](2026-07-09-yggdrasil-full-loop-design.md) |
| 2026-07-11 | [Activation Authorization — IAM authZ plane (T6) — Design](2026-07-11-activation-authorization-design.md) |
| 2026-07-11 | [Signed Activation — Identity (T5) — Design](2026-07-11-identity-signed-activation-design.md) |
| 2026-07-12 | [Multi-site federation — per-site brokers under one federated governance authority (Design)](2026-07-12-multisite-federation-design.md) |
