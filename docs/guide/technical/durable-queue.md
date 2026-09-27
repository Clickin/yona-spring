# Durable queue: transactional store and executor

enqueue/query, 순수 상태 전이 모델과 애플리케이션 내부 실행기를 제공한다. 관리자 HTTP API/UI, archive exporter/importer는 아직 포함하지 않는다. 기존 메일·웹훅 등 callback 호출 경로는 변경하지 않는다. 실행할 수 있는 작업은 애플리케이션이 명시적으로 등록한 handler뿐이다.

## 애플리케이션 인터페이스

Spring이 주입한 `Queue.enqueue(type, version, payload, dueAt, idempotencyKey, callerScope)`를 호출한다. `REQUIRED` 트랜잭션으로 호출자의 업무 변경과 함께 저장되며, 반환값은 `ProvisionalQueueReceipt`다. 외부 트랜잭션이 commit하기 전에는 영속화 성공으로 간주하면 안 된다. rollback하면 job, key, counter 변경도 취소된다. `Queue.find(jobId)`는 payload를 노출하지 않는 snapshot을 반환한다.

- type: 소문자 ASCII `[a-z0-9._-]{1,120}`, version: 양의 정수.
- payload: 최대 1 MiB. 호출자가 나중에 byte array를 변경해도 저장 내용은 바뀌지 않는다.
- 등록된 type/version은 엄격한 UTF-8 JSON object만 허용한다. BOM, 잘못된 UTF-8, 중복 필드, trailing JSON은 거부한다. 허용 필드 등 task별 검증은 `TaskDefinition.validate`가 담당한다.
- 미등록 type/version은 decoder 실행 없이 원본 bytes를 보존한다. 저장되었다고 실행 가능한 것은 아니다.
- callerScope와 nullable idempotencyKey는 각각 최대 200 UTF-8 bytes의 대소문자 구분 식별자다.
- 같은 type/scope/key 요청은 version과 **정확한 payload bytes**가 같을 때만 기존 job을 반환한다. JSON 의미가 같아도 bytes가 다르면 `IDEMPOTENCY_CONFLICT`다. key가 없으면 매번 별도 job이다.
- dueAt의 sub-millisecond 값은 다음 millisecond로 올림한다. 저장 정밀도 때문에 요청 시각보다 먼저 실행하도록 만들지 않는다.

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
| `lease-millis` | 60000 |
| `heartbeat-millis` | 15000 |
| `shutdown-grace-millis` | 30000 |
| `data-dir` | `${yona.data:data}/queue` |
| `instance-id` | 매 프로세스 UUID |

Worker 수는 설정된 Hikari connection budget을 넘지 않아야 한다. Type별 lane 기본값은 1이다. Poll은 최대 64개의 due ID를 bounded keyset으로 훑으며 ID cursor를 순환한다. 예약 시각 우선순위는 보장하지 않는다. 실행 대기 메모리 큐도 bounded이며, commit된 attempt를 만든 다음 handler를 호출한다.

실행 중인 메모리 상태는 job ID만이 아니라 `(jobId, attemptNo)`로 식별한다. Replay-safe 작업의 이전 attempt가 lease를 잃고도 살아 있다면, 가용 worker/type lane이 허용하는 새 attempt와 구분하여 둘의 실제 slot을 모두 계산한다. 이전 attempt의 반환이 새 attempt의 slot이나 fence를 지우지 않는다.

`TaskContext.checkpoint()`로 취소/종료를 확인하고, `fencedDb { entityManager -> ... }`로 짧은 DB 변경을 수행한다. DB write는 현재 job/attempt/resource fence와 만료 시각을 같은 트랜잭션에서 확인한다. Flush 후에도 lease를 다시 검증한다. IO나 sleep을 이 트랜잭션 안에 넣지 않는다.

명시적 `RetryableTaskFailure`만 자동 재시도한다. `PermanentTaskFailure`는 실패, `RecoveryRequiredTaskFailure`와 미분류 예외는 운영자 확인 상태가 된다. 기본 attempt 한도는 generation당 5회이며, 지연은 exponential ceiling의 1/2~1 사이 deterministic jitter다. 수동 retry는 generation만 새로 시작하고 전체 attempt 번호·fence·이력을 지우지 않는다.

`QueueControl.cancel/retry`는 DB의 `SITE_ADMIN` 상태를 다시 확인하며 command UUID로 중복을 처리한다. 실행 중 취소는 `CANCEL_REQUESTED`일 뿐 종료가 아니다. 메모리 취소 신호는 외부 업무 트랜잭션까지 commit된 뒤에만 전달한다. `RECOVERY_REQUIRED` 재실행은 명시적 확인과 사유가 필요하다.

모든 resource key에 정렬된 DB fence와 안정적인 lock-file의 `FileChannel` guard를 함께 사용한다. Lease 만료는 thread 종료 증거가 아니다. 물리 guard는 handler/context 작업이 실제 반환하거나 프로세스가 끝날 때까지 유지된다. 종료 시 새 claim을 중단하고 grace 동안 heartbeat를 유지한다. 끝나지 않은 작업을 성공/취소로 꾸미지 않고 durable 상태를 복구 대상으로 남긴다. 운영 파일시스템이 프로세스 간 advisory lock을 보장해야 한다.

`writeArtifact(relativePath) { output -> ... }`는 attempt 전용 staging에 streaming하고 file data를 force한다. Handler가 성공한 뒤 현재 fence 아래에서 같은 파일시스템의 불변 경로로 atomic move하며 성공 상태와 포인터를 함께 commit한다. Commit 응답이 실패해도 실제 DB commit은 끝났을 수 있으므로 최종 파일을 즉시 삭제하지 않는다. 포인터 없는 파일은 공개하지 않으며 별도 reconciliation 대상이다. 이 프로세스 재시작 보장을 파일시스템·DB의 분산 atomic commit이나 power-loss 보장으로 해석하지 않는다.

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
