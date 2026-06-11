# ADR-0010 — OPC UA 정보모델 → Sparkplug UDT 매핑 거버넌스

- 상태: **Accepted (PoC)**
- 일자: 2026-06-10
- 관계: ADR-0007 레지스트리를 UDT 권위로, ADR-0008(#608/#607/#603) Definition/thin codec을 와이어로 직접 재사용. ADR-0005(UDT SemVer)와 정합. namespace-standard §2(ISA-95)/§5(UDT)에서 교차참조. 코드 `src/.../opcua/`, 데모 `OpcUaUdtBridgeDemo.java`.

## Context
전사 UNS가 OT 의미론을 1급으로 운반하려면 OPC UA **정보모델**(ObjectType 서브타입 상속 + `HasInterface` 다중상속 + NodeId + EURange/EngineeringUnits + 32비트 StatusCode + 100ns/1601 DateTime)을 Sparkplug **UDT**(상속 없는 flat 템플릿, edge-로컬 정수 alias, ms/1970 DateTime, 3-state quality)로 사상해야 한다. 두 타입 시스템은 표현력이 다르므로 이 매핑은 **무손실이 아니다**. 대부분의 Sparkplug/UNS 실무자는 IT/MQTT 측이라 OPC UA 타입 시스템을 다루지 않는다 — 이 매핑은 그 가장 어려운 OT↔IT 접점을 **동작 코드 + 명시적 손실원장**으로 통치하는 결정이다.

본 PoC는 라이브 Milo browse → `OpcUaTypeMapper` → `UdtDefinition` + `LossLedger` → #608 Definition(retained) + thin NDATA 왕복(ADR-0008) → 소비자 typed view + side-channel 복원을 실증한다(데모 exit 0).

## Decision

### 1. 손실경계 = 1급 산출물 (무손실 아님)
- DataType 매핑(`UaDataTypeMapper`)은 멤버마다 **LossClass**(CLEAN / PRECISION_LOSS / TYPE_IDENTITY_LOSS / SIDE_CHANNEL_REQUIRED)를 부여하고 `LossLedger`로 집계한다. 데모가 멤버별 원장 라인 + 요약(예: "9 members: 8 clean / 1 side-channel preserved / 0 type-identity lost")을 출력 — 정직성을 눈에 보이는 산출물로.
- **무손실 진실은 side-channel property에 verbatim 보존:**
  - `ua_statuscode`(UInt32) — 32비트 StatusCode 원본. quality 투영(#603)이 GOOD로 평탄화한 Uncertain도 여기서 severity 비트로 복원 가능. 데모: `ua_statuscode=0x40000000 severity=Uncertain (quality 투영=GOOD; 진실은 ua_statuscode)`.
  - `ua_ticks`(Int64) — DateTime 멤버의 원본 100ns/1601 틱. Sparkplug ms/1970은 10,000× 정밀도 + 에포크 손실이므로 SOE/서브-ms 충실도가 필요하면 이 틱으로 복원.
- 이 side-channel은 ADR-0008 `ThinCodec`을 **건드리지 않고** 신규 순수 `OpcUaThinCodec`이 확장 PropertySet으로 빌드(ADR-0008 시그니처/테스트 불변).

### 2. 평탄화 규칙 (`TypeFlattener` — 핵심 거버넌스 로직)
결정적 멤버 순서 = ① target own(선언순) → ② supertype 체인 own(위로) → ③ `HasInterface` 순서로 각 인터페이스(인터페이스 supertype 체인 포함). 이 순서가 **alias 입력**(ADR-0008 `alias=i+1`).
- **서브타입 override = most-derived 채택:** 동명 멤버가 파생/상위에 모두 있으면 가장 파생된 선언의 타입/eng을 채택(위치 고정), provenance에 OWN+SUPERTYPE 둘 다 기록. **충돌 아님.**
- **인터페이스 dedup:** 두 인터페이스가 동명 멤버를 같은 타입으로 선언 → 단일 `FlatMember`(provenance=두 INTERFACE). **타입 충돌이면** `Conflict` 표면화 + **결정적 폴백**(첫 등장 채택, 충돌 기록 — 드롭 아님).
- **ObjectType-own 우선:** ObjectType 멤버 vs 인터페이스 동명 멤버 충돌 시 own 채택(OWN/SUPERTYPE가 끼면 충돌 아님, override로 간주).

### 3. alias = 평탄화 순서
alias는 평탄화 결정 순서로 `i+1`(ADR-0008 `DefinitionCodec.aliasOf`와 동형, 왕복 검증). alias는 edge-로컬 BIRTH 내 식별자이지 이식 가능한 NodeId가 아니다. 안정성은 correctness invariant가 아니라 거버넌스 정책(소비자 rebirth-miss 방어 + historian 키 안정).

### 4. #608/#607/#603 수렴의 OT측 구현체
- #608 Definition = OPC UA TypeDefinition을 인스턴스에서 분리하는 방식과 동형 → ADR-0007 레지스트리가 권위, retained Definition이 wire truth.
- #607 engUnit/engLow/engHigh ↔ OPC UA `EngineeringUnits`(EUInformation)/`EURange`. 최소범위 = engUnit 문자열(데모는 Rpm=rpm).
- #603 quality(GOOD/STALE/BAD) ↔ StatusCode severity의 **손실 투영**(아래 "알려진 한계" 참조).

## 알려진 한계 / 비범위 (정직하게 명시)

- **다이아몬드 상속 순회 한계(OUT OF SCOPE, 문서화):** `TypeFlattener`는 visited-set이 없다. 한 노드가 두 인터페이스 경로로 도달 가능하면(다이아몬드) **두 번 순회** → provenance 중복 / 가짜 충돌(spurious Conflict) 가능. OPC UA 타입 그래프는 비순환(acyclic)이므로 무한루프/크래시는 없다. PoC 모델(명판 2개는 BaseInterfaceType 외 공통 상위 없음)에선 발생하지 않으며, 일반 해법(노드별 visited-set + provenance 병합)은 PoC 범위 밖.
- **다중서버 alias 네임스페이싱 = 설계만(비범위):** 다중 OPC UA 서버를 한 edge로 묶으면 각 서버가 같은 alias를 발행해 edge 범위 유일성 위반. 레지스트리가 소스별 alias 공간을 네임스페이싱해야 한다. 2서버 필요 → 코드 X, 설계만 기록.
- **BIRTH 정의 freeze 갭:** 인스턴스 발행 전 전체 ObjectType를 browse해 멤버를 BIRTH 시점에 고정. address space가 동적이라 BIRTH 후 멤버 등장 시 rebirth로 정의 재선언 = BIRTH-storm 악화. 본 PoC는 단발 browse→freeze만.
- **인터페이스 다중상속 실무 빈도 근거:** OPC UA Part 3는 ObjectType **단일상속 시맨틱만** 정의 — 다중상속은 **오직 `HasInterface`(+`HasAddIn`)** 으로(1.04 도입). Machinery(OPC 40001)/DI(OPC 10000-100)의 `IVendorNameplateType`/`ITagNameplateType` 명판이 전부 인터페이스 기반 → **정보모델 리치 서버에서는 인터페이스 평탄화가 거의 확정**(table-stakes). 반면 평탄 태그 서버(Kepware류)는 ObjectType 자체가 없어 ObjectType→UDT 매핑이 무의미 → 비범위.
- **Uncertain→GOOD 투영의 정직성:** 일반 Uncertain은 quality에서 GOOD로 투영(손실), `UncertainLastUsableValue`류만 STALE. **Uncertain ≢ STALE**(STALE 동치는 historian이 제시간 값을 stale로 오판). 무손실 진실은 항상 `ua_statuscode`에 있고 데모가 severity 비트를 디코드해 증명한다.
- 기타 비범위: 평탄 태그 서버 폴더 browse, RBE↔OPC UA 구독 임피던스(폴링만), 다중서버 단일 seq 직렬화, ModellingRule 강제(메타데이터 보존만), 중첩 Structure→중첩 UDT, 25개 빌트인 DataType 전체(대표 11종으로 4 LossClass 전부 커버), 에러처리/재접속/HA.

## Consequences
- OPC UA 타입시스템(상속·인터페이스 다중상속·NodeId·EURange·StatusCode)→UDT 매핑이 **동작 코드 + 손실원장**으로 = "OT 정보모델 거버넌스" 역량을 설계에서 증명으로. ADR-0007(권위)·ADR-0008(#608/#607/#603) 직접 재사용으로 일관.
- 순수 코어(`UaDataTypeMapper`/`TypeFlattener`/`OpcUaTypeMapper`/`LossLedger`/`OpcUaThinCodec`)는 서버 없이 TDD 검증, 라이브 셸(`OpcUaBrowser`/`OpcUaInstanceReader`/데모)은 같은 코드를 라이브로 실증 → fixture가 못 만드는 충돌 케이스도 단위테스트로 강하게 증명.
- 한계는 모두 문서화(다이아몬드 순회·다중서버 alias·BIRTH freeze·Uncertain 투영) — 손실 경계를 숨기지 않고 명시하는 것이 이 설계의 원칙.

## Links
- 코드(순수+셸): `../../src/main/java/dev/krillin/sparkplug/opcua/`
- 데모: `../../src/main/java/dev/krillin/sparkplug/OpcUaUdtBridgeDemo.java`
- 시뮬: `../../opcua-sim-server.py`
- 선행: ADR-0005(UDT SemVer), ADR-0007(레지스트리 게이트), ADR-0008(#608/#607/#603), `namespace-standard.md` §2/§5
