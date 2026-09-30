# feat(queue): add portable bounded administrator event streams

- Branch: `feat/queue-04-sse`
- Base: `feat/queue-03-admin-api` (`f291b76b`)
- 로컬 검증 커밋: `8d877028849d836eed3047af212491d5f2b2e354`
- 아래 검증 SHA는 `upstream/next` (`6dae7982a`) 정렬 전 이력입니다.

## 목적

REST 재조회 신호만 보내는 servlet 표준 SSE를 추가합니다.

## 설계 요약

- Spring MVC SseEmitter와 공유 권한/generation poller를 사용합니다. Tomcat 내부 API와 protocol 교체를 제거합니다.
- node32/principal2/session2 quota, reset/changed/comment heartbeat, 5분 수명과 재연결을 제공합니다.
- Stream별 pending1개를 유지하고 별도 VT/platform sender로 slow reader를 격리합니다.
- 권한 회수 후 추가 전송을 멈추고 complete를 요청합니다. 실제 OS socket 종료 시점은 보장하지 않습니다.

## Queue 외 변경

앱 connector나 HTTP protocol을 교체하지 않습니다. Servlet transport에 종속된 전역 변경은 없습니다.

## 운영 영향

공유 poller1개가 매초 권한/generation을 조회합니다. platform sender는 최대32개 스레드, 완료 작업도 등록 quota에 의해 최대32개이며 VT 설정을 따르면 각각 가상 스레드를 사용합니다. 권한 조회의 pool 대기도 공유 JDK timer의1초 기한으로 격리하고, 기한 초과 조회가 진행 중이면 신규 stream에503을 반환합니다. text/event-stream을 압축 MIME 목록에 넣지 마세요. Proxy buffering/write timeout은 배포 경로에서 검증해야 합니다.

화면을 열어 두면 SSE 재연결 요청이 session의 마지막 접근 시각을 갱신하므로 idle timeout으로 만료되지 않을 수 있습니다. 로그아웃이나 session 무효화는 계속 적용됩니다.

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

- 위 최종 SHA에서 H2 / MariaDB / PostgreSQL / CUBRID 전체 queue suite: **각 114 passed, 실패/error/skip 0**. SSE만 분리한 부분 suite가 아닙니다.
- 최종 통합 05(`9a5cd461b71881b9230b6751764ef673ffb025c5`)의 실제 두 JVM, `/queue-it`/platform 구성에서 REST 16개 + SSE 기본 4개 + 확장 7개가 통과했습니다.
- 같은 HTTP 실행에서 느린 raw-socket reader 격리, 정상 stream·worker 진행, quota, 권한·세션 회수, 종료와 파일 다운로드 guard를 확인했습니다. 16 MiB 느린 다운로드 중 DB connection 사용량은 **0→0**이었습니다.
- 이전 root/VT 구성과 pool 고갈 EOF의 1794ms/1787ms 측정은 과거 스택 기록이며 이번 최종 SHA에서 재측정하지 않았습니다.
