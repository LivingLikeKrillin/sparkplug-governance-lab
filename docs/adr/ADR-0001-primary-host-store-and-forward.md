# ADR-0001 — Primary Host STATE 기반 Store-and-Forward 및 다중 소비자 아키텍처 상충 해소

- 상태: **Accepted**
- 일자: 2026-06-03
- 근거: 실증 테스트 (`src/.../StateStoreForwardDemo.java` + `PrimaryHost`/`SfEdgeNode`), HiveMQ CE

## 1. 배경 및 맥락 (Context)
Sparkplug 사양의 Store-and-Forward(S&F) 메커니즘은 **단일 Primary Host**의 생존 상태(`spBv1.0/STATE/{hostId}`, JSON online/offline, Retained)에 연동됩니다. Host가 오프라인으로 전환되면 엣지 노드가 데이터를 로컬 버퍼에 축적하고, 온라인 복귀 시 순서대로 플러시(Flush)하여 정본 기록 시스템(System of Record)의 데이터 결손을 방지합니다. 

그러나 UNS(Unified Namespace) 환경은 다수의 독립적 소비자(MES, 히스토리안, 실시간 분석 엔진, ERP)가 N:M 구조로 연계되므로, 복수의 소비자 중 어떤 주체를 Primary Host로 지정할 것인가에 대한 아키텍처 상충이 발생합니다.

## 2. 실험 및 실측 결과 (Empirical Verification)
`StateStoreForwardDemo`를 통한 무손실 전송 실증:
- 시나리오: Host 온라인 → 엣지 LIVE 데이터 발행(seq 1, 2) → Host 정상 오프라인 전환(Graceful STATE offline) → 엣지 노드 데이터 버퍼링(seq 3, 4, 5 미전달 보관) → Host 온라인 복귀 → 엣지 노드 순차 플러시 수행 → Host가 seq 3, 4, 5 정상 수신 확인 → LIVE 데이터 재개(seq 6). (exit code: 0)

## 3. 식별 및 조치된 결함 (Findings & Mitigations)
실증 과정에서 동시성 및 순서 제어 결함 2건을 식별하고 해결 방안을 적용하였습니다:

1. **Paho 콜백 스레드 내 플러시 실행 시 교착 상태(Deadlock) 발생**:
   - 원인: STATE 수신 콜백(통신 스레드) 내부에서 동기적 `client.publish`를 호출하여 특정 시퀀스(seq=4)에서 상호 블로킹 발생.
   - 조치: 플러시 처리를 별도 작업 스레드로 격리. 버퍼 스냅샷 추출은 임계 영역(Lock) 내부에서 수행하고, 네트워크 발행은 임계 영역 외부에서 비동기 처리.
2. **소비자 데이터 구독 전 STATE 온라인 발행으로 인한 백로그 유실**:
   - 원인: 소비자가 토픽을 구독하기 전에 STATE online을 선제 발행하여, 엣지의 고속 플러시가 구독 체결을 앞질러 초기 백로그가 유실됨.
   - 조치: Host가 `spBv1.0/<group>/#` 토픽 구독을 완료한 후 STATE online을 발행하도록 순서 보장.

## 4. 아키텍처 결정 (Decision)
- **Store-and-Forward 대상 단일화**: 단 하나의 정본 기록 시스템(일반적으로 히스토리안 또는 UNS 영속 기록 계층)만을 Primary Host로 지정하여 해당 가용성에 연동합니다.
- **다중 소비자 상태 동기화 분리**: 일반 분석/대시보드 소비자는 최선 노력(Best-effort) 실시간 구독으로 운용하며, 초기 상태 복구는 Store-and-Forward가 아닌 브로커 상태 증명(Aware Broker Certificates, ADR-0002)을 활용합니다.
- **역할 및 책임 명확화**:
  - Primary Host Store-and-Forward: 기록 계층 데이터의 완결성(Completeness) 보장.
  - Aware-Broker Retained Certificate: 임의 소비자의 최신 상태(Current State) 즉시 동기화.
  이 분리 설계를 통해 Sparkplug의 1:N 구조와 UNS의 N:M 분산 구독 구조 간의 상충을 해소합니다.

## 5. 결과 및 영향 (Consequences)
- **Primary Host 지정 거버넌스화**: 엣지 설정이 단일 `primaryHostId`에 바인딩되므로, 어떤 계층을 정본 기록 시스템으로 운용할지 네임스페이스 표준(`namespace-standard.md` §8)에 명문화합니다.
- **다중 소비자 상태 복구**: 임의 시점 접속 소비자의 상태 동기화는 ADR-0002(Aware Broker) 사양에 의존합니다.
- **플러시 QoS 트레이드오프**: 기록 완결성이 엄격히 요구되는 경우 플러시 전송을 QoS 1 및 영속 디스크 버퍼로 구성합니다.

## 6. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/{PrimaryHost,SfEdgeNode,StateStoreForwardDemo}.java`
- 연계 ADR: ADR-0002 (Late Joiner 상태 동기화)
