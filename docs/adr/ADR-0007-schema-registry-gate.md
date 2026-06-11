# ADR-0007 — 스키마 레지스트리 게이트 / 데이터계약 강제 (ADR-0005 구현)

- 상태: **Accepted**
- 일자: 2026-06-09
- 관계: ADR-0005(결정)를 **동작하는 강제 메커니즘**으로 구현. 코드: `src/.../schema/`, 데모 `SchemaGateDemo.java`.

## Context
ADR-0005는 "외부 레지스트리 + SemVer + CI 게이트로 UDT를 통치한다"는 *결정*을 내렸으나, 기존 코드(`UdtDemo`)는 스키마가 검사 없이 조용히 교체되는 *공백*만 실증했다. 본 ADR은 그 결정을 실행 코드로 강제한다.

## Decision
1. **레지스트리 = source of truth.** `registry/udt/<ref>/<semver>.json` 데이터계약. NBIRTH `_types_`는 wire truth(TemplateAdapter로 추출해 대조).
2. **policy-as-code.** `registry/policy.json`의 호환성 모드(Confluent 어휘 FORWARD/BACKWARD/FULL/NONE). **UNS 기본 = FORWARD** — 프로듀서가 모델을 진화시키고 다수 소비자가 지연되므로 "구 소비자가 신 데이터 읽기"를 보장. 멤버 추가=OK, 제거/타입변경=파괴 → 새 `templateRef`+major bump(ADR-0005 `Motor`→`Motor2`).
3. **shift-left 게이트.** `SchemaGate` CLI가 제안 정의를 등록본과 대조해 **breaking이면 non-zero exit**로 CI/배포를 차단(런타임에 OT 데이터를 떨구지 않음).
4. **fail-closed.** policy.json 부재/오류 시 통과시키지 않음(exit 2).

## Consequences
- 거버넌스가 프로토콜 밖 CI에서 *실제로* 강제됨(문서가 아니라 exit code).
- SpB 4.0 수렴과 정합: **#608 Definition(schema) 메시지**(스키마↔데이터 분리)는 외부 스키마 권위를 스펙화하는 방향 — 본 레지스트리가 그 역할의 PoC. #607 payload properties도 메타데이터 거버넌스로 확장 가능.
- 한계(PoC): 단일 디스크 레지스트리·단일 major 라인·인스턴스 자동 마이그레이션 없음. 런타임 드리프트 탐지는 비범위(detect-only는 별도 트랙).

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/schema/`
- 데모: `../../src/main/java/dev/krillin/sparkplug/SchemaGateDemo.java`
- 레지스트리: `../../registry/`
- 선행: ADR-0005. OPC UA측 적용: ADR-0010
