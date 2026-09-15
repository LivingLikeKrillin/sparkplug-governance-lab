# UNS 네임스페이스 거버넌스 표준 (Draft v0.1)

> 상태: 🟢 공식 초안. PoC 실증 시험(SessionDemo, LateJoiner, Udt, StateStoreForward, StolenSession, JsonBridge) 및 ADR-0001~ADR-0012의 설계를 종합한 엔터프라이즈 거버넌스 표준 규격입니다.  
> 목적: 전사 UNS 환경에서 토픽 네임스페이스, 식별자 체계, 데이터 모델, 접근 통제 및 가용성 상태를 일관된 아키텍처 원칙으로 통제 및 관리합니다.

---

## 1. 표준의 범위 및 적용 대상 (Scope)
본 표준은 HiveMQ 및 SCADA(Ignition/Cirrus Link 등) 기반의 Sparkplug B(3.0) UNS 인프라를 대상으로 합니다. 적용 대상은 다음과 같습니다:
- 토픽 네임스페이스 구조 및 인코딩 규칙
- 설비 및 게이트웨이 식별자(Identifier) 전역 유일성 체계
- 메트릭 명명 및 UDT(User Defined Type) 스키마 거버넌스
- 메트릭 별칭(Alias) 할당 및 수명주기 정책
- NCMD 제어 명령에 대한 계층형 접근 통제(ACL) 및 인가
- 고가용성 상태 관리 및 장애 복구(Failover) 절차

---

## 2. ISA-95 모델과 Sparkplug B 토픽 매핑 (ADR-0010)
Sparkplug B 표준의 고정 4단계 토픽 구조(`spBv1.0/{group}/{msgtype}/{edge}[/{device}]`)와 ISA-95의 6단계 공정 모델(`Enterprise/Site/Area/Line/Cell/Device`)을 다음과 같이 상호 매핑합니다:

### 2.1 계층 인코딩 규칙
- 상위 물리/조직 계층을 `group_id` 및 `edge_node_id`에 구분자(`:`)로 인코딩합니다.
  - `group_id` = `"{Enterprise}:{Site}:{Area}"` (예: `Acme:Busan:Press`)
  - `edge_node_id` = `"{Line}:{Cell-or-Gateway}"` (예: `L1:GW3`)
  - `device_id` = 물리적 설비 고유 식별자 (예: `Press01`)
  - 설비 내부 구조: **메트릭 경로(Metric Path)** 로 표현 (예: `Hydraulics/Pump/Pressure`)
- **트레이드오프 명시**: 단일 토픽 세그먼트 내 계층 인코딩 방식으로 인해 MQTT 와일드카드를 통한 "특정 Area 전체 구독" 등의 부분 계층 필터링이 제한됩니다. 이러한 횡단 분석 쿼리는 별도의 JSON 브리지(ADR-0004) 또는 다운스트림 인덱싱 엔진으로 보완합니다.
- 계층 구분자(`:`)는 네임스페이스 내부 예약어로 지정되며, 일반 명명 문자열 내 사용이 엄격히 금지됩니다 (§3).
- **OPC UA 데이터 소스 연계**: OPC UA Browse 계층을 ISA-95 UNS 경로로 정규화할 때, 네임스페이스 인덱스(`ns=`)만 다르고 BrowseName이 동일한 노드는 메트릭 충돌이 발생할 수 있으므로 식별자 정규화 규칙을 적용합니다 (ADR-0010 평탄화 규칙 준수).

### 2.2 Kafka Egress 매핑 (IT 데이터 파이프라인 확장, ADR-0009)
UNS 데이터를 상위 엔터프라이즈 분석 및 카프카 스트림으로 중계할 때 동일한 ISA-95 계층을 토픽명으로 확장합니다:
- **Kafka 토픽명**: `uns.{Enterprise}.{Site}.{Area}.{Line}.{Cell}` (구분자 `:` → `.`)
- **메시지 키 (Message Key)**: `{Device-or-Cell}/{metricPath}` (메트릭 고유 식별자)
  - **로그 압축(Log Compaction) 기반 상태 복원**: Sparkplug RBE의 최신값 의미론을 Kafka Log-compacted 토픽의 마지막 레코드 보존 특성과 1:1로 정합시켜, 소비자가 오프셋 0부터 읽더라도 전체 설비의 최신 상태를 즉시 복원할 수 있도록 지원합니다.
- **계약 위반 데이터 격리**: 스키마 레지스트리(ADR-0007) 대조 결과 타입 불일치 또는 미등록 메트릭이 검출되면 메인 스트림 오염을 방지하기 위해 Dead Letter Queue(`uns.dlq`) 토픽으로 즉시 격리합니다.
- **수명주기 전파**: NBIRTH는 초기 상태 확립, NDEATH는 STALE 톰스톤(Tombstone) 레코드로 변환하여 다운스트림 소비자가 설비의 통신 두절을 명시적으로 인지하도록 합니다.

---

## 3. 식별자 명명 규약 및 전역 유일성 (ADR-0006)
- `group_id`, `edge_node_id`, `device_id`는 전사 범위에서 **전역 유일성(Globally Unique)** 을 가져야 하며, 중앙 식별자 레지스트리를 통해 발급 및 관리합니다.
- 파생되는 **MQTT Client ID 유일성 보장**: `edge-{group}-{edge}` 명명 규칙을 적용하여 중복 접속 시 발생하는 세션 탈취(Session Takeover) 및 LWT NDEATH 연쇄 플래핑(Flapping) 장애를 원천 방지합니다.
- **허용 문자 집합**: `[A-Za-z0-9_-]`, 계층 구분자 `:`(group 한정). 공백, `/`, MQTT 예약 와일드카드(`+`, `#`) 사용 금지.
- **브로커 감시 체계**: 짧은 시간 내 빈번한 재접속을 감지하는 플랩 감지(Flap Detection) 모니터링을 상시 운용합니다.

---

## 4. 메트릭 명명 규칙 및 데이터 계약
- **메트릭 경로**: 설비 내부의 기능적 의미 계층을 반영합니다 (`Subsystem/Component/Signal`).
- **데이터 계약 명세**: 각 메트릭은 데이터 타입, 엔지니어링 단위(`engUnit`), 허용 범위 및 유효성 검증 규칙을 공식 계약 문서로 관리해야 합니다.
- **JSON 브리지 경로 정합성 (ADR-0004)**: `uns/{group}/{edge}/{metricPath}` 경로를 사용하여 원시 Sparkplug 메트릭 경로와 1:1 대칭을 엄격히 유지합니다 (경로 드리프트 금지).

---

## 5. UDT 스키마 거버넌스 및 시맨틱 버저닝 (ADR-0005, ADR-0007)
- **정본 원천(Source of Truth)**: UDT 정의의 정본은 Git 형상 관리 기반의 외부 스키마 레지스트리(`registry/udt/`)이며, NBIRTH의 `_types_/`는 전송 시점의 표현(Wire Truth)으로 취급합니다.
- **유의적 버전 규칙(SemVer) 강제**:
  - 하위 호환 필드 추가: Minor 버전 증가.
  - 필드 삭제 또는 데이터 타입 변경: Major 버전 증가 및 신규 `templateRef` 분리 (`Motor` → `Motor2`)로 기존 소비자 보호.
- **소비자 측 유효성 검증**: 소비자는 수신된 버전의 Major 번호를 검증하여 미지 버전 수신 시 해당 데이터를 격리하고 경보를 발령합니다.
- **OPC UA ObjectType 사상 규칙 (ADR-0010)**:
  - ObjectType 상속 및 `HasInterface` 다중 상속을 결정론적 순서로 평탄화합니다 (`TypeFlattener`: 최하위 파생 오버라이드, 인터페이스 중복 제거, 충돌 시 결정론적 폴백, 타입 자체 멤버 우선).
  - **손실 원장(`LossLedger`) 및 부가 채널 운용**: 변환 시 발생하는 정밀도 및 타입 축소를 수치로 기록하며, 32비트 원시 상태 코드(`ua_statuscode`) 및 100ns 에포크 틱(`ua_ticks`)을 부가 채널로 무손실 보존합니다.
- **CI 게이트 강제 메커니즘 (ADR-0007)**: `SchemaGate` CLI를 통해 PR 및 빌드 단계에서 비호환 변경(Breaking Changes)을 기계적으로 차단(Non-zero Exit)합니다.

---

## 6. 메트릭 별칭(Alias) 할당 및 관리 정책
- 메트릭 별칭은 **엣지 노드별 독립적인 정수값**으로 부여되며, NBIRTH 세션이 확립된 이후부터 유효합니다.
- `NodeId / Metric Name ↔ Alias` 매핑은 노드 재기동 및 Rebirth 이후에도 불변하도록 안정적으로 관리되어야 합니다.
- NBIRTH를 수신하지 못한 지연 접속 소비자를 위해 Aware 브로커의 상태 증명(ADR-0002)을 활용하거나 브리지를 통해 매핑을 보장합니다.

---

## 7. 제어 명령 인가 및 보안 거버넌스 (ADR-0011)
- 토픽 ACL: 발행 및 구독 권한을 `group` 및 `edge` 단위로 최소 할당합니다.
- **NCMD/DCMD(OT 제어 명령) 인가**: 장비 물리 제어 경로이므로 엄격한 보안 감사가 요구됩니다.
- **계층형 인가 아키텍처 (Defense-in-Depth)**:
  - NCMD 토픽(`spBv1.0/{group}/NCMD/{edge}`)은 명령 식별자를 포함하지 않으므로, 브로커 토픽 ACL은 **노드 레벨 네트워크 도달성**만을 제어합니다.
  - 명령 식별자 및 파라미터 수치 범위에 대한 세부 인가는 페이로드를 직접 검사하는 엣지 레벨의 `CommandAuthorizer`가 **기본 거부(Deny-by-Default / Fail-Closed)** 원칙으로 집행합니다.
  - 단일 정책 원천(`registry/command-policy.json`)을 브로커 ACL 아티팩트(`BrokerAclProjector`), 엣지 인가 엔진, CI 린트 게이트(`CommandPolicyGate`)로 일관되게 전개합니다.

---

## 8. 상태 수명주기 및 장애 복구(Failover) 거버넌스 (ADR-0001, ADR-0002)
- **단일 정본 기록 시스템(System of Record) 연동**: Store-and-Forward(S&F)는 히스토리안 등 단 하나의 핵심 기록 시스템 가용성에만 바인딩하여 다중 소비자 간의 상태 상충을 방지합니다.
- **접속 시점 상태 동기화**: 임의의 신규 소비자는 대규모 Rebirth 폭풍을 유발하지 않고, Aware 브로커가 보관하는 상태 증명(`$sparkplug/certificates/#`)을 구독하여 즉시 최신 상태를 취득합니다.
- **경합 조건 방지**: 소비자는 데이터 토픽 구독을 완료한 후에 STATE online 메시지를 발행하여 백로그 유실을 차단합니다.

---

## 9. 런타임 관측 및 스키마 드리프트 거버넌스 (ADR-0012)
- **사전 배포 게이트(ADR-0007)와 사후 런타임 관측의 상호보완**: CI 레벨에서 스키마 계약을 사전 검증하고, 런타임에서는 실제 흐르는 NBIRTH 및 장비 활성도를 지속 관측하여 전체 정책 폐루프(Closed Loop)를 달성합니다.
- **수동 관측자 (`DriftMonitor`)**:
  - `spBv1.0/#` 토픽을 수동 구독(Detect-Only)하여 현장 OT 텔레메트리 트래픽의 임의 드롭이나 제어 차단 없이 안전하게 관측을 수행합니다.
  - 실제 수신된 UDT 정의를 레지스트리 최신본과 비교하여 `UNREGISTERED`, `VERSION_DRIFT`, `UNKNOWN_MEMBER`, `MISSING_MEMBER`, `TYPE_DRIFT` 등 5대 편차를 실시간 탐지합니다.
- **설비 침묵 상태(Staleness) 추적**: `LivenessTracker`를 통해 임계 시간을 초과하여 통신이 두절된 노드를 `STALE` 상태로 분류하고 전체 네임스페이스 적합률(Conformance Rate)을 산출합니다.

---

## 부록 — 관련 자산 및 구현 근거
- 소스 코드: 저장소 루트 `src/main/java/dev/krillin/sparkplug/`
- 아키텍처 결정 기록: `docs/adr/ADR-0001` ~ `ADR-0012`
- 표준 용어 사전: `docs/glossary.md`
