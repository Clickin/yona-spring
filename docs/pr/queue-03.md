# feat(queue): add administrator REST and bounded observability

- Branch: `feat/queue-03-admin-api`
- Base: `feat/queue-02-executor` (`dd306d34`)
- 로컬 검증 커밋: `feadb8bc1fbee56fa49987c0ed196a6ecabb2b28`
- 아래 검증 SHA는 `upstream/next` (`6dae7982a`) 정렬 전 이력입니다.

## 목적

사이트 관리자 전용 조회·명령·검증된 결과 다운로드와 bounded observability를 제공합니다.

## 설계 요약

- Payload/경로를 숨기고 decimal-string ID와 keyset 이력을 반환합니다.
- cancel/retry/abandon/prioritize/deprioritize는 현재 SITE_ADMIN과 CSRF를 검증하고 commandId로 멱등 처리합니다.
- 선행 2FA 수정의 remember-me key를 공유하고 SAMEORIGIN을 적용합니다. API 익명 요청은 JSON401이며 2FA 미완료 로그인 응답의 쿠키로 관리 API에 접근할 수 없습니다.
- 별도 metrics daemon과 clock.trusted gauge를 제공합니다. Metrics는 clock과 같은 lifecycle phase로 worker보다 먼저 시작하고 나중에 종료됩니다.

## Queue 외 변경

GlobalModelAttributeAdvice의 queue 분기는 JSON/download 요청에서 HTML용 사용자/초기설정 조회와 OSIV connection 점유를 피합니다. WebMvcConfig 관련 제외 경로도 이 목적입니다. Remember-me key는 선행 `fix/remember-me-2fa`의 상수를 재사용하며 이 PR에서 메인 로그인 체인을 다시 변경하지 않습니다.

## 운영 영향

metrics-refresh-millis=30000, metrics daemon1개. 새 요청마다 관리자 권한을 DB에서 확인합니다. failed-jobs counter backfill 때문에 모든 이전 node를 중지하고 새 node1대를 먼저 기동한 뒤 나머지를 기동해야 합니다.

기존 queue schema를 업그레이드할 때는 이전 node 전체를 정지하고 새 node 한 대를 먼저 기동합니다. Priority의 DB 기본값은0이며 이전 due 인덱스 제거 절차는 durable-queue.md를 따릅니다. 이 스택은 이력 자동 삭제를 구현하지 않습니다.

## 검증 명령

```sh
./gradlew test --tests 'com.github.yonaprojects.yona.queue.*' -Dyona.it.db=h2
# SQL/schema 변경 검증은 같은 명령에 mariadb, postgres, cubrid 사용
```

```sh
python3 support-script/queue-acceptance/run.py
```

## 후속 작업

주기 작업은 poller 발화/worker 실행과 멱등 발화 key 방식으로 후속 PR에서 구현합니다. 첫 이전 대상은 웹훅 또는 메일입니다. DB 이력 보존은 30일 terminal/365일 audit 설계와 수동 정리 절차만 문서화했습니다. SSE 재설계는 이 스택의04에서 반영하며 배포별 proxy/TLS 검증은 별도로 필요합니다.

## 확인한 검증 결과

- 위 최종 SHA에서 H2 / MariaDB / PostgreSQL / CUBRID 전체 queue suite: **각 103 passed, 실패/error/skip 0**.
- 첫 MariaDB 실행 중 JDBC EOF와 이후 connection refused가 발생해 context 생성이 실패했습니다. 실패 로그를 보존하고 새 Testcontainer에서 같은 최종 SHA의 전체 suite를 다시 실행해 103개 통과를 확인했습니다.
- 제공된 `QueueAdminTwoFactorRememberMeSpec`의 단언은 변경하지 않았으며, 2FA 미완료 로그인 쿠키를 재전송한 관리 API 요청이 401로 거부됩니다.
- 최종 통합 05(`9a5cd461b71881b9230b6751764ef673ffb025c5`)의 실제 두 JVM, `/queue-it`/platform HTTP runner에서 REST 16개, 파일 손상·누락·symlink 거부, 16 MiB 느린 다운로드 중 DB connection **0→0**, production fixture 부재를 확인했습니다.
- 이전 root/VT 및 실제 legacy 로그인→remember-me-only 관리자 페이지 검증은 과거 스택 기록입니다. 이번 선행 보안 수정에서는 H2 25개 테스트로 2FA 지연 발급, 기존 key 쿠키 거부, 기존 session 유지와 비2FA password-upgrade 쿠키 재사용을 확인했습니다.
