# #843 식별자 점수와 도메인 편향 검증

기준선은 `8457928da`다. 다음 실험은 기존 Nori 일치 결과의 순위에 식별자 일치를 반영하는 것만 대상으로 한다. NNB·VX, minimumShouldMatch, 한글 bigram, BM25 파라미터는 바꾸지 않는다. 기본 DB 검색과 새 의존성도 변경하지 않는다.

## 다른 Lucene 계열 이슈 트래커의 근거

### GitLab v18.6.0-ee

구현은 commit `5927b4ad8ecf8b257f14761954d6e25858a3dc33`에 고정했다. 최신 버전 전체에 대한 주장이 아니다.

- [이슈/작업 항목 쿼리](https://gitlab.com/gitlab-org/gitlab/-/blob/5927b4ad8ecf8b257f14761954d6e25858a3dc33/ee/lib/search/elastic/work_item_query_builder.rb): `FIELDS = %w[iid^50 title^2 description]`. 제목 2, 본문 1(기본값), 숫자 이슈 IID 50이다. `#123`은 별도 필터 경로로 처리한다. **IID는 getHTTPResponse 같은 코드 식별자가 아니다.**
- [공통 쿼리](https://gitlab.com/gitlab-org/gitlab/-/blob/5927b4ad8ecf8b257f14761954d6e25858a3dc33/ee/lib/search/elastic/queries.rb): 일반 텍스트는 AND multi_match와 phrase multi_match를 SHOULD로 결합하고 최소 1개를 요구한다. 구문 분기에 명시적 가중치·slop은 없다. `multi_match` 기본 best_fields 방식이므로 Yona의 필드 간 토큰 AND/필드 점수 합산과 다르다. 고급 구문은 simple_query_string 경로다.
- 같은 공통 쿼리의 `DEFAULT_RELATED_ID_BOOST = 0.7`은 별도 댓글 검색에서 얻은 이슈 ID의 terms 분기에 적용한다. **댓글 필드의 BM25를 0.7배 하는 것이 아니며 댓글 점수를 전달하지 않는다.** [호출 경로](https://gitlab.com/gitlab-org/gitlab/-/blob/5927b4ad8ecf8b257f14761954d6e25858a3dc33/ee/lib/gitlab/elastic/search_results.rb)는 댓글 결과에서 부모 ID를 중복 제거한다. GitLab.com 전역 검색과 프로젝트/그룹 검색의 적용 범위도 다르다.
- [필드 매핑](https://gitlab.com/gitlab-org/gitlab/-/blob/5927b4ad8ecf8b257f14761954d6e25858a3dc33/ee/lib/search/elastic/types/work_item.rb)과 [분석기](https://gitlab.com/gitlab-org/gitlab/-/blob/5927b4ad8ecf8b257f14761954d6e25858a3dc33/ee/lib/elastic/latest/config.rb): 제목은 standard 기반, 본문·댓글은 whitespace 기반 word_delimiter_graph 분석이다. 원형을 보존하지만 Nori나 별도 코드 식별자 가중 필드는 확인되지 않았다.
- 공통 쿼리의 kNN 가중치 5는 조건부 의미 검색용이다. Yona의 구문 가중치 5 또는 식별자 가중치의 근거로 쓰지 않는다.

### Jira Data Center 9.12

- [공식 텍스트 검색 문서](https://confluence.atlassian.com/jirasoftwareserver0912/search-syntax-for-text-fields-1346049675.html)는 Lucene 기반임을 명시하고 기본 term boost 1, fuzzy term boost 0.2를 설명한다. `^4`와 `~10`은 사용자 입력 예제이지 필드 가중치·근접도 기본값이 아니다.
- [텍스트 필드 범위](https://confluence.atlassian.com/jirasoftwareserver0912/advanced-searching-fields-reference-1346049670.html)는 Summary, Description, Environment, Comments와 일부 사용자 정의 필드를 포함한다. **각 필드의 기본 숫자 가중치는 확인하지 못했다.**
- [빠른 검색](https://confluence.atlassian.com/jirasoftwareserver0912/quick-searching-1346049646.html)의 ABC-107 직접 이동은 이슈 키 조회다. 코드 식별자 점수 모델의 근거가 아니다. 즉시 검색과 Enter 후 검색의 대상 필드도 다르다.
- [Jira 8.0 Lucene 업그레이드](https://confluence.atlassian.com/jiracore/lucene-upgrade-975041047.html)는 3.3→7.3 경계를 설명한다. [OpenSearch 안내](https://confluence.atlassian.com/enterprise/opensearch-for-jira-data-center-1680483103.html)는 Jira 11.2부터 선택 가능하다고 설명한다. 버전별 분석기·유사도 구현을 같다고 가정하지 않는다.

### 적용 판단

외부 근거는 제목 우선, 서로 다른 일치 신호의 추가 점수, 이슈 번호와 코드 식별자의 구분을 참고하는 데 사용한다. **GitLab이나 Jira가 Yona용 코드 식별자 가중치 0.5 또는 1을 검증해 준 것은 아니다.** 분석기, 필드 길이, 문서 빈도와 쿼리 구조가 다르므로 계수를 그대로 옮기지 않는다.

기존 제목/본문/댓글 3/1/1과 구문 5/1은 이번 실험에서 고정한다. 기존 식별자 필드의 쿼리를 선택 점수로 추가하고, 중복된 텍스트 증거를 과대평가하지 않도록 식별자 점수 계수 **0.5와 1.0 두 개만** 비교한다. 이는 실험 후보이며 다른 제품의 기본값이라는 주장이 아니다. 순위만 바꾸고 일치 문서 집합, 보조 결과의 후순위·0점 처리, 따옴표와 댓글 경계는 유지한다. 분석기·필드 형식을 바꾸지 않으므로 VERSION은 3이다.

## 표본 편향과 사전 평가 기준

같은 평가 데이터에서 seed만 바꾸는 검증은 특정 표본에 대한 과적합을 확인할 뿐, 다른 자료로의 일반화를 입증하지 못한다. 특정 분야의 단어 목록이나 동의어를 제품 검색기에 넣지 않는다.

- 후보 선정: 평가 데이터, seed 843, sample 300. 전체·추가 식별자 MRR 및 상위 20건 비율이 기준선보다 낮아지면 채택하지 않는다. 일치 집합 변화와 기존 정답 누락은 0건이어야 한다.
- 선정 후 고정 검증: 같은 평가 데이터 seed 844와 아래 독립 공개 저장소 2개. 이 결과를 보고 가중치를 다시 조정하지 않는다. 전체·추가 식별자 MRR이 낮아지거나 일치 집합이 달라지면 범용 변경으로 채택하지 않는다. 유형별 변화와 응답 시간은 별도로 공개한다.
- 공개 corpus: [Yona 이슈](https://github.com/yona-projects/yona/issues), [Spring Framework 이슈](https://github.com/spring-projects/spring-framework/issues). GitHub GraphQL에서 2026-10-03T00:00:00Z 이전 생성된 최근 이슈 각 500개와 수집 당시 댓글 전체를 고정했다. PR은 제외한다. Yona는 댓글 1,342개, Spring은 699개다. 제목·본문·댓글 실제 데이터를 쓰며 임의로 만든 문서로 대체하지 않는다.
- 공개 corpus SHA-256: Yona `4d2080c572bb2b1d24bfb23b339b889f4da48fff675576a487a0f7f7eaefc9d5`, Spring `556223ef859f0bf7d93233ead5d27369b0dcd4372fdea1265a142571d75d052b`.
- 공개 검증은 같은 IssueSearchIndex와 검색어 생성 규칙, seed 844를 사용하는 **색인 단독 품질 평가**다. 실제 DB 권한·서비스 응답 시간은 별도 MariaDB 측정으로 검증하며 공개 평가 수치로 대신하지 않는다.
- 공개 두 corpus도 소프트웨어 개발 이슈에 치우치고 Spring은 영어 비중이 크다. 영어 corpus는 주로 식별자 견고성을 검증한다. 범용 한국어 업무, 자연어 사용자 검색 로그, 사람이 판정한 관련도·정밀도까지 검증한 것은 아니다. 결과를 합쳐 하나의 범용 점수로 제시하지 않는다.

검색어 원문·검색어별 결과·공개 corpus 원문도 저장소에 넣지 않고 집계와 출처만 기록한다.

기존 평가에서 선택한 구문 가산점 자체도 범용 최적값으로 단정하지 않는다. 공개 두 corpus에서는 분석기와 나머지 조건을 그대로 둔 채 **구문 가산점 없음과 현재 5/1을 별도로 비교**한다. 이 비교는 기존 선택의 외부 견고성 확인이며, 공개 결과를 보고 계수를 새로 맞추는 튜닝에는 사용하지 않는다. 제목 3이 GitLab의 2보다 범용적으로 낫다고 주장하지 않는다.

여기서 식별자 필드는 의미 기반 분류기가 아니라 ASCII 영문·숫자와 구분자를 분해하는 필드다. 일반 영어 단어와 숫자도 포함한다. 특정 분야의 약어를 추정해 보정하지 않으며, 영어 개발 이슈에서의 중복 점수 효과도 별도로 확인한다.
