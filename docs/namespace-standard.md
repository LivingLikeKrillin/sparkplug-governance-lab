# UNS 네임스페이스 거버넌스 표준 (draft v0.1)

> 상태: 🟢 초안. PoC 실험(SessionDemo/LateJoiner/Udt/StateStoreForward/StolenSession/JsonBridge)과 ADR-0001~0012를 종합한 거버넌스 표준.
> 목적: 전사 UNS에서 토픽·식별자·데이터모델·접근·상태를 일관되게 통치.

## 1. 범위
HiveMQ + Ignition(+Cirrus Link) 기반 Sparkplug B(3.0) UNS. 대상: 토픽 네임스페이스, 식별자, metric/UDT 스키마, alias, 접근통제, 상태/페일오버.

## 2. ISA-95 ↔ Sparkplug 토픽 매핑  
Sparkplug 고정 4단 `spBv1.0/{group}/{msgtype}/{edge}[/{device}]` vs ISA-95 6단(Enterprise/Site/Area/Line/Cell/Device).
- **규약:** 상위 계층을 `group_id`에 구분자로 인코딩.
  - `group_id` = `"{Enterprise}:{Site}:{Area}"`  (예: `Acme:Busan:Press`)
  - `edge_node_id` = `"{Line}:{Cell-or-Gateway}"` (예: `L1:GW3`)
  - `device_id` = 물리 장비 (예: `Press01`)
  - 장비 하위 구조는 **metric 경로**로 (예: `Hydraulics/Pump/Pressure`).
- **트레이드오프(명시):** group_id 인코딩은 깔끔한 native 계층이 아님 → "Area 전체 구독" 같은 **부분 와일드카드 불가**. 분석용 횡단 조회는 JSON 브리지(uns/, ADR-0004)나 소비측 인덱싱으로 보완.
- 구분자(`:`)는 식별자 내에서 예약 — §3 명명에서 금지문자로.
- **OPC UA 소스(ADR-0010):** OPC UA browse 계층(서버-로컬 `ns=` 인덱스)을 ISA-95 UNS 경로로 정규화할 때 ns만 다른 동명 BrowseName은 평탄 metric에서 충돌 → 디스앰비규에이션 필요. NodeId→alias는 §6 + ADR-0010 규약(평탄화 순서 `alias=i+1`, 다중서버 네임스페이싱은 비범위).

### 2.1 Kafka egress 매핑 (IT 경계 확장, ADR-0009)
UNS를 IT 분석(Kafka)으로 넘길 때 동일 ISA-95 매핑을 토픽명으로 확장한다:
- **Kafka 토픽** = `uns.{Enterprise}.{Site}.{Area}.{Line}.{Cell}` (group/edge 구분자 `:` → `.`).
- **메시지 키** = `{Device-or-Cell}/{metricPath}` (metric 정체성). → **log-compacted 토픽 = last-known-value 스토어**: Sparkplug RBE(변한 것만 전송)의 현재상태 의미론을 Kafka 컴팩션으로 보존. 소비자는 from-beginning 읽기로 전 metric 최신값 복원.
- **계약 위반**(타입불일치/미지 metric, ADR-0007 레지스트리 대조) → **DLQ 토픽** `uns.dlq`(메인 토픽 미오염).
- 상태: NBIRTH=초기 상태 확립, NDEATH=STALE tombstone(키 삭제 아님 — staleness를 값으로 노출).

## 3. 식별자 명명 규약 (전역 유일)  (ADR-0006)
- `group_id`/`edge_node_id`/`device_id`는 **전역 유일**, 중앙 **레지스트리**가 발급·소유 관리.
- 파생 **MQTT client-id도 유일**(= `edge-{group}-{edge}` 규약). 충돌 시 takeover→NDEATH 폭풍.
- 허용: `[A-Za-z0-9_-]`, 계층 구분자 `:`(group 한정). 공백·MQTT 와일드카드(`+`,`#`)·`/` 금지.
- 브로커에 **flap 감지/경보**(잦은 재접속) 운영.

## 4. Metric 명명 & 데이터 계약
- metric 경로 = 장비 내부 의미 계층(`Subsystem/Component/Signal`).
- 각 metric: datatype·엔지니어링 단위(properties)·범위 정의를 데이터 계약으로 문서화.
- 브리지 JSON 경로(ADR-0004) = `uns/{group}/{edge}/{metricPath}` — Sparkplug metric 경로와 1:1 유지(드리프트 금지).

## 5. UDT(Template) 스키마 거버넌스 & 버저닝  (ADR-0005/0007)
- UDT 정의는 **외부 레지스트리 + 리뷰**가 source of truth (NBIRTH `_types_/`는 wire truth).
- **SemVer 강제(거버넌스가):** 멤버 추가=minor(하위호환), 삭제/타입변경=major → **새 `templateRef`로 분리**(v1 소비자 보호).
- 소비자는 수신 version의 major를 기대값과 대조, 미지 major 거부/경보. CI로 additive-only 게이트.
- OPC UA 정보모델 사상은 ADR-0010 규약 따름.
- **OPC UA ObjectType → UDT 소스 매핑(ADR-0010, 구현됨):** ObjectType(서브타입 상속 + `HasInterface` 다중상속)을 평탄화해 UDT 멤버 집합을 도출(`TypeFlattener`: most-derived override / 인터페이스 dedup·결정적 충돌 폴백 / ObjectType-own 우선). **무손실 아님** — 멤버별 손실원장(`LossLedger`) + side-channel(`ua_statuscode` UInt32 verbatim / `ua_ticks` Int64 원본 100ns)로 무손실 진실 보존. #607 engUnit ↔ OPC UA EngineeringUnits/EURange, #603 quality ↔ StatusCode severity(손실 투영). 코드 `src/.../opcua/`, 데모 `OpcUaUdtBridgeDemo`.

> **강제 메커니즘(ADR-0007):** 위 UDT 버전·소유권 규약은 문서가 아니라 **스키마 레지스트리 게이트**로 강제된다. `registry/udt/<ref>/<semver>.json`(source of truth) + `policy.json`(기본 FORWARD) + `SchemaGate` CI 게이트(breaking→non-zero exit). 코드/데모: `src/.../schema/`, `SchemaGateDemo.java`.

## 6. Alias 할당 정책
- alias는 **edge별 정수, NBIRTH 이후 유효**. NodeId/metric명 ↔ alias 매핑을 edge별 **안정·유일**하게 레지스트리 관리(재기동·rebirth 후 동일).
- NBIRTH 누락 소비자 보호: aware-cert(ADR-0002) 또는 rebirth로 매핑 재수신.

## 7. 접근통제 / 명령 거버넌스
- 토픽 ACL: 발행/구독 권한을 group/edge 단위로.
- **NCMD/DCMD(OT writeback)** publish 권한은 최소권한 + 감사 — OT 장비 명령 경로이므로 보안 핵심.
- `$sparkplug/certificates/#`(ADR-0002) 및 `uns/#`(ADR-0004) 읽기 권한도 ACL 대상.

**계층형 강제 메커니즘 (ADR-0011).** NCMD 명령 이름은 토픽이 아닌 payload metric 안에 있으므로(`spBv1.0/{group}/NCMD/{edge}` 한 토픽에 Rebirth·Reboot·Setpoint 공존) 브로커 토픽 ACL은 구조상 **노드 단위 도달성**(누가 이 노드에 명령 가능한가)까지만 강제한다. per-command·per-value 인가는 payload를 보는 edge에서만 가능하다. 따라서 **단일 정책 소스 1개**(`registry/command-policy.json`, deny-by-default)를 **두 강제 지점 + 한 CI 게이트**로 전개한다: ▶강제① `CommandAuthorizer`(edge 층 — 명령 allowlist + 값 범위/타입, fail-closed, 런타임 강제) ▶강제② 브로커 토픽 ACL(신원→노드 도달성, MQTT auth가 강제) ◆아티팩트 `BrokerAclProjector`(②를 위한 브로커 ACL 표현 산출 — principal→NCMD 토픽 PUBLISH, `*`→MQTT `+`; 투영·검증만, 라이브 RBAC 미가동) ◆CI `CommandPolicyGate`(shift-left 린트, non-zero exit). 코드 경로 `src/main/java/dev/krillin/sparkplug/acl/`, 라이브 데모 `CommandAclDemo`.

## 8. 상태·페일오버 거버넌스  (ADR-0001/0002)
- **store-and-forward는 단일 시스템-of-record(historian)** 를 primary host로 게이트(완결성). edge는 단일 `primaryHostId`에 바인딩.
- **임의 소비자의 현재상태는 aware 브로커 cert(ADR-0002)** 로 — rebirth 폭풍 회피. 역할 분리가 n:1↔n:m 간극의 답.
- 소비자는 **구독 후 STATE online 선언**(구독 완료 전에 flush가 도착하는 race 회피).

## 9. 관측·드리프트 거버넌스  (ADR-0012)
- **배포前(ADR-0007) ↔ 런타임(ADR-0012) 루프.** ADR-0007 `SchemaGate`는 배포 前 CI에서 데이터계약을 fail-closed 강제(ADR-0007). ADR-0012는 배포 後 런타임에서 실제 흐르는 NBIRTH/생명성을 관측 — 둘이 합쳐 "CI 강제 + 런타임 관측"의 완결 루프.
- **DriftMonitor = `spBv1.0/#` passive 관측자(detect-only).** NBIRTH의 UDT 정의(`_types_/<ref>`, `TemplateAdapter`로 추출)를 `DefinitionStore.latest(ref)` source-of-truth와 대조해 스키마 드리프트 탐지: `UNREGISTERED`/`VERSION_DRIFT` + 멤버 3종(`UNKNOWN_MEMBER`/`MISSING_MEMBER`/`TYPE_DRIFT`). raw 편차(CompatMode 시맨틱 아님).
- **staleness.** 노드별 마지막 관측 시각(clock 주입) 추적 → 임계 초과 무발행 시 `STALE`. NDEATH 제외, rebirth는 death 해제.
- **거버넌스 건강 스냅샷.** 총노드·conformant·conformance rate·kind별 drift 건수·stale 수 집계(append-only 감사 누적).
- **detect-only — OT 절대 안 떨굼**(재발행·드롭·DLQ 없음 — OT는 런타임 차단 대신 관측한다는 원칙). ADR-0009(§2.1)는 데이터플레인 DLQ 게이트, ADR-0012는 관측 레이어로 역할 분리.
- 한계: node-level / NDATA 값 드리프트 비범위 / 단일 프로세스 PoC / ADR-0007 레지스트리 재사용. 코드 `src/.../drift/`, 데모 `DriftMonitorDemo`.

## 부록 — 근거
PoC 코드: repo 루트 `src/`. ADR: `docs/adr/`.
