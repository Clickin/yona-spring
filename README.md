# Yona #843 Lucene 검색 PoC 기록

[#843](https://github.com/yona-projects/yona/issues/843)의 이슈 전문 검색을 Lucene으로 구현하고 성능과 검색 품질을 측정했다. 기본 검색은 지금처럼 DB이고, Lucene은 설정으로 켠다. 구현은 `poc/issue-843-lucene-search` 브랜치에, 문서와 측정 도구는 이 브랜치에 있다.

- 측정한 구현: `590f049b95b0bf82e238890970066d84eb75967d`
- 측정: 2026-10-07. 전체 측정이 38분 만에 끝났고 검색 회귀 테스트 22개가 모두 통과했다.
- 평가 데이터: 이슈 4,882건, 댓글 6,335건

## 검색 품질

기존 DB 검색(LIKE)을 기준선으로 삼아 표본 3,371개를 seed 843, sample 300으로 측정했다. 최초 표본 1,731개와 코드 이름 부분 검색용 표본 1,640개로 이뤄진다.

**코드 이름**은 제목·본문·댓글에 나오는 `SocketTimeoutException`, `user_id`, `com.example.Foo` 같은 식별자를 말한다. `[Bug]`, `[UI]` 같은 제목 머리말은 해당하지 않는다. 부분 검색은 `TimeoutException`으로 `SocketTimeoutException`이 들어 있는 글을 찾는 것이다.

| 검색어 | 건수 | LIKE | Lucene |
|---|---:|---:|---:|
| 최초 표본 전체 | 1,731 | 17.79% | 95.78% |
| 최초 표본의 코드 이름 부분 검색 | 8 | 100% | 100% |
| camelCase 이름의 뒷부분 검색 | 300 | 100% | 100% |
| camelCase 이름의 앞부분 검색 | 300 | 100% | 100% |
| 점·밑줄·하이픈·슬래시로 구분된 코드 이름 부분 검색 | 1,040 | 100% | 100% |
| **전체** | **3,371** | **57.79%** | **97.83%** |

코드 이름 부분 검색 8건은 최초 표본 1,731개에 들어 있다.

Lucene에서 전체 검색어의 상위 5·10·20건 적중률은 각각 **48.77%, 57.82%, 67.34%**이고 MRR은 **0.383512**다. 코드 이름 부분 검색으로 찾은 결과는 Nori 결과 뒤에 붙이며 스니펫은 만들지 않는다.

한국어 분석 때문에 놓치는 검색어 73건은 남아 있다. 재현율 100%가 LIKE처럼 임의의 부분 문자열을 모두 찾는다는 뜻은 아니다.

## 속도와 크기

전체 검색어로 첫 페이지 20건을 가져오는 시간이다. HTTP와 화면 렌더링은 제외했다.

| 항목 | 측정값 |
|---|---:|
| LIKE p50 / p95 | 28.27 / 57.35ms |
| 단어별 LIKE AND p50 / p95 | 40.42 / 79.50ms |
| Lucene 관련도순 p50 / p95 | 4.29 / 18.33ms |
| Lucene 날짜순 p50 / p95 | 58.20 / 72.14ms |
| 색인 파일 | 4,944,547 bytes (4.72 MiB) |

측정 환경은 관리자 권한, 클라이언트 1개, MariaDB 10.11, JDK 21.0.6, 최대 heap 1 GiB다. HTTP 응답, 화면 렌더링, 동시 접속 부하는 측정하지 않았다.
## 동작

- `yona.search.backend=lucene`으로 설정하면 제목·본문·댓글을 로컬 디스크에 색인한다. 단일 노드에서만 쓸 수 있다. 다중 노드에서는 `elasticsearch` 또는 `opensearch`를 지정해 외부 클러스터에 색인한다([설정과 한계](docs/search-poc.md#외부-검색-엔진-elasticsearchopensearch)).
- 한국어 분석은 그대로 두고, 코드 이름의 원형과 부분을 담는 보조 필드를 하나 더 둔다. 약어나 한글과 붙어 있는 코드 이름도 나누며, 검색어가 여러 부분이면 보조 필드에서 서로 인접해 있어야 일치한다. 큰따옴표 구문 검색은 이전과 같다.
- 접근 권한과 검색 조건은 그때그때 DB에서 확인한다. 이슈 변경은 DB에 먼저 기록하고 기존 작업 큐를 거쳐 색인에 반영하므로, 잠시 동안은 이전 내용으로 검색될 수 있다. 한 요청은 시작 시점의 색인 reader 하나로 결과를 최대 200건씩 읽는다.
- 색인 형식은 3이다. 이전 형식의 색인은 재시작할 때 처음부터 다시 만든다. 색인이 아직 준비되지 않았거나 실패하면 DB 검색을 쓴다.

## 문서

- [검색 동작과 운영](docs/search-poc.md)
- [검색 품질 측정과 전후 집계](docs/search-quality-measurement.md)
- [이전 속도·자원 측정](docs/search-resource-measurement.md)
- [색인 갱신 방식 조사](docs/search-indexing-policy-research.md)
- [색인과 DB 내용이 다를 때의 처리](docs/search-stale-document-research.md)

## 재현

구현 worktree에 이 브랜치의 도구를 가져와 실행한다. 환경변수와 빈 전용 DB 준비는 [측정 문서](docs/search-quality-measurement.md#재현)를 따른다.

```sh
git worktree add --detach /tmp/yona-search-benchmark 590f049b95b0bf82e238890970066d84eb75967d
git -C /tmp/yona-search-benchmark restore --source=docs/issue-843-lucene-poc --worktree -- \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchQualityProbe.kt \
  support-script/search-poc/resource-probe.gradle
cd /tmp/yona-search-benchmark
./gradlew searchQualityProbe --init-script support-script/search-poc/resource-probe.gradle
```

검색어 원문과 검색 결과는 저장소에 넣지 않는다. 측정 도구는 단계가 바뀔 때와 약 30초마다 진행 상황과 처리 건수를 출력한다.