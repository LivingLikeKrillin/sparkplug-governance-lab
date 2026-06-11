# ADR-0006 — edge_node_id / MQTT client-id 전역 유일성

- 상태: **Accepted**
- 일자: 2026-06-03
- 근거: 직접 실험 (`src/.../StolenSessionDemo.java`), HiveMQ CE

## Context
Sparkplug는 `group_id`+`edge_node_id`로 엣지를 식별하고, MQTT **client-id**가 보통 여기서 파생된다. 대규모/다(多)통합사 환경에서 동일 id가 중복 발급되면?

## Experiment (실측)
동일 group/edge(=동일 client-id) EdgeNode 둘을 접속:
- A 접속 + NBIRTH → host 수신.
- B가 **같은 client-id**로 접속 → 브로커가 A를 끊음(`connection lost (32109) EOFException` = takeover).
- A의 끊김은 비정상 → **A의 LWT(NDEATH) 발행** → host가 NDEATH 수신.
- B의 NBIRTH → host가 다시 NBIRTH 수신.
- 결과 host 로그: **NBIRTH(A) → NDEATH(A) → NBIRTH(B)**. 두 인스턴스가 살아서 재접속을 반복하면 이 패턴이 **flapping(birth/death 폭풍)**.

## Findings
- MQTT 3.1.1 takeover 규약: 기존 client-id로 새 연결이 오면 브로커는 **기존 연결을 끊는다**. takeover에 의한 끊김은 비정상 처리 → **Will(NDEATH) 발행**.
- 따라서 id 충돌 = 단발 사고가 아니라 **상태 진동(device가 죽었다 살아나기 반복)** + 데이터 귀속 오염 + 소비자 혼란.

## Decision (거버넌스)
- **edge_node_id(및 파생 client-id)는 전사 전역 유일** — 프로비저닝 시점에 강제. 명명 규약(site/area/edge)으로 충돌 원천 차단(→ namespace-standard §3).
- **중앙 레지스트리**로 id 발급·소유 관리(통합사/벤더 간 중복 방지).
- **브로커 측 방어:** client-id 기반 ACL + **flap 감지/경보**(짧은 시간 잦은 재접속 탐지)로 폭풍 조기 포착.

## Consequences
- 프로비저닝/레지스트리 프로세스가 거버넌스 산출물이 됨.
- 브로커 모니터링에 flap-detection 추가.
- 네임스페이스 명명 규약과 직접 연결 — id 유일성은 명명 규약의 일부.

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/StolenSessionDemo.java`
- 명명 규약: namespace-standard §3
