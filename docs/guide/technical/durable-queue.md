# Durable queue: transactional store

이 변경은 enqueue/query와 순수 상태 전이 모델만 제공한다. 작업 실행기, 관리자 HTTP API/UI, archive exporter/importer는 포함하지 않는다. 기존 메일·웹훅 등 callback 호출 경로는 변경하지 않는다.

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

`queue_job`은 payload/status/version을, `queue_idempotency_key`는 key가 있는 요청만, `queue_job_resource`는 정렬·중복 제거한 resource 요구사항을 저장한다. `queue_attempt`는 실행 시도 이력을 위한 스키마다. `QueueTransition`은 DB와 분리된 상태 전이 모델이며 실제 worker가 아니다.

일정은 DB UTC clock을 사용한다. 시작 시와 이후 1초 간격으로 업무 트랜잭션 밖에서 DB 시각이 host 요청/응답 구간의 ±250 ms 안인지 검증한다. 실패하거나 마지막 정상 검증이 5초보다 오래되면 admission을 막는다. 검증의 유효기간은 wall clock 변경의 영향을 받지 않는 monotonic clock으로 판단한다. 기존 Spring scheduler가 지연되어도 오래된 정상 결과로 계속 승인하지 않는다. CUBRID JDBC는 timestamp의 Calendar 인자를 신뢰할 수 없어 DB 내부 epoch 차이를 숫자로 전송한다. CUBRID health 검사는 millisecond `CURRENT_DATETIME`, 일정 시각은 whole-second `CURRENT_TIMESTAMP`를 사용한다. 후자는 최대 1초 미만 늦을 수 있지만 일찍 실행해서는 안 된다.

Quartz 등 별도 scheduler의 실행 상태와 업무 DB 상태를 중복 관리하지 않고, 이 store가 요구하는 caller transaction·정확한 idempotency·상태 전이를 직접 보존한다. 이 선택은 애플리케이션 전체의 active-active 지원을 의미하지 않는다.

## 검증

```sh
./gradlew test \
  --tests 'com.github.yonaprojects.yona.queue.QueueTransitionAcceptanceTest' \
  --tests 'com.github.yonaprojects.yona.queue.acceptance.QueueStoreAcceptanceTest' \
  --tests 'com.github.yonaprojects.yona.queue.QueueClockHealthTest' \
  -Dyona.it.db=h2
```

`yona.it.db`는 `h2`, `mariadb`, `postgres`, `mysql`, `mssql`, `cubrid`를 지원한다. H2 외에는 Docker가 필요하다. fixture의 H2는 file-backed이며 기존 domain의 `value` 컬럼 때문에 `NON_KEYWORDS=VALUE`를 설정한다. 이것은 기본 H2 애플리케이션 설정 전체의 호환성을 보증하지 않는다.

store 검증에는 업무 rollback, concurrent keyed/unkeyed admission, 기존 MySQL business snapshot 이후의 idempotency 조회, payload 소유권, malformed payload, 기존 user 보존을 포함한 schema 추가 및 재연결이 포함된다. 별도 Java 프로세스에서 commit 후 종료하고 새 JVM에서 동일 file-backed H2를 열어 job ID/type/status/attempt count가 유지되는 것도 확인했다.
