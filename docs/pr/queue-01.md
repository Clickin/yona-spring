# feat(queue): add transactional durable store and state model

- Branch: `feat/queue-01-store`
- Base: `fix/remember-me-2fa` (`479d5094`)
- 로컬 검증 커밋: `8435e5bd4b26178c6e19dc60625a51ff530d863e`
- 정렬된 스택 root: `upstream/next` (`6dae7982a`). 아래 검증 SHA는 정렬 전 이력입니다.

## 목적

업무 트랜잭션과 함께 commit/rollback되는 DB queue 저장소와 순수 상태 전이 모델을 도입합니다.

## 설계 요약

- 정확한 payload bytes 기반 멱등성, assigned ID, retained attempt 스키마를 유지합니다.
- 전용 clock 스레드와 조정 가능한 skew/trust, 세션 timezone 보존을 적용합니다.
- 별도 autocommit pending 집계와 트랜잭션 로컬 admission 수로 MySQL 전체 pending row 잠금을 제거합니다.
- SMALLINT priority와 cancellation/abandon 전이를 정의합니다. 관리자 명령·실행기는 상위 PR에서 연결합니다.
- 취소 중 lease 만료는 `CANCELLED`/`LEASE_LOST`로 종결합니다. Clock은 worker보다 먼저 시작하고 나중에 종료하며 HikariCP가 아니면 기동을 중단합니다.

## Queue 외 변경

공통 CUBRID 잠금과 layout context-path 수정은 선행 fix 브랜치로 분리했습니다. 이 PR은 queue 저장소만 포함합니다.

## 운영 영향

clock daemon 1개, 1초 health 조회. clock-skew-millis=250(50–5000), clock-trust-millis=5000. 업무 트랜잭션은 pool connection 두 개를 잠시 사용할 수 있고 2초 내 확보하지 못하면 QUEUE_UNAVAILABLE입니다.

기존 queue schema를 업그레이드할 때는 이전 node 전체를 정지하고 새 node 한 대를 먼저 기동합니다. Priority의 DB 기본값은 0이며 이전 due 인덱스 제거 절차는 durable-queue.md를 따릅니다. 이 스택은 이력 자동 삭제를 구현하지 않습니다.

## 검증 명령

```sh
./gradlew test --tests 'com.github.yonaprojects.yona.queue.*' -Dyona.it.db=h2
# SQL/schema 변경 검증은 같은 명령에 mariadb, postgres, cubrid 사용
```

## 후속 작업

주기 작업은 poller 발화/worker 실행과 멱등 발화 key 방식으로 후속 PR에서 구현합니다. 첫 이전 대상은 웹훅 또는 메일입니다. DB 이력 보존은 30일 terminal/365일 audit 설계와 수동 정리 절차만 문서화했습니다. SSE 재설계는 이 스택의 04에서 반영하며 배포별 proxy/TLS 검증은 별도로 필요합니다.

## 확인한 검증 결과

- 위 최종 SHA에서 H2 / MariaDB / PostgreSQL / CUBRID 전체 queue suite: **각 50 passed, 실패/error/skip 0**.
- Docker DB는 OrbStack socket을 `DOCKER_HOST`로 지정해 실행했습니다. 최초 기본 socket 탐색 실패는 환경 문제였으며 socket 지정 후 세 DB 모두 통과했습니다.
- MySQL·SQL Server와 6종 EXPLAIN 검증은 이전 스택 기록이며 이번 최종 SHA에서 재실행하지 않았습니다.
- 회귀: 공유 scheduler 10초 정지 중 clock 유지, pool 고갈의 2초 거부, 트랜잭션 로컬 admission/rollback, 취소 중 실패 outcome, abandon acknowledgement.
- PostgreSQL epoch cast는 조기 실행을 막도록 FLOOR를 적용합니다. CUBRID 인덱스 삭제는 `DROP INDEX ... ON queue_job`입니다.

## 선행 PR

- `fix/cubrid-pessimistic-lock` — `a1acfedfe`
- `fix/layout-context-path` — `e1717b113`
- `fix/remember-me-password-upgrade` — `0717a984c`: legacy password 재해시 후 오래된 principal hash로 remember-me cookie를 서명하던 공통 로그인 버그. 독립 18개 테스트와 실제 cookie-only HTTP 접근을 검증했습니다.
- `fix/remember-me-2fa` — `f7b5f8d35`: 2FA 완료 뒤에만 remember-me 발급. 기존 remember-me 쿠키 전체 무효화, 로그인 session 유지. 관련 H2 25개 테스트 통과.
- CUBRID 공통 잠금 수정의 전체 suite에서는 21개 실패가 남았습니다. upstream dialect로 되돌려 같은 21개 테스트의 실패를 재현했으며, queue suite와 별개입니다.
