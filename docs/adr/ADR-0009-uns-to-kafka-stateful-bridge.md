# ADR-0009 — UNS→Kafka stateful 거버넌스 다리

- 상태: **Accepted (PoC)**
- 일자: 2026-06-10
- 관계: ADR-0007 레지스트리를 런타임 conformance 게이트로 재사용. `SparkplugToJsonBridge`(stateful alias 해석)의 직계 후속. namespace-standard §2(ISA-95 매핑)를 IT 경계로 확장. 코드 `src/.../kafka/`, 데모 `UnsToKafkaDemo.java`.

## Context
전사 UNS는 OT 경계(Sparkplug/MQTT, RBE, 상태머신)에서 IT 분석/스트리밍 경계(Kafka)로 넘어가야 한다. Sparkplug는 stateful(alias는 NBIRTH에서만, NDATA는 alias-only RBE)인데 Kafka는 stateless append-only → 순진한 다리는 alias를 못 풀고 부분 상태만 흘린다.

## Decision
1. **Stateful 다리(척추).** `UnsStateStore`가 edge별 alias→name 매핑 + last-known-value를 복원. NDATA(RBE)는 변한 것만 와도 last-known이 전체 현재상태를 보유. 상태 없이는 올바른 레코드 불가.
2. **RBE ↔ Kafka log-compaction 동형.** 메시지 **키 = metric 정체성**(`<cell>/<metric>`)으로 두면 compacted 토픽이 last-known-value 스토어가 됨 → IT 소비자가 from-beginning 읽어도 모든 metric 최신값 복원. OT 상태 의미론을 IT 스트리밍 primitive로 번역.
3. **계약 검증 → DLQ(거버넌스 차원 2).** ADR-0007 레지스트리(`DefinitionStore`)를 런타임 conformance로 재사용. 타입불일치/미지 metric → DLQ(메인 미오염). 멤버누락은 birth 시점만(RBE 정합).
4. **ISA-95 → 토픽(거버넌스 차원 3).** namespace-standard §2(`group=Ent:Site:Area`, `edge=Line:Cell`) → Kafka 토픽 `uns.Ent.Site.Area.Line.Cell`.
5. **상태머신 운반.** NBIRTH=상태 확립, NDEATH=STALE tombstone(드롭 아님 — 소비자가 staleness 인지).

## 정직성(중요)
실무 UNS→Kafka는 흔히 Confluent+Avro+Schema Registry. 본 PoC는 JSON+ADR-0007 파일 레지스트리로 self-contained. 컴팩션 데모는 단일노드 KRaft/단일 파티션. **seq 재정렬·out-of-order NDATA 미구현**(MQTT 순서 가정). at-least-once(exactly-once 비범위). 데모 배너·이 ADR에 명시.

## Consequences
- 거버넌스: alias 해석·last-known·계약·네임스페이스가 IT 경계까지 1급으로 운반. 위반은 DLQ로 격리.
- 비동기 상태 일관성 패턴(latest-wins/correlation)의 정통 응용. data-contracts/policy-as-code 어휘 + HiveMQ↔Kafka 통합(Ignition 8.3 Event Streams와 같은 패턴 계열)과 맞닿음.
- 한계: node-level 중심(device 매핑은 지원, 데모는 node), 단일 계약 매핑, seq 재정렬 없음, at-least-once. producer send는 비동기 fire-and-forget이며 전송 실패는 async 콜백으로 로깅만(producer 재시도/idempotence는 기본값, 전달보장은 at-least-once 수준).

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/kafka/`
- 데모: `../../src/main/java/dev/krillin/sparkplug/UnsToKafkaDemo.java`
- 선행: ADR-0007(레지스트리), ADR-0004(JSON 브리지), `namespace-standard.md` §2
