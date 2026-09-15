# ADR-0005 — UDT(Template) 버전 관리 및 스키마 거버넌스 체계

- 상태: **Accepted**
- 일자: 2026-06-03
- 근거: 실증 테스트 (`src/.../UdtDemo.java`), Eclipse Tahu 1.0.14 / HiveMQ CE

## 1. 배경 및 맥락 (Context)
UDT(Sparkplug Template)는 UNS 상에서 복합 설비의 구조를 정의하는 핵심 데이터 모델입니다. 정의(Definition)와 인스턴스(Instance)가 NBIRTH 페이로드를 통해 전송되며, 사양상 `version` 필드가 제공됩니다. 

다중 공장 및 이기종 벤더 환경에서 설비 데이터 모델이 지속적으로 진화할 때, UDT 정의의 변경을 어떻게 통제하고 하위 호환성을 보장할 것인가가 핵심 아키텍처 과제입니다.

## 2. 실험 및 실측 결과 (Empirical Verification)
`UdtDemo`를 활용하여 "Motor" UDT의 버전 변경 시나리오를 실증하였습니다:
- **v1.0 정의**: def{Rpm: Double, Running: Boolean} + 파라미터 Location; 인스턴스 Motors/Motor1{Rpm=1500, Running=true}.
- **v2.0 정의**: 동일 정의에 신규 멤버 Temperature: Double 추가, version="2.0"; 인스턴스에 Temperature=65.4 반영.
- 수신 호스트 디코드 결과: 두 버전 모두 정상 수신되었으며, definition/ref/version/param/member 구조가 온전하게 전달됨을 확인하였습니다.

## 3. 핵심 식별 사항 (Findings)
1. **`version` 필드의 강제력 부재**: 프로토콜 사양상 `version`은 단순 자유 형식 문자열이며, 브로커나 프로토콜 레벨에서 시맨틱 버저닝(SemVer) 규칙이나 호환성을 강제하지 않습니다.
2. **중앙 스키마 레지스트리의 부재**: UDT 정의의 유일한 전송 경로가 NBIRTH 페이로드이므로, 초기 NBIRTH를 수신하지 못했거나 구버전 기준으로 개발된 소비자는 수신 데이터의 유효성을 검증할 권위 있는 기준이 없습니다.
3. **스키마 변경 제약 부재**: 멤버 추가/삭제/타입 변경 시 브로커는 호환성 검사, 필드 마이그레이션, 비호환 페이로드 거부 등의 기능을 수행하지 않으므로, 구버전을 전제한 소비 시스템에서 런타임 오류가 발생할 수 있습니다.
4. **이기종 모델 간 드리프트 위험**: SCADA(Ignition 등) 고유 UDT와 원시 Sparkplug Template 간의 변환 과정에서 이중 정의 불일치(Drift) 위험이 존재합니다.

## 4. 아키텍처 결정 (Decision)
- **외부 스키마 레지스트리 기반 거버넌스**: UDT 정의의 단일 진실 원천(Source of Truth)을 외부 스키마 레지스트리(`registry/udt/`)와 Git 형상 관리 프로세스로 일원화합니다. NBIRTH에 포함되는 `_types_/`는 전송 시점의 표현(Wire Truth)으로 취급합니다.
- **유의적 버전 규칙(SemVer) 강제**:
  - 하위 호환 멤버 추가: Minor 버전 증가 (기존 소비자 호환 유지).
  - 멤버 삭제 또는 데이터 타입 변경: Major 버전 증가 (하위 호환성 파괴).
  - **파괴적 변경 시 `templateRef` 분리**: 호환성이 파괴되는 경우 신규 `templateRef`(예: `Motor` → `Motor2`)로 식별자를 분리하여 기존 소비자를 보호합니다.
- **소비자 측 Major 버전 검증**: 소비자는 수신된 버전의 Major 번호를 검증하여 예상치 못한 파괴적 변경 발생 시 해당 인스턴스를 격리하고 알림을 발생시킵니다.

## 5. 결과 및 영향 (Consequences)
- **프로토콜 외부 거버넌스 도구화**: Sparkplug 사양이 제공하지 않는 스키마 검증을 중앙 레지스트리, CI 게이트(ADR-0007), 린트 도구를 통해 보완합니다.
- **네임스페이스 표준 준수**: UDT 소유권, 버전 부여 절차, `templateRef` 분리 규약을 표준 문서(`namespace-standard.md` §5)에 공식 명문화합니다.
- **상위 정보 모델 연계 기준**: 향후 OPC UA 정보 모델을 Sparkplug UDT로 사상할 때 본 버저닝 규약을 기준으로 매핑을 통제합니다 (ADR-0010).

## 6. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/UdtDemo.java`
- 연계 ADR: ADR-0007 (스키마 레지스트리 게이트), ADR-0010 (OPC UA to UDT 매핑)
