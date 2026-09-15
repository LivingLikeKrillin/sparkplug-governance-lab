# 시각 아키텍처 자산 거버넌스 및 설계 규격서 (Visual Assets & Architecture Governance)

본 디렉토리는 시스템 아키텍처, 프로토콜 수명주기, 데이터 파이프라인의 엔지니어링 설계를 시각화하고 버전 관리하기 위한 **다이어그램 자산 관리 체계**를 정의합니다.

기술 문서(README, 기술 블로그, ADR, 슬라이드, PDF 보고서)에 임베드되는 모든 시각 자산은 본 사양서의 렌더링 원칙과 기계적 레이아웃 검증 기준을 엄격히 준수해야 합니다 (세부 디자인 사양: `../superpowers/specs/2026-06-17-portfolio-visuals-design.md`).

---

## 1. 시각 디자인 및 렌더링 설계 원칙 (Design Principles)

모든 아키텍처 다이어그램은 다양한 플랫폼 환경(GitHub 라이트/다크 모드, 인쇄물, 고해상도 디스플레이)에서 왜곡 없이 일관된 시각적 명확성을 제공하기 위해 다음 3대 원칙을 강제합니다:

1. **불투명 카드형 캔버스 (`-b "#ffffff"`)**:
   - 투명 캔버스(Transparent Canvas)를 채택할 경우, GitHub 다크 모드 등 어두운 테마 환경에서 어두운 텍스트 및 연결선이 배경에 묻혀 가독성이 치명적으로 저하됩니다.
   - 따라서 모든 SVG는 둥근 모서리(`rx="14"`)와 은은한 경계선(`stroke="#d0d7de"`)을 가진 불투명 흰색 카드 형태의 캔버스를 독립적으로 보유합니다.
2. **배경 칩 배제 및 선 측면 라우팅 (Chip-free Orthogonal Routing)**:
   - 연결선(Edge) 위에 불투명한 텍스트 배경 칩(Background Chip)을 덮는 방식을 전면 배제합니다. 칩이 화살표 머리나 교차선을 가려 데이터 흐름을 왜곡하는 현상을 방지하기 위해, 모든 텍스트 레이블은 연결선 측면에 직교 배치됩니다.
3. **수작업 제작 표준 SVG 일원화 (House-dialect Hand-authored SVG)**:
   - 런타임 렌더러(`resvg`, Puppeteer 등)에 따라 CSS 스타일이나 `<foreignObject>`가 깨지는 문제를 방지하기 위해, 순수 SVG 프리미티브(`<rect>`, `<text>`, `<polyline>`, `<path>`) 기반의 수작업 제작 SVG로 관리합니다.

---

## 2. 관리 자산 현황 (Assets Catalog)

| 파일 경로 | 다이어그램 주제 및 표현 내용 | 렌더링 방식 | 노출 위치 |
|-----------|------------------------------|-------------|-----------|
| [`svg/system-architecture.svg`](svg/system-architecture.svg) | **전체 시스템 거버넌스 아키텍처**<br>Bifrost 정본 권위 평면, HiveMQ 브로커, Heimdall 엣지 인가 엔진(Deny-by-Default), OPC UA 현장 설비, UNS/Muninn 피드백 루프 통합 표현 | 수작업 제작 SVG | 메인 `README.md` §3 |
| [`svg/system-architecture.ko.svg`](svg/system-architecture.ko.svg) | **시스템 거버넌스 아키텍처 (한국어 변형본)**<br>기술 발표 슬라이드 및 아티클 전용 한국어 뷰 | 수작업 제작 SVG | 슬라이드 / 블로그 |
| [`svg/governance-lifecycle.svg`](svg/governance-lifecycle.svg) | **핵심 거버넌스 닫힌 루프 (The Closed Loop)**<br>사전 배포 게이트(`SchemaGate`)와 런타임 관측(`DriftMonitor`)의 상호 피드백 루프 | 수작업 제작 SVG | 메인 `README.md` §2 |
| [`svg/seq-schema-data-separation.svg`](svg/seq-schema-data-separation.svg) | **Sparkplug 4.0 스키마-데이터 분리 시퀀스**<br>영속 DEFINITION 1회 등록 및 경량 `schemaRef` 텔레메트리 스트리밍 절차 | 수작업 제작 SVG | `docs/adr/ADR-0008` |
| [`svg/seq-ncmd-authorization.svg`](svg/seq-ncmd-authorization.svg) | **NCMD 제어 명령 2계층 심층 인가 시퀀스**<br>브로커 토픽 ACL(도달성)과 엣지 페이로드 인가(명령/값)의 심층 방어 구조 | 수작업 제작 SVG | `docs/adr/ADR-0011` |
| [`svg/ot-it-dataflow.svg`](svg/ot-it-dataflow.svg) | **OT→IT 엔터프라이즈 데이터 파이프라인**<br>OPC UA 계층 모델 탐색 → 타입 사상 및 손실 원장 → Kafka 로그 압축 연계 | 수작업 제작 SVG | 메인 `README.md` §4 |
| [`svg/loss-ledger.svg`](svg/loss-ledger.svg) | **변환 손실 원장(Loss Ledger) 통계 차트**<br>타입 사상 시 발생하는 손실 항목 정량화 및 사이드 채널 보존 통계 | 수작업 제작 SVG | 기술 블로그 / 슬라이드 |
| [`svg/nbirth-size.svg`](svg/nbirth-size.svg) | **NBIRTH 페이로드 크기 비교 차트**<br>인라인 전송(328 B) 대비 스키마 분리 전송(162 B)의 50% 절감 실측치 | 수작업 제작 SVG | 기술 블로그 / 슬라이드 |

---

## 3. 3대 레이아웃 결함 기계적 검증 기준 (Layout Conformance)

한글 텍스트는 영문 대비 글자 폭(Font Metrics: 영문 ~0.6em, 한글 ~1.05em)이 넓으므로, 텍스트 확장으로 인한 시각적 결함을 사전에 방지하기 위해 폰트 메트릭 검증기를 통해 다음 3대 결함 **0건**을 불변식으로 검증합니다:

1. **캔버스 오버플로우 (Canvas Overflow)**: 모든 텍스트와 사각형이 `viewBox` 외곽 캔버스 경계를 벗어나지 않아야 합니다 (마진 >= 8px).
2. **텍스트 충돌 (Text Collision)**: 독립된 두 텍스트 바운딩 박스 간의 수평/수직 겹침이 발생하지 않아야 합니다 (마진 >= 4px).
3. **박스 경계 침범 (Box Overflow)**: 노드 및 그룹 사각형 내부의 텍스트가 컨테이너 박스 폭(`width`)을 초과하여 삐져나오지 않아야 합니다.
