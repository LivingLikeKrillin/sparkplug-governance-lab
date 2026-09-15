# HiveMQ 확장 기능 (외부 다운로드, 저장소 미포함)

지연 참여자(Late-Joiner) A/B 실험(`LateJoinerExperiment`, ADR-0002)은 순수 HiveMQ CE 브로커와 **Sparkplug 인식(Sparkplug-aware)** 브로커의 상태 복원 동작을 비교 검증합니다. Sparkplug 인식 브로커 모드를 실행하려면 아래 절차를 따르십시오:

1. 오픈소스 [hivemq-sparkplug-aware-extension](https://github.com/hivemq/hivemq-sparkplug-aware-extension/releases) 릴리스(4.33.4 버전 검증 완료)를 다운로드합니다.
2. 압축을 해제하여 `hivemq-extensions/hivemq-sparkplug-aware-extension/hivemq-extension.xml` 경로 구조로 배치합니다.
3. aware 오버레이 설정을 적용하여 브로커를 기동합니다:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.aware.yml up -d --force-recreate
   ```

해당 오버레이는 컨테이너에 이 하위 디렉토리만 마운트합니다. 전체 `/opt/hivemq/extensions` 디렉토리를 마운트할 경우 로컬 개발 환경에서 사용하는 기본 allow-all 확장이 덮어씌워져 인증 오류가 발생할 수 있습니다.
