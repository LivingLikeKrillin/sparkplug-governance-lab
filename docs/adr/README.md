# 아키텍처 결정 레코드 (Architecture Decision Records, ADR)

문서 구조: 맥락(Context) → 결정(Decision) → 결과(Consequences). 모든 ADR은 **두 가지 언어**로 제공됩니다: 한국어 원문(`*.md`, 정본) 및 영어 번역본(`*.en.md`).  
본 문서군 전반에서 사용하는 도메인 및 거버넌스 표준 용어 정의는 [`docs/glossary.md`](../glossary.md)를 참조하십시오.

| ADR | 주제 (Topic) | 한국어 (KO) | 영어 (EN) |
|-----|-------------|------------|-----------|
| 0001 | 기본 호스트 STATE 및 저장 후 전달(Store-and-Forward) vs UNS 다자간 소비자(n:m) 구조적 모순 | [KO](ADR-0001-primary-host-store-and-forward.md) | [EN](ADR-0001-primary-host-store-and-forward.en.md) |
| 0002 | 지연 참여자(Late-Joiner) 접속 시 상태 동기화: Sparkplug 인식 브로커 vs 강제 Rebirth (A/B 테스트 검증) | [KO](ADR-0002-late-joiner-state-on-connect.md) | [EN](ADR-0002-late-joiner-state-on-connect.en.md) |
| 0004 | Protobuf 페이로드 불투명성: 비-Sparkplug 소비자를 위한 상태 기반 Sparkplug→JSON 변환 브리지 | [KO](ADR-0004-protobuf-opacity-json-bridge.md) | [EN](ADR-0004-protobuf-opacity-json-bridge.en.md) |
| 0005 | UDT 버전 관리 및 스키마 거버넌스 (프로토콜 외적 관례에 의한 SemVer 규약) | [KO](ADR-0005-udt-versioning-schema-governance.md) | [EN](ADR-0005-udt-versioning-schema-governance.en.md) |
| 0006 | edge_node_id 및 Client ID 고유성 보장 ("세션 탈취" 폭풍 방지) | [KO](ADR-0006-edge-node-id-uniqueness.md) | [EN](ADR-0006-edge-node-id-uniqueness.en.md) |
| 0007 | 데이터 계약 레지스트리 및 호환성 검증 게이트 (폐쇄형 실패 CI, FORWARD 기본값) | [KO](ADR-0007-schema-registry-gate.md) | [EN](ADR-0007-schema-registry-gate.en.md) |
| 0008 | 스키마-데이터 분리 프로토타입 (Sparkplug 4.0 [#608](https://github.com/eclipse-sparkplug/sparkplug/issues/608)/[#607](https://github.com/eclipse-sparkplug/sparkplug/issues/607)/[#603](https://github.com/eclipse-sparkplug/sparkplug/issues/603) 제안 개념 실증) | [KO](ADR-0008-schema-data-separation.md) | [EN](ADR-0008-schema-data-separation.en.md) |
| 0009 | 상태 기반 UNS→Kafka 브리지 (RBE 최신 상태 ↔ Kafka 로그 압축 동형성) | [KO](ADR-0009-uns-to-kafka-stateful-bridge.md) | [EN](ADR-0009-uns-to-kafka-stateful-bridge.en.md) |
| 0010 | OPC UA 정보 모델 → Sparkplug UDT 매핑 및 명시적 손실 원장(Loss Ledger) | [KO](ADR-0010-opcua-udt-mapping.md) | [EN](ADR-0010-opcua-udt-mapping.en.md) |
| 0011 | NCMD 제어 명령 인가: 계층형 정책 코드화 (Policy-as-Code, [#600](https://github.com/eclipse-sparkplug/sparkplug/issues/600) 연계) | [KO](ADR-0011-command-authorization.md) | [EN](ADR-0011-command-authorization.en.md) |
| 0012 | 런타임 스키마 드리프트 감지 (관측 전용 모니터링을 통한 거버넌스 피드백 루프 완결) | [KO](ADR-0012-runtime-drift-detection.md) | [EN](ADR-0012-runtime-drift-detection.en.md) |

(ADR-0003은 네임스페이스 사양서 §2에 통합되었으며, 별도 파일로 분리되지 않습니다.)
