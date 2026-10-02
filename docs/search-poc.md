# #843: Yona 전문 검색 PoC

[제안 #843](https://github.com/yona-projects/yona/issues/843)을 실제 Yona 웹·REST 경로에 적용한다. 기존 빌드를 사용하며 기본값은 DB 검색이다.

## 전체 색인과 검색 요청

검색 요청마다 메모리 색인을 만들던 첫 PoC를 **전체 이슈를 담는 공유 디스크 색인**으로 교체했다. 이전 방식도 조건에 맞는 결과를 찾을 수는 있었지만 요청마다 DB 조회와 분석을 반복했고, 관련도 통계가 필터에 따라 달라졌다. 현재 검색 요청은 이미 커밋된 색인을 읽기만 한다.

- 공개·비공개를 포함한 모든 프로젝트의 이슈 제목·본문·댓글을 색인한다. 검색자의 접근 권한으로 색인 범위를 제한하지 않는다.
- Lucene 색인은 파생 데이터다. 원본은 항상 DB이며, `yona.search.index-dir`에 저장한다.
- 초기 전체 동기화가 끝나야 전문 검색을 사용한다. 재시작 후에도 DB 대조가 끝난 뒤 활성화한다.
- 기존 프로젝트·상태·태그(라벨)·작성자·담당자·마일스톤·댓글 작성자·마감일 조건을 JPA로 적용한다. 일반 Lucene 검색은 현재 이슈 READ 권한도 DB 조건으로 적용한다. DB 정렬이면 DB에서 count·page를 계산하며, 관련도순이면 허용된 ID만 조회해 Lucene 순서에서 page를 정한다. 두 경로 모두 이슈 본문은 해당 페이지에만 읽는다.
- 텍스트 일치·관련도는 마지막 게시된 색인을 따른다. 수정 직후에는 이전 검색어로 잡히거나 새 검색어로 아직 잡히지 않을 수 있다. 전체 건수는 **색인 hit 중 현재 DB에 존재하고 조건·권한을 통과한 이슈 수**다. 최신 본문을 다시 검색한 건수라는 의미는 아니다.
- 제목·본문은 DB에서 읽으며, 최종 페이지에만 댓글 조회와 지문 비교를 수행한다. 색인과 내용이 다르면 그 결과의 스니펫·댓글 링크를 생략한다. 삭제된 이슈와 권한을 잃은 이슈는 페이지·건수에서 제외한다. 모두 해당 검색 트랜잭션에서 관측한 DB 상태 기준이다.
- 이 정책은 [stale 문서 사례 조사](search-stale-document-research.md)에 근거한다. 전체 hit의 본문·댓글 지문을 매 요청 재검증하던 정책을 변경했다. 첫 변경 후 2초는 작업 등록 기준이며, 장애·큐 지연 중에는 텍스트 불일치가 더 오래 지속될 수 있다.

Lucene의 [`IndexWriter` 문서](https://lucene.apache.org/core/9_12_3/core/org/apache/lucene/index/IndexWriter.html)에 따른 커밋·롤백과 reader 교체를 사용한다. 작업 도중의 부분 색인은 요청에 노출하지 않고, 성공한 동기화의 커밋을 게시한다.

## 전역 고정 배치 창과 durable queue

변경마다 큐 작업을 만들거나 전체 DB를 주기적으로 대조하지 않는다. **변경 ID의 영속 기록**과 **전역 배치 실행**을 분리한다.

1. Hibernate의 이슈·댓글 생성·수정·삭제 콜백이 원본 저장과 **같은 JDBC 연결·트랜잭션**에서 `issue_search_pending`에 이슈 ID와 변경 세대를 기록한다. 같은 ID는 한 행으로 합쳐지며, 원본이 롤백되면 변경 기록도 롤백된다. 큐에는 아직 작업을 생성하지 않는다.
2. `issue_search_window`의 한 행이 전체 이슈에 공통인 최초·최근 변경 시각을 보관한다. 이슈별 타이머는 없다. 기본 **최초 변경 후 2초가 지나면** 배치 등록을 시도한다. 후속 변경은 이 시점을 연장하지 않는다. 기존 작업이 진행 중이면 다음 배치로 넘기며, 처리 중 도착한 변경의 다음 창은 이전 배치 snapshot 시각을 기준으로 한다.
3. 500ms 간격의 스케줄러는 이 시각 행만 확인한다. 유휴 시간에는 이슈·댓글 테이블을 읽지 않고 빈 작업도 만들지 않는다. 이미 대기·실행·재시도 중인 `search.issues.sync` 작업이 있으면 합친다.
4. 워커는 변경 ID·세대의 스냅샷을 잡고, 해당 이슈와 댓글만 200개씩 읽어 Lucene을 갱신한다. DB에 없는 ID는 색인에서 삭제한다. 큐 payload는 `{}`이며 과거 본문을 담지 않는다.
5. Lucene 커밋 후에만 처리한 변경 기록을 삭제한다. 삭제 조건에 ID와 세대를 함께 넣어 실행 중 추가된 변경은 다음 배치로 남긴다. 실패하면 미처리 기록을 유지하고, 재시도는 최신 DB 내용을 읽는다.

변경 목록과 배치 창 시각이 모두 DB에 있으므로 재시작에도 남는다. 프로세스 시작 시에는 한 번 전체 복구 색인을 요청한다. 이는 DB 검색 모드로 운영하던 기간의 변경과 파일 유실도 복구하기 위한 것이며, 주기적인 전체 대조는 아니다. 그 외 전체 대조는 수동 재색인 및 일괄 백업 복원 때만 요청한다. 백업 복원은 ORM 콜백을 거치지 않으므로 복원 트랜잭션에서 명시적으로 기록한다. 검색 변경 테이블과 실행 중인 Lucene 파일은 백업·복원 대상에서 제외한다.

작업 진행·실패·재시도는 기존 `/site/admin/queue`에서 `search.issues.sync`로 필터링한다. 작업 단계는 `search.rebuild` 또는 `search.update`, 진행량은 `scanned`로 표시한다. 같은 화면의 **이슈 검색 색인 동기화** 버튼은 전체 재색인을 요청한다. 별도 화면이나 사이드바 메뉴는 없다.

큐 등록·Lucene 쓰기는 이슈 저장 이후 별도 작업으로 실행된다. 단, 원본과 변경 기록의 원자성을 위해 변경 기록 DB 쓰기에 실패하면 원본 트랜잭션도 실패한다. 큐 등록 실패와 색인 실패는 원본 저장을 되돌리지 않는다. 사용자 SQL 등 지원 경로 밖의 직접 DB 수정 후에는 수동 재색인이 필요하다.

## 실행과 비교

```sh
./gradlew bootRun --args='--spring.profiles.active=h2 --yona.search.backend=lucene'
```

선택 설정:

```yaml
yona:
  search:
    backend: lucene                 # 기본 db
    index-dir: ./data/search/issues # 단일 Yona 노드의 로컬 디스크
    batch-window-millis: 2000      # 최초 변경 기준; 후속 변경으로 연장하지 않음
    poll-millis: 500               # 변경 시각 한 행만 확인
    initial-delay-millis: 1000
```

`backend=db`에서는 색인 객체·색인 작업·색인 파일을 만들지 않는다. 미지원 backend 값은 시작 시 오류로 안내한다. Lucene 초기화·동기화가 준비되지 않았거나 검색이 실패하면 DB 검색으로 전환한다. 웹에 대체 검색 안내를 표시하고 REST는 `X-Yona-Search-Backend: db|lucene` 헤더로 실제 방식을 알린다.

이슈 본문 또는 댓글에 `로그인 처리 중 인증 오류가 발생`을 입력한 뒤 `로그인 오류`를 검색하면 Lucene에서는 일치하고 기존 LIKE에서는 일치하지 않는다. 라벨·작성자·담당자 조건도 함께 지정할 수 있다.

```text
/{owner}/{project}/issues?filter=로그인%20오류&labelIds=1&authorId=2&assigneeId=3&orderBy=relevance
```

```sh
curl --get -H "Authorization: Bearer $YONA_TOKEN" \
  'http://localhost:8080/api/v1/projects/OWNER/PROJECT/issues' \
  --data-urlencode 'filter=로그인 오류' \
  --data-urlencode 'label=bug' \
  --data-urlencode 'author=alice' \
  --data-urlencode 'assignee=bob'
```

서버 포트는 기존 설정을 따른다. 웹은 숫자 ID 조건을, REST `author`·`assignee`는 기존 로그인 ID 조건을 유지한다. `labelIds` 내에서는 기존 OR 의미를 유지하고, 종류가 다른 조건은 AND로 결합한다. REST는 `milestoneId`, `commenterId`, `dueDate`, `titleHead`도 받을 수 있다.

## 제공 범위

- 프로젝트 이슈 목록·엑셀 내보내기, 프로젝트 이슈 REST 두 경로, 전역·프로젝트·조직 통합 검색의 **이슈 탭**, 전역 이슈 검색 REST에 적용한다. 댓글 전용 탭 등 다른 검색 유형은 기존 방식이다.
- 여러 단어는 AND, 큰따옴표는 분석된 토큰의 구문 검색이다. 따옴표 밖 텍스트는 공백으로 나누지 않고 색인과 같이 한 문자열로 Nori 분석한다(단어마다 따로 분석하면 문맥이 달라 원문 그대로의 단어도 놓친다, [품질 측정](search-quality-measurement.md)). 제목 가중치는 3, 본문·댓글은 1이다. 제목·본문·댓글에 나뉜 단어도 찾으며 서로 다른 댓글 경계를 가로지르는 구문은 일치시키지 않는다.
- 웹의 기본 날짜 정렬을 유지하며 `관련도순`을 선택할 수 있다. REST는 `sort` 생략 시 관련도순이다. 웹 정렬·엑셀 링크는 기존 조건을 보존한다.
- 현재 DB 내용으로 HTML 이스케이프된 강조 스니펫을 생성한다. 댓글에서 일치하면 `#comment-ID` 링크를 제공한다. REST 결과의 선택 필드 `searchSnippet`에는 강조 HTML과 `commentId`가 들어간다.
- 이슈·게시글·PR 목록의 머리말 클릭은 별도 `titleHead` 조건이다. 제목 앞의 연속 머리말만 추출하며 `[Bug][UI]`는 두 값을 가진다. 본문이나 제목 중간에만 있는 언급은 제외한다.
- 과거 `filter=[Bug]` URL은 머리말 필터로 해석한다. 현재 웹 폼은 `literalFilter=true`를 보내므로 직접 입력한 `[Bug]`는 일반 텍스트 검색이다. REST는 `filter`를 일반 텍스트로, `titleHead`를 명시적 필터로 취급한다.
- Lucene은 머리말을 keyword로 색인한다. DB 이슈·PR 검색은 이스케이프된 제목 LIKE로 후보를 좁히고 동일한 머리말 추출 규칙으로 확인한다. 게시글은 기존 조회 조건을 유지한 후 제목 머리말만 검사한다.

## 다음 페이지와 머리말 필터

매 요청은 같은 검색 조건과 요청자의 현재 권한으로 다시 조회한다. 서버에 이전 페이지 결과를 보관하거나 클라이언트에 Lucene cursor를 넘기지 않는다.

- 전문 검색·머리말 경로의 DB 정렬: 권한·검색 조건을 적용하고 정렬한 뒤 DB offset/limit으로 페이지를 읽는다. 정렬값이 같으면 ID 오름차순으로 순서를 고정한다(이미 ID 정렬이 있으면 유지).
- 관련도순: Lucene hit 중 현재 DB 조건·권한을 통과한 ID를 Lucene 순서대로 놓고, `offset = page × size` 구간의 이슈만 읽는다. 예를 들어 `page=1&size=20`은 두 번째 페이지인 21–40번째다. 다음 페이지에도 전체 hit·허용 ID 조회는 필요하지만 이전 페이지의 본문을 다시 읽지는 않는다. Lucene 내부의 `searchAfter`는 한 요청 안의 hit 수집에 쓰며, HTTP 페이지 간 cursor가 아니다.
- 정확한 머리말: 후보 ID·제목만 projection으로 읽고 기존 연속 머리말 규칙으로 판정한다. 이슈·PR은 LIKE로 먼저 후보를 좁히며, 게시글은 기존 native SQL의 텍스트·라벨 조건을 유지한다. 정확히 일치한 ID에 DB 페이징을 적용하거나 Lucene 관련도 순서에서 페이지를 정한다. 제목 중간의 `[Bug]`는 제외하며 `%`, `_` 등은 머리말 문자로 취급한다.
- 이슈의 기존 메타 조건·권한, 게시글의 프로젝트·공지 제외·라벨 OR·검색어 조건, PR의 기존 조건을 유지한다. 본문은 최종 페이지에서만 조회하며 unpaged 내보내기는 예외다.

한 요청 안의 여러 SQL은 해당 트랜잭션의 DB isolation을 따른다. **여러 페이지 요청에 걸친 결과 snapshot은 아니다.** 중간에 추가·삭제·권한 변경·색인 갱신이 있으면 total과 순서가 바뀌어 offset 페이지 사이 중복·누락이 생길 수 있다. 같은 데이터·색인에서는 정렬 tie를 고정하며, 변경 중에도 고정된 결과를 보장하는 cursor/snapshot 기능은 제공하지 않는다.

검증에서는 53개 이슈를 DB/Lucene 및 날짜순/관련도순으로 20·20·13·0건씩 연속 조회했다. 각 페이지의 이슈 엔티티 로딩은 정확히 그 페이지 건수이며, 전체 건수 53과 중복·누락 없음을 확인했다. 날짜가 모두 같은 사례, 특수문자 머리말, 제목 중간의 가짜 머리말, 동기화 전 머리말 제거·이슈 삭제도 포함한다. 게시글·PR 웹 경로는 33건을 15·15·3·0건으로 확인했고, 게시글 라벨 중복 매칭과 검색어 조건을 검증했다. 기존 2,105건 회귀도 관련도순 두 번째 페이지의 이슈 로딩이 20건임을 확인한다.

## 검증과 한계

`IssueSearchIndexSpec`은 디스크 보존·재시작·실패 롤백·삭제 후 반복 실행을 검증한다. `IssueSearchQueueSpec`은 실제 durable queue 워커를 통해 전역 변경 병합, 유휴 작업 미생성, 증분 갱신, 롤백, 삭제, 처리 중 새 변경의 보존과 영속 변경 기록을 확인한다. `SearchChangeWindow`의 테스트는 최초 변경 기준 deadline, 후속 변경에도 deadline 유지, 유휴 시 미실행을 시간 지연 없이 검증한다. `IssueSearchServiceSpec`은 H2의 실제 조건식, 권한, 페이지·정렬, 머리말, 스니펫 및 웹·REST 렌더링을 검증한다. 2,105개 hit의 정렬·전체 건수, 페이지 밖 내용 변경의 지연 반영, 20건 페이지의 댓글 조회 1회 및 0 hit의 DB 조회 생략도 확인한다. Hibernate entity 통계로 DB 정렬과 관련도순 모두 이슈 엔티티 로딩이 20건임을 확인한다. 권한 회귀는 익명·게스트·관리자·작성자·담당자·공유자·부모 공유·프로젝트 멤버·조직 관리자/멤버를 공개·보호·비공개 프로젝트와 조합해 기존 판정과 SQL 결과를 대조한다. 권한별 count와 두 정렬의 page, 공유 철회, 익명 접근 차단도 확인한다. 별도 회귀는 동기화 전후 텍스트 일치, 최신 제목, 삭제된 댓글의 스니펫·링크 생략, 상태 변경·프로젝트 비공개·이슈 삭제의 DB 적용을 확인한다.

Kotest가 무관한 Docker 전용 테스트를 정적 초기화하지 않도록 검증 대상을 제한할 수 있다. 이는 별도 빌드가 아니라 기존 빌드의 테스트 발견 범위 제한이다.

```sh
cat > /tmp/yona-search-tests.gradle <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        include '**/DataBackupServiceH2IntegrationSpec.class', '**/DataBackupProjectMergeH2Spec.class', '**/IssueSearch*Spec.class', '**/IssueControllerSpec.class', '**/IssueViewControllerSpec.class', '**/IssueRestApiControllerSpec.class', '**/BoardViewControllerSpec.class', '**/PullRequestViewControllerSpec.class', '**/SearchServiceSpec.class', '**/TitleHeadServiceImplSpec.class', '**/QueueAdminTemplateRenderingSpec.class', '**/QueueAdminSecurityIntegrationSpec.class'
    }
}
GRADLE
./gradlew test -Dyona.it.db=h2 --init-script /tmp/yona-search-tests.gradle
```

검증 결과(2026-10-03): 위 대상의 H2 통합·회귀 테스트 **500개 통과**. 실제 큐 워커 실행, 전체 프로젝트 색인, 재시작·롤백, 웹 렌더링·REST·공통 큐 UI 검증을 포함한다.

남아 있는 운영 제한:

- 단일 노드 전용이다. 다중 노드용 OpenSearch는 구현하지 않았다.
- 전역 변경 시각과 변경 세대는 한 DB 행의 잠금으로 직렬화한다. 쓰기가 많아지는 환경에서는 이 짧은 기록 작업의 경합을 측정해야 한다. 2초 창은 작업 등록 기준이며 큐 대기·색인 실행 시간은 별도다.
- 일반 Lucene 검색은 DB 권한 조건으로 페이지의 이슈만 읽는다. 관련도순에서 허용된 전체 ID 목록을 읽는 비용과 Lucene 전체 hit 목록 비용은 남는다. 머리말 검색은 후보 ID·제목을 읽어 정확히 판정한 후 페이지의 본문만 읽는다. Lucene 장애 시 DB 대체 경로도 권한 조건과 DB 페이징을 사용한다. 머리말 후보 제목 전체를 검사하는 비용은 남는다. 전체 내보내기처럼 unpaged 요청은 전체 내용을 읽는다. 댓글 조회·지문 비교·snippet 생성은 결과 페이지에 한정한다.
- H2 회귀 테스트와 MariaDB 10.11의 평가 데이터 복원·검색·증분 색인 측정을 수행했다. 나머지 DB 회귀는 미완료다. 검색 품질은 [별도 문서](search-quality-measurement.md)에서 LIKE와 비교했다. 이슈 4,882건에서 확인한 검색 후처리 병목을 개선했으며, 같은 데이터의 전후 측정은 아래 문서에 기록한다.

고정 창 전환 후 리소스 측정 방법·결과는 [검색 리소스 측정](search-resource-measurement.md)을 참고한다. 유휴 상태에서도 500ms마다 window 한 행 조회는 남으며, Lucene 갱신·commit·색인 job이 없다는 뜻이지 전체 프로세스의 작업량이 0이라는 뜻은 아니다. 초기 복구 색인은 별도다.

조회 projection은 Spring Data JPA의 [Specification Fluent API](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html)를 사용한다. 별도 검색용 DB 테이블이나 결과 캐시는 추가하지 않았다. 권한 SQL은 `AccessControl.readableIssues`에 기존 READ 판정 옆에 두며, 권한 정책을 변경하면 두 경로와 대조 테스트를 함께 갱신해야 한다.
