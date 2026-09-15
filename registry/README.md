# UDT 데이터 계약 레지스트리 (UDT Data-Contract Registry)

UDT(Sparkplug Template) 정의의 **단일 진실 원천(Source of Truth)**입니다. NBIRTH 내의 `_types_/<Name>` 메트릭은 회선상의 표현(Wire Truth)에 불과합니다 (ADR-0005).

## 디렉토리 구조
- `policy.json` — 강제 적용할 호환성 모드 (`FORWARD` (기본값) / `BACKWARD` / `FULL` / `NONE`).
- `udt/<templateRef>/<semver>.json` — UDT 타입별 버전화된 데이터 계약 파일.

## 워크플로우 (PR 기반 데이터 계약 관리)
1. UDT 정의를 변경하려면 `udt/<ref>/<new-semver>.json` 위치에 제안 파일을 생성합니다.
2. CI에서 검증 게이트를 실행합니다:
   `mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.schema.SchemaGate -Dexec.args="registry <proposed.json>"`
3. 반환 코드(exit code): exit 0 = 호환성 만족(머지 허용), exit 1 = 파괴적 변경 감지(차단), exit 2 = 실행 오류.
4. 머지 승인 시 `--promote` 옵션으로 레지스트리에 정식 승격 반영합니다.

## 정책적 근거 (FORWARD 기본값 채택 이유)
통합 네임스페이스(UNS) 환경에서는 생산자(엣지 노드)가 UDT를 신규 버전으로 발전시키는 동안 수많은 소비자가 구버전에 머물러 있을 수 있습니다. 뒤처진 소비자가 생산자의 스키마 변경을 안전하게 수용하려면, 새로운 데이터를 수신하더라도 구형 스키마로 정상 해석이 가능해야 합니다(= FORWARD 호환성). 따라서 멤버 **추가는 허용**되나, 멤버 **삭제 및 타입 변경은 파괴적 변경(Breaking Change)**으로 규정되어 차단됩니다. 파괴적 변경 시에는 신규 `templateRef` 생성 및 메이저 버전 승격이 필수적입니다 (예: `Motor` → `Motor2`).
