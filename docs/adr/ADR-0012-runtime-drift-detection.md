# ADR-0012 — 런타임 드리프트 탐지 (detect-only) / 배포 後 관측

- 상태: **Accepted**
- 일자: 2026-06-11
- 관계: ADR-0007(배포前 게이트)의 **런타임 짝**. 코드: `src/.../drift/`, 셸 `DriftMonitor`, 데모 `DriftMonitorDemo.java`.

## Context
ADR-0007은 UDT 데이터계약을 **배포 前 shift-left**(CI, fail-closed)로 강제한다. 그러나 배포 *후* 런타임에 실제로 흐르는 NBIRTH가 등록된 계약(source-of-truth)과 일치하는지, 노드가 조용히 죽었는지(staleness), 네임스페이스 전역 준수율이 얼마인지 **관측하는 것이 없다**. 두 원칙이 이 결정을 끌고 간다: "거버넌스를 관측 가능하게 — 안 보이면 안 지켜진다", 그리고 "OT는 런타임 차단 대신 관측".

## Decision
1. **detect-only 런타임 관측.** `spBv1.0/#` **passive 구독** — 재발행·드롭·DLQ 없음. **OT 데이터는 절대 안 떨군다.** 알림·감사·건강메트릭만.
2. **스키마 드리프트 = NBIRTH 정의 ↔ 레지스트리 `latest(ref)` source-of-truth.** `TemplateAdapter`로 NBIRTH `_types_/<ref>` 정의 Template을 observed `UdtDefinition`로 추출해 대조 → 5 kind: `UNREGISTERED`(레지스트리에 없음)/`VERSION_DRIFT`(version 불일치)/`UNKNOWN_MEMBER`(observed 추가)/`MISSING_MEMBER`(registered 누락)/`TYPE_DRIFT`(공통 멤버 타입 차이). raw 편차이며 CompatMode 시맨틱 아님.
3. **staleness = 시간 차원.** `LivenessTracker`가 노드별 마지막 관측 시각 추적(clock 주입=결정적), 임계 초과 무발행 → `STALE`. NDEATH는 제외, rebirth(markSeen)는 death 해제.
4. **거버넌스 건강 스냅샷.** `GovernanceHealth`가 총노드·conformant·conformance rate(distinct node)·kind별 drift 건수·stale 노드 수 집계.

## Consequences
- 거버넌스가 런타임에 *관측 가능*해짐 — ADR-0007 배포前 게이트 + 본 ADR의 배포後 관측 = **정책 루프 완결**(CI에서 강제 + 런타임에서 본다).
- **ADR-0009 ContractValidator와의 경계:** ADR-0009는 Kafka egress **데이터플레인 게이트**(위반→DLQ, 라우팅 목적, 호출자가 고정 계약 전달). 본 ADR은 **관측 레이어** — 비교 대상이 `DefinitionStore.latest` source-of-truth, VERSION_DRIFT·UNREGISTERED·STALE(시간) 추가, 전역 건강 메트릭+감사, detect-only·passive(차단 없음).
- 한계(정직성): **detect-only**(차단·DLQ·OT 드롭 없음)·**node-level**(group/edge, device-level 아님)·**NDATA per-metric 값 드리프트 비범위**(observed 스키마는 NBIRTH에서만, NDATA는 liveness markSeen만)·**단일 프로세스 PoC**(분산·HA 아님; `audit`/`liveness` 수집은 **비동기화** — Paho 콜백 스레드가 mutate·`report()`는 호출 스레드가 read, 데모가 순차라 안전하나 동시 수집은 비범위)·**ADR-0007 레지스트리 재사용**(별도 진실원 아님). 셸은 단위테스트 비대상 — 실 HiveMQ 데모로 검증.

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/drift/`
- 셸/데모: `../../src/main/java/dev/krillin/sparkplug/DriftMonitor.java`, `DriftMonitorDemo.java`
- 선행: ADR-0007(스키마 레지스트리 게이트), ADR-0009(Kafka egress DLQ 게이트)
