# Durable queue: store and executor

트랜잭션 queue 저장소, 순수 상태 전이, fenced 실행기와 내부 관리자 명령을 제공한다. 관리자 REST/SSE/UI와 archive exporter/importer는 아직 포함하지 않는다. 기존 메일·웹훅 callback 경로는 변경하지 않는다.

## 애플리케이션 인터페이스

Spring이 주입한 `Queue.enqueue(type, version, payload, dueAt, idempotencyKey, callerScope)`를 호출한다. `REQUIRED` 트랜잭션으로 호출자의 업무 변경과 함께 저장되며, 반환값은 `ProvisionalQueueReceipt`다. 외부 트랜잭션이 commit하기 전에는 영속화 성공으로 간주하면 안 된다. rollback하면 job, key, counter 변경도 취소된다. `Queue.find(jobId)`는 payload를 노출하지 않는 snapshot을 반환한다.

- type: 소문자 ASCII `[a-z0-9._-]{1,120}`, version: 양의 정수.
- payload: 최대 1 MiB. 호출자가 나중에 byte array를 변경해도 저장 내용은 바뀌지 않는다.
- 등록된 type/version은 엄격한 UTF-8 JSON object만 허용한다. BOM, 잘못된 UTF-8, 중복 필드, trailing JSON은 거부한다. 허용 필드 등 task별 검증은 `TaskDefinition.validate`가 담당한다.
- 미등록 type/version은 decoder 실행 없이 원본 bytes를 보존한다. 저장되었다고 실행 가능한 것은 아니다.
- callerScope와 nullable idempotencyKey는 각각 최대 200 UTF-8 bytes의 대소문자 구분 식별자다.
- 같은 type/scope/key 요청은 version과 **정확한 payload bytes**가 같을 때만 기존 job을 반환한다. JSON 의미가 같아도 bytes가 다르면 `IDEMPOTENCY_CONFLICT`다. key가 없으면 매번 별도 job이다.
- dueAt의 sub-millisecond 값은 다음 millisecond로 올림한다. 저장 정밀도 때문에 요청 시각보다 먼저 실행하도록 만들지 않는다.
- resource key: ASCII `[a-z][a-z0-9-]*:[a-z0-9]+`, 각 300 bytes 이하이며 정렬·중복 제거한 안정적인 opaque ID를 사용한다. 관리자 API 계층에서 허용 문자 집합을 확장하고 서로 다른 resource 16개 상한을 적용한다.

`yona.queue.max-pending` 기본값은 10,000이며 QUEUED/RUNNING/RETRY_WAIT/CANCEL_REQUESTED를 센다. `yona.queue.max-payload-bytes`는 기본 1,048,576이고 더 작게 설정할 수 있다. admission 실패는 `QueueAdmissionException.code`로 구분한다.

Admission은 `next-id` 잠금 뒤 별도 autocommit connection에서 읽은 committed pending 수와 현재 트랜잭션이 추가한 수를 합산한다. Pending row 전체에 `FOR UPDATE`를 걸지 않으므로 긴 enqueue 트랜잭션이 기존 실행의 heartbeat를 막지 않는다. 업무 트랜잭션 하나가 connection 두 개를 잠시 사용할 수 있다. Hikari의 별도 connection 획득이 2초 안에 끝나지 않으면 `QUEUE_UNAVAILABLE`로 거부한다. Pool에는 worker뿐 아니라 업무·clock·관리 조회에 필요한 여유도 남겨야 한다.

Durable queue는 HikariCP를 요구하며 DataSource를 HikariDataSource로 unwrap할 수 없으면 기동을 중단한다.

Pool 크기는 동시에 connection을 보유하는 업무 트랜잭션 수에 admission 집계용 1개와 clock/운영 조회 여유를 더해 잡는다. 모든 connection이 이미 호출자의 업무 트랜잭션에 묶인 상황에서는 `next-id` 획득 순서를 바꾸어도 독립 집계 connection을 만들 수 없으며, 이때의 admission 거부는 정상적인 fail-closed 동작이다. 관리자 REST는 `QUEUE_UNAVAILABLE`를 503으로 반환한다.
## 저장과 시간

`queue_meta`의 assigned BIGINT counter를 같은 트랜잭션에서 갱신하므로 JDBC generated keys에 의존하지 않는다. 현재 admission은 하나의 counter에서 직렬화한다. 이 잠금은 호출자의 외부 트랜잭션 종료까지 유지되므로 업무 트랜잭션을 짧게 유지해야 한다. 실제 경합이 확인되기 전에는 별도의 분산 coordinator를 두지 않는다.

`queue_job`은 payload/status/version을, `queue_idempotency_key`는 key가 있는 요청만, `queue_job_resource`는 정렬·중복 제거한 resource 요구사항을 저장한다. `queue_attempt`는 완료 후에도 남는 실행 이력이다. `queue_resource_lock`은 소유권을 해제해도 fence counter를 보존하고, `queue_admin_audit`는 관리자 명령을 기록한다. `queue_artifact`는 성공한 attempt와 같은 트랜잭션에서 commit되는 결과 파일 포인터다.

일정은 DB UTC clock을 사용한다. Host와 DB에 NTP 동기화가 필요하다. `yona-queue-clock` 전용 daemon 스레드가 시작 시와 이후 1초 간격으로 업무 트랜잭션 밖에서 DB 시각을 검증한다. 공유 Spring scheduler나 worker poller가 지연되어도 clock 검사 스레드를 막지 않는다. `yona.queue.clock-skew-millis`는 기본 250 ms(50–5000), `yona.queue.clock-trust-millis`는 기본 5000 ms다. DB 시각이 host 요청/응답 구간의 허용 오차를 벗어나거나 마지막 정상 검증이 trust 기간보다 오래되면 admission을 막는다. 검증 유효기간은 monotonic clock으로 판단하며 신뢰 상실은 측정 skew와 함께 warn, 회복은 info로 기록한다. PostgreSQL/H2의 timezone-aware timestamp는 세션 설정을 바꾸지 않고 읽는다. CUBRID JDBC는 timestamp의 Calendar 인자를 신뢰할 수 없어 잠시 UTC에서 DB 내부 epoch 차이를 구한 뒤 원래 세션 timezone을 `finally`에서 복원한다. CUBRID health/lease는 millisecond `CURRENT_DATETIME`, 일정은 whole-second `CURRENT_TIMESTAMP`를 사용하므로 최대 1초 미만 늦을 수 있지만 일찍 실행하지 않는다.

Clock lifecycle은 worker보다 먼저 시작하고 나중에 종료되도록 phase를 `Int.MAX_VALUE - 200`으로 둔다.

Lease 검증은 row lock을 모두 얻은 **후**의 DB 시각을 사용한다. PostgreSQL `CURRENT_TIMESTAMP`와 H2의 기본 `CURRENT_TIMESTAMP`는 transaction-start 시각이므로 잠금 대기 후에도 과거 값을 반환할 수 있다. Lease/health에는 PostgreSQL `clock_timestamp()`, H2 자기 세션의 `INFORMATION_SCHEMA.SESSIONS.EXECUTING_STATEMENT_START`, CUBRID `CURRENT_DATETIME`을 사용한다. 일정의 not-before 조회는 기존 보수적인 clock을 유지한다. H2의 동작은 [함수 문서](https://h2database.com/html/functions.html#current_timestamp)와 [시스템 테이블 문서](https://h2database.com/html/systemtables.html)에 정의되어 있다.

Due/lease 스캔은 DB 시각 식을 SQL에 포함해 별도 시각 조회 왕복을 없앤다. PostgreSQL epoch millisecond는 반올림 cast 대신 `FLOOR`를 사용해 not-before 경계를 보존한다. CUBRID 스캔은 `NEW_TIME(..., SESSIONTIMEZONE(), 'UTC')`로 변환하므로 pooled connection의 timezone을 변경하지 않는다.

Hibernate 7.4.5의 [CUBRID dialect](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-community-dialects/src/main/java/org/hibernate/community/dialect/CUBRIDDialect.java#L316-L324)는 pessimistic locking에도 빈 SQL 절을 반환한다. `YonaCubridDialect`가 이를 [CUBRID 11.4의 native `FOR UPDATE`](https://www.cubrid.org/manual/en/11.4/sql/query/select.html#for-update)로 보완한다. 빈 절을 그대로 두면 다른 트랜잭션이 잡았다고 생각한 row lock을 통과할 수 있다. `NOWAIT`나 `SKIP LOCKED` 지원을 추가한 것은 아니다.

Quartz 등 별도 scheduler의 실행 상태와 업무 DB 상태를 중복 관리하지 않고, 이 store가 요구하는 caller transaction·정확한 idempotency·상태 전이를 직접 보존한다. 이 선택은 애플리케이션 전체의 active-active 지원을 의미하지 않는다.
## 실행과 복구

`TaskDefinition`에 `handler`, `replaySafe`, `maxAttempts`, `laneLimit`을 등록한다. Validation-only 등록에는 handler가 없으므로 실행하지 않는다. 알 수 없는 type/version은 원본 payload를 유지한 채 `BLOCKED_UNSUPPORTED`로 남으며, 등록만으로 자동 재실행하지 않는다.

기본 `yona.queue` 설정:

| 설정 | 기본값 |
|---|---:|
| `workers` | 4 (동시 실행 slot 수) |
| `poll-millis` | 250 |
| `idle-poll-max-millis` | 1000 |
| `recovery-poll-millis` | `min(5000, lease-millis / 4)` |
| `claim-batch` | 64 (1–64) |
| `lease-millis` | 60000 |
| `heartbeat-millis` | 15000 |
| `shutdown-grace-millis` | 30000 |
| `data-dir` | `${yona.data:data}/queue` |
| `instance-id` | 매 프로세스 UUID |
| `error-summary-codepoints` | 2048 (1–2048) |

Worker slot 수는 설정된 Hikari maximum-pool-size를 넘지 않아야 한다. Type별 lane 기본값은 1이며, **lane 제한과 worker slot은 노드별**이다. 여러 노드가 같은 DB를 공유해도 claim/resource fence는 DB에서 검증한다. 매 poll은 우선순위 내림차순·ID 오름차순 `(priority, id)` keyset으로 맨 위부터 최대 4 × 64건을 확인하고, lane이 찬 type은 SQL에서 제외한다. Resource에 막힌 앞쪽 작업을 건너뛰어 뒤쪽의 실행 가능한 작업을 찾는다.

Due 후보가 없으면 poll 간격을 두 배씩 늘려 `idle-poll-max-millis`까지 쉬고, 후보를 찾으면 `poll-millis`로 돌아간다. 같은 노드의 enqueue/retry는 외부 트랜잭션 commit 뒤 poller를 깨운다. Rollback은 worker를 깨우지 않는다. Lease 만료 스캔과 소량의 orphan 정리는 별도의 `recovery-poll-millis` 주기를 따른다. Poll 실패가 시작될 때만 stack trace를 포함한 warn을 남기고, 연속 실패는 debug, 복구는 info로 기록한다. 실패 대기 간격도 `idle-poll-max-millis`까지 두 배씩 늘린다. `CLOCK_UNTRUSTED`는 clock 스레드가 원인을 기록하므로 poller에서는 stack trace 없이 한 줄만 남긴다.

관리자 우선 실행은 `SMALLINT` priority를 0에서 127로 바꾸는 **best-effort** 명령이다. 대기 작업의 claim 순서만 앞당기며 실행 중이거나 resource·lane에 막힌 작업에는 효과가 없다. 같은 priority는 ID 순 FIFO다. Producer·task 정의에는 priority 옵션이 없다. 수동 retry/종결은 0으로 초기화하고 자동 retry는 유지한다.

실행 중인 메모리 상태는 job ID만이 아니라 `(jobId, attemptNo)`로 식별한다. Replay-safe 작업의 이전 attempt가 lease를 잃고도 살아 있다면, 가용 worker/type lane이 허용하는 새 attempt와 구분하여 둘의 실제 slot을 모두 계산한다. 이전 attempt의 반환이 새 attempt의 slot이나 fence를 지우지 않는다.

Semaphore permit을 claim **전에** 얻고, claim 실패·빈 결과·submit 실패면 반환한다. 성공한 claim의 permit은 worker가 complete → resource 소유권 해제 → staging 정리 → 물리 guard 해제 → active 제거를 마친 뒤 마지막 `finally`에서 반환한다. Lease를 잃어도 handler가 반환하기 전에는 slot을 반납하지 않는다. 종료 grace가 지난 뒤 남은 slot 수를 warn 로그로 기록한다.

### 가상 스레드

`spring.threads.virtual.enabled`를 따른다. true이면 작업마다 가상 스레드, false이면 `workers` 크기의 platform daemon pool을 사용하며 Spring의 공유 application executor는 사용하지 않는다. Poller, heartbeat 제출용 scheduler, orphan 정리용 scheduler는 platform daemon 각 한 개다. 실제 heartbeat는 handler pool과 분리된 executor에서 job별로 실행하며, 가상 스레드 설정 또는 `workers` 크기의 별도 platform pool을 따른다. 각 실행에는 heartbeat 하나만 진행할 수 있어 느린 job의 DB 잠금이 다른 job의 heartbeat를 직렬로 막지 않는다. DB 장애만으로 실행을 stale로 판정하지 않는다. 실행 thread 종류를 기동 시 info 로그로 남긴다.

Java 21에서는 `synchronized` 안의 blocking IO가 carrier 스레드를 pinning한다(JDK 24 JEP 491에서 해소). Queue의 gate는 `ReentrantLock` 계열을 사용하지만 handler와 라이브러리의 pinning은 handler 작성자가 확인해야 한다. `-Djdk.tracePinnedThreads=short` 또는 JFR `jdk.VirtualThreadPinned` 이벤트로 진단한다. CUBRID 및 구버전 MySQL Connector/J 등 JDBC 드라이버 내부의 `synchronized`도 확인 대상이다.

`TaskContext.checkpoint()`로 취소/종료를 확인하고, `fencedDb { entityManager -> ... }`로 짧은 DB 변경을 수행한다. DB write는 현재 job/attempt/resource fence와 만료 시각을 같은 트랜잭션에서 확인한다. Flush 후에도 lease를 다시 검증한다. `fencedDb`의 transaction timeout은 `max(1, floor(lease-millis / 4000))`초이며 기본 15초다. 한도를 넘기면 transaction을 rollback하고 handler에 예외를 전달한다. 이 timeout이 handler 스레드를 강제 종료하는 것은 아니므로 IO나 sleep을 이 트랜잭션 안에 넣지 않는다.

`progress(stage, counters)`의 stage는 최대 160 Unicode code points, counter는 최대 16개이며 음수가 아닌 `Long` 값이다. API에서는 정밀도를 보존하는 decimal string으로 반환한다. Control 문자·잘못된 Unicode·한도 초과 갱신은 기존 진행 상태를 변경하지 않고 거부한다. 안전한 오류 요약은 surrogate pair를 자르지 않고 설정된 길이로 제한한다. 원래 exception message나 stack trace를 API에 반환하지 않는다.

같은 stage의 진행 갱신은 최신 snapshot 하나로 합쳐 DB에 5초당 최대 한 번 기록한다. 첫 갱신과 stage 변경은 즉시 기록하고, 이후 호출이 없어도 worker poller가 대기 snapshot을 반영한다. Progress gate가 잠겨 있으면 poller는 기다리지 않고 다음 루프로 넘겨 다른 작업의 claim과 복구를 계속한다. 입력 counter는 복사하여 호출자의 후속 변경과 분리한다. Handler 반환 시 남은 snapshot은 별도 progress transaction 없이 최종 상태와 같은 fenced transaction에서 job·attempt 양쪽에 반영한다.
`fencedDb` 안의 진행 갱신은 해당 transaction이 실패하면 coalescing 상태도 되돌린다. 진행 갱신과 업무 transaction은 같은 순서로 progress gate와 DB fence를 얻어 서로의 잠금을 기다리는 역전을 피한다.

명시적 `RetryableTaskFailure`만 자동 재시도한다. `PermanentTaskFailure`는 실패, `RecoveryRequiredTaskFailure`와 미분류 예외는 운영자 확인 상태가 된다. 기본 attempt 한도는 generation당 5회이며, 지연은 exponential ceiling의 1/2~1 사이 deterministic jitter다. 수동 retry는 generation만 새로 시작하고 전체 attempt 번호·fence·이력을 지우지 않는다.

`QueueControl.cancel/retry/abandon/prioritize`는 DB의 `SITE_ADMIN` 상태를 다시 확인하며 command UUID로 중복을 처리한다. 실행 중 취소는 `CANCEL_REQUESTED`일 뿐 종료가 아니다. 취소 중 handler가 실패하면 실패 종류와 관계없이 즉시 `CANCELLED`로 끝내되 attempt에는 원래 failure outcome, job에는 안전한 error code/summary를 보존한다. 취소 요청 뒤 lease가 만료되면 replay-safe 여부와 관계없이 `CANCELLED`(attempt `LEASE_LOST`)로 끝난다. Job의 error code는 `LEASE_LOST`이고 종료 시각을 기록하며 failed counter는 바꾸지 않는다. 이전 handler가 반환될 때까지 resource는 해제되지 않는다. 종료 중 협력 취소(`CooperativeCancel` + `shutdownRequested`)의 계약은 바꾸지 않는다. 이미 취소 중/취소된 작업에 새 cancel을 보내면 멱등성용 audit만 남기고 change-generation은 늘리지 않는다. 메모리 취소 신호는 외부 업무 트랜잭션까지 commit된 뒤에 전달한다. `RECOVERY_REQUIRED` 재실행은 명시적 확인과 최대 300 Unicode code points의 사유가 필요하다. 수동 retry도 enqueue와 같은 admission lock·현재 pending 수를 사용하며, 용량이 가득 차면 `QUEUE_FULL`로 거부하고 상태·감사를 변경하지 않는다.

`abandon`은 `FAILED`, `RECOVERY_REQUIRED`, `BLOCKED_UNSUPPORTED`를 `CANCELLED`로 종결한다. 사유는 필수이며 최대 300 Unicode code points다. `RECOVERY_REQUIRED`에는 별도 확인도 필요하다. FAILED를 종결하면 failed-jobs counter도 같은 트랜잭션에서 줄어든다. 지원하지 않는 handler와 달리 payload 검증 실패는 `INVALID_PAYLOAD`, resource key 불일치는 `RESOURCE_MISMATCH`로 구분하며 모두 `BLOCKED_UNSUPPORTED`에 남긴다.

모든 resource key에 정렬된 DB fence와 안정적인 lock-file의 `FileChannel` guard를 함께 사용한다. Lease 만료는 thread 종료 증거가 아니다. 물리 guard는 handler/context 작업이 실제 반환하거나 프로세스가 끝날 때까지 유지된다. 종료 시 새 claim을 중단하고 grace 동안 heartbeat를 유지한다. 끝나지 않은 작업을 성공/취소로 꾸미지 않고 durable 상태를 복구 대상으로 남긴다. 운영 파일시스템이 프로세스 간 advisory lock을 보장해야 한다.

`writeArtifact(relativePath) { output -> ... }`는 attempt 전용 staging에 streaming하고 file data를 force한다. Handler가 성공한 뒤 현재 fence 아래에서 같은 파일시스템의 불변 경로로 atomic move하며 성공 상태와 포인터를 함께 commit한다. Commit 응답이 실패해도 실제 DB commit은 끝났을 수 있으므로 최종 파일을 즉시 삭제하지 않는다. Handler 반환 뒤 staging을 symlink를 따라가지 않고 삭제하며 실패는 warn으로 기록한다. 비어 있는 `staging/<jobId>`도 지우되 다른 attempt가 동시에 파일을 만들면 보존한다.

Orphan 정리는 기동을 동기로 막지 않고 poller 시작 뒤 전용 단일 daemon scheduler에서 `recovery-poll-millis` fixed delay로 실행한다. 정리 회차는 겹치지 않으며 job row 잠금을 기다려도 claim poller나 heartbeat를 막지 않는다. Runtime 종료 시 정리 scheduler도 중단한다. 정리 실패는 상태가 바뀔 때 warn, 연속 실패는 debug, 복구는 info로 기록한다. 한 번에 DB 확인 transaction 기본 100개, 최대 1000개를 사용하며 중간 디렉터리 방문은 예산에 포함하지 않는다. `jobId` 디렉터리를 숫자순으로 처리하고 마지막으로 처리를 완료한 ID를 `<data-dir>/cleanup.cursor`에 기록한다. 끝에 도달하면 0으로 되돌린다. 한 job의 staging 도중 예산이 소진되면 다음 회차에 그 위치부터 이어 가며, 재기동하면 미완료 job을 다시 검사한다. 참조 중인 결과 파일은 보존하고 DB 포인터 없는 파일만 `artifacts-orphaned/`로 옮긴 뒤 빈 디렉터리를 아래에서부터 삭제한다. 게시와 같은 job row 잠금을 보유한 채 포인터 확인·격리를 수행하므로 다른 node의 미확정 publication과 경합하지 않는다. Rollback으로 RUNNING attempt 아래에 남은 결과 파일도 잠금 아래에서 포인터가 없음을 확인하면 격리하지만, RUNNING attempt의 staging은 보존한다. 이동한 파일은 관리자가 commit 불확실성을 확인한 뒤 정리한다. 이 보장을 파일시스템·DB의 분산 atomic commit이나 power-loss 보장으로 해석하지 않는다.

`resource-guards/*.lock`은 resource 수에 비례해 쌓이는 0바이트 파일이며 삭제하지 않는다. 삭제하면 다른 프로세스가 서로 다른 inode를 잠가 resource 배타성이 깨질 수 있다.

Job 하나에는 다운로드할 결과 파일 하나만 게시할 수 있다. 작성 중이거나 staging을 마친 결과가 있으면 추가 writer는 실행 전에 거부한다. 상대 경로는 정상 Unicode·최대 1024 UTF-8 bytes, basename은 최대 255 UTF-8 bytes이며 control 문자·경로 이탈을 거부한다.
## 스키마 업그레이드

기존 큐 설치 업그레이드는 **모든 이전 버전 node를 중지한 뒤** 새 버전 node 하나를 먼저 시작한다. 최초 초기화에서 기존 FAILED 수를 한 번 집계하고 worker·HTTP serving 전에 commit한다. 이 일회성 backfill은 데이터량에 따라 시간이 걸릴 수 있다. 완료 후 나머지 새 node를 시작한다. 이후 시작은 해당 row만 확인한다. 이전/새 버전을 섞은 rolling upgrade는 이 파생 counter를 유지하지 못하므로 지원하지 않는다.

기존 row의 priority는 DB 기본값 0으로 채운다. `queue_job_ready(status, priority, id)`가 새 스캔 인덱스다. Hibernate `ddl-auto=update`는 예전 인덱스를 삭제하지 않으므로 정지된 업그레이드 단계에서 기존 `queue_job_due`와 `queue_job_retry_due`가 있으면 제거한다. PostgreSQL/H2는 `DROP INDEX <index>`, MySQL/MariaDB/CUBRID/SQL Server는 `DROP INDEX <index> ON queue_job` 형식을 쓰며 먼저 실제 schema/catalog의 존재를 확인한다.

## 검증

```sh
./gradlew test --tests 'com.github.yonaprojects.yona.queue.*' -Dyona.it.db=h2
```

`yona.it.db`는 h2/mariadb/postgres/mysql/mssql/cubrid를 지원한다. H2 외에는 Docker가 필요하다. DB SQL/schema 변경은 각 DB의 clock/timezone, admission, populated-schema upgrade와 스캔 계획을 함께 검증한다. H2 fixture는 기존 domain의 value 컬럼 때문에 NON_KEYWORDS=VALUE를 사용한다.

## 향후 계획

### Background job 일원화와 주기 작업

이번 스택은 cron/recurring을 구현하지 않는다. 후속 PR의 첫 이전 대상은 웹훅 전송 또는 메일 발송이다. 모든 node는 같은 버전과 같은 handler 구성을 배포해야 한다. Handler 없는 node도 claim 과정에서 `BLOCKED_UNSUPPORTED`로 표시하므로 서로 다른 handler 전용 node를 혼용하지 않는다.

주기 작업의 발화는 poller, 실제 실행은 일반 worker claim 경로가 맡는다. 발화 시 `enqueue(type, v, payload, dueAt = 발화시각, idempotencyKey = "<type>@<발화시각 epoch>", callerScope = "recurring")`를 호출한다. 여러 node가 발화해도 idempotency로 job 한 건만 생성하므로 leader election은 필요 없다. 이전 발화와 겹칠 때 `overlap = SKIP | QUEUE`를 제공하며 기본 SKIP으로 설계한다. 발화용 enqueue의 lock timeout은 1초로 제한하고 실패하면 다음 loop에서 재시도해 next-id 대기가 poller를 장시간 막지 않게 한다.

Worker는 자기 handler의 종결 전이, resource 해제, staging 정리, 물리 guard 해제와 slot 반환을 마지막 `finally`에서 책임진다. Poller는 lease가 만료된 소유자 복구를 맡으며 handler를 직접 실행하지 않는다. 주기적인 소량의 orphan 정리는 전용 scheduler가 맡는다.

### DB 이력 보존

**계획이며 아직 자동 삭제나 아래 설정은 구현하지 않는다.** `yona.queue.retention.terminal-days` 기본 30(0이면 비활성), `yona.queue.retention.batch` 기본 500으로 설계한다. `SUCCEEDED`/`CANCELLED`이고 `finished_at_epoch_ms`가 기준보다 오래된 job만 삭제한다. FAILED/RECOVERY_REQUIRED/BLOCKED_UNSUPPORTED는 관리자가 종결할 때까지 보존한다. 순서는 artifact 파일 → queue_artifact → queue_attempt → queue_job_resource → queue_idempotency_key → queue_job이다. queue_admin_audit는 독립적인 365일 보존 기간을 사용한다.

Idempotency key를 삭제하면 같은 key의 enqueue가 새 job을 만든다. Audit를 삭제한 commandId도 더 이상 멱등 재요청을 보장하지 않는다. `queue_meta`, `queue_resource_lock`의 fence와 resource guard 파일은 이 정리에서 지우지 않는다.

임시 수동 정리는 **모든 node/handler를 정지하고 DB·파일을 백업한 뒤** 수행한다. `:cutoff_ms`는 보존 기준 UTC epoch milliseconds이며 다음 SELECT에서 확정한 ID를 최대 500개씩 `:job_ids`에 바인딩한다. SQL 도구별 bind/list 문법을 사용하며, 조회된 storage_path를 queue data root 아래의 검증된 경로로 해석해 파일을 먼저 삭제한다. 파일 삭제에 실패한 ID는 DB 삭제 대상에서 제외한다.

```sql
SELECT id FROM queue_job
WHERE status IN ('SUCCEEDED', 'CANCELLED')
  AND finished_at_epoch_ms < :cutoff_ms
ORDER BY id;
SELECT storage_path FROM queue_artifact WHERE job_id IN (:job_ids);
-- 파일 삭제 성공 후 아래 DELETE들을 하나의 트랜잭션에서 실행한다.
DELETE FROM queue_artifact WHERE job_id IN (:job_ids);
DELETE FROM queue_attempt WHERE job_id IN (:job_ids);
DELETE FROM queue_job_resource WHERE job_id IN (:job_ids);
DELETE FROM queue_idempotency_key WHERE job_id IN (:job_ids);
DELETE FROM queue_job WHERE id IN (:job_ids)
  AND status IN ('SUCCEEDED', 'CANCELLED')
  AND finished_at_epoch_ms < :cutoff_ms;
-- 별도 트랜잭션: :audit_cutoff_ms는 365일 보존 기준.
DELETE FROM queue_admin_audit WHERE created_at_epoch_ms < :audit_cutoff_ms;
```
