# 다이어그램 자산 (Diagram Assets)

포트폴리오 표면(README, 기술 블로그, 발표 슬라이드, PDF 등)을 위해 버전 관리되는 다이어그램 시각 자산 목록입니다.  
디자인 스타일: "Light / GitHub-native". 사양서: `../superpowers/specs/2026-06-17-portfolio-visuals-design.md`.

## 자산 현황 (Assets)

| 파일 경로 | 원천 소스 | 자동 렌더링 여부 | 표시 위치 |
|-----------|-----------|------------------|-----------|
| `svg/governance-lifecycle.svg` | 파일 자체 (수작업 제작) | 아니오 | Hero 섹션; README "핵심 개념 (The one-line idea)" |
| `svg/system-architecture.svg` | 파일 자체 (수작업 제작) | 아니오 | README "아키텍처 (Architecture)" |
| `svg/system-architecture.ko.svg` | 파일 자체 (수작업 제작) | 아니오 | 발표 슬라이드 / 기술 블로그 (한국어 아키텍처 다이어그램) |
| `svg/seq-schema-data-separation.svg` | 파일 자체 (수작업 제작) | 아니오 | ADR-0008 |
| `svg/seq-ncmd-authorization.svg` | 파일 자체 (수작업 제작) | 아니오 | ADR-0011 |
| `svg/ot-it-dataflow.svg` | 파일 자체 (수작업 제작) | 아니오 | README "OT→IT 데이터 흐름" |
| `svg/nbirth-size.svg` | 파일 자체 (수작업 제작) | 아니오 | 발표 슬라이드 / 기술 블로그 |
| `svg/loss-ledger.svg` | 파일 자체 (수작업 제작) | 아니오 | 발표 슬라이드 / 기술 블로그 |

## 렌더링 및 캔버스 설계 원칙

본 저장소의 모든 다이어그램은 resvg 렌더링 호환성 및 다크 모드 가독성을 보장하기 위해 수작업 제작된 표준 SVG(House-dialect SVG)로 일원화되어 관리됩니다.

- **불투명 흰색 캔버스(`-b "#ffffff"`)**: 투명 캔버스를 사용할 경우 GitHub 다크 모드에서 어두운 배경 위에 어두운 노드 텍스트가 겹쳐 가독성이 저하되므로, 모든 다이어그램은 깨끗한 카드 형태의 불투명 흰색 배경을 사용합니다.
- **배경 칩(Chip) 배제 및 직교 라우팅**: 선(edge) 위에 텍스트 배경 칩을 덮지 않고, 텍스트 레이블을 선 옆에 배치하여 화살표 및 연결선의 가시성을 100% 확보합니다.
- **레이아웃 결함 0건 기계적 검증**: 캔버스 오버플로우(Canvas Overflow), 텍스트 충돌(Text Collision), 박스 경계 초과(Box Overflow)를 방지하기 위해 폰트 메트릭 기반의 검증 스크립트로 레이아웃 무결성을 보장합니다.
