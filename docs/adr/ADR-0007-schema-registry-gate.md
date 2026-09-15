# ADR-0007 — 스키마 레지스트리 게이트 및 데이터 계약 검증 체계 (ADR-0005 구현)

- 상태: **Accepted**
- 일자: 2026-06-09
- 관계: ADR-0005의 아키텍처 결정을 **실행 가능한 강제 메커니즘**으로 구체화. 소스 코드: `src/.../schema/`, 데모: `SchemaGateDemo.java`. (참고: `SchemaGate` 및 `CompatibilityChecker`의 핵심 검증 로직은 이후 [bifrost](https://github.com/yggdrasil-iiot/bifrost)로 정식 이관됨)

## 1. 배경 및 맥락 (Context)
ADR-0005에서는 외부 스키마 레지스트리, 유의적 버전 관리(SemVer), CI 검증 게이트를 통해 UDT 데이터 모델을 관리한다는 아키텍처 원칙을 수립하였습니다. 

그러나 초기 `UdtDemo` 단계에서는 스키마 변경에 대한 자동화된 유효성 검증 체계가 부재하여 비호환 변경이 발생하더라도 사전에 차단되지 않는 한계가 존재하였습니다. 본 ADR은 데이터 계약(Data Contract)을 사전 배포 시점에 기계적으로 강제하는 게이트웨이 메커니즘을 정의합니다.

## 2. 아키텍처 결정 (Decision)
1. **레지스트리의 정본 원천(Source of Truth) 확립**:
   - `registry/udt/<ref>/<semver>.json` 경로의 데이터 계약 정의를 권위 있는 정본으로 설정합니다.
   - 실제 네트워크를 통해 전송되는 NBIRTH의 `_types_`는 전송 시점의 표현(Wire Truth)으로 간주하며, `TemplateAdapter`를 통해 추출하여 레지스트리와 대조 검증합니다.
2. **코드형 정책 관리(Policy-as-Code)**:
   - `registry/policy.json`에 스키마 호환성 모드(FORWARD, BACKWARD, FULL, NONE)를 선언합니다.
   - **UNS 기본 모드 = FORWARD**: 설비 엣지(생산자)가 스키마를 선제 진화시키고 상위 소비자 시스템의 업그레이드가 지연되는 도메인 특성을 반영하여, "기존 소비자가 신규 데이터를 결손 없이 소비할 수 있는 전방 호환성"을 보장합니다.
   - 호환성 규칙: 필드 추가는 허용(FORWARD 충족), 필드 제거 및 데이터 타입 변경은 파괴적 변경으로 분류하여 신규 `templateRef` 분리 및 Major 버전을 증가시킵니다 (ADR-0005 `Motor` → `Motor2`).
3. **시프트-레프트(Shift-Left) 게이트 적용**:
   - `SchemaGate` CLI를 통해 변경 제안된 정의를 등록된 최신 버전과 비교 검증합니다.
   - 호환성 파괴가 감지되면 비정상 종료 코드(non-zero exit)를 반환하여 CI 빌드 및 배포 파이프라인을 차단함으로써, 결함이 런타임 현장 환경으로 전파되는 것을 원천 차단합니다.
4. **폐쇄형 실패(Fail-Closed) 원칙**:
   - `policy.json` 부재, 파일 I/O 오류, 파싱 실패 등 불확실한 상태가 발생하면 즉시 exit code 2로 종료하고 검증을 통과시키지 않습니다.

## 3. 결과 및 영향 (Consequences)
- **결정론적 거버넌스 강제**: 단순한 절차 문서나 가이드라인에 의존하지 않고, CI/CD 파이프라인의 종료 코드를 통해 아키텍처 계약을 결정론적으로 집행합니다.
- **Sparkplug 4.0 표준화 동향 부합**:
  - Sparkplug 4.0 제안(#608, 스키마와 데이터 분리)은 외부 스키마 권위를 표준화하는 방향성을 가지며, 본 레지스트리 구조는 해당 사양의 개념적 실증 역할을 수행합니다.
  - 메타데이터 확장(#607 PropertySet) 역시 본 레지스트리 거버넌스 프레임워크 내에서 포괄 관리할 수 있습니다.
- **한계점 (PoC 범위)**: 파일 시스템 기반 단일 저장소를 사용하며, 자동 데이터 마이그레이션 도구는 본 모듈 범위에 포함되지 않습니다 (런타임 드리프트 탐지는 ADR-0012에서 별도 취급).

## 4. 관련 자산 및 링크
- 소스 코드: `src/main/java/dev/krillin/sparkplug/schema/`
- 라이브 데모: `src/main/java/dev/krillin/sparkplug/SchemaGateDemo.java`
- 레지스트리 정의: `registry/`
- 연계 ADR: ADR-0005 (UDT 버전 관리), ADR-0010 (OPC UA to UDT 사상)
