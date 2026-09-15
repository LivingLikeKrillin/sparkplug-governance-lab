# ADR-0009 — 상태 기반 UNS-Kafka 거버넌스 브리지 및 로그 압축 동형성

- 상태: **Accepted (개념 검증 PoC)**
- 일자: 2026-06-10
- 관계: ADR-0007 레지스트리를 런타임 적합성(Conformance) 검증 엔진으로 재사용. ADR-0004(상태 기반 별칭 해석)의 스트리밍 파이프라인 확장. 네임스페이스 표준(`namespace-standard.md` §2)의 ISA-95 계층을 엔터프라이즈 Kafka 토픽으로 투영. 소스 코드: `src/.../kafka/`, 데모: `UnsToKafkaDemo.java`.

## 1. 배경 및 맥락 (Context)
전사 UNS 아키텍처는 OT 도메인(Sparkplug/MQTT, RBE, 상태 수명주기)의 데이터를 엔터프라이즈 IT 분석 및 스트리밍 데이터 파이프라인(Kafka)으로 무손실 전송할 수 있어야 합니다. 

Sparkplug B는 상태 의존적(Stateful) 프로토콜로 NBIRTH에서 메트릭 별칭을 등록하고 NDATA에서는 별칭 기반 RBE 데이터만을 전송하는 반면, Kafka는 상태 비의존적(Stateless) 분산 로그 구조를 가집니다. 따라서 상태를 관리하지 않는 단순 포워딩 브리지는 메트릭 별칭을 역참조할 수 없으며 공정의 최신 전체 상태를 유지할 수 없습니다.

## 2. 아키텍처 결정 (Decision)
1. **상태 기반 복원 코어 (`UnsStateStore`)**:
   - 엣지 노드별 `Alias → Name` 매핑 테이블과 최종 수신값(Last-Known-Value)을 메모리에 누적 복원합니다.
   - NDATA(RBE)를 통해 변경된 메트릭만 도착하더라도, 브리지가 전체 메트릭의 최신 공정 상태를 조합하여 완전한 레코드를 생성합니다.
2. **Sparkplug RBE와 Kafka 로그 압축(Log Compaction)의 동형성 활용**:
   - 메시지 키를 메트릭 고유 식별자(`<cell>/<metric>`)로 설정합니다.
   - Kafka의 Compacted 토픽을 활용함으로써, IT 소비자가 오프셋 0(from-beginning)부터 스트림을 읽더라도 전체 메트릭의 최신 상태를 즉시 복원할 수 있습니다. 이는 OT 상태 의미론을 IT 스트리밍 프리미티브로 완결성 있게 변환합니다.
3. **런타임 데이터 계약 검증 및 DLQ 격리**:
   - ADR-0007의 `DefinitionStore`를 런타임 적합성 게이트로 활용합니다.
   - 타입 불일치 또는 미등록 메트릭이 감지되면 주 데이터 파이프라인의 오염을 방지하기 위해 Dead Letter Queue(`uns.dlq`) 토픽으로 즉시 격리 라우팅합니다. (필드 누락 검증은 RBE 특성을 고려하여 NBIRTH 시점에만 수행)
4. **ISA-95 기반 Kafka 토픽 정규화**:
   - 네임스페이스 표준 §2(`group = Ent:Site:Area`, `edge = Line:Cell`) 규칙에 따라 Kafka 토픽명을 `uns.Ent.Site.Area.Line.Cell`로 매핑합니다.
5. **수명주기 상태 전이 전파**:
   - NBIRTH 수신 시 초기 상태를 확립하고, NDEATH 수신 시 데이터를 삭제하지 않고 STALE 톰스톤(Tombstone) 레코드를 발행하여 다운스트림 소비자가 설비 단절 상태를 인지하도록 합니다.

## 3. 구현 제약 및 한계 명시
실무 엔터프라이즈 환경에서는 Confluent Schema Registry 및 Avro를 주로 활용하나, 본 PoC는 파일 기반 레지스트리 및 JSON 직렬화를 사용하여 독립적으로 검증 가능하도록 구성하였습니다. 검증 환경은 단일 노드 KRaft / 단일 파티션을 전제하며, MQTT의 전달 순서 보장을 기반으로 하므로 비순차 NDATA 재정렬 기능은 포함하지 않습니다 (전달 보증 수준: At-least-once).

## 4. 결과 및 영향 (Consequences)
- **거버넌스 범위의 IT 경계 확장**: 메트릭 별칭 복원, 최신값 누적, 데이터 계약 검증, ISA-95 네임스페이스 투영이 엔터프라이즈 데이터 스트림까지 일관되게 보장됩니다.
- **이상 데이터 격리**: 계약 위반 데이터가 메인 분석 토픽으로 유입되지 않고 DLQ로 격리되어 데이터 파이프라인의 안정성이 유지됩니다.
- **스트리밍 최적화**: Kafka Log Compaction과 RBE의 정합을 통해 불필요한 전체 상태 폴링을 배제하고 이벤트 구동형 아키텍처를 구현합니다.

## 5. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/kafka/`
- 라이브 데모: `src/main/java/dev/krillin/sparkplug/UnsToKafkaDemo.java`
- 연계 문서: ADR-0004 (JSON 브리지), ADR-0007 (스키마 레지스트리), `namespace-standard.md` §2
