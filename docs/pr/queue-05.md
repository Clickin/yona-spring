# feat(queue): add localized Thymeleaf administration with Turbo refresh

- Branch: `feat/queue-05-admin-ui`
- Base: `feat/queue-04-sse` (`2d9724cf`)
- 로컬 검증 커밋: `9a5cd461b71881b9230b6751764ef673ffb025c5`
- 아래 검증 SHA는 `upstream/next` (`6dae7982a`) 정렬 전 이력입니다.

## 목적

서버 템플릿 기반 Job queue/작업 큐 관리 화면을 제공합니다.

## 설계 요약

- 목록·상세·진행·이력·오류·결과를 Thymeleaf로 렌더링하며 native 폼은 JavaScript 없이 동작합니다.
- 취소/재시도/종결/우선 실행을 제공하고 영어·한국어 메시지와 영어 fallback을 적용합니다.
- Turbo fragment 갱신은 편집 입력을 보호하고 단순 링크 focus는 복원하면서 갱신합니다.
- remember-me 접근과 로그인 후 원래 URL 복귀를 지원합니다.

## Queue 외 변경

공통 layout 자산 context-path 변경은 선행 fix PR에 분리했습니다. 여기서는 사이트 관리 사이드바 마지막 항목만 추가합니다. npm/package-lock/Turbo는 upstream #834 것을 재사용합니다.

## 운영 영향

별도 SPA/Vue/클라이언트 상태 저장소/목록 polling은 없습니다. 기존 SSE와 요청 시 서버 렌더링을 사용합니다. 관리자는 페이지 권한을 매 요청 재확인합니다.

기존 queue schema를 업그레이드할 때는 이전 node 전체를 정지하고 새 node 한 대를 먼저 기동합니다. Priority의 DB 기본값은0이며 이전 due 인덱스 제거 절차는 durable-queue.md를 따릅니다. 이 스택은 이력 자동 삭제를 구현하지 않습니다.

## 검증 명령

```sh
./gradlew test --tests 'com.github.yonaprojects.yona.queue.*' -Dyona.it.db=h2
# SQL/schema 변경 검증은 같은 명령에 mariadb, postgres, cubrid 사용
```

```sh
python3 support-script/queue-acceptance/run.py
```

```sh
python3 support-script/queue-acceptance/run.py --serve
# 출력한 private 환경 파일을 source한 별도 터미널
e2e/node_modules/.bin/playwright test --config=e2e/queue/playwright.config.ts
```

## 후속 작업

주기 작업은 poller 발화/worker 실행과 멱등 발화 key 방식으로 후속 PR에서 구현합니다. 첫 이전 대상은 웹훅 또는 메일입니다. DB 이력 보존은 30일 terminal/365일 audit 설계와 수동 정리 절차만 문서화했습니다. SSE 재설계는 이 스택의04에서 반영하며 배포별 proxy/TLS 검증은 별도로 필요합니다.

## 확인한 검증 결과

- 위 최종 SHA에서 H2 / MariaDB / PostgreSQL / CUBRID 전체 queue suite: **각 124 passed, 실패/error/skip 0**. 이전 통합 SHA나 SSE 부분 suite의 숫자를 합산한 결과가 아닙니다.
- 같은 SHA의 실제 두 JVM HTTP runner(`/queue-it`, platform threads): REST **16 passed**, SSE 기본 **4 passed**, 확장 **7 passed**. 결과 파일 손상·누락·symlink 차단, production classpath의 fixture 부재도 통과했습니다.
- 실제 16 MiB 느린 다운로드 중 DB connection 사용량 **0→0**, `QUEUE_HTTP_ACCEPTANCE_OK`를 확인했고 fixture 프로세스는 종료했습니다.
- 제공된 RED 테스트 파일 8개는 원본과 바이트 단위로 동일합니다. 2FA 전 remember-me 쿠키 발급과 queue 접근 회귀는 상위 통합 코드에서 통과했습니다.
- 이전 root/`/queue-it` Playwright 각 16개, screenshot, 실제 로그인 복귀 및 remember-me-only UI 검증은 과거 스택 기록입니다. 이번에는 UI 템플릿을 변경하지 않았으며 브라우저 suite를 재실행하지 않았습니다.
