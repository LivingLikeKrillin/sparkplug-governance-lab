# Architecture Decision Records

Format: Context → Decision → Consequences. Every ADR is available in **two languages**: the Korean original (`*.md`, authoritative) and an English translation (`*.en.md`).

| ADR | Topic | KO | EN |
|-----|-------|----|----|
| 0001 | Primary Host STATE + store-and-forward vs the UNS multi-consumer (n:m) contradiction | [KO](ADR-0001-primary-host-store-and-forward.md) | [EN](ADR-0001-primary-host-store-and-forward.en.md) |
| 0002 | Late-joiner state-on-connect: Sparkplug-aware broker vs forced rebirth (A/B tested) | [KO](ADR-0002-late-joiner-state-on-connect.md) | [EN](ADR-0002-late-joiner-state-on-connect.en.md) |
| 0004 | Protobuf opacity: stateful Sparkplug→JSON bridge for non-Sparkplug consumers | [KO](ADR-0004-protobuf-opacity-json-bridge.md) | [EN](ADR-0004-protobuf-opacity-json-bridge.en.md) |
| 0005 | UDT versioning / schema governance (SemVer by convention, not protocol) | [KO](ADR-0005-udt-versioning-schema-governance.md) | [EN](ADR-0005-udt-versioning-schema-governance.en.md) |
| 0006 | edge_node_id / client-id uniqueness ("stolen session" storms) | [KO](ADR-0006-edge-node-id-uniqueness.md) | [EN](ADR-0006-edge-node-id-uniqueness.en.md) |
| 0007 | Data-contract registry + compatibility gate (fail-closed CI, FORWARD default) | [KO](ADR-0007-schema-registry-gate.md) | [EN](ADR-0007-schema-registry-gate.en.md) |
| 0008 | Schema↔data separation prototype (Sparkplug 4.0 [#608](https://github.com/eclipse-sparkplug/sparkplug/issues/608)/[#607](https://github.com/eclipse-sparkplug/sparkplug/issues/607)/[#603](https://github.com/eclipse-sparkplug/sparkplug/issues/603) concepts) | [KO](ADR-0008-schema-data-separation.md) | [EN](ADR-0008-schema-data-separation.en.md) |
| 0009 | UNS→Kafka stateful bridge (RBE last-known-value ↔ log compaction isomorphism) | [KO](ADR-0009-uns-to-kafka-stateful-bridge.md) | [EN](ADR-0009-uns-to-kafka-stateful-bridge.en.md) |
| 0010 | OPC UA information model → Sparkplug UDT mapping with an explicit loss ledger | [KO](ADR-0010-opcua-udt-mapping.md) | [EN](ADR-0010-opcua-udt-mapping.en.md) |
| 0011 | NCMD command authorization: layered policy-as-code (relates to [#600](https://github.com/eclipse-sparkplug/sparkplug/issues/600)) | [KO](ADR-0011-command-authorization.md) | [EN](ADR-0011-command-authorization.en.md) |
| 0012 | Runtime schema-drift detection (detect-only observability, closes the policy loop) | [KO](ADR-0012-runtime-drift-detection.md) | [EN](ADR-0012-runtime-drift-detection.en.md) |

(ADR-0003 was merged into the namespace standard §2 — there is no separate file.)
