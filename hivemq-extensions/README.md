# 지연 참여자 상태 복원 실증 가이드 (HiveMQ Sparkplug-Aware Extension)

본 디렉토리는 뒤늦게 세션에 참여한 소비자(Late-Joiner)가 접속 즉시 엣지 설비의 전체 메트릭 상태를 누락 없이 복원할 수 있는지 검증하기 위한 **HiveMQ Sparkplug-Aware 확장 기능 실증 환경**을 다룹니다 ([ADR-0002](../docs/adr/ADR-0002-late-joiner-state-on-connect.md)).

---

## 1. 문제 배경: 일반 MQTT 브로커와 지연 참여자(Late-Joiner)의 한계

Sparkplug B 사양에서 엣지 노드는 세션 수립 시 단 1회의 `NBIRTH`를 발행하고 이후에는 변경된 메트릭만 `NDATA`(Report by Exception)로 전송합니다.

- **일반 MQTT 브로커 환경**:
  - 브로커는 페이로드의 Sparkplug 의미론을 알지 못하므로, 노드가 기동된 이후 뒤늦게 구독을 시작한 지연 소비자(MES, 대시보드 등)는 메트릭 별칭(Alias) 매핑과 이전 상태를 수신하지 못합니다.
  - 소비자가 상태를 복원하려면 엣지 노드에 강제로 `Rebirth` 명령(NCMD)을 전송해야 하며, 이는 현장 제어망에 불필요한 네트워크 트래픽 급증(Storm)을 유발합니다.
- **Sparkplug 인식(Sparkplug-Aware) 브로커 환경**:
  - 브로커가 회선의 Sparkplug 페이로드를 파싱하여 최신 메트릭 상태 테이블을 메모리에 유지합니다.
  - 신규 소비자가 구독을 등록하는 즉시 브로커가 합성된 가상 NBIRTH를 캐시에서 직접 주입하여, 엣지 노드에 대한 재발행 요청 없이 즉각적인 상태 복원(State-on-Connect)을 보장합니다.

---

## 2. 실증 환경 구축 및 실행 절차

### 1단계: 확장 플러그인 다운로드 및 배치
1. HiveMQ 공식 오픈소스 저장소에서 릴리스 패키지를 다운로드합니다:
   - [hivemq-sparkplug-aware-extension 릴리스 (4.33.4 버전 검증 완료)](https://github.com/hivemq/hivemq-sparkplug-aware-extension/releases)
2. 다운로드한 아카이브의 압축을 해제하여 아래 경로 구조로 배치합니다:
   ```
   hivemq-extensions/
   └── hivemq-sparkplug-aware-extension/
       ├── hivemq-extension.xml
       └── hivemq-sparkplug-aware-extension-*.jar
   ```

### 2단계: Docker Compose 오버레이 기동
로컬 개발 환경의 기본 보안 설정(allow-all)을 훼손하지 않기 위해 다중 Compose 파일 오버레이 방식을 적용합니다:

```bash
docker compose -f docker-compose.yml -f docker-compose.aware.yml up -d --force-recreate
```

> **오버레이 마운트 격리 원칙**:
> 전체 `/opt/hivemq/extensions` 디렉토리를 통째로 바인드 마운트할 경우 이미지에 내장된 기본 allow-all 확장이 덮어씌워져 로컬 클라이언트 인증 오류가 발생합니다. 본 랩의 `docker-compose.aware.yml`은 해당 플러그인의 단일 하위 디렉토리만 정밀하게 컨테이너에 투영하도록 격리되어 있습니다.

### 3단계: A/B 비교 테스트 실행
순수 브로커와 인식 브로커 모드 간의 상태 복원 동작 차이는 다음 명령어로 실증합니다:

```bash
mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.LateJoinerExperiment
```
