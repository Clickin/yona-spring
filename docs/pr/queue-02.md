# feat(queue): run bounded fenced workers with durable recovery

- Branch: `feat/queue-02-executor`
- Base: `feat/queue-01-store` (`f0cbd4e0`)
- 로컬 검증 커밋: `23308ea259b694669e3e60700c3d771ff507d1fb`
- 아래 검증 SHA는 `upstream/next` (`6dae7982a`) 정렬 전 이력입니다.

## 목적

DB fence·lease·resource guard를 검증하는 실행기와 관리자 내부 명령을 추가합니다.

## 설계 요약

- Semaphore slot을 claim 전에 확보하고 handler가 실제 반환할 때까지 유지합니다. Spring virtual-thread 설정을 따릅니다.
- DB 시각 포함 스캔, recovery 별도 주기, idle backoff와 after-commit wake를 적용합니다.
- 우선순위 keyset으로 최대 4×64건을 탐색하고 포화 lane을 제외합니다.
- 취소 중 실패와 lease 만료를 CANCELLED로 종결하고 원래 failure outcome을 보존합니다. abandon/prioritize 명령을 audit·멱등 처리합니다.
- 실행 후 빈 staging 부모까지 정리합니다. Orphan 정리는 기동 후 별도 scheduler에서 DB 확인 100개씩 수행하며 숫자순 job cursor로 다음 회차를 이어갑니다.
- job별 heartbeat executor와 in-flight 제한, progress `tryLock`, lease의 1/4 transaction timeout으로 느린 fenced DB 작업의 영향을 격리합니다.
- poller·cleanup 장애는 상태 전이 시 warn, 반복은 debug, 복구는 info로 기록합니다. Poller 실패 간격은 idle 상한까지 늘립니다.

## Queue 외 변경

WebMvcConfig와 SvnController의 servlet 조건은 HTTP listener 없는 worker context에서 웹 전용 bean을 생성하지 않기 위해 필요합니다.

## 운영 영향

workers는 동시 실행 slot 수이며 node별입니다. Worker와 heartbeat executor는 분리하며 virtual-thread 설정을 따릅니다. Poller·heartbeat 제출·orphan 정리 daemon이 각각 1개 있습니다. recovery-poll-millis 기본 min(5000, lease/4), idle-poll-max-millis=1000. Guard 파일은 삭제하지 않습니다. Pool에는 handler·heartbeat·cleanup과 admission의 두 connection을 위한 여유가 필요합니다.

기존 queue schema를 업그레이드할 때는 이전 node 전체를 정지하고 새 node 한 대를 먼저 기동합니다. Priority의 DB 기본값은0이며 이전 due 인덱스 제거 절차는 durable-queue.md를 따릅니다. 이 스택은 이력 자동 삭제를 구현하지 않습니다.

## 검증 명령

```sh
./gradlew test --tests 'com.github.yonaprojects.yona.queue.*' -Dyona.it.db=h2
# SQL/schema 변경 검증은 같은 명령에 mariadb, postgres, cubrid 사용
```

## 후속 작업

주기 작업은 poller 발화/worker 실행과 멱등 발화 key 방식으로 후속 PR에서 구현합니다. 첫 이전 대상은 웹훅 또는 메일입니다. DB 이력 보존은 30일 terminal/365일 audit 설계와 수동 정리 절차만 문서화했습니다. SSE 재설계는 이 스택의04에서 반영하며 배포별 proxy/TLS 검증은 별도로 필요합니다.

## 확인한 검증 결과

- 위 최종 SHA에서 H2 / MariaDB / PostgreSQL / CUBRID 전체 queue suite: **각 92 passed, 실패/error/skip 0**.
- 제공된 R-2/R-3/R-4/R-5/D-1 RED 테스트는 단언 변경 없이 통과했습니다. Staging이 있는 느린 fenced 작업과 cleanup 경합, 정리 cursor의 재시작·부분 진행 회귀도 포함합니다.
- 가상/플랫폼 스레드의 slot 제한·claim 예외·grace 반환, resource에 막힌 100개 작업 뒤의 실행, priority/FIFO와 포화 lane 제외를 확인했습니다.
- 유휴 10초의 due-scan 상한과 **commit 후** 100ms 내 handler 진입을 확인했습니다. 호출자의 업무 트랜잭션이 아직 commit되지 않은 시간은 wake 지연이 아닙니다.
- 취소 중 실패의 즉시 CANCELLED 종결, abandon 멱등성과 failed-jobs 감소, staging 삭제, stale publication 격리 및 동시 게시 안전성을 검증했습니다.
- 기존 post-lock lease 검사는 새 `fencedDb` timeout에 가려지지 않도록 명시적 transaction 안의 `assertCurrent` 경로로 분리했습니다. 기존 `StaleAttempt`와 DB 효과 없음 단언은 유지했습니다.
- 이전 커밋에서 별도로 실행한 전체 worker VT 설정 및 pinning trace 결과는 이번 최종 SHA의 추가 검증으로 주장하지 않습니다.
