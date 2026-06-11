# ADR-0005 — UDT(Template) 버전·스키마 거버넌스

- 상태: **Accepted**
- 일자: 2026-06-03
- 근거: 직접 실험 (`src/.../UdtDemo.java`), Tahu 1.0.14 / HiveMQ CE

## Context
UDT(Sparkplug Template)는 UNS의 **데이터 모델**이다. 정의(definition)와 인스턴스(instance)가 NBIRTH로 흐르고, `version` 필드가 있다. 거버넌스 아키텍트의 핵심 질문: *전사·다사이트·다벤더에서 UDT 정의를 어떻게 통치·버저닝하는가.*

## Experiment (실측)
`UdtDemo`로 "Motor" UDT를 발행:
- **v1.0:** def{Rpm:Double, Running:Boolean} + param Location; 인스턴스 Motors/Motor1{Rpm=1500, Running=true}.
- **v2.0:** 동일 def에 **멤버 Temperature:Double 추가**, version="2.0"; 인스턴스에 Temperature=65.4.

호스트 디코드 결과: 두 버전 모두 정상 수신. definition/ref/version/param/member 전부 보존.

## Findings
1. **`version`은 자유 문자열 — 프로토콜이 강제하지 않음.** "1.0"/"2.0"은 관례일 뿐, 호환성 의미 없음.
2. **스키마 레지스트리 부재.** 정의의 유일한 출처가 NBIRTH 페이로드. birth를 놓쳤거나 v1 기준으로 만든 소비자는 정의를 **검증할 권위가 없다.**
3. **멤버 추가/삭제/타입변경이 무제약.** v1→v2에서 멤버가 늘어도 브로커/프로토콜은 (a)호환검사 (b)마이그레이션 (c)거부 무엇도 하지 않음 → v1 가정 소비자가 **조용히 깨질 수 있음.**
4. **Ignition UDT ↔ raw Sparkplug Template은 별개 모델** → 이중 정의 정합성(드리프트) 위험.

## Decision (거버넌스 — 프로토콜 밖에서 강제)
- UDT 정의를 **외부 스키마 레지스트리 + 리뷰**로 통치(소유권·승인·이력). NBIRTH의 `_types_/`는 *wire truth*일 뿐 *source of truth* 아님.
- **버전 정책 = SemVer 강제(거버넌스가):** 멤버 추가=minor(하위호환), 멤버 삭제/타입변경=major(파괴). **파괴 변경은 새 `templateRef`로** 분리(예: `Motor` → `Motor2`)하여 v1 소비자 보호.
- **소비자는 수신 version의 major를 기대값과 대조**, 미지 major는 거부/경보.
- CI에서 정의 변경의 호환성(additive-only within major)을 게이트.

## Consequences
- 거버넌스 부담이 **프로토콜 밖**에 있음(Sparkplug는 안 막아줌) → 레지스트리·CI·정책 문서가 실제 통제 수단.
- 네임스페이스 표준(namespace-standard.md §5)에 UDT 소유권·버전·`templateRef` 규약 명문화 필요.
- OPC UA 정보모델을 UDT로 사상할 때 이 버저닝 규약이 매핑 거버넌스의 뼈대(ADR-0010).

## Links
- 코드: `../../src/main/java/dev/krillin/sparkplug/UdtDemo.java`
- OPC UA→UDT 매핑: ADR-0010
