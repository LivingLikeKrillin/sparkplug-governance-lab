# ADR-0004 — Protobuf 역직렬화 불투명성 해소를 위한 상태 기반 JSON UNS 브리지

- 상태: **Accepted** (비-Sparkplug IT 소비 시스템 연계 시)
- 일자: 2026-06-03
- 근거: 실증 테스트 (`src/.../JsonBridgeDemo.java` + `SparkplugToJsonBridge.java`), HiveMQ CE

## 1. 배경 및 맥락 (Context)
Sparkplug B 표준 페이로드는 Protobuf 바이너리로 인코딩되므로, 전용 디코더나 스키마 정의를 보유하지 않은 일반 IT 모니터링 도구(MQTT Explorer 등), 경량 웹 대시보드, 범용 분석 시스템에서는 페이로드 내부 데이터를 직접 판독할 수 없습니다. 또한 표준 Sparkplug 브로커 토픽에는 Retained 플래그 기반의 최신 상태 저장이 기본 제공되지 않습니다. 

이에 따라 비-Sparkplug IT 소비자가 즉각적으로 현장 데이터를 활용할 수 있도록 지원하는 프로토콜 변환 및 투명화 메커니즘이 요구됩니다.

## 2. 실험 및 실측 결과 (Empirical Verification)
`SparkplugToJsonBridge`를 통해 `spBv1.0/{group}/#` 토픽을 구독 및 역직렬화한 후, `uns/{group}/{edge}/{metric}` 토픽에 **Retained JSON** 형식으로 재발행하는 실증을 수행하였습니다:
- NBIRTH 수신 시: 메트릭 카탈로그(Temperature, Pump, Running 등)를 파싱하여 개별 JSON 토픽으로 변환 및 영속 발행.
- NDATA 수신 시: 압축 전송된 별칭 전용 페이로드(예: 50바이트 바이너리)를 브리지 내부 캐시를 통해 `Alias → Metric Name`으로 복원한 후, JSON 형태(예: `{"value": 21.0, "timestamp": ...}`)로 재발행.
- 신규 접속한 IT 소비자가 `uns/#`를 구독하는 즉시 Retained된 최신 공정값(Temp=21.0, Pump=false)을 결손 없이 수신함을 확인하였습니다.

## 3. 핵심 식별 사항 (Findings)
1. **바이너리 불투명성(Opacity)**: Protobuf 스키마를 탑재하지 않은 일반 엔터프라이즈 애플리케이션은 원시 Sparkplug B 토픽을 직접 소비할 수 없습니다.
2. **브리지의 상태 유지(Stateful) 필수성**: NDATA의 메트릭 별칭(Alias)을 정상 역참조하기 위해서는 브리지가 NBIRTH 메시지를 선제 수신하여 `Name ↔ Alias` 매핑 테이블을 메모리에 캐싱하고 유지해야 합니다.
3. **병행 네임스페이스 운용 트레이드오프**: 바이너리 토픽(`spBv1.0/...`)과 JSON 토픽(`uns/...`)의 이중 네임스페이스 운용으로 인해 토픽 관리 비용 및 브리지 장애 시 데이터 지연 위험이 수반됩니다.

## 4. 아키텍처 결정 (Decision)
- **거버넌스된 Sparkplug-JSON 브리지 도입**: 엔터프라이즈 IT 소비 계층과의 연계를 위해 상태 기반 프로토콜 변환 브리지를 운영하고, 최신 상태를 Retained JSON 형태로 발행합니다.
- **소비자 계층별 연계 경로 분리**:
  - Sparkplug 지원 소비자: 표준 Sparkplug B 토픽 및 Aware 브로커 상태 증명(ADR-0002) 직접 구독.
  - 비-Sparkplug IT/웹 소비자: 정규화된 JSON 네임스페이스(`uns/...`) 구독.
- **브리지의 1급 컴포넌트 거버넌스**: 브리지 장애 시 Rebirth를 통한 메트릭 카탈로그 재수집 루틴, 별칭 캐시 영속성, 클라이언트 분리 설계를 필수로 적용합니다.

## 5. 결과 및 영향 (Consequences)
- **브리지 가용성 관리**: 브리지 기동 상태가 상위 IT 데이터 파이프라인의 핵심 선행 조건이 됩니다.
- **토픽 매핑 규약 명문화**: Sparkplug 메트릭 경로와 JSON UNS 경로 간의 1:1 정합성 규칙을 네임스페이스 표준(`namespace-standard.md` §4)에 반영합니다.
- **동시성 설계 준수**: Paho MQTT 클라이언트 운용 시 구독 스레드와 발행 스레드를 분리하여 자체 메시지 루프에 의한 상호 교착을 차단합니다.

## 6. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/{SparkplugToJsonBridge,JsonBridgeDemo}.java`
- 연계 ADR: ADR-0002 (Aware Broker 상태 증명), ADR-0005 (UDT 스키마 거버넌스)
