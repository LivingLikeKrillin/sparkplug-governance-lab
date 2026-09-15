# ADR-0012 — 런타임 스키마 드리프트 감지(Detect-Only) 및 배포 사후 관측 체계

- 상태: **Accepted**
- 일자: 2026-06-11
- 관계: ADR-0007(사전 배포 게이트)의 **사후 런타임 관측 및 피드백 보완 체계**. 소스 코드: `src/.../drift/`, 모니터 데몬: `DriftMonitor`, 데모: `DriftMonitorDemo.java`.

## 1. 배경 및 맥락 (Context)
ADR-0007은 스키마 레지스트리와 CI 게이트를 통해 배포 사전 단계(Shift-Left, Fail-Closed)에서 UDT 데이터 계약을 강제합니다. 

그러나 배포 사후 런타임 환경에서 실제로 전송되는 NBIRTH 페이로드가 공인된 스키마 계약과 일치하는지, 비인가 수정을 거친 장비가 유입되었는지, 특정 노드가 장시간 침묵(Staleness) 상태인지, 전사 네임스페이스의 스키마 준수율(Conformance Rate)이 어느 수준인지를 실시간으로 모니터링할 수 있는 관측 체계가 요구됩니다. 

현장 OT 가동률 최우선 원칙에 따라, 런타임 관측은 실제 제어 및 텔레메트리 트래픽을 인라인 차단하지 않고 순수 수동 감시(Passive Observation)로 동작해야 합니다.

## 2. 아키텍처 결정 (Decision)
1. **순수 수동 관측 (Detect-Only / Non-Invasive)**:
   - `spBv1.0/#` 토픽을 수동 구독(Passive Subscription)하여 트래픽을 모니터링합니다.
   - 데이터 재발행, 강제 드롭, DLQ 라우팅을 수행하지 않으며, 현장 OT 데이터 흐름에 일체의 간섭이나 중단을 유발하지 않습니다 (감사 로그, 이벤트 알림, 거버넌스 건전성 메트릭만 산출).
2. **스키마 드리프트 대조 메커니즘**:
   - `TemplateAdapter`를 통해 NBIRTH의 `_types_/<ref>`로부터 실제 관측된 UDT 정의(`observed UdtDefinition`)를 추출하고, 중앙 레지스트리의 최신 정본(`DefinitionStore.latest`)과 대조합니다.
   - 편차 유형 5종 분류:
     - `UNREGISTERED`: 레지스트리에 등록되지 않은 신규 스키마.
     - `VERSION_DRIFT`: 버전 번호 불일치.
     - `UNKNOWN_MEMBER`: 정본에 정의되지 않은 신규 멤버 관측.
     - `MISSING_MEMBER`: 정본에 선언된 필수 멤버 누락.
     - `TYPE_DRIFT`: 공통 멤버의 데이터 타입 불일치.
3. **시간 차원 활성도 추적 (`LivenessTracker`)**:
   - 노드별 마지막 통신 시각을 기록(Clock 주입을 통해 결정론적 단위 테스트 지원)하고, 사전 설정된 임계 시간을 초과하여 메시지가 없는 노드를 `STALE` 상태로 전이합니다 (NDEATH 수신 시에는 추적 일시 중지, 신규 메시지 수신 시 정상 복원).
4. **전사 거버넌스 건전성 지표 (`GovernanceHealth`) 산출**:
   - 활성 노드 수, 적합 노드 수, 네임스페이스 적합률(Conformance Rate), 드리프트 유형별 건수, 침묵(Stale) 노드 수를 실시간 스냅샷 메트릭으로 제공합니다.

## 3. 결과 및 영향 (Consequences)
- **폐루프(Closed Loop) 거버넌스 완성**: 배포 전 CI 게이트(ADR-0007)와 배포 후 런타임 관측(본 ADR)이 상호 결합하여, 정책 수립-강제-검증의 전체 거버넌스 피드백 루프를 완결합니다.
- **ADR-0009(Kafka Egress 게이트)와의 역할 분담**:
  - ADR-0009: Kafka로 향하는 데이터 평면 게이트(위반 시 DLQ 라우팅, 메시지 차단).
  - 본 ADR: 전체 UNS에 대한 모니터링 평면(수동 관측, 비차단, 전사 건전성 지표 산출).
- **구현 제약 사항**: 본 구현은 단일 프로세스 관측 데몬으로 동작하며, NDATA 내부의 개별 메트릭 값 수준의 통계적 드리프트는 본 사양의 범위에 포함되지 않습니다 (NBIRTH 구조 및 활성도 추적에 집중).

## 4. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/drift/`
- 데몬 및 라이브 데모: `src/main/java/dev/krillin/sparkplug/DriftMonitor.java`, `DriftMonitorDemo.java`
- 연계 문서: ADR-0007 (스키마 레지스트리 게이트), ADR-0009 (Kafka 브리지)
