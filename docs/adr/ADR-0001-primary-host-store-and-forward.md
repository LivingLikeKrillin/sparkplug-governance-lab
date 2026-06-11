# ADR-0001 — Primary Host STATE + store-and-forward, 그리고 다중소비자 모순

- 상태: **Accepted**
- 일자: 2026-06-03
- 근거: 직접 실험 (`src/.../StateStoreForwardDemo.java` + `PrimaryHost`/`SfEdgeNode`), HiveMQ CE

## Context
Sparkplug의 store-and-forward는 **단일 Primary Host** 의 STATE(`spBv1.0/STATE/{hostId}`, JSON online/offline, retained)에 게이트된다. host가 offline이면 edge가 데이터를 버퍼링, online 복귀 시 순서대로 flush → 시스템-of-record가 데이터를 놓치지 않게 한다. 그러나 UNS는 다수의 독립 소비자(MES·historian·분석·ERP) n:m → "primary가 누구냐"가 모순이 된다.

## Experiment (실측, 무손실 확인)
시나리오: host online → edge LIVE(seq1,2) → host OFFLINE(graceful STATE) → edge가 seq3,4,5 **버퍼링**(전달 안 됨) → host 복귀 → edge가 **순서대로 flush** → host가 **seq3,4,5 전부 수신** → LIVE seq6 재개. (exit 0)

## Findings — 동시성/순서 결함 2건 (발견→수정)
이 실험 자체가 두 개의 진짜 동시성 버그를 드러냈고 고쳤다:

1. **flush를 Paho 콜백 스레드에서 publish → 교착.** STATE 콜백(comms 스레드) 안에서 `client.publish`를 돌리니 seq=4에서 영구 hang. **수정:** flush를 별도 스레드에서; 버퍼 스냅샷은 lock 안, 네트워크 publish는 lock 밖.
2. **소비자가 데이터 구독 전에 STATE online 발행 → flush가 구독을 앞질러 backlog 유실.** 처음엔 seq=3만 도착. **수정:** host가 `spBv1.0/<group>/#` 구독을 **STATE online 발행보다 먼저** → 3·4·5 전부 도착.

## Decision (거버넌스 — 다중소비자 모순 해소)
- **store-and-forward는 '단 하나의 시스템-of-record'(보통 historian/UNS 기록계층)를 primary host로 지정**해 그 가용성에만 게이트한다. 그 외 소비자(분석·대시보드 등)는 **best-effort live 구독**으로 두고, 현재상태 복구는 **store-and-forward가 아니라 aware-broker certificates(ADR-0002)** 로 해결한다.
- **모든 소비자를 primary로 만들려 하지 않는다** — 의미상 모순(edge가 N개 STATE를 동시에 만족시킬 수 없음).
- 즉 **역할 분리:** primary-host store-and-forward = *기록계층의 완결성*, aware-broker retained cert = *임의 소비자의 현재상태*. 이 분리가 "Sparkplug(n:1) ↔ UNS(n:m)" 간극의 거버넌스 답.

## Consequences
- **primary host 정체성 = 거버넌스 결정.** edge 설정이 단일 `primaryHostId`에 바인딩됨 → 누구를 기록계층으로 둘지 명문화(namespace-standard §8).
- 다중소비자 현재상태는 ADR-0002(aware broker)에 의존.
- **flush QoS 선택**(0 vs 1)은 완결성 트레이드오프 — 기록계층 완결성이 중요하면 flush를 QoS1 + persistent buffer로.
- 코드 교훈: Paho 콜백에서 publish 금지(스냅샷-후-별도스레드), 소비자는 "구독 후 online 선언".

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/{PrimaryHost,SfEdgeNode,StateStoreForwardDemo}.java`
- ADR-0002(late-joiner)와 한 뿌리
