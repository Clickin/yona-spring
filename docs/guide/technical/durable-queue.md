# Durable queue: store, executor and administrator REST API

enqueue/query, 순수 상태 전이 모델, 애플리케이션 내부 실행기와 관리자 REST API를 제공한다. SSE·관리 UI, archive exporter/importer는 아직 포함하지 않는다. 기존 메일·웹훅 등 callback 호출 경로는 변경하지 않는다. 실행할 수 있는 작업은 애플리케이션이 명시적으로 등록한 handler뿐이다.

## 애플리케이션 인터페이스

Spring이 주입한 `Queue.enqueue(type, version, payload, dueAt, idempotencyKey, callerScope)`를 호출한다. `REQUIRED` 트랜잭션으로 호출자의 업무 변경과 함께 저장되며, 반환값은 `ProvisionalQueueReceipt`다. 외부 트랜잭션이 commit하기 전에는 영속화 성공으로 간주하면 안 된다. rollback하면 job, key, counter 변경도 취소된다. `Queue.find(jobId)`는 payload를 노출하지 않는 snapshot을 반환한다.

- type: 소문자 ASCII `[a-z0-9._-]{1,120}`, version: 양의 정수.
- payload: 최대 1 MiB. 호출자가 나중에 byte array를 변경해도 저장 내용은 바뀌지 않는다.
- 등록된 type/version은 엄격한 UTF-8 JSON object만 허용한다. BOM, 잘못된 UTF-8, 중복 필드, trailing JSON은 거부한다. 허용 필드 등 task별 검증은 `TaskDefinition.validate`가 담당한다.
- 미등록 type/version은 decoder 실행 없이 원본 bytes를 보존한다. 저장되었다고 실행 가능한 것은 아니다.
- callerScope와 nullable idempotencyKey는 각각 최대 200 UTF-8 bytes의 대소문자 구분 식별자다.
- 같은 type/scope/key 요청은 version과 **정확한 payload bytes**가 같을 때만 기존 job을 반환한다. JSON 의미가 같아도 bytes가 다르면 `IDEMPOTENCY_CONFLICT`다. key가 없으면 매번 별도 job이다.
- dueAt의 sub-millisecond 값은 다음 millisecond로 올림한다. 저장 정밀도 때문에 요청 시각보다 먼저 실행하도록 만들지 않는다.
- resource key: 소문자 ASCII `[a-z0-9._-]+:[a-z0-9._-]+`, 각 300 bytes 이하. UUID 등 안정적인 opaque ID도 허용하며 정렬·중복 제거 후 최대 16개다. Lock 파일명에는 원문 대신 hash를 사용한다.

`yona.queue.max-pending` 기본값은 10,000이며 QUEUED/RUNNING/RETRY_WAIT/CANCEL_REQUESTED를 센다. `yona.queue.max-payload-bytes`는 기본 1,048,576이고 더 작게 설정할 수 있다. admission 실패는 `QueueAdmissionException.code`로 구분한다.

## 저장과 시간

`queue_meta`의 assigned BIGINT counter를 같은 트랜잭션에서 갱신하므로 JDBC generated keys에 의존하지 않는다. 현재 admission은 하나의 counter에서 직렬화한다. 이 잠금은 호출자의 외부 트랜잭션 종료까지 유지되므로 업무 트랜잭션을 짧게 유지해야 한다. 실제 경합이 확인되기 전에는 별도의 분산 coordinator를 두지 않는다.

`queue_job`은 payload/status/version을, `queue_idempotency_key`는 key가 있는 요청만, `queue_job_resource`는 정렬·중복 제거한 resource 요구사항을 저장한다. `queue_attempt`는 완료 후에도 남는 실행 이력이다. `queue_resource_lock`은 소유권을 해제해도 fence counter를 보존하고, `queue_admin_audit`는 관리자 명령을 기록한다. `queue_artifact`는 성공한 attempt와 같은 트랜잭션에서 commit되는 결과 파일 포인터다.

일정은 DB UTC clock을 사용한다. 시작 시와 이후 1초 간격으로 업무 트랜잭션 밖에서 DB 시각이 host 요청/응답 구간의 ±250 ms 안인지 검증한다. 실패하거나 마지막 정상 검증이 5초보다 오래되면 admission을 막는다. 검증의 유효기간은 wall clock 변경의 영향을 받지 않는 monotonic clock으로 판단한다. 기존 Spring scheduler가 지연되어도 오래된 정상 결과로 계속 승인하지 않는다. CUBRID JDBC는 timestamp의 Calendar 인자를 신뢰할 수 없어 DB 내부 epoch 차이를 숫자로 전송한다. CUBRID health 검사는 millisecond `CURRENT_DATETIME`, 일정 시각은 whole-second `CURRENT_TIMESTAMP`를 사용한다. 후자는 최대 1초 미만 늦을 수 있지만 일찍 실행해서는 안 된다.

Lease 검증은 row lock을 모두 얻은 **후**의 DB 시각을 사용한다. PostgreSQL `CURRENT_TIMESTAMP`와 H2의 기본 `CURRENT_TIMESTAMP`는 transaction-start 시각이므로 잠금 대기 후에도 과거 값을 반환할 수 있다. Lease/health에는 PostgreSQL `clock_timestamp()`, H2 자기 세션의 `INFORMATION_SCHEMA.SESSIONS.EXECUTING_STATEMENT_START`, CUBRID `CURRENT_DATETIME`을 사용한다. 일정의 not-before 조회는 기존 보수적인 clock을 유지한다. H2의 동작은 [함수 문서](https://h2database.com/html/functions.html#current_timestamp)와 [시스템 테이블 문서](https://h2database.com/html/systemtables.html)에 정의되어 있다.

Hibernate 7.4.5의 [CUBRID dialect](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-community-dialects/src/main/java/org/hibernate/community/dialect/CUBRIDDialect.java#L316-L324)는 pessimistic locking에도 빈 SQL 절을 반환한다. `YonaCubridDialect`가 이를 [CUBRID 11.4의 native `FOR UPDATE`](https://www.cubrid.org/manual/en/11.4/sql/query/select.html#for-update)로 보완한다. 빈 절을 그대로 두면 다른 트랜잭션이 잡았다고 생각한 row lock을 통과할 수 있다. `NOWAIT`나 `SKIP LOCKED` 지원을 추가한 것은 아니다.

Quartz 등 별도 scheduler의 실행 상태와 업무 DB 상태를 중복 관리하지 않고, 이 store가 요구하는 caller transaction·정확한 idempotency·상태 전이를 직접 보존한다. 이 선택은 애플리케이션 전체의 active-active 지원을 의미하지 않는다.

## 실행과 복구

`TaskDefinition`에 `handler`, `replaySafe`, `maxAttempts`, `laneLimit`을 등록한다. Validation-only 등록에는 handler가 없으므로 실행하지 않는다. 알 수 없는 type/version은 원본 payload를 유지한 채 `BLOCKED_UNSUPPORTED`로 남으며, 등록만으로 자동 재실행하지 않는다.

기본 `yona.queue` 설정:

| 설정 | 기본값 |
|---|---:|
| `workers` | 4 |
| `poll-millis` | 250 |
| `claim-batch` | 64 (1–64) |
| `lease-millis` | 60000 |
| `heartbeat-millis` | 15000 |
| `shutdown-grace-millis` | 30000 |
| `data-dir` | `${yona.data:data}/queue` |
| `instance-id` | 매 프로세스 UUID |
| `error-summary-codepoints` | 2048 (1–2048) |
| `metrics-refresh-millis` | 30000 |

Worker 수는 설정된 Hikari connection budget을 넘지 않아야 한다. Type별 lane 기본값은 1이다. Poll은 최대 64개의 due ID를 bounded keyset으로 훑으며 ID cursor를 순환한다. 예약 시각 우선순위는 보장하지 않는다. 실행 대기 메모리 큐도 bounded이며, commit된 attempt를 만든 다음 handler를 호출한다.

실행 중인 메모리 상태는 job ID만이 아니라 `(jobId, attemptNo)`로 식별한다. Replay-safe 작업의 이전 attempt가 lease를 잃고도 살아 있다면, 가용 worker/type lane이 허용하는 새 attempt와 구분하여 둘의 실제 slot을 모두 계산한다. 이전 attempt의 반환이 새 attempt의 slot이나 fence를 지우지 않는다.

`TaskContext.checkpoint()`로 취소/종료를 확인하고, `fencedDb { entityManager -> ... }`로 짧은 DB 변경을 수행한다. DB write는 현재 job/attempt/resource fence와 만료 시각을 같은 트랜잭션에서 확인한다. Flush 후에도 lease를 다시 검증한다. IO나 sleep을 이 트랜잭션 안에 넣지 않는다.

`progress(stage, counters)`의 stage는 최대 160 Unicode code points, counter는 최대 16개이며 음수가 아닌 `Long` 값이다. API에서는 정밀도를 보존하는 decimal string으로 반환한다. Control 문자·잘못된 Unicode·한도 초과 갱신은 기존 진행 상태를 변경하지 않고 거부한다. 안전한 오류 요약은 surrogate pair를 자르지 않고 설정된 길이로 제한한다. 원래 exception message나 stack trace를 API에 반환하지 않는다.

명시적 `RetryableTaskFailure`만 자동 재시도한다. `PermanentTaskFailure`는 실패, `RecoveryRequiredTaskFailure`와 미분류 예외는 운영자 확인 상태가 된다. 기본 attempt 한도는 generation당 5회이며, 지연은 exponential ceiling의 1/2~1 사이 deterministic jitter다. 수동 retry는 generation만 새로 시작하고 전체 attempt 번호·fence·이력을 지우지 않는다.

`QueueControl.cancel/retry`는 DB의 `SITE_ADMIN` 상태를 다시 확인하며 command UUID로 중복을 처리한다. 실행 중 취소는 `CANCEL_REQUESTED`일 뿐 종료가 아니다. 메모리 취소 신호는 외부 업무 트랜잭션까지 commit된 뒤에만 전달한다. `RECOVERY_REQUIRED` 재실행은 명시적 확인과 최대 300 Unicode code points의 사유가 필요하다. 수동 retry도 enqueue와 같은 admission lock·현재 pending 수를 사용하며, 용량이 가득 차면 `QUEUE_FULL`로 거부하고 상태·감사를 변경하지 않는다.

모든 resource key에 정렬된 DB fence와 안정적인 lock-file의 `FileChannel` guard를 함께 사용한다. Lease 만료는 thread 종료 증거가 아니다. 물리 guard는 handler/context 작업이 실제 반환하거나 프로세스가 끝날 때까지 유지된다. 종료 시 새 claim을 중단하고 grace 동안 heartbeat를 유지한다. 끝나지 않은 작업을 성공/취소로 꾸미지 않고 durable 상태를 복구 대상으로 남긴다. 운영 파일시스템이 프로세스 간 advisory lock을 보장해야 한다.

`writeArtifact(relativePath) { output -> ... }`는 attempt 전용 staging에 streaming하고 file data를 force한다. Handler가 성공한 뒤 현재 fence 아래에서 같은 파일시스템의 불변 경로로 atomic move하며 성공 상태와 포인터를 함께 commit한다. Commit 응답이 실패해도 실제 DB commit은 끝났을 수 있으므로 최종 파일을 즉시 삭제하지 않는다. 포인터 없는 파일은 공개하지 않으며 별도 reconciliation 대상이다. 이 프로세스 재시작 보장을 파일시스템·DB의 분산 atomic commit이나 power-loss 보장으로 해석하지 않는다.

Job 하나에는 다운로드할 결과 파일 하나만 게시할 수 있다. 작성 중이거나 staging을 마친 결과가 있으면 추가 writer는 실행 전에 거부한다. 상대 경로는 정상 Unicode·최대 1024 UTF-8 bytes, basename은 최대 255 UTF-8 bytes이며 control 문자·경로 이탈을 거부한다.

## 관리자 REST

모든 경로는 배포 context path에 상대적이다.

| 경로 | 동작 |
|---|---|
| `GET /api/admin/queue/v1/jobs` | status/type/resource filter와 ID 내림차순 keyset 목록 |
| `GET /api/admin/queue/v1/jobs/{jobId}` | 현재 상태와 최신 attempt부터 시작하는 이력 page |
| `POST /api/admin/queue/v1/jobs/{jobId}/cancel` | 대기 작업 취소 또는 실행 중 협력 취소 요청 |
| `POST /api/admin/queue/v1/jobs/{jobId}/retry` | 지원되는 실패·복구 대상의 새 generation |
| `GET /api/admin/queue/v1/jobs/{jobId}/result` | 권한·파일 무결성 확인 후 streaming 다운로드 |

목록 `limit`과 상세 `attemptLimit`은 기본 50, 최대 100이다. 반환된 opaque cursor를 그대로 전달한다. Attempt cursor는 해당 job에 묶여 있고 이전 page의 마지막 번호보다 작은 이력만 반환한다. 새 attempt가 생겨도 이미 읽은 이력을 중복하지 않으며 새로 조회하면 최신 page부터 시작한다. ID, `attemptNo`, `fence`, `executionGeneration`, 이력 건수와 `snapshotGeneration`은 decimal string이므로 JavaScript `Number`로 변환하지 않는다. `payloadVersion`과 generation 내부 순번 `generationAttemptNo`는 정수다. Payload·idempotency 원문·내부 storage path는 반환하지 않는다.

완료된 로그인 session만 허용하며 매 요청 DB의 `SITE_ADMIN` 상태를 확인한다. 익명은 JSON 401, 일반/조직·프로젝트 관리자와 2FA 대기 session은 JSON 403이다. PAT는 이 API의 인증 수단이 아니다. Mutation에는 실제 cookie와 일치하는 `X-XSRF-TOKEN`이 필요하며 `Authorization`/`Yona-Token` 헤더가 있다고 CSRF를 생략하지 않는다. `GET /jobs`가 필요한 XSRF cookie를 발급한다.

명령 body는 최대 4096 bytes이며 UUID `commandId`를 포함한다. 같은 명령의 재전송은 감사·generation을 중복하지 않고 `changed=false`를 반환한다. 새로운 retry와 실행 중 취소 요청은 202, 대기 취소·이미 처리한 명령은 200이다. 잘못된 입력은 400, 없는 대상은 404, 상태·command ID·용량 충돌은 409, body 한도 초과는 413이며 오류는 JSON이다.

다운로드는 조회 전용 EntityManager를 별도로 만들고 transaction과 연결을 닫은 뒤 파일을 연다. 요청의 OSIV EntityManager를 재사용하지 않으며, 큐 JSON API에서는 HTML용 초기 설정·사용자 모델 조회도 실행하지 않는다. 크기와 SHA-256을 확인한 **같은 열린 handle**에서 고정 크기 buffer로 전송한다. 파일 누락은 404, 무결성 불일치는 409이며 실패 응답에 이전 파일의 길이·다운로드 헤더를 남기지 않는다.

`SecureDirectoryStream`을 지원하는 파일시스템은 descriptor 기준으로 탐색한다. macOS 등 미지원 환경은 symlink/real-path 점검과 `NOFOLLOW_LINKS`를 사용한다. 이 경로는 악의적인 OS 사용자의 ancestor 교체 경합을 막는다고 주장하지 않는다. **Queue data directory와 그 상위 경로는 신뢰할 수 없는 OS principal이 수정할 수 없어야 하며, 게시한 파일을 제자리에서 수정하지 않아야 한다.**

## 관측과 업그레이드

- `yona.queue.jobs{status=...}`: QUEUED/RUNNING/RETRY_WAIT/CANCEL_REQUESTED/FAILED 현재 수.
- `yona.queue.oldest.due.age`: 실행 가능 시각이 지난 대기 작업의 가장 오래된 지연, seconds.
- `yona.queue.attempts{outcome=...}`와 `yona.queue.retries`: 이 프로세스가 commit을 확인한 완료 outcome·재실행 claim 수. 프로세스 재시작 시 초기화하며 과거 이력을 다시 세지 않는다.
- `yona.queue.execution`: 실제 handler 실행·정리 시간 timer.
- `yona.queue.worker.slots.used/capacity/utilization`: 해당 프로세스의 물리 실행 slot. Lease를 잃어도 handler가 남아 있으면 계속 센다.

DB 기반 gauge는 기본 30초 간격으로 갱신하며 아직 확인하지 못했거나 DB 조회가 실패하면 NaN으로 표시한다. Browser/scrape마다 DB를 조회하지 않는다. Pending 집계는 admission 한도 안의 상태만 읽고, 무한히 보존되는 terminal job/attempt 이력을 주기적으로 훑지 않는다. FAILED 현재 수는 기존 `queue_meta`의 파생 `failed-jobs` row에 상태 변경과 같은 transaction으로 유지한다. Metric label에 job ID를 넣지 않는다.

기존 큐 설치 업그레이드는 **모든 이전 버전 node를 중지한 뒤** 새 버전 node 하나를 먼저 시작한다. 최초 초기화에서 기존 FAILED 수를 한 번 집계하고 worker·HTTP serving 전에 commit한다. 이 일회성 backfill은 데이터량에 따라 시간이 걸릴 수 있다. 완료 후 나머지 새 node를 시작한다. 이후 시작은 해당 row만 확인한다. 이전/새 버전을 섞은 rolling upgrade는 이 파생 counter를 유지하지 못하므로 지원하지 않는다.


## 검증

```sh
./gradlew test \
  --tests 'com.github.yonaprojects.yona.queue.*' \
  -Dyona.it.db=h2
```

`yona.it.db`는 `h2`, `mariadb`, `postgres`, `mysql`, `mssql`, `cubrid`를 지원한다. H2 외에는 Docker가 필요하다. fixture의 H2는 file-backed이며 기존 domain의 `value` 컬럼 때문에 `NON_KEYWORDS=VALUE`를 설정한다. 이것은 기본 H2 애플리케이션 설정 전체의 호환성을 보증하지 않는다.

store 검증에는 업무 rollback, concurrent keyed/unkeyed admission, 기존 MySQL business snapshot 이후의 idempotency 조회, payload 소유권, malformed payload, 기존 user 보존을 포함한 schema 추가 및 재연결이 포함된다. 별도 Java 프로세스에서 commit 후 종료하고 새 JVM에서 동일 file-backed H2를 열어 job ID/type/status/attempt count가 유지되는 것도 확인했다.

실행기 수용 테스트는 web listener 없는 실제 Yona JVM들을 시작한다. DB-only claim 경합, attempt commit 후 handler 진입, stale owner의 DB/파일 쓰기 거절, 취소/종료 중 guard 유지, JVM 강제 종료·재시작, retry 한도·지연과 수동 retry 이력을 직접 SQL/파일로 확인한다. Fixture의 H2는 `AUTO_SERVER=TRUE`로 같은 file-backed DB를 공유한다. Servlet 전용 MVC/SVN 설정은 이 NONE web context에 등록하지 않는다.

별도 Java smoke에서도 실제 child JVM이 결과 파일을 게시하고, 새 JVM이 같은 resource를 실행한 뒤 원래 파일과 resource fence 증가를 확인했다. 테스트의 setup 실패/skip은 수용성 통과가 아니다.

PR03 검증은 실제 두 servlet JVM과 session cookie로 REST 16개 시나리오를 실행한다. Root 및 `/queue-it` context에서 권한/2FA/CSRF, 권한 회수, 105개 attempt paging, 두 node의 claim 경합, 취소·retry·정확한 결과 bytes를 검사했다. 조회/메트릭 경로는 위 6개 DB에서 별도 Java 프로세스로도 확인한다. SSE·Vue page 검증은 별도 단계이며 이 REST 통과에 포함하지 않는다.

Java 21과 Python 3.10 이상으로 재현한다. 추가 Python package나 외부 DB는 필요하지 않다.

```sh
python3 src/test/queue/run.py
```

Runner는 test class를 컴파일하고 private H2/storage와 두 loopback JVM을 만든다. REST 외에 checksum 손상·동일 bytes를 가리키는 파일/ancestor symlink·파일 누락, OSIV를 켠 상태의 16 MiB 느린 다운로드와 DB 연결 반환, main classpath에서 fixture route/handler 부재를 검사한다. 끝나면 소유한 JVM을 종료하고 mode-700 임시 directory에 증거를 남긴다. Cookie/control token을 출력하지 않으며 생성한 환경 파일은 mode 600이다.

`YONA_QUEUE_HTTP_CONTEXT_PATH`로 context를 변경하고 `--serve`로 수동 검증용 fixture만 유지할 수 있다. [독립 OpenAPI 계약](../../../src/test/queue/api-openapi.yaml)과 Python verifier는 test 디렉터리에 보존한다. 계약의 SSE/page 항목은 후속 단계용이다.
