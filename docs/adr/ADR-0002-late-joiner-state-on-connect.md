# ADR-0002 — Late-joiner state-on-connect: Sparkplug-Aware 브로커 의존

- 상태: **Accepted** (다중 소비자 UNS 전제)
- 일자: 2026-06-03
- 근거: 직접 실험 (`src/.../LateJoinerExperiment.java`), HiveMQ CE 2026.5

## Context
Sparkplug 클라이언트는 NBIRTH를 **retained로 보내지 않는다.** 따라서 edge가 birth 후 조용한 동안 **늦게 접속한 소비자(late joiner)** 는 구독 직후 현재상태를 받지 못한다. UNS는 임의의 소비자(MES·historian·분석·신규 대시보드)가 수시로 late join 하므로, "접속 즉시 현재 상태 파악"이 거버넌스 요건이다.

## Experiment
동일 코드(`LateJoinerExperiment`)를 두 브로커에 실행. 측정: *edge가 NBIRTH 후 침묵하는 동안, 늦게 붙어 `spBv1.0/<group>/#` + `$sparkplug/certificates/#` 구독 시 3초 내 현재상태 수신 여부.*

- **A (non-aware):** HiveMQ CE + Allow-All 익스텐션만.
- **B (aware):** A + `hivemq-sparkplug-aware-extension:4.33.4` (무료 OSS, 프리빌트 릴리스).
  실행: `docker compose -f docker-compose.yml -f docker-compose.aware.yml up -d --force-recreate`

## Findings (실측 evidence)
| | 구독 직후 현재상태 | 증거 |
|---|---|---|
| **A non-aware** | ❌ NO | `[판정] ... = NO`, `수신토픽=[]`. rebirth(NCMD) 발행 후에야 `spBv1.0/.../NBIRTH` 수신 |
| **B aware** | ✅ YES | 접속 즉시 `$sparkplug/certificates/spBv1.0/Krillin/NBIRTH/Edge1` (retained, 136B) 수신 |

브로커 로그(B): `Extension "Sparkplug Aware Extension" version 4.33.4 started successfully`. aware 익스텐션이 NBIRTH/NDEATH를 `$sparkplug/certificates/#` 에 retained 미러링한다.

## Decision
다중 소비자 UNS에서 **"접속 즉시 현재 상태"는 Sparkplug-Aware 브로커로 보장**한다. 순수 Sparkplug가 그것을 준다고 가정하지 않는다.
- 현재 상태가 필요한 소비자는 `$sparkplug/certificates/#`(읽기 전용)를 구독한다.
- **rebirth(NCMD)를 late-join 표준 수단으로 쓰지 않는다** — edge 재발행을 강제하므로, 소비자마다 요청하면 **rebirth 폭풍**이 된다(다중소비자에서 비용 증가 → ADR-0001과 연결). aware 브로커의 retained cert는 소비자 수에 무관하게 스케일.

## Consequences
- **브로커가 거버넌스 대상 컴포넌트가 된다:** "aware인가"가 요건. 선택지 = HiveMQ aware 익스텐션 / HiveMQ Enterprise. (기본 Mosquitto 등은 non-aware → 부적합.) → 벤더/구성 거버넌스 항목.
- **`$sparkplug/certificates/#` = 병행 읽기전용 네임스페이스:** 소비자가 알아야 하는 별도 토픽 공간 → 네임스페이스 표준(namespace-standard.md)에 명문화 필요.
- **보안:** certificates 토픽 읽기 권한 ACL 거버넌스 필요.
- **한계(추가 검증 과제):** 본 실험은 NBIRTH 한정. NDEATH/late-death, 다수 edge, certificate 만료/정합성은 후속 실험 대상.

## Links
- ADR-0001(다중소비자)과 연결
- 코드: `../../src/main/java/dev/krillin/sparkplug/LateJoinerExperiment.java`
- 익스텐션: https://github.com/hivemq/hivemq-sparkplug-aware-extension
