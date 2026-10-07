# Yona #843 Lucene 검색 PoC 기록

[#843](https://github.com/yona-projects/yona/issues/843)의 이슈 전문 검색을 구현하고 측정했다. 기본 검색은 DB이며 Lucene은 선택 사항이다. 구현은 `poc/issue-843-lucene-search`, 문서와 측정 도구는 이 브랜치에 둔다.

- 측정 구현: `814c069a9a134df9084bbe4bcc8901ee37d624e2`
- 재측정: 2026-10-07, 전체 측정 실행 성공 (38분 12초). H2 전체 회귀 테스트는 이번에 재실행하지 않았다.
- 평가 규모: 이슈 4,882건과 댓글 6,335건

## 검색 품질

기존 표본 1,731개와 추가 식별자 표본 1,640개를 동일한 seed 843·sample 300으로 다시 측정했다. 최신 커밋의 품질·순위·결과 건수 집계는 이전 `44647acf3` 측정과 모두 일치했다.

| 검색어 | 건수 | LIKE | 식별자 도입 전 | 최신 Lucene |
|---|---:|---:|---:|---:|
| 기존 표본 | 1,731 | 17.79% | 95.32% | 95.78% |
| 기존 식별자 일부 | 8 | 100% | 0% | 100% |
| camel 뒷부분 | 300 | 100% | 24.67% | 86.33% |
| camel 앞부분 | 300 | 100% | 32.00% | 94.67% |
| 점·snake·kebab·슬래시 일부 | 1,040 | 100% | 100% | 100% |
| **전체** | **3,371** | **57.79%** | **84.84%** | **96.14%** |

기존 식별자 8건은 기존 표본에 포함된다. 식별자 도입 전 수치는 과거 측정이며 이번에 다시 실행하지 않았다.

최신 커밋의 전체 검색어 상위 5·10·20건 적중률은 각각 **47.88%, 56.75%, 66.00%**, MRR은 **0.375937**이다. 식별자 일부로 추가 일치한 결과는 Nori 결과 뒤에 붙이고 스니펫은 생략한다. 별도 `poc/issue-843-search-ranking` 브랜치의 근접도·식별자 경계 개선 수치는 이 구현의 결과가 아니다.

추가 식별자 표본에서는 57건을 놓쳤다. 약어의 대문자 경계와 한글에 바로 붙은 식별자를 충분히 나누지 못했다. 한국어 분석에서 놓치던 73건도 남아 있다.

## 속도와 크기

전체 검색어로 첫 페이지 20건을 가져오는 시간이다. HTTP와 화면 렌더링은 제외했다.

| 항목 | 최신 커밋 재측정 |
|---|---:|
| LIKE p50 / p95 | 28.35 / 57.54ms |
| 단어별 LIKE AND p50 / p95 | 40.46 / 79.75ms |
| Lucene 관련도순 p50 / p95 | 4.28 / 18.21ms |
| Lucene 날짜순 p50 / p95 | 58.70 / 72.83ms |
| 색인 파일 | 8,370,516 bytes (7.98 MiB) |

관리자 권한·단일 클라이언트·MariaDB 10.11·JDK 21.0.6·최대 heap 1 GiB 환경이다. HTTP·화면·동시 부하 성능을 나타내지 않는다. 이전 실행과 환경 편차가 있으므로 속도 차이를 코드 변경의 개선율로 해석하지 않는다.

## 동작

- `yona.search.backend=lucene`이면 제목·본문·댓글을 로컬 디스크에 색인한다. 단일 노드 전용이다.
- 한국어 분석은 유지하고, 식별자의 원형과 부분을 보조 필드에 추가한다. 여러 부분은 같은 필드에 인접해야 한다. 큰따옴표 구문 검색은 기존대로다.
- 접근 권한과 검색 조건은 현재 DB에서 확인한다. 변경은 DB에 기록하고 기존 작업 큐를 통해 반영하므로 잠시 이전 내용으로 검색될 수 있다. 요청은 고정된 색인 reader에서 최대 200건씩 결과를 읽는다.
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
git worktree add --detach /tmp/yona-search-benchmark 814c069a9a134df9084bbe4bcc8901ee37d624e2
git -C /tmp/yona-search-benchmark restore --source=docs/issue-843-lucene-poc --worktree -- \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchQualityProbe.kt \
  support-script/search-poc/resource-probe.gradle
cd /tmp/yona-search-benchmark
./gradlew searchQualityProbe --init-script support-script/search-poc/resource-probe.gradle
```

검색어 원문과 개별 결과는 저장소에 넣지 않는다. 측정 도구는 단계별 상태와 약 30초마다 처리 건수를 출력한다. 평가 입력은 변경하지 않았다.
