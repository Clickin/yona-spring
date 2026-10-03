# 검색 품질 측정

2026-10-03. LIKE로는 찾지 못하는 검색어에서 Lucene이 원래 이슈를 찾는지, 그리고 그때의 첫 페이지 응답 시간을 잰다. [리소스 측정](search-resource-measurement.md)의 검색어(`오류`, `수정`)는 LIKE로도 찾을 수 있는 한 단어라 이 차이를 보여주지 못했다.

## 방법

**정답 이슈 찾기(known-item).** 이슈를 무작위로 300개 뽑고(seed 843), 각 이슈의 제목·본문·댓글 문장을 아래 규칙으로 바꿔 검색어를 만든다. 원래 이슈가 결과에 있는지, 몇 번째인지를 잰다. 사람이 관련도를 판정하지 않으며 원문을 공개하지 않고도 재현할 수 있다.

| 유형 | 규칙 | LIKE |
|---|---|---|
| `exact` | 연속된 한글 두 단어 그대로 (대조군) | 찾음 |
| `separated` | 같은 문장에서 3–7단어 떨어진 두 단어 | 못 찾음 |
| `reordered` | 연속된 두 단어의 순서를 바꿈 | 못 찾음 |
| `inflected` | `배포했습니다` → `배포된`, `복구됩니다` → `복구하는` | 못 찾음 |
| `particle` | 조사 교체 `오류가` → `오류를`. 어간이 같은 이슈에 다른 형태로도 있을 때만 | 못 찾음 |
| `joined` | 조사·어미 없는 두 단어를 붙임 `권한 설정` → `권한설정` | 못 찾음 |
| `identifierSplit` | `user_id`, `com.example.Foo`의 구분자를 공백으로 | 못 찾음 |
| `identifierPart` | `NullPointerException` → `PointerException` | 찾음 |
| `missing` | 임의 문자열 20개 | 0건 |

`LIKE` 열이 "못 찾음"인 유형은 변형한 문자열이 원래 이슈의 어느 필드에도 없는 경우만 남긴다. 단어를 임의로 반으로 나누는 규칙은 `업데 이트`처럼 실제로 입력하지 않을 검색어를 만들어 제외했다.

**비교 대상.** 모두 같은 JVM과 같은 DB에서 실행한다.

- `like`: 현행 DB 검색. 검색어 전체를 `%검색어%`로 비교, 날짜순.
- `likeAnd`: 공백으로 나눈 단어마다 LIKE를 걸고 AND. DB 쪽을 최소한으로 고쳤을 때의 기준선. 날짜순.
- `lucene`: 관련도순. `luceneDate`: 같은 hit를 날짜순으로.

**지표.** 찾은 비율(전체 결과 안), 첫 페이지 20건 안 비율, MRR, 결과 건수, 첫 페이지 응답 시간. 응답 시간은 실제 `IssueSearchService` 호출이며 HTTP·렌더링은 제외한다. 검색어마다 1회 예열 후 3회 중 중앙값을 쓰고, 그 값의 유형별 p50/p95를 낸다.

**환경.** 평가 데이터(이슈 4,882건, 댓글 6,335건)를 전용 MariaDB 10.11 컨테이너(`utf8mb4_unicode_ci`)에 복원. 관리자 권한. JDK 21, `-Xmx1g`. 수정 전·후 구현을 새 JVM·빈 DB에서 순서대로 실행했다.

## 수정한 결함: 검색어를 단어마다 따로 분석

첫 측정에서 LIKE가 찾는 `exact` 검색어 일부가 Lucene에서 0건이었다. `SimpleQueryParser`가 검색어를 공백으로 자른 뒤 단어마다 Nori로 분석하는데, Nori는 앞뒤 문맥에 따라 다르게 분해한다.

| 텍스트 | 색인(문장 단위) | 검색어(단어 단위) |
|---|---|---|
| `교수님의` | `교수` | `교수` + `님의` |
| `길이` (`길이 제한`) | `길` | `길이` |
| `열수` (`열수 있도록`) | `수` | `열수` |

[`2ee28a5`](https://github.com/Clickin/yona-spring/commit/2ee28a5a1510ff68d6a1fee7db606a04362b8053)에서 따옴표 밖 텍스트를 한 문자열로 분석하도록 바꿨다. 토큰마다 제목·본문·댓글 중 하나에 있으면 되고 모든 토큰이 필요하다. 따옴표 구문 검색과 제목 가중치 3은 그대로다. `lucene-queryparser`가 필요 없어져 Lucene JAR는 9개 14.95 MiB에서 6개 13.92 MiB(14,601,137 bytes)로 줄었다. H2 회귀 500개 통과(분석 회귀 테스트 1개 추가).

## 결과

검색어 1,731개(`missing` 제외). 찾은 비율이며, 괄호 안은 첫 페이지 20건 안 비율이다.

| 유형 | 건수 | like | likeAnd | Lucene 수정 전 | **Lucene 수정 후** |
|---|---:|---:|---:|---:|---:|
| exact | 300 | 100% (86.6%) | 100% (73.6%) | 93.3% (57.0%) | **95.6% (58.6%)** |
| separated | 288 | 0% | 100% (81.9%) | 94.4% (66.3%) | **96.5% (67.3%)** |
| reordered | 300 | 0% | 100% (71.3%) | 92.3% (58.3%) | **93.6% (58.6%)** |
| inflected | 165 | 0% | 1.2% (1.2%) | 95.1% (50.3%) | **97.5% (50.9%)** |
| particle | 187 | 0% | 8.0% (6.9%) | 88.2% (66.3%) | **96.7% (72.1%)** |
| joined | 300 | 0% | 0% | 92.6% (54.0%) | **93.0% (50.3%)** |
| identifierSplit | 183 | 0% | 100% (85.2%) | 100% (98.9%) | **100% (98.9%)** |
| identifierPart | 8 | 100% (100%) | 100% (100%) | 0% | **0%** |
| **전체** | 1,731 | 17.7% (15.4%) | 63.3% (49.1%) | 93.1% (62.7%) | **95.3% (63.3%)** |

수정 후 Lucene만 찾은 검색어는 1,363개, LIKE만 찾은 검색어는 21개(`exact` 13, `identifierPart` 8)다.

첫 페이지 응답 시간(수정 후 실행):

| | like | likeAnd | lucene | luceneDate |
|---|---:|---:|---:|---:|
| 전체 p50 | 29.48ms | 43.26ms | **5.16ms** | 6.96ms |
| 전체 p95 | 48.37ms | 91.92ms | **21.93ms** | 27.19ms |
| missing p50 | 28.58ms | 28.64ms | **0.22ms** | 0.23ms |

결과 건수 중앙값은 like 0, likeAnd 2, Lucene 30건, p90은 각각 6, 148, 916건이다.

## 해석

- LIKE로 못 찾는 6개 유형에서 Lucene은 93–100%를 찾는다. 현행 LIKE는 0%, 단어별 LIKE AND는 떨어진 단어·어순·식별자는 찾지만 활용형·조사·붙여 쓰기는 0–8%다.
- 단어별 LIKE AND도 응답 시간 p95가 92ms로, 단어 수만큼 본문·댓글 LIKE가 늘어난다.
- **Lucene은 결과가 넓다.** 형태소 단위로 일치시키므로 결과 건수가 많고, 원래 이슈가 첫 페이지에 드는 비율은 대조군(`exact`)에서 LIKE 86.6%, Lucene 58.6%다. 순위는 BM25만 쓰며 단어가 붙어 있는지는 반영하지 않는다. 근접도 가산점이 다음 개선 대상이다.
- **LIKE만 찾는 경우가 남는다.** `exact` 13건은 모두 단어 경계에서 일치하는 텍스트다. 확인한 사례는 검색어 끝이나 필드 시작처럼 앞뒤 문맥이 없는 위치에서 Nori가 다르게 분해한 경우다. 예를 들어 본문 `안전보고 출력`은 `안전`으로 색인되지만 검색어 `안전보고`는 `안전`+`보`가 된다. 줄이려면 n-gram 보조 필드 같은 별도 장치가 필요하다. `identifierPart`처럼 식별자 일부를 찾는 검색은 Lucene이 못 한다(표본 8건).

## 한계

- 검색어는 원문에서 자동으로 만든 것이며 실제 사용자 검색 로그가 아니다. 규칙이 만든 검색어가 어색한 경우가 남아 있다(예: `particle`의 일부).
- 정답이 원래 이슈 하나뿐이다. 다른 관련 이슈가 앞에 오는 것은 실패가 아니지만 순위 지표에서는 불리하게 잡힌다. 정밀도(엉뚱한 결과 비율)는 재지 않았다.
- 관리자 권한, 단일 클라이언트, MariaDB 하나에서만 쟀다. 응답 시간은 같은 실행 안에서만 비교한다. 두 실행 사이의 차이는 실행 간 편차를 포함한다.

## 재현

[측정 드라이버](../src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchQualityProbe.kt)를 [Gradle init script](../support-script/search-poc/resource-probe.gradle)의 `searchQualityProbe`로 실행한다. 리소스 측정과 같이 빈 전용 DB와 존재하지 않는 출력 디렉터리가 필요하다.

```sh
export YONA_PROBE_ARCHIVE=/absolute/path/to/migration.zip
export YONA_PROBE_OUTPUT=/tmp/yona-search-quality-r1
export YONA_PROBE_JDBC=jdbc:mariadb://127.0.0.1:PORT/EMPTY_PROBE_DB
export YONA_PROBE_DB_PASSWORD=DISPOSABLE_DB_PASSWORD
# 선택: YONA_PROBE_SAMPLE=300 YONA_PROBE_SEED=843 YONA_PROBE_AS=<loginId>
# 선택: YONA_PROBE_CASES_OUTPUT=/private/path/cases.json  # 검색어 원문 포함. 저장소에 넣지 않는다.
./gradlew searchQualityProbe --init-script support-script/search-poc/resource-probe.gradle
```

집계는 [search-quality-results.json](search-quality-results.json)에 수정 전(`before`)·후(`after`)로 보관한다. 검색어 원문과 검색어별 결과는 저장소에 포함하지 않는다.
