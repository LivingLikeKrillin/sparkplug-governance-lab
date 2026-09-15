# 아키텍처 결정 레코드 체계 및 설계 로드맵 (ADR Suite & Architectural Narrative)

본 문서는 Sparkplug B 프로토콜과 통합 네임스페이스(UNS) 환경에서 데이터 정합성, 제어 무결성, 분산 신뢰성을 달성하기 위해 내린 **11편의 핵심 아키텍처 결정 레코드(ADR)**를 수록합니다.

모든 ADR은 **맥락(Context) → 결정(Decision) → 결과(Consequences)** 구조로 작성되었으며, 한국어 원문(`*.md`, 정본)과 영어 번역본(`*.en.md`)을 상호 제공합니다. 문서 내 모든 표준 기술 용어의 정의는 [`docs/glossary.md`](../glossary.md)를 따릅니다.

---

## 1. 아키텍처 결정 간 유기적 연결 로드맵 (Architectural Narrative)

11편의 결정 레코드는 개별적 이슈가 아닌, 단일 산업 데이터 플랫폼의 신뢰성을 보장하기 위한 4단계 아키텍처 계층 구조를 형성합니다:

```
[계층 4] 엣지 제어 보안 & 런타임 관측    ──▶ ADR-0011 (NCMD 2계층 인가)  ──▶ ADR-0012 (스키마 드리프트 감지)
                                                     ▲                               ▲
[계층 3] 이기종 브리징 & 무손실 보존      ──▶ ADR-0004 (JSON 브리지)     ──▶ ADR-0009 (Kafka 로그 압축) ──▶ ADR-0010 (OPC UA 손실 원장)
                                                     ▲                               ▲
[계층 2] 데이터 계약 & 스키마 거버넌스   ──▶ ADR-0005 (UDT SemVer 규약) ──▶ ADR-0007 (레지스트리 게이트)──▶ ADR-0008 (스키마-데이터 분리)
                                                     ▲                               ▲
[계층 1] 세션 수명주기 & 분산 신뢰성     ──▶ ADR-0001 (기본 호스트 모순) ──▶ ADR-0002 (지연 참여자 복원) ──▶ ADR-0006 (Client ID 유일성)
```

1. **세션 수명주기 및 분산 신뢰성 (Session Lifecycle & Network Reliability)**:
   - [`ADR-0001`](ADR-0001-primary-host-store-and-forward.md): Sparkplug의 단일 Primary Host 중심 Store-and-Forward와 UNS의 다자간(n:m) 소비자 요구 간의 구조적 모순을 분석하고 해법을 제시합니다.
   - [`ADR-0002`](ADR-0002-late-joiner-state-on-connect.md): 지연 접속 소비자를 위해 설비 재발행 폭풍(Rebirth Storm)을 유발하지 않고 브로커가 상태를 주입하는 방식을 실증합니다.
   - [`ADR-0006`](ADR-0006-edge-node-id-uniqueness.md): 클라이언트 ID 중복 시 발생하는 세션 탈취 플래핑(Flapping) 폭풍을 방지하기 위한 전역 유일성 규칙을 정의합니다.

2. **데이터 계약 및 스키마 거버넌스 (Data Contracts & Schema Evolution)**:
   - [`ADR-0005`](ADR-0005-udt-versioning-schema-governance.md): 회선 페이로드의 한계를 극복하고 관례 기반 SemVer를 UDT에 부여하는 거버넌스 규약을 정립합니다.
   - [`ADR-0007`](ADR-0007-schema-registry-gate.md): 배포 전 단계에서 파괴적 변경을 차단(Fail-Closed)하는 GitOps 레지스트리 게이트를 수립합니다.
   - [`ADR-0008`](ADR-0008-schema-data-separation.md): 영속 DEFINITION과 경량 텔레메트리를 분리하여 페이로드 크기를 50% 절감하는 차세대 구조를 실증합니다.

3. **이기종 브리징 및 무손실 보존 (Heterogeneous Bridging & Lossless Preservation)**:
   - [`ADR-0004`](ADR-0004-protobuf-opacity-json-bridge.md): 비-Sparkplug 시스템을 위해 Protobuf 불투명성을 해소하는 상태 기반 JSON 변환을 규정합니다.
   - [`ADR-0009`](ADR-0009-uns-to-kafka-stateful-bridge.md): Sparkplug RBE 최신값과 Kafka 로그 압축(Log Compaction) 간의 수학적 동형성을 증명하고 DLQ 격리를 구현합니다.
   - [`ADR-0010`](ADR-0010-opcua-udt-mapping.md): OPC UA 계층 모델 사상 시 발생하는 손실을 손실 원장(`LossLedger`)으로 명시하고 사이드 채널로 무손실성을 달성합니다.

4. **엣지 제어 보안 및 런타임 관측 (Edge Security & Runtime Observability)**:
   - [`ADR-0011`](ADR-0011-command-authorization.md): 브로커 토픽의 가시성 한계를 밝히고, 엣지 페이로드 단위의 기본 거부(Deny-by-Default) 2계층 심층 인가를 확립합니다.
   - [`ADR-0012`](ADR-0012-runtime-drift-detection.md): 런타임에 유통되는 페이로드의 스키마 드리프트를 수동 관측하여 사전 게이트와 연계된 거버넌스 닫힌 루프를 완성합니다.

---

## 2. ADR 카탈로그 (Architecture Decision Records)

| ADR 번호 | 의사결정 주제 | 한국어 정본 (KO) | 영어 번역본 (EN) |
|----------|---------------|------------------|------------------|
| **ADR-0001** | Primary Host STATE 및 저장 후 전달(Store-and-Forward) vs UNS 다자간 소비자(n:m) 구조적 모순 | [KO](ADR-0001-primary-host-store-and-forward.md) | [EN](ADR-0001-primary-host-store-and-forward.en.md) |
| **ADR-0002** | 지연 참여자(Late-Joiner) 접속 시 상태 복원: Sparkplug 인식 브로커 vs 강제 Rebirth (A/B 테스트 검증) | [KO](ADR-0002-late-joiner-state-on-connect.md) | [EN](ADR-0002-late-joiner-state-on-connect.en.md) |
| **ADR-0004** | Protobuf 역직렬화 불투명성: 비-Sparkplug 소비자를 위한 상태 기반 Sparkplug→JSON 변환 브리지 | [KO](ADR-0004-protobuf-opacity-json-bridge.md) | [EN](ADR-0004-protobuf-opacity-json-bridge.en.md) |
| **ADR-0005** | UDT 버전 관리 및 스키마 거버넌스 (프로토콜 외적 관례에 의한 SemVer 규약) | [KO](ADR-0005-udt-versioning-schema-governance.md) | [EN](ADR-0005-udt-versioning-schema-governance.en.md) |
| **ADR-0006** | `edge_node_id` 및 Client ID 전역 고유성 보장 (세션 탈취 플래핑 폭풍 방지) | [KO](ADR-0006-edge-node-id-uniqueness.md) | [EN](ADR-0006-edge-node-id-uniqueness.en.md) |
| **ADR-0007** | 데이터 계약 레지스트리 및 호환성 검증 게이트 (폐쇄형 실패 CI, FORWARD 기본값) | [KO](ADR-0007-schema-registry-gate.md) | [EN](ADR-0007-schema-registry-gate.en.md) |
| **ADR-0008** | 스키마-데이터 분리 프로토타입 (Sparkplug 4.0 [#608](https://github.com/eclipse-sparkplug/sparkplug/issues/608)/[#607](https://github.com/eclipse-sparkplug/sparkplug/issues/607)/[#603](https://github.com/eclipse-sparkplug/sparkplug/issues/603) 제안 개념 실증) | [KO](ADR-0008-schema-data-separation.md) | [EN](ADR-0008-schema-data-separation.en.md) |
| **ADR-0009** | 상태 기반 UNS→Kafka 브리지 (RBE 최신값 보존 ↔ Kafka 로그 압축 동형성) | [KO](ADR-0009-uns-to-kafka-stateful-bridge.md) | [EN](ADR-0009-uns-to-kafka-stateful-bridge.en.md) |
| **ADR-0010** | OPC UA 정보 모델 → Sparkplug UDT 매핑 및 명시적 손실 원장(Loss Ledger) | [KO](ADR-0010-opcua-udt-mapping.md) | [EN](ADR-0010-opcua-udt-mapping.en.md) |
| **ADR-0011** | NCMD 제어 명령 인가: 계층형 정책 코드화 (Policy-as-Code, [#600](https://github.com/eclipse-sparkplug/sparkplug/issues/600) 연계) | [KO](ADR-0011-command-authorization.md) | [EN](ADR-0011-command-authorization.en.md) |
| **ADR-0012** | 런타임 스키마 드리프트 감지 (수동 관측 모니터링을 통한 거버넌스 피드백 루프 완결) | [KO](ADR-0012-runtime-drift-detection.md) | [EN](ADR-0012-runtime-drift-detection.en.md) |

*(참고: ADR-0003은 별도 파일이 아닌 네임스페이스 사양서 [`docs/namespace-standard.md`](../namespace-standard.md) §2에 직접 통합되었습니다.)*
