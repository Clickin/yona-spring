# Durable queue: transactional store and state model

enqueue/query, 순수 상태 전이 모델과 업무 DB 저장소를 제공한다. 실행기·관리자 API/UI는 다음 스택 계층에서 추가하며 archive exporter/importer는 제외한다. 기존 메일·웹훅 callback 경로는 변경하지 않는다. Queue는 항상 켜지는 핵심 인프라이며 활성화 스위치를 두지 않는다.

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

`queue_job`은 payload/status/version을, `queue_idempotency_key`는 key가 있는 요청만, `queue_job_resource`는 정렬·중복 제거한 resource 요구사항을 저장한다. `queue_attempt`는 완료 후에도 남는 실행 이력이다. 실행 계층에서 `queue_resource_lock`의 fence, `queue_admin_audit`의 관리자 명령 이력과 `queue_artifact`의 결과 파일 포인터를 추가한다.

일정은 DB UTC clock을 사용한다. Host와 DB에 NTP 동기화가 필요하다. `yona-queue-clock` 전용 daemon 스레드가 시작 시와 이후 1초 간격으로 업무 트랜잭션 밖에서 DB 시각을 검증한다. 공유 Spring scheduler나 worker poller가 지연되어도 clock 검사 스레드를 막지 않는다. `yona.queue.clock-skew-millis`는 기본 250 ms(50–5000), `yona.queue.clock-trust-millis`는 기본 5000 ms다. DB 시각이 host 요청/응답 구간의 허용 오차를 벗어나거나 마지막 정상 검증이 trust 기간보다 오래되면 admission을 막는다. 검증 유효기간은 monotonic clock으로 판단하며 신뢰 상실은 측정 skew와 함께 warn, 회복은 info로 기록한다. PostgreSQL/H2의 timezone-aware timestamp는 세션 설정을 바꾸지 않고 읽는다. CUBRID JDBC는 timestamp의 Calendar 인자를 신뢰할 수 없어 잠시 UTC에서 DB 내부 epoch 차이를 구한 뒤 원래 세션 timezone을 `finally`에서 복원한다. CUBRID health/lease는 millisecond `CURRENT_DATETIME`, 일정은 whole-second `CURRENT_TIMESTAMP`를 사용하므로 최대 1초 미만 늦을 수 있지만 일찍 실행하지 않는다.

Clock lifecycle은 worker보다 먼저 시작하고 나중에 종료되도록 phase를 `Int.MAX_VALUE - 200`으로 둔다.

Lease 검증은 row lock을 모두 얻은 **후**의 DB 시각을 사용한다. PostgreSQL `CURRENT_TIMESTAMP`와 H2의 기본 `CURRENT_TIMESTAMP`는 transaction-start 시각이므로 잠금 대기 후에도 과거 값을 반환할 수 있다. Lease/health에는 PostgreSQL `clock_timestamp()`, H2 자기 세션의 `INFORMATION_SCHEMA.SESSIONS.EXECUTING_STATEMENT_START`, CUBRID `CURRENT_DATETIME`을 사용한다. 일정의 not-before 조회는 기존 보수적인 clock을 유지한다. H2의 동작은 [함수 문서](https://h2database.com/html/functions.html#current_timestamp)와 [시스템 테이블 문서](https://h2database.com/html/systemtables.html)에 정의되어 있다.

실행 계층의 due/lease 스캔은 DB 시각 식을 SQL에 포함해 별도 시각 조회 왕복을 없앤다. PostgreSQL epoch millisecond는 반올림 cast 대신 `FLOOR`를 사용해 not-before 경계를 보존한다. CUBRID 스캔은 `NEW_TIME(..., SESSIONTIMEZONE(), 'UTC')`로 변환하므로 pooled connection의 timezone을 변경하지 않는다.

Hibernate 7.4.5의 [CUBRID dialect](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-community-dialects/src/main/java/org/hibernate/community/dialect/CUBRIDDialect.java#L316-L324)는 pessimistic locking에도 빈 SQL 절을 반환한다. `YonaCubridDialect`가 이를 [CUBRID 11.4의 native `FOR UPDATE`](https://www.cubrid.org/manual/en/11.4/sql/query/select.html#for-update)로 보완한다. 빈 절을 그대로 두면 다른 트랜잭션이 잡았다고 생각한 row lock을 통과할 수 있다. `NOWAIT`나 `SKIP LOCKED` 지원을 추가한 것은 아니다.

Quartz 등 별도 scheduler의 실행 상태와 업무 DB 상태를 중복 관리하지 않고, 이 store가 요구하는 caller transaction·정확한 idempotency·상태 전이를 직접 보존한다. 이 선택은 애플리케이션 전체의 active-active 지원을 의미하지 않는다.

`CANCEL_REQUESTED`에서 lease가 만료되면 replay-safe 여부와 관계없이 `CANCELLED`로 끝나며 attempt outcome은 `LEASE_LOST`로 보존한다. 이전 handler가 실제 반환하기 전에는 resource를 해제하지 않는다.
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

Worker는 자기 handler의 종결 전이, resource 해제, staging 정리, 물리 guard 해제와 slot 반환을 마지막 `finally`에서 책임진다. Poller는 lease가 만료된 소유자 복구와 기동 시 orphan 정리만 맡으며 handler를 직접 실행하지 않는다.

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
