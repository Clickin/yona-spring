# 검색 색인 변경 수집·반영 정책 조사

조사일: 2026-10-02. 후속 구현: [고정 창 PoC](search-poc.md)로 전환했으며, 아래의 “현재 2초/10초”는 조사 당시 상태를 설명한다. 공식 문서와 버전 고정 공식 소스를 기준으로 정리했다. 최신 문서의 설정값과 과거 태그의 구현을 같은 버전으로 간주하지 않는다. 이번 작업은 조사이며 PoC 구현은 변경하지 않았다.

## 판단

Yona와 직접 비교하기 좋은 Gitea·Forgejo는 **전역 큐에서 짧은 고정 시간 동안 항목을 모으고, 수량이 차면 먼저 처리하는 방식**이다. 코드 이름에는 debounce가 있지만, 새 변경마다 타이머를 뒤로 미루는 trailing debounce는 아니다. 현재 Yona의 전역 범위와 durable 변경 집합은 유지할 만하다. 배치 시작 정책은 현재의 `마지막 변경 + 2초 / 최초 변경 + 10초`와 **`최초 변경 + 2초` 고정 창**을 비교하는 것이 좋다. 2초·10초 자체는 외부 제품에서 검증된 표준값이 아니라 PoC 가정이다. 아래 소스 비교에 근거한 설계 판단이다.

## 구분해야 할 정책

| 계층 | 하는 일 | 서로 대체할 수 없는 이유 |
| --- | --- | --- |
| 변경 기록·중복 제거 | 바뀐 문서 ID를 잃지 않고 모음 | ID별 중복 제거는 ID별 타이머를 뜻하지 않음 |
| debounce·batch | 언제, 몇 건을 작업으로 넘길지 결정 | quiet 시간, 고정 창, 수량 제한은 서로 다른 정책 |
| refresh·reader reopen | 기록한 색인을 검색에서 보이게 함 | 원본 DB 변경을 수집하거나 durable outbox를 대신하지 않음 |
| commit·복구 | 색인 또는 작업을 장애 후 복구 가능하게 보존 | 검색 가시성과 디스크 내구성은 별도 시점일 수 있음 |

## 제품별 확인 결과

| 대상·조사 기준 | 확인한 정책 | Yona에 주는 의미 |
| --- | --- | --- |
| Gitea v1.24.6 | issue unique queue, 첫 항목에서 100ms 타이머 시작, 추가 항목으로 재설정하지 않음. 기본 batch length 20에 도달하면 먼저 dispatch | 이슈별 대기 타이머 없이 전역 큐에서 병합 가능 |
| Forgejo v16.0.0 | 위와 같은 100ms 고정 배치 대기와 수량 기준 dispatch | 전역 고정 창의 직접적인 선례 |
| GitLab v18.4.0-ee | Redis ZSET에 변경 참조를 모아 scheduled worker가 bulk 처리. 해당 버전 기본 cron은 매분 | callback마다 독립 색인 job을 만들 필요 없음 |
| Lucene 9.12.3 | reader 재개방 주기를 애플리케이션이 정하는 API 제공 | DB→색인 배치 정책은 Yona 책임 |
| Elasticsearch 현행 문서 | 기본 주기 refresh, 필요 시 가시성 대기 또는 강제 refresh | refresh 주기를 앱 debounce 값으로 복사하면 안 됨 |
| OpenSearch 현행 문서 | 기본 1초 refresh와 검색 idle 최적화 | 검색 가시성 정책이며 전체 DB 대조 정책이 아님 |
| Meilisearch 현행 문서 | 영속 task queue와 호환 작업 auto-batching | 고정 quiet 시간을 두지 않고도 작업 병합 가능 |
| Solr 현행 문서 | oldest update 기준 시간·문서 수 commit 조건, hard/soft commit 분리 | 첫 변경 기준 deadline은 연속 쓰기에도 밀리지 않음 |

### Gitea·Forgejo: unique queue + 짧은 고정 배치 창

Gitea v1.24.6의 이슈 indexer는 `CreateUniqueQueue`를 사용한다. handler는 작업 실행 시 DB에서 현재 이슈 데이터를 읽고, 이미 없으면 색인에서 삭제한다. 따라서 큐 중복 제거와 현재 상태 재조회가 결합되어 있다. [이슈 큐 생성](https://github.com/go-gitea/gitea/blob/v1.24.6/modules/indexer/issues/indexer.go#L69), [handler](https://github.com/go-gitea/gitea/blob/v1.24.6/modules/indexer/issues/indexer.go#L165-L201)

Gitea queue의 `batchDebounceDuration`은 100ms이다. 타이머가 없을 때만 시작하고 이미 있으면 유지한다. `batchLength`에 도달하면 타이머 만료 전에도 dispatch한다. 즉, **마지막 변경 후 100ms가 아니라 배치를 모으기 시작한 시점 기준의 대기**다. 이 시간은 worker dispatch 조건이며 색인 완료 보장은 아니다. [상수](https://github.com/go-gitea/gitea/blob/v1.24.6/modules/queue/workergroup.go#L18), [배치 처리 루프](https://github.com/go-gitea/gitea/blob/v1.24.6/modules/queue/workergroup.go#L339-L368)

Forgejo v16.0.0에서도 issue `CreateUniqueQueue`와 동일한 타이머 정책을 확인했다. [큐 생성](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/modules/indexer/issues/indexer.go#L72), [100ms 상수](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/modules/queue/workergroup.go#L18), [배치 처리 루프](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/modules/queue/workergroup.go#L313-L333)

공식 설정 문서는 큐 기본 backend를 LevelDB 기반 `level`, `BATCH_LENGTH`를 20으로 설명한다. 메모리 `channel` 등 다른 backend도 있으므로 모든 구성의 내구성이 같지는 않다. 또한 Gitea 현행 문서와 Forgejo v17 문서는 `issue_indexer`를 non-unique 목록에 두는데, 위 고정 태그의 `CreateUniqueQueue`와 차이가 있다. 이 보고서의 unique 판정은 **확인한 태그 소스 기준**이다. 디스크 큐라는 사실만으로 원본 DB와 원자적 commit 또는 처리 중 모든 crash 구간의 무손실까지 보장한다고 해석하지 않았다. [Gitea 설정](https://docs.gitea.com/administration/config-cheat-sheet/#queue-queue-and-queue), [Forgejo 설정](https://forgejo.org/docs/v17.0/admin/config-cheat-sheet/#queue-queue-and-queue)

### GitLab: 변경 참조 집합 + 주기 bulk worker

공식 개발 문서는 DB record 변경을 callback·EventStore에서 추적하고 Redis ZSET에 모은 뒤 scheduled Sidekiq worker가 Elasticsearch Bulk API로 처리한다고 설명한다. Git 저장소 데이터는 별도 경로다. [Advanced search 개발 문서](https://docs.gitlab.com/development/advanced_search/#indexing-overview)

v18.4.0-ee의 `ProcessBookkeepingService`는 직렬화한 참조를 ZSET member로 넣고 증가하는 순번을 score로 쓴다. 같은 member의 재등록은 score를 갱신한다. 처리할 score 구간을 기억하고 bulk 작업 후 그 구간만 삭제하며 실패 항목은 먼저 다시 등록한다. 후속 변경을 오래된 처리 완료로 지우지 않는 방식이라는 점에서 Yona의 generation 비교 ack와 목적이 비슷하다. **이는 코드에 근거한 해석이며 두 구현이 동일하다는 뜻은 아니다.** [변경 등록·조회](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/elastic/process_bookkeeping_service.rb#L27-99), [bulk·재등록·ack](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/elastic/process_bookkeeping_service.rb#L157-219)

해당 버전 `ElasticIndexBulkCronWorker`의 기본 스케줄은 `*/1 * * * *`이다. quiet 시간 debounce가 아닌 주기적인 증분 큐 처리다. 현행 문서에는 처리량을 위한 indexing worker 자동 재등록 옵션도 있으므로 모든 배포에서 항상 1분씩 기다린다고 일반화하면 안 된다. Redis에 보관한다는 사실은 원본 SQL 트랜잭션과 원자적이라는 의미가 아니며, Redis 장애 내구성은 배포 설정까지 따로 확인해야 한다. [v18.4.0-ee 스케줄](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/config/initializers/1_settings.rb#L857-865), [현행 관리 설정](https://docs.gitlab.com/integration/advanced_search/elasticsearch/)

### Lucene·Elasticsearch·OpenSearch: 검색 가시성 주기

Lucene `ControlledRealTimeReopenThread`는 reader를 주기적으로 다시 연다. `targetMaxStaleSec`는 특정 generation을 기다리는 호출자가 없을 때의 reopen 간격 상한, `targetMinStaleSec`는 기다리는 호출자가 있을 때 최소 간격을 조절한다. DB 변경 debounce나 작업 영속화 기능이 아니다. [Lucene 9.12.3 API](https://lucene.apache.org/core/9_12_3/core/org/apache/lucene/search/ControlledRealTimeReopenThread.html)

Elasticsearch는 기본적으로 최근 30초 동안 검색한 index를 1초마다 refresh한다. refresh API 문서는 Stack 기본 1초, Serverless 기본 5초를 구분한다. `refresh=false`는 기본 동작, `wait_for`는 다음 refresh를 기다리는 동작, `true`는 강제 refresh로 추가 비용을 만든다. 이 주기는 이미 받은 쓰기의 검색 가시성에 관한 것이다. [Near real-time search](https://www.elastic.co/docs/manage-data/data-store/near-real-time-search), [refresh 매개변수](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/refresh-parameter)

OpenSearch 문서는 `index.refresh_interval` 기본 1초와 `index.search.idle.after` 기본 30초를 설명한다. refresh interval을 명시하지 않은 경우 idle shard는 refresh를 멈추고 다음 검색에서 재개하는 최적화가 적용된다. Yona가 밤에도 원본 DB 전체를 대조해야 한다는 근거로 사용할 수 없다. [OpenSearch index settings](https://docs.opensearch.org/latest/install-and-configure/configuring-opensearch/index-settings/)

### Meilisearch: 영속 task queue와 auto-batching

Meilisearch는 비동기 작업을 영속 큐에 두고, 재시작 때 처리 중이던 작업을 다시 enqueued 상태로 돌린다. 같은 index·호환되는 type·content type의 연속 작업을 순서를 지키며 자동 배치한다. [Tasks and batches](https://www.meilisearch.com/docs/capabilities/indexing/tasks_and_batches/async_operations)

역사적으로 v0.29 릴리스에서는 auto-batching을 기본으로 전환하면서 `--debounce-duration-sec` 설정을 제거했다. 이 과거 옵션을 현행 debounce 기본값으로 인용하면 안 된다. [v0.29 공식 릴리스 설명](https://www.meilisearch.com/blog/whats-new-in-v0-29)

### Solr: 첫 변경 기준 시간과 문서 수

Solr는 hard commit과 검색 가시성을 위한 soft commit을 구분한다. `maxTime`은 가장 오래된 미반영 update부터의 시간, `maxDocs`는 이전 commit 이후 update 수로 조건을 정한다. `commitWithin`은 update에 시간 제약을 부여하며 기본적으로 soft commit을 사용한다. 문서의 10초 설정 예제는 모든 배포의 기본값이 아니다. [Commits and transaction logs](https://solr.apache.org/guide/solr/latest/configuration-guide/commits-transaction-logs.html)

## Yona에 적용할 판단

현재 코드는 원본 변경과 함께 dirty ID를 DB에 기록하고, 전역 `last + 2초` 또는 `first + 10초` 조건을 500ms마다 확인한다. 이미 진행 중인 sync job은 재사용하고, 처리한 generation만 ack한다. 이 조사에서는 구현을 그대로 두었다. [현재 debounce·job 코드](https://github.com/Clickin/yona-spring/blob/c1d6aa89fe92cfa01d63c190b47fde3e654eb6e8/src/main/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchJobs.kt), [현재 변경 집합·window 코드](https://github.com/Clickin/yona-spring/blob/c1d6aa89fe92cfa01d63c190b47fde3e654eb6e8/src/main/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchChanges.kt)

추천하는 다음 비교 실험은 다음과 같다. 아래 값과 우선순위는 외부 제품의 보장값이 아니라 Yona에 대한 판단이다.

1. **전역 고정 창을 우선 비교한다.** 첫 dirty 발생 후 2초에 실행 가능하게 하고 새 변경이 deadline을 미루지 않게 한다. 읽기가 많고 쓰기가 드물다면 trailing 방식과 묶이는 건수는 비슷하면서 연속 쓰기 때 지연을 예측하기 쉽다. 더 많은 병합의 가치가 확인되면 현재 2초/10초 방식과 비교해 선택한다.
2. **시간 정책과 ID 중복 제거는 분리한다.** 같은 ID는 최신 상태를 한 번 읽도록 유지한다. 이를 위해 이슈별 timer가 필요하지 않다. 독립 job 수를 줄이는 전역 정책과 양립한다.
3. **durable 변경 집합과 generation ack는 유지한다.** 배치 정책을 바꿔도 원본 commit 직후 종료·색인 처리 중 재수정·실패 재시도의 안전성은 별도 요구사항이다.
4. **수량 조기 실행은 측정 후 추가한다.** Gitea의 20건·100ms를 그대로 복사하지 않는다. Yona에서 대량 import나 연속 수정이 있을 때 batch 크기, commit 비용, 큐 대기가 문제가 되는지 먼저 본다.
5. **등록 지연과 검색 반영 지연을 따로 측정한다.** 현재 max-wait 10초는 작업 등록을 시도할 시점의 목표다. polling 오차, 기존 job·재시도 대기, DB 조회, 색인 처리·commit 시간이 더해지므로 검색 완료 SLA가 아니다.

비교 지표는 `원본 commit → 검색 가능` p50/p95/p99, 배치당 고유 ID 수, 초당 Lucene commit 수, idle DB 쿼리 수, dirty oldest age와 backlog면 충분하다. 야간에 변경이 없을 때 신규 색인 job과 전체 issue 조회가 없는지도 확인한다. 실제 Yona 데이터에서 이 측정은 아직 수행하지 않았다.
