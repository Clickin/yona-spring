# 검색 결과의 stale 문서 처리 조사

조사일: 2026-10-02. Gitea v1.24.6, Forgejo v16.0.0, GitLab v18.4.0-ee의 공식 소스를 읽었다. 아래의 “hash 재검증 없음”은 **명시한 조회 경로에서 본문·댓글 전체를 다시 읽어 색인 내용과 비교하는 코드가 없다는 뜻**이며, 제품 전체의 모든 검색 경로를 증명한 것은 아니다. 조사 작업에서는 구현을 수정하지 않았다.

## 결론

**모든 검색 hit의 현재 본문·댓글 hash를 매번 대조할 필요는 없다는 직접적인 선례가 있다.** Gitea의 저장소 이슈 목록은 전문 검색으로 후보 ID를 얻고, 현재 DB 조건으로 count와 페이지를 만든다. GitLab은 색인에서 페이지를 정한 뒤 DB record를 읽고, 현재 권한으로 마지막 접근 검사를 수행한다. 두 경로 모두 내용 일치 여부와 현재 접근 권한을 별개로 다룬다. [Gitea 목록](https://github.com/go-gitea/gitea/blob/v1.24.6/routers/web/repo/issue_list.go#L539-L644), [GitLab 응답 매핑](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/lib/search/elastic/response_mapper.rb#L15-46), [GitLab 최종 권한 검사](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/search_service.rb#L132-160)

Yona에는 **현재 DB의 존재·ACL·메타 조건으로 count와 페이지를 결정하고, 전문 검색의 내용 일치는 비동기 색인 반영을 따르는 정책**을 권한다. 본문·댓글 hash는 최종 페이지의 snippet을 붙일지 결정할 때만 사용하면 된다. 아래 비교에 근거한 Yona 설계 판단이며, 외부 제품이 이 snippet 정책까지 그대로 사용한다는 뜻은 아니다.

## 조회 정책 비교

| 경로 | 내용 일치 판정 | DB 삭제 문서 | 현재 메타 조건·권한 | count |
| --- | --- | --- | --- | --- |
| Gitea 저장소 이슈 목록 | index 후보 ID, 전체 내용 hash 비교 없음 | DB 조회에서 제외 | DB 조건으로 재필터링·페이징, 저장소 접근 경로 별도 | 후보 ID에 DB stats 적용 |
| Gitea SearchIssues JSON | index 페이지 ID, 전체 내용 hash 비교 없음 | DB ID 조회에서 제외 | 검색 옵션에 접근 가능 저장소 등 전달; 이 함수에서 전 hit 재검사하지 않음 | index total 유지 |
| Forgejo 저장소 이슈 목록 | index 후보와 index 페이지, 전체 내용 hash 비교 없음 | DB ID 조회에서 제외 | stats는 DB 조건; 페이지 ID는 검색 엔진에서 다시 얻음 | 전체 후보 ID의 DB stats |
| GitLab work item advanced search | index 페이지와 매핑된 DB record, 전체 내용 hash 비교 없음 | SQL ID 조회에서 제외 | index query의 권한·메타 필터 + page record의 최종 Ability 검사 | ES total 유지, 최종 redact 뒤 재계산 안 함 |

각 행은 아래 고정 버전 근거에 한정한다. 특히 같은 제품에서도 HTML 목록과 JSON 검색, count와 페이지의 처리 경로가 다르다.

### Gitea: 현재 DB 조건으로 후보를 좁히는 사례

저장소 이슈 목록은 다음 순서다. `SearchIssues`로 얻은 `keywordMatchedIssueIDs`를 `statsOpts.IssueIDs`에 넣고 `GetIssueStats`를 호출한다. 그 count로 pagination을 만든 뒤 `FindWithIssueOptions`에 같은 후보 ID와 담당자·작성자·마일스톤·상태·라벨 등의 현재 DB 조건을 전달한다. 마지막으로 페이지 ID의 현재 이슈를 읽는다. 이 경로는 keyword를 DB에서 다시 검색하지 않으며 본문·댓글 digest 비교도 하지 않는다. [v1.24.6 issue_list.go 539–644](https://github.com/go-gitea/gitea/blob/v1.24.6/routers/web/repo/issue_list.go#L539-L644)

`GetIssuesByIDs`는 SQL `IN` 조회 결과에 실제 존재하는 ID만 유지하므로 이미 DB에서 삭제한 이슈는 목록에 반환하지 않는다. 반면 JSON `SearchIssues`는 index가 반환한 total을 그대로 header에 넣는다. 따라서 이 JSON 경로에서 삭제된 hit가 SQL에서 빠졌다고 count가 즉시 줄어든다고 말할 수 없다. [DB 조회](https://github.com/go-gitea/gitea/blob/v1.24.6/models/issues/issue.go#L595-L624), [JSON 검색](https://github.com/go-gitea/gitea/blob/v1.24.6/routers/web/repo/issue_list.go#L226-L238)

빈 검색어·숫자 검색어는 DB indexer를 사용하는 분기도 있다. 소스 주석은 색인 반영에 시간이 걸려 새 이슈가 목록에서 빠질 수 있음을 이유로 든다. 이는 모든 조회를 최신 본문과 대조해서 동기식으로 만들기보다, 일반 목록과 전문 검색의 일관성 요구를 분리한 사례다. [SearchIssues 분기](https://github.com/go-gitea/gitea/blob/v1.24.6/modules/indexer/issues/indexer.go#L284-L302)

권한은 별도 주의가 필요하다. Gitea JSON 검색은 사용자와 접근 가능 저장소를 검색 옵션으로 구성하며 공개 저장소 일부는 indexer 필터에 맡긴다. 이 사례를 근거로 Yona의 최종 DB ACL 검사를 없애서는 안 된다. [저장소 조건 구성](https://github.com/go-gitea/gitea/blob/v1.24.6/routers/web/repo/issue_list.go#L58-L122)

### Forgejo: count와 페이지 경로가 다름

v16.0.0 저장소 목록은 전체 keyword 후보 ID에 DB `GetIssueStats`를 적용해 count를 만든다. 그러나 실제 페이지는 keyword와 paginator를 포함해 `issueIDsFromSearch`를 다시 호출하고, 반환 ID를 `GetIssuesByIDs`로 읽는다. Gitea v1.24.6의 DB 재페이징 경로와 같다고 단정하면 안 된다. 조회 경로에서 내용 hash 재검증은 보이지 않는다. [stats](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/routers/web/repo/issue.go#L208-L246), [페이지 조회](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/routers/web/repo/issue.go#L270-L317), [검색 helper](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/routers/web/repo/issue.go#L501-L511)

Forgejo의 `GetIssuesByIDs`도 SQL에 존재하는 ID만 반환한다. 검색 token이 없으면 DB indexer를 선택하며, 소스 주석에 비동기 색인 반영 지연을 설명한다. [DB 조회](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/models/issues/issue.go#L559-L584), [SearchIssues](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/modules/indexer/issues/indexer.go#L333-L356)

### GitLab: 내용은 index, 결과 접근은 현재 권한

work item advanced search는 `WorkItemQueryBuilder`로 Elasticsearch query를 만들고 `ResponseMapper`에 결과를 넘긴다. query 단계에서 상태·라벨·작성자·담당자와 권한·confidentiality 필터를 적용한다. 따라서 이 메타 조건이 모두 현재 DB 값으로 재평가된다는 의미는 아니다. [검색 진입점](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/lib/gitlab/elastic/search_results.rb#L429-439), [query builder](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/lib/search/elastic/work_item_query_builder.rb#L29-58)

`ResponseMapper.records`는 응답 hit ID의 SQL record만 읽고 index 순서로 정렬한다. SQL에 없는 삭제 문서는 여기에서 제외된다. total은 ES `hits.total.value`이며, page 내용을 DB에서 얻었다고 total을 DB 기준으로 보정하지는 않는다. `highlight_map` 역시 index 응답에서 가져온다. 이 매핑 코드에는 현재 본문·댓글 전체 hash와 index hash를 비교하는 단계가 없다. [ResponseMapper](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/lib/search/elastic/response_mapper.rb#L15-46)

`SearchService.search_objects`는 page 결과에 `redact_unauthorized_results`를 적용한다. 각 객체의 현재 `Ability.allowed?` 결과로 제거하지만, 배열의 `total_count`는 원래 값을 유지한다. 공식 개발 문서는 이 마지막 보안 검사가 **색인 지연이나 버그에 따른 Elasticsearch 권한 데이터 불일치**를 처리한다고 명시한다. 따라서 본문 freshness와 ACL correctness를 같은 정책으로 취급하면 안 된다. [호출](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/search_service.rb#L57-60), [권한·count 처리](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/search_service.rb#L132-160), [공식 개발 문서](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/doc/development/advanced_search.md#permissions-tests)

## 갱신·삭제·실패 복구와 조회 검증은 별도다

Gitea·Forgejo의 index handler는 처리 시 현재 DB 데이터를 가져오고, 이슈가 없으면 index delete를 호출한다. 실패 항목은 unhandled 목록으로 돌린다. “현재 DB를 다시 읽는다”는 write-side 수렴 방식이며, 모든 검색 조회에서 본문 hash를 검증한다는 뜻이 아니다. 이 소스만으로 모든 동시 실행의 순서 보장이나 crash 구간의 무손실까지 증명하지 않았다. [Gitea handler](https://github.com/go-gitea/gitea/blob/v1.24.6/modules/indexer/issues/indexer.go#L165-L201), [Forgejo handler](https://codeberg.org/forgejo/forgejo/src/tag/v16.0.0/modules/indexer/issues/indexer.go#L173-L209)

GitLab work item reference도 현재 SQL record를 preload하고 존재하면 upsert, 없으면 delete를 선택한다. bookkeeping service는 순번 score를 가진 Redis ZSET의 처리 범위를 기억하고, 실패 항목을 다시 등록한 다음 이전 처리 범위를 지운다. 후속 변경을 오래된 완료 처리로 지우지 않으려는 구조라는 점에서 Yona generation ack와 목적이 비슷하다. [reference preload·operation](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/lib/search/elastic/references/work_item.rb#L78-129), [재등록·ack](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/elastic/process_bookkeeping_service.rb#L157-219)

단, 큐 순번은 Elasticsearch 문서의 외부 version과 다르다. 확인한 `BulkIndexer.build_op`는 `_index`, `_id`, routing을 만들고 upsert는 `doc_as_upsert`를 사용한다. 이 경로에서 외부 문서 version 조건은 확인하지 못했다. `schema_version` 역시 색인 스키마 migration을 위한 필드이므로 원본 변경 generation과 혼동하면 안 된다. [bulk 연산](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/lib/gitlab/elastic/bulk_indexer.rb#L182-215), [schema version 용도](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/doc/development/advanced_search.md#L381)

Elasticsearch의 기본 `refresh=false`는 이미 전달된 쓰기가 즉시 검색에 보인다는 보장이 없다는 뜻이고, `wait_for`는 그 쓰기의 가시성을 기다린다. 어느 쪽도 애플리케이션 DB 변경을 자동으로 수집하거나 SQL ACL을 재검사하지 않는다. delete version 보관도 임시이므로 오래된 작업의 재실행·삭제 후 재등장 문제는 앱 동기화 정책까지 검토해야 한다. [refresh](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/refresh-parameter), [Delete API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-delete)

## Yona에 대한 최소 정책 제안

다음은 위 사례를 참고한 권고다. GitLab처럼 index total을 그대로 사용하는 수준까지 완화할 필요는 없다.

1. **검색 후보의 현재 DB 존재·ACL·메타 조건은 count와 page보다 먼저 적용한다.** DB에서 삭제한 이슈, 접근 불가로 바뀐 이슈, 담당자·작성자·라벨·상태 조건에서 벗어난 이슈를 결과와 count에서 제거한다. title-head처럼 현재 제목에 대한 명시적 조건도 DB 기준을 유지한다.
2. **내용 일치는 index snapshot 기준으로 정의한다.** 수정 직후 이전 단어 검색에 잠시 남고 새 단어 검색에는 늦게 나타날 수 있다. hash가 달라졌다는 이유로 모든 결과를 숨기는 정책은 제거할 수 있다. 현재 제목·본문은 DB에서 표시하므로 잠시 “검색어가 보이지 않는 결과”가 생길 수 있음을 수용하는 결정이다.
3. **snippet은 최종 페이지에서만 검증한다.** 그 페이지의 현재 본문·댓글로 digest를 만들고 hit digest와 다르면 snippet·comment anchor를 생략한다. 같을 때 현재 DB 내용으로 escape한 snippet을 만든다. stale 결과를 통째로 숨기지 않으면서 오래된 댓글 내용·링크를 보여주지 않는 보수적인 정책이다. 페이지당 모든 댓글 읽기는 남으므로 댓글이 매우 많은 단일 이슈의 비용까지 사라지는 것은 아니다.
4. **page-only 검증으로 결과를 삭제하지 않는다.** 페이지를 먼저 확정한 뒤 stale hit를 제거하면 짧은 페이지와 total 불일치가 생긴다. 전체 freshness를 보장하지 못하면서 pagination까지 불안정해진다. page-only 검증은 표시 보조정보인 snippet의 생성 여부에만 쓰는 편이 명확하다.
5. **동기화의 안전성은 유지한다.** durable dirty ID, 실행 시 현재 DB 재조회, 삭제 처리, generation 조건 ack와 재시도를 줄이지 않는다. 조회 hash 검증은 누락된 update를 복구하지 못하며 새로운 검색어의 false negative도 해결하지 못한다.

이 정책의 total은 “현재 DB 조건·권한을 만족하는 index text hit의 수”다. “현재 DB 본문을 지금 전문 분석했을 때 일치하는 수”를 약속하지 않는다. 같은 요청 안에서도 DB isolation과 변경 경쟁의 한계가 있으므로 모든 단계가 하나의 실시간 snapshot이라고 표현하지 않는다.

검증은 수정 직후 old/new query, 댓글 삭제 후 snippet·anchor, 이슈 삭제, 권한 철회, 메타 조건 변경, 다음 색인 후 수렴을 포함한다. 성능 비교에서는 hit 수가 늘어날 때 댓글 조회량이 전체 hit에서 최종 page로 줄었는지 확인한다. 이 조사 문서는 해당 변경의 테스트 결과나 실측 수치를 주장하지 않는다.
