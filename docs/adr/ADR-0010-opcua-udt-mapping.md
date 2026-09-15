# ADR-0010 — OPC UA 정보 모델의 Sparkplug UDT 매핑 거버넌스 및 손실 원장(Loss Ledger)

- 상태: **Accepted (개념 검증 PoC)**
- 일자: 2026-06-10
- 관계: ADR-0007 레지스트리를 UDT 권위로 활용, ADR-0008(#608/#607/#603) 스키마 분리 및 경량 코덱 재사용. ADR-0005(UDT SemVer) 사양 준수. 네임스페이스 표준(`namespace-standard.md` §2, §5) 반영. 소스 코드: `src/.../opcua/`, 데모: `OpcUaUdtBridgeDemo.java`.

## 1. 배경 및 맥락 (Context)
전사 UNS 아키텍처에서 현장의 풍부한 OT 의미론을 온전하게 전달하기 위해서는 OPC UA **정보 모델**(ObjectType 단일 상속 + `HasInterface` 다중 상속 + NodeId 식별자 + EURange/EngineeringUnits 메타데이터 + 32비트 StatusCode + 100ns/1601 에포크 DateTime)을 Sparkplug **UDT**(상속이 배제된 평탄한 템플릿, 엣지 로컬 정수 별칭, 밀리초/1970 에포크 DateTime, 3단계 품질 상태)로 변환해야 합니다.

두 타입 시스템 간의 구조적 표현력 차이로 인해 이 변환 과정은 필연적으로 **손실(Lossy)**을 수반합니다. 본 결정은 OPC UA와 Sparkplug 간의 구조적 간극을 정량적 손실 원장(`LossLedger`)과 확정적 평탄화 규칙을 통해 체계적으로 관리하는 엔지니어링 표준을 수립합니다.

본 PoC는 Eclipse Milo 기반의 실시간 Browse → `OpcUaTypeMapper` → `UdtDefinition` 및 `LossLedger` 도출 → Retained Definition 발행 및 경량 NDATA 전송(ADR-0008) → 소비자 측 정형 뷰 복원 및 부가 채널(Side-channel) 데이터 추출 전 과정을 실증합니다.

## 2. 아키텍처 결정 (Decision)

### 2.1 손실 경계의 1급 산출물화 (Loss Ledger)
- 데이터 타입 매핑 엔진(`UaDataTypeMapper`)은 각 멤버 변수별로 **LossClass**(CLEAN, PRECISION_LOSS, TYPE_IDENTITY_LOSS, SIDE_CHANNEL_REQUIRED)를 분류하고 `LossLedger`로 집계합니다.
- **무손실 데이터의 부가 채널(Side-channel) 병행 보존**:
  - `ua_statuscode`(UInt32): 32비트 원시 StatusCode 보존. Sparkplug 품질 투영(#603)에서 GOOD으로 처리된 Uncertain 상태라도 부가 채널의 심각도(Severity) 비트를 통해 완전 복원 가능.
  - `ua_ticks`(Int64): DateTime의 100ns/1601 에포크 틱을 원본 그대로 보존하여 밀리초 축소로 인한 시간 해상도 손실 방지.
- 부가 채널은 기존 ADR-0008의 코덱 인터페이스를 훼손하지 않고 `OpcUaThinCodec`의 확장 PropertySet으로 투명하게 구현됩니다.

### 2.2 결정론적 평탄화 규칙 (`TypeFlattener`)
멤버 정렬 순서는 다음과 같이 결정론적으로 수립되며, 이 순서가 곧 메트릭 별칭(Alias) 할당(`alias = i + 1`)의 기준이 됩니다:
1. 대상(Target) 자체 멤버 (선언 순서)
2. 상위 타입(Supertype) 체인의 자체 멤버 (상향 탐색)
3. `HasInterface` 관계를 통해 연결된 인터페이스 멤버 (인터페이스 상속 체인 포함)
- **서브타입 오버라이드 (Most-Derived 선호)**: 파생 및 상위 클래스에 동일 이름의 멤버가 존재할 경우 가장 최하위 파생 선언을 채택하며, 출처(Provenance)에 OWN 및 SUPERTYPE을 모두 기록합니다.
- **인터페이스 중복 제거 (Dedup) 및 충돌 해결**: 복수의 인터페이스가 동일 이름/타입의 멤버를 가질 경우 단일 `FlatMember`로 통합합니다. 만약 타입이 상충할 경우 `Conflict`를 명시적으로 기록하고 결정론적 폴백(최초 등장 항목 채택)을 적용합니다.
- **타입 자체 멤버 우선**: ObjectType 고유 멤버와 인터페이스 멤버 간 이름 충돌 시 고유 멤버를 우선 채택합니다.

### 2.3 차세대 Sparkplug 표준화(#608/#607/#603) 매핑 정렬
- #608 Definition: OPC UA의 TypeDefinition을 인스턴스와 분리 발행하는 아키텍처와 동형으로 정렬.
- #607 PropertySet: OPC UA의 `EngineeringUnits`(EUInformation) 및 `EURange`를 Sparkplug 메타데이터로 사상.
- #603 Quality: OPC UA의 StatusCode 심각도를 Sparkplug 3단계 품질 상태(GOOD, STALE, BAD)로 압축 투영.

## 3. 기술적 제약 사항 및 향후 과제 (Scope Limitations)
- **다이아몬드 상속 순회 제한**: 현재 구현은 노드 방문 집합(Visited-set)을 유지하지 않으므로, 동일 인터페이스가 다이아몬드 경로로 중복 참조될 경우 복수 순회로 인한 가짜 충돌(Spurious Conflict)이 보고될 수 있습니다 (OPC UA 타입 그래프는 비순환이므로 무한 루프는 발생하지 않음).
- **다중 서버 환경의 별칭 네임스페이스 격리**: 단일 엣지 아래 복수의 OPC UA 서버가 연계될 경우 서버 간 별칭 충돌이 발생할 수 있으므로, 중앙 레지스트리 기반의 서버별 별칭 공간 분리 설계가 향후 요구됩니다.
- **상태 코드 투영의 의미론적 손실**: 일반 `Uncertain` 상태는 사양상 제시간 유효 데이터로 해석될 여지가 있어 품질 투영 시 GOOD으로 처리되므로, 정확한 현장 진단이 필요한 소비자는 반드시 `ua_statuscode` 부가 채널을 조회해야 합니다.

## 4. 결과 및 영향 (Consequences)
- **OT 정보 모델의 투명한 거버넌스 확립**: 상속 및 복합 인터페이스를 평탄화하고 손실 경계를 수치로 보고하는 동작 코드를 확보함으로써, OT-IT 데이터 통합의 신뢰성을 입증하였습니다.
- **검증의 헤르메틱성(Hermeticity)**: Milo 기반의 실제 OPC UA 서버 연동 검증과 모의 객체 기반 단위 테스트(TDD)를 분리 구축하여 외부 서비스 의존 없이 100% 재현 가능한 테스트 스위트를 구성하였습니다.

## 5. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/opcua/`
- 라이브 데모: `src/main/java/dev/krillin/sparkplug/OpcUaUdtBridgeDemo.java`
- 연계 문서: ADR-0005 (UDT 버전 관리), ADR-0007 (스키마 레지스트리), ADR-0008 (스키마-데이터 분리)
