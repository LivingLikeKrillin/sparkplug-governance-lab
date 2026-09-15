# 표준 기술 용어 사전 (Glossary)

본 문서는 `sparkplug-governance-lab` 및 상위 IIoT 거버넌스 아키텍처 전반에서 사용하는 핵심 기술 용어의 표준 정의를 제공합니다. 시스템 설계, API 명세, 아키텍처 결정 기록(ADR) 및 다이어그램 간의 용어 드리프트(Term Drift)를 방지하고, 단일 진실의 원천(SSOT) 역할을 수행합니다.

---

## 1. 산업 공정 및 현장 도메인 (Manufacturing & Industrial Automation Domain)

### ISA-95 계층 모델 (ISA-95 Hierarchy)
제조 제어 및 엔터프라이즈 시스템 간의 통합 표준 구조입니다.
- **L1 (기기 제어)**: 센서, 밸브, 액추에이터 및 모터 드라이브.
- **L2 (공정 제어)**: PLC(Programmable Logic Controller), DCS, 임베디드 컨트롤러.
- **L3 (제조 운영 관리, MOM/MES)**: 공정 모니터링, 생산 실적 집계, 작업 지시 배포.
- **L4 (비즈니스 계획 및 물류, ERP)**: 전사적 자원 관리 및 생산 계획.
- 본 아키텍처에서는 ISA-95 계층 경로(`Enterprise/Site/Area/Line/Cell/Device`)를 Sparkplug 토픽 네임스페이스 및 Kafka 토픽명으로 정규화하여 매핑합니다.

### Unified Namespace (UNS, 통합 네임스페이스)
공장 내 모든 시스템(OT 설비, 엣지 게이트웨이, SCADA, MES, 클라우드 데이터 레이크)이 공통의 의미론적 계층 구조(ISA-95 기준)로 실시간 상태 데이터를 발행하고 구독하는 단일 중앙 아키텍처 허브입니다.

### Edge of Network (EoN) 노드
OT 현장 네트워크(PLC, 필드버스, 산업용 이더넷)와 IT/UNS 네트워크(MQTT 브로커)의 물리적·논리적 경계에 위치하여 프로토콜 변환, 데이터 컨텍스트화, 메트릭 별칭(Alias) 할당 및 상태 보고를 수행하는 주체 게이트웨이입니다.

### 제어 경계 (Control Boundary)
OT 설비에 대한 물리적 쓰기(Write/Command) 제어가 허용되는 물리적·논리적 보안 한계선입니다. 본 아키텍처에서는 브로커 토픽 ACL과 엣지 단의 인가 엔진을 분리하여 이중 방어를 구성합니다.

### 메트릭 (Metric) vs 태그 (Tag)
- **태그 (Tag)**: PLC 메모리 주소(예: `DB100.DBD0`, `MW20`)에 직접 바인딩된 원시 제어 변수.
- **메트릭 (Metric)**: 엔지니어링 단위, 데이터 타입, 의미 계층(`Subsystem/Component/Signal`)이 부여되어 데이터 계약으로 승격된 표준 데이터 항목.

---

## 2. 인터페이스 계약 및 데이터 모델 (Interface Contracts & Data Modeling)

### Sparkplug B 프로토콜
MQTT 위에서 산업용 토픽 네임스페이스(`spBv1.0/...`), 상태 수명주기(Birth/Death/Data), Protobuf 페이로드 인코딩 및 효율적인 대역폭 압축을 규정하는 산업 표준 사양(Eclipse Tahu 기반)입니다.

### UDT (User Defined Type, 사용자 정의 타입) / Template
Sparkplug 사양에서 복합 설비(예: 믹서, 모터, 펌프)의 데이터 구조를 선언하기 위한 템플릿 모델입니다. 정의(Definition)와 인스턴스(Instance)로 분리되며, 멤버 변수 목록, 파라미터 및 타입을 포함합니다.

### PropertySet
Sparkplug B 페이로드 내에서 메트릭 또는 UDT 멤버에 부가적인 메타데이터(엔지니어링 단위 `engUnit`, 데이터 품질 `quality`, 최소/최대 범위, 엔지니어링 스케일링)를 키-값 형태로 부착하기 위한 확장 메커니즘입니다.

### 메트릭 별칭 (Metric Alias)
대역폭 절감을 위해 긴 문자열 경로의 메트릭 이름 대신 할당되는 정수 식별자입니다. NBIRTH 시점에 `Name ↔ Alias` 매핑이 수립되며, 이후 NDATA에서는 정수 별칭만으로 데이터를 전송합니다.

### 손실 원장 (Loss Ledger)
풍부한 정보 모델(예: OPC UA)을 제한된 wire 포맷(예: Sparkplug B)으로 투영할 때 발생하는 정밀도 손실(DateTime 100ns → 밀리초), 상태 코드 축소, 타입 식별자 탈락을 정량적으로 기록하고 보존하는 명세입니다.
- 원본 정밀도와 식별자는 부가 채널(`ua_ticks`, `ua_statuscode` 등)을 통해 무손실로 병행 전송됩니다.

### 스키마-데이터 분리 (Schema-Data Separation)
대규모 UDT 정의를 매 NBIRTH마다 전체 인라인으로 전송하지 않고, 영속 보관(Retained) 토픽에 선제 발행한 후 실제 데이터 페이로드에는 얇은 참조(`schemaRef`)와 별칭 메트릭만 탑재하여 전송 효율을 극대화하는 아키텍처 패턴입니다.

---

## 3. 상태머신 및 수명주기 (State Machines & Lifecycle)

### Sparkplug 세션 수명주기
- **NBIRTH (Node Birth)**: 엣지 노드가 온라인 복귀 시 자신의 전체 메트릭 카탈로그, UDT 정의 및 별칭 매핑을 브로커에 등록하는 세션 초기화 메시지.
- **NDATA (Node Data)**: 상태 변경이 발생한 메트릭 값만을 RBE로 보고하는 주기적/이벤트성 데이터 메시지.
- **NDEATH (Node Death)**: 브로커의 LWT(Last Will and Testament) 메커니즘을 통해 엣지 노드의 비정상 단절 시 강제 발행되는 세션 종료 메시지.
- **DBIRTH / DDATA / DDEATH**: 엣지 노드 하위에 연결된 개별 물리 디바이스의 수명주기 메시지.

### Primary Host 가용성 및 Store-and-Forward (S&F)
Sparkplug 세션의 중심 기록계층(System of Record)으로 지정된 Primary Host의 생존 상태(`STATE` 토픽)에 연동되는 버퍼링 메커니즘입니다.
- Host가 오프라인으로 전환되면 엣지 노드가 데이터를 로컬 큐에 축적하고, Host 온라인 복귀 시 결손 없이 순차 재전송(Flush)합니다.

### 접속 시점 상태 복원 (State-on-Connect / Aware Broker)
새로운 소비자가 브로커에 늦게 접속(Late Joiner)했을 때, 전체 엣지에 대해 고비용의 Rebirth를 강제하지 않고 브로커가 보관 중인 마지막 상태 증명(Retained Certificate)을 즉시 전달하여 상태를 동기화하는 패턴입니다.

### 세션 경합 및 단절 폭풍 (Flap / Session Takeover)
동일한 MQTT Client ID가 중복 접속을 시도할 때 기존 연결이 강제 종료되고 LWT NDEATH가 발행되며, 재접속 루프가 연쇄 폭발하여 네트워크를 마비시키는 장애 현상입니다. 안정적인 Client ID 전역 유일성 보장이 필수적입니다.

---

## 4. 통신 및 신뢰성 프로토콜 (Communication & Reliability Protocols)

### MQTT QoS (Quality of Service)
- **QoS 0 (최대 1회 전송, At most once)**: 전송 확인 없음. 일반 시계열 텔레메트리에 적용.
- **QoS 1 (최소 1회 전송, At least once)**: 핸드셰이크(PUBACK) 확인. NCMD 제어 명령 및 S&F 버퍼 플러시에 적용.
- **QoS 2 (정확히 1회 전송, Exactly once)**: 4단계 핸드셰이크. 고지연 산업 환경에서는 대역폭 제약으로 제한적 사용.

### RBE (Report-by-Exception, 예외 보고)
이전 전송 값과 비교하여 임계치 이상의 변화(Deadband)가 발생한 메트릭만을 선별 전송하여 네트워크 대역폭 소비를 최소화하는 전송 원칙입니다.

### Kafka 로그 압축 (Log Compaction) 동형성
Sparkplug RBE의 "키별 최신값 유지" 의미론과 Kafka Log-compacted 토픽의 "동일 메시지 키에 대해 마지막 레코드만 영속화"하는 특성이 수학적으로 동형(Isomorphic)임을 의미합니다.

### Dead Letter Queue (DLQ)
계약 위반(스키마 불일치, 알 수 없는 필드, 타입 오류)이 발생한 페이로드를 정상 데이터 파이프라인에서 즉시 격리하여 격납하는 보조 큐입니다. 정상 소비 파이프라인의 오염 및 역직렬화 장애를 방지합니다.

### 역직렬화 불투명성 (Deserialization Opacity)
이진 프로토콜(Protobuf) 페이로드를 소비할 때, 스키마 정의를 알지 못하는 상위 IT 소비자가 페이로드 내부 구조를 조회할 수 없는 제약입니다. 본 아키텍처에서는 표준 JSON 브리지를 통해 이를 투명화합니다.

---

## 5. 운영 거버넌스 및 보안 인가 (Operational Governance & Security Authorization)

### 폐쇄형 실패 (Fail-Closed)
시스템 오류, 네트워크 장애, 스키마 불일치 또는 검증 실패가 발생했을 때 기본적으로 처리를 거부하고 안전 상태(Safe State)를 유지하는 보안 및 안정성 설계 원칙입니다.

### 기본 거부 (Deny-by-Default)
사전 등록된 명시적 허용 정책(Allowlist)이 존재하지 않는 모든 제어 명령 및 스키마 변경 요청을 차단하는 원칙입니다.

### 스키마 호환성 모드 (Schema Compatibility Modes)
- **FORWARD (전방 호환)**: 새로운 스키마로 작성된 데이터를 기존 소비자가 문제없이 읽을 수 있는 상태.
- **BACKWARD (후방 호환)**: 기존 스키마로 작성된 데이터를 새로운 소비자가 문제없이 읽을 수 있는 상태.
- **FULL (완전 호환)**: 전방 및 후방 호환성이 동시에 만족되는 상태.
- **NONE**: 호환성 검증을 수행하지 않는 상태.

### 유의적 버전 규칙 (SemVer Governance)
- **MAJOR**: 하위 호환성을 파괴하는 변경(멤버 삭제, 타입 축소 등). 신규 `templateRef` 분리 필수.
- **MINOR**: 하위 호환성을 유지하는 부가적 변경(멤버 추가 등).
- **PATCH**: 의미론적 영향이 없는 메타데이터 또는 설명 수정.

### NCMD 페이로드 단위 명령 인가 (Payload-level Command AuthZ)
MQTT 토픽(`spBv1.0/{group}/NCMD/{edge}`)에는 개별 명령 이름이나 파라미터가 명시되지 않으므로, 브로커 토픽 ACL(노드 도달성만 제어) 외에 페이로드 내부 메트릭(명령 식별자, 허용 수치 범위, 주체 신원)을 엣지 런타임에서 직접 검증하는 심층 방어 인가 체계입니다.

### 런타임 드리프트 감지 (Runtime Drift Detection)
실제 네트워크상에서 관측된 NBIRTH 페이로드의 UDT 스키마 및 메트릭 구조를 중앙 스키마 레지스트리의 정본 정의와 지속적으로 대조하여, 미등록 스키마(UNREGISTERED)나 버전 불일치(VERSION_DRIFT)를 실시간으로 탐지·경보하는 모니터링 체계입니다.

### 정본 원천 (Source of Truth) vs 전송 시점 사실 (Wire Truth)
- **정본 원천 (Source of Truth)**: 코드 리뷰 및 형상 관리를 거쳐 레지스트리에 공인 승인된 공식 스키마 정의.
- **전송 시점 사실 (Wire Truth)**: 실제 런타임 네트워크 페이로드(`_types_/`)에 실려 전달된 스키마.
- 거버넌스의 핵심 목적은 전송 시점 사실이 정본 원천과 일치함을 보증하고, 불일치 발생 시 이를 통제하는 것입니다.
