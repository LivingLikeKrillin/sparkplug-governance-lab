# UDT 데이터 계약 레지스트리 운영 규격서 (UDT Data-Contract Registry Specification)

본 디렉토리는 산업 현장의 분산 엣지 노드가 발행하는 Sparkplug UDT(Template) 데이터 계약의 **단일 진실 원천(Registry-of-Record)**입니다.

Sparkplug B 프로토콜에서 NBIRTH 페이로드의 `_types_/<Name>` 메트릭은 회선상의 관측 표현(Wire Truth)에 불과하며, 데이터 소비자와 생산자 간의 장기적인 계약 정합성을 보장하지 못합니다 ([ADR-0005](../docs/adr/ADR-0005-udt-versioning-schema-governance.md)). 본 레지스트리는 스키마 변경을 GitOps 기반으로 통제하여 분산 네임스페이스의 데이터 오염을 방지합니다.

---

## 1. 디렉토리 구조 및 구성 체계

```
registry/
├── policy.json                      # 레지스트리 전역 호환성 강제 정책
└── udt/
    └── <templateRef>/               # UDT 타입별 네임스페이스 (예: Motor, Pump)
        ├── 1.0.0.json               # SemVer 기반 버전화된 데이터 계약 파일
        ├── 1.1.0.json
        └── 2.0.0.json
```

- **`policy.json`**: 강제 적용할 호환성 모드 정의 (`FORWARD` (기본값) / `BACKWARD` / `FULL` / `NONE`).
- **`udt/<templateRef>/<semver>.json`**: 각 UDT 버전의 멤버 목록, 데이터 타입, 파라미터 제약조건을 명시한 정본 계약 정의.

---

## 2. GitOps 기반 데이터 계약 관리 워크플로우 (Data-Contract-as-PR)

모든 UDT 스키마의 수정 및 확장은 반드시 Pull Request(PR)를 통한 정적 검증 게이트를 거쳐야 합니다:

1. **스키마 변경 제안**:
   - 변경하고자 하는 UDT의 신규 버전 파일을 `udt/<templateRef>/<new-semver>.json` 경로에 작성합니다.
2. **CI 사전 배포 게이트 검증**:
   - CI 파이프라인에서 레지스트리 호환성 검증기를 실행합니다:
     ```bash
     mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.schema.SchemaGate -Dexec.args="registry <proposed.json>"
     ```
3. **판정 결과 및 반환 코드 (Exit Code Semantics)**:
   - `exit 0 (COMPATIBLE)`: 호환성 검증 통과. PR 머지 허용.
   - `exit 1 (BREAKING)`: 비호환 파괴적 변경 감지. 파이프라인 실패 및 머지 차단 (폐쇄형 실패).
   - `exit 2 (ERROR)`: 문법 오류 또는 파일 접근 실패 등 비정상 실행 오류.
4. **정식 승격 (Promotion)**:
   - PR 검토 및 머지 완료 시 `--promote` 옵션을 통해 레지스트리 활성 버전으로 정식 승격 반영합니다.

---

## 3. 호환성 정책 설계 근거: 왜 FORWARD 모드가 기본값인가?

통합 네임스페이스(UNS) 아키텍처에서는 수십~수백 대의 엣지 노드(생산자)가 각자의 릴리스 주기에 맞춰 독립적으로 스키마를 업데이트합니다. 반면 MES, ERP, 시계열 분석 플랫폼 등 다수의 소비자(Consumer)는 상대적으로 보수적인 배포 주기를 가집니다.

- **FORWARD 호환성 (Forward Compatibility)**:
  - 구버전 스키마를 가진 소비자가 신규 스키마로 발행된 최신 데이터를 정상적으로 역직렬화하고 해석할 수 있는 상태를 의미합니다.
  - **허용 변경 (Non-breaking)**: 신규 멤버 추가 (지연 소비자는 미인지 필드를 안전하게 무시).
  - **차단 변경 (Breaking)**: 기존 멤버 삭제, 멤버 이름 변경, 데이터 타입 변경 (지연 소비자의 파싱 크래시 유발).
- **파괴적 변경 대응 원칙**:
  - 기존 멤버의 타입 변경이나 삭제가 불가피한 경우, 동일 `templateRef` 내에서의 마이너 업데이트를 금지하며, 반드시 신규 타입 식별자(예: `Motor` → `Motor2`)를 부여하고 메이저 버전을 승격해야 합니다.
