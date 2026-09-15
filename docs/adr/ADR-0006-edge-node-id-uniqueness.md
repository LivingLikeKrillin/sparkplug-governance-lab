# ADR-0006 — 세션 탈취 방지를 위한 Edge Node ID 및 MQTT Client ID 전역 유일성 보장

- 상태: **Accepted**
- 일자: 2026-06-03
- 근거: 실증 테스트 (`src/.../StolenSessionDemo.java`), HiveMQ CE

## 1. 배경 및 맥락 (Context)
Sparkplug B 사양은 `group_id`와 `edge_node_id`의 조합으로 네트워크상의 엣지 노드를 식별하며, MQTT의 `client_id` 역시 통상 이 식별자 체계에서 파생됩니다. 

대규모 제조 공장 또는 다수의 시스템 통합사(SI) 및 벤더가 공존하는 브라운필드 환경에서 식별자가 중복 발급될 경우, 브로커 레벨의 세션 충돌 및 비정상 상태 전이가 발생할 수 있습니다.

## 2. 실험 및 실측 결과 (Empirical Verification)
`StolenSessionDemo`를 통해 동일한 식별자를 가진 두 개의 엣지 노드 인스턴스(A, B)가 순차 접속하는 시나리오를 검증하였습니다:
- 인스턴스 A가 정상 접속하여 NBIRTH를 발행하고, 호스트가 이를 정상 수신함.
- 인스턴스 B가 **동일한 Client ID**로 접속을 시도함.
- 브로커가 기존 연결인 인스턴스 A를 강제 종료(`connection lost (32109) EOFException` — MQTT 세션 탈취).
- 인스턴스 A의 비정상 단절로 인해 브로커가 사전 등록된 LWT(Last Will and Testament) 메시지인 **NDEATH**를 발행.
- 인스턴스 B의 세션 수립에 따라 신규 **NBIRTH**가 발행됨.
- 호스트 수신 시퀀스: **NBIRTH(A) → NDEATH(A) → NBIRTH(B)**.
두 인스턴스가 자동 재연결 로직을 활성화할 경우, 이 패턴이 주기적으로 반복되는 상태 플래핑(Flapping) 및 Birth/Death 메시지 폭풍이 발생함을 실측하였습니다.

## 3. 핵심 식별 사항 (Findings)
- **MQTT 세션 탈취(Takeover) 규약의 파급 효과**: MQTT 3.1.1 사양에 따라 동일 Client ID 접속 시 기존 연결은 비정상 단절로 간주되어 의도치 않은 LWT NDEATH가 브로드캐스트됩니다.
- **상태 무결성 훼손**: 식별자 충돌은 단순 통신 장애에 그치지 않고, 설비 온라인/오프라인 상태의 지속적 진동, 텔레메트리 데이터 귀속 오염, 상위 모니터링 시스템의 허위 경보를 유발합니다.

## 4. 아키텍처 결정 (Decision)
- **전역 고유 식별자 체계 강제**: `edge_node_id` 및 파생되는 MQTT `client_id`는 전사 레벨에서 전역 유일성을 보장해야 하며, 장비 프로비저닝 단계에서 엄격히 검증합니다.
- **계층형 네임스페이스 명명 규칙 적용**: `Site:Area:Line:Cell:Gateway` 등 계층 구조를 인코딩하여 물리적/논리적 영역 간 충돌을 원천 차단합니다 (`namespace-standard.md` §3).
- **중앙 식별자 레지스트리 운용**: 벤더 및 SI 간 중복 발급을 방지하기 위해 장비 식별자를 중앙 레지스트리에서 발급·관리합니다.
- **브로커 레벨의 모니터링 및 방어**:
  - Client ID 화이트리스트 기반 ACL 적용.
  - 단기간 내 빈번한 재접속을 탐지하는 플랩 감지(Flap Detection) 및 경보 체계 도입.

## 5. 결과 및 영향 (Consequences)
- **프로비저닝 거버넌스 수립**: 장비 도입 및 엣지 게이트웨이 설정 시 식별자 중복 검증 프로세스가 필수 선행 조건으로 지정됩니다.
- **네임스페이스 표준 연계**: 식별자 명명 규칙과 제약 사항을 네임스페이스 표준(`namespace-standard.md` §3)에 구체화합니다.

## 6. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/StolenSessionDemo.java`
- 연계 표준: `docs/namespace-standard.md` §3 (식별자 명명 규약)
