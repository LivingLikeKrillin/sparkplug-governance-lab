# ADR-0002 — 접속 시점 상태 복원(State-on-Connect): Sparkplug-Aware 브로커 기반 상태 증명

- 상태: **Accepted** (다중 소비자 UNS 아키텍처 전제)
- 일자: 2026-06-03
- 근거: 실증 테스트 (`src/.../LateJoinerExperiment.java`), HiveMQ CE 2026.5

## 1. 배경 및 맥락 (Context)
Sparkplug B 표준 사양에서 클라이언트는 NBIRTH 메시지를 Retained 플래그 없이 전송합니다. 따라서 엣지 노드가 초기 기동 후 유휴(Idle) 상태를 유지하는 동안 새롭게 네트워크에 접속한 소비자(Late Joiner)는 토픽을 구독하더라도 즉시 현재 상태를 수신할 수 없습니다. 

UNS 환경에서는 다양한 엔터프라이즈 시스템(MES, 히스토리안, 모니터링 대시보드)이 동적으로 접속하므로, 접속 즉시 전체 설비의 최신 상태 및 메트릭 구조를 복원하는 메커니즘이 거버넌스 요구사항으로 대두됩니다.

## 2. 실험 및 검증 구성 (Experimental Setup)
동일한 검증 시나리오(`LateJoinerExperiment`)를 두 가지 브로커 구성 환경에서 비교 측정하였습니다. 엣지 노드가 NBIRTH 전송 후 유휴 상태일 때, 신규 소비자가 `spBv1.0/<group>/#` 및 `$sparkplug/certificates/#`를 구독하고 3초 이내에 상태를 수신하는지 여부를 검증하였습니다.

- **A 구성 (Non-Aware 브로커)**: HiveMQ CE 기본 구성 (Allow-All 확장 적용).
- **B 구성 (Sparkplug-Aware 브로커)**: A 구성 + `hivemq-sparkplug-aware-extension:4.33.4` (오픈소스 확장) 적용.
  - 실행: `docker compose -f docker-compose.yml -f docker-compose.aware.yml up -d --force-recreate`

## 3. 실측 결과 및 분석 (Findings & Evidence)
| 브로커 구성 | 접속 직후 상태 수신 여부 | 실측 판정 결과 |
|---|---|---|
| **A (Non-Aware)** | ❌ 미수신 (NO) | 토픽 수신 버퍼 공백. 명시적 Rebirth(NCMD) 명령을 발행한 후에야 `spBv1.0/.../NBIRTH` 수신 가능 |
| **B (Aware)** | ✅ 즉시 수신 (YES) | 접속 즉시 `$sparkplug/certificates/spBv1.0/Krillin/NBIRTH/Edge1` (Retained, 136 Bytes) 수신 |

브로커 로그(B 구성): `Extension "Sparkplug Aware Extension" version 4.33.4 started successfully`. Aware 확장이 수신된 NBIRTH/NDEATH를 `$sparkplug/certificates/#` 네임스페이스에 Retained 상태로 미러링함을 확인하였습니다.

## 4. 아키텍처 결정 (Decision)
- **Sparkplug-Aware 브로커 확장 채택**: 다중 소비자 환경에서 접속 시점 상태 복원(State-on-Connect)은 브로커 측의 Sparkplug-Aware 확장을 통해 보장합니다.
- **상태 조회 전용 토픽 운용**: 초기 상태 복원이 필요한 소비자는 읽기 전용 토픽인 `$sparkplug/certificates/#`를 구독하여 상태 증명(Certificate)을 취득합니다.
- **Rebirth(NCMD) 기반 상태 복구 지양**: 개별 소비자가 기동할 때마다 엣지에 Rebirth를 요청하는 방식은 소비자 수 증가에 비례하여 Rebirth 폭풍(Storm) 및 네트워크 대역폭 급증을 초래하므로 지양합니다. Aware 브로커의 Retained 미러링은 소비자 수와 무관하게 O(1)의 전달 확장성을 제공합니다.

## 5. 결과 및 영향 (Consequences)
- **브로커 사양 거버넌스**: MQTT 브로커 선정 시 Sparkplug-Aware 확장 지원 여부가 필수 거버넌스 평가 항목으로 지정됩니다.
- **병행 읽기 전용 네임스페이스 수립**: `$sparkplug/certificates/#` 경로를 공식 네임스페이스 표준(`namespace-standard.md` §8)에 명시하고 관리합니다.
- **접근 통제(ACL) 반영**: 상태 증명 토픽에 대한 읽기 권한을 인가 정책에 포함하여 인가되지 않은 엔터티의 토폴로지 열람을 제한합니다.

## 6. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/LateJoinerExperiment.java`
- 연계 ADR: ADR-0001 (Store-and-Forward 및 다중 소비자 아키텍처)
- 외부 참조: [HiveMQ Sparkplug Aware Extension](https://github.com/hivemq/hivemq-sparkplug-aware-extension)
