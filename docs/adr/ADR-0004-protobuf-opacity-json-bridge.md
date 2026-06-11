# ADR-0004 — protobuf 불투명성 → JSON UNS 브리지 (이중 네임스페이스)

- 상태: **Accepted** (비-Sparkplug 소비자 필요 시)
- 일자: 2026-06-03
- 근거: 직접 실험 (`src/.../JsonBridgeDemo.java` + `SparkplugToJsonBridge.java`)

## Context
Sparkplug 페이로드는 **바이너리 protobuf** → MQTT Explorer/IT 툴이 못 읽고, raw Sparkplug에는 retained 현재상태도 없다(ADR-0002). IT/분석/평문 소비자가 데이터를 쓰려면?

## Experiment (실측)
브리지가 `spBv1.0/{group}/#` 구독·디코드 → `uns/{group}/{edge}/{metric}` 에 **retained JSON** 재발행:
- NBIRTH Temperature/Pump/Running → JSON 재발행.
- NDATA(alias-only, raw=`50B protobuf 08 c0 ce 87...` 안 읽힘) → 브리지가 **alias→name 해석** 후 JSON(value=20.5/21.0…) 재발행.
- 늦게 붙은 평문 JSON 소비자가 `uns/#` 구독 → 접속 즉시 **retained 현재값(Temp=21.0, Pump=false) 확보**.

## Findings
1. **protobuf는 불투명** — Sparkplug 모르는 소비자는 직접 못 씀.
2. **브리지는 Sparkplug-stateful이어야 함** — NDATA alias를 풀려면 NBIRTH의 name↔alias를 캐시(birth 추적). 단순 republish 아님. NBIRTH 놓치면 alias 미해석.
3. **JSON+retained = 임의 IT 소비자에게 state-on-connect** (ADR-0002의 aware-cert를 평문 세계로 확장).
4. **대가: 네임스페이스 둘**(spBv1.0 protobuf + uns JSON) → 드리프트/유지비, 브리지가 단일 장애점.

## Decision (거버넌스)
- IT/평문 소비자·휴먼 브라우징이 필요하면 **거버넌스된 Sparkplug→JSON 브리지**로 retained 현재상태를 병행 UNS에 발행.
- 브리지를 **1급 컴포넌트로 통치**: 모니터링, 재시작 시 rebirth로 birth 재수집, alias 캐시 영속.
- **Sparkplug-native 소비자는 aware-cert(ADR-0002)**, **비-Sparkplug 소비자만 JSON 브리지** — 역할 분리(ADR-0001 패턴과 동일 사고).
- JSON 네임스페이스 스키마를 **UDT(ADR-0005)와 함께 거버넌스**해 이중 정의 드리프트 방지.

## Consequences
- 브리지 가용성/상태 = 운영 리스크(birth 못 받으면 alias 깨짐).
- spBv1.0 metric 경로 → uns 경로 매핑 = 네임스페이스 결정(namespace-standard §4).
- 구현 교훈: 브리지는 구독/발행 client를 **분리**해야 함 — Paho 콜백 스레드에서 publish하면 자기 메시지 루프를 막아 교착.

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/{SparkplugToJsonBridge,JsonBridgeDemo}.java`
- ADR-0002·ADR-0005 연결
