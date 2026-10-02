# Yona #843 Lucene 검색 PoC 기록

[#843](https://github.com/yona-projects/yona/issues/843)의 이슈 전문 검색을 구현하고 측정했다. 기본 검색은 DB이며 Lucene은 선택 사항이다. 구현은 `poc/issue-843-lucene-search`, 문서와 측정 도구는 이 브랜치에 둔다.

- 구현: `1a85fc396` (로컬 커밋, push하지 않음)
- 검증: H2 통합·회귀 505개 통과
- 평가 규모: 이슈 4,882건과 댓글 6,335건

## 검색 품질

기존 검색어 1,731개에 식별자 검색어 1,640개를 추가했다. 같은 검색어로 수정 전후에 원래 이슈를 찾는 비율을 비교했다.

| 검색어 | 건수 | LIKE | Lucene 전 | Lucene 후 |
|---|---:|---:|---:|---:|
| 기존 표본 | 1,731 | 17.79% | 95.32% | 95.78% |
| 기존 식별자 일부 | 8 | 100% | 0% | 100% |
| camel 뒷부분 | 300 | 100% | 24.67% | 86.33% |
| camel 앞부분 | 300 | 100% | 32.00% | 94.67% |
| 점·snake·kebab·슬래시 일부 | 1,040 | 100% | 100% | 100% |
| **전체** | **3,371** | **57.79%** | **84.84%** | **96.14%** |

기존 식별자 8건은 기존 표본에 포함된다. 다른 검색 유형의 찾은 비율은 떨어지지 않았다. 기존에 찾던 이슈의 순위 하락도 없었다.

전체 검색어의 상위 5·10·20건 적중률은 각각 **47.88%, 56.75%, 66.00%**다. 수정 전에는 40.55%, 48.62%, 57.16%였다. 식별자 일부로 새로 찾은 결과는 기존 결과 뒤에 붙이고 스니펫은 생략한다.

추가 식별자 표본에서는 57건을 놓쳤다. 약어의 대문자 경계와 한글에 바로 붙은 식별자를 충분히 나누지 못했다. 한국어 분석에서 놓치던 73건도 남아 있다.

## 속도와 크기

전체 검색어로 첫 페이지 20건을 가져오는 시간이다. HTTP와 화면 렌더링은 제외했다.

| 항목 | 전 | 후 |
|---|---:|---:|
| LIKE p50 / p95 | 60.07 / 119.57ms | 59.90 / 119.81ms |
| Lucene p50 / p95 | 10.42 / 40.99ms | 10.59 / 42.35ms |
| 색인 파일 | 2.81 MiB | 7.99 MiB |

색인 크기는 2.84배가 됐다. 이전 기록의 2.82 MiB와 거의 같은 기준선에서 다시 측정했다. 관리자 권한·단일 클라이언트·MariaDB 10.11 환경이며, 동시 부하 성능을 나타내지는 않는다.

## 동작

- `yona.search.backend=lucene`이면 제목·본문·댓글을 로컬 디스크에 색인한다. 단일 노드 전용이다.
- 한국어 분석은 유지하고, 식별자의 원형과 부분을 보조 필드에 추가한다. 여러 부분은 같은 필드에 인접해야 한다. 큰따옴표 구문 검색은 기존대로다.
- 접근 권한과 검색 조건은 현재 DB에서 확인한다. 변경은 기존 작업 큐를 통해 반영하므로 잠시 이전 내용으로 검색될 수 있다.
- 이전 형식의 색인은 재시작 때 전체 재생성한다. 색인이 준비되지 않았거나 실패하면 DB 검색으로 돌아간다.

## 문서

- [검색 동작과 운영](docs/search-poc.md)
- [검색 품질 측정과 전후 집계](docs/search-quality-measurement.md)
- [이전 속도·자원 측정](docs/search-resource-measurement.md)
- [색인 갱신 방식 조사](docs/search-indexing-policy-research.md)
- [색인과 DB 내용이 다를 때의 처리](docs/search-stale-document-research.md)

## 재현

구현 worktree에 이 브랜치의 도구를 가져와 실행한다. 환경변수와 빈 전용 DB 준비는 [측정 문서](docs/search-quality-measurement.md#재현)를 따른다.

```sh
git worktree add --detach /tmp/yona-search-benchmark poc/issue-843-lucene-search
git -C /tmp/yona-search-benchmark restore --source=docs/issue-843-lucene-poc --worktree -- \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchQualityProbe.kt \
  support-script/search-poc/resource-probe.gradle
cd /tmp/yona-search-benchmark
./gradlew searchQualityProbe --init-script support-script/search-poc/resource-probe.gradle
```

검색어 원문과 개별 결과는 저장소에 넣지 않는다. 이번 측정용 컨테이너와 임시 복원본은 삭제했다. 평가 입력은 변경하지 않았다.
