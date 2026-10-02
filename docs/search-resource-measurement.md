# Lucene PoC 리소스 측정

2026-10-02. 첫 변경 후 2초의 전역 고정 배치 창을 적용한 코드로 측정한다. 후속 변경은 deadline을 연장하지 않는다. `yona.search.batch-window-millis=2000`, `poll-millis=500`이며 이전 PoC의 `debounce-millis`/`max-wait-millis` 설정은 제거했다.

## 머리말 경로 추가 최적화 — 조회량 검증

이슈·게시글·PR의 머리말 필터도 전체 후보 엔티티 로딩을 제거했다. 후보의 **ID·제목만** 읽어 정확한 leading-head 규칙으로 검사하고, 최종 페이지의 본문을 조회한다. 조건이 없는 전체 제목 조회가 아니라 기존 프로젝트·검색 조건(이슈 권한, 게시글 라벨 포함)을 통과한 후보에 한정한다. Lucene 관련도순은 이 ID를 hit 순서와 결합한다.

H2 회귀 **499개 통과**. 53건 이슈는 DB/Lucene 및 DB 정렬/관련도순에서 페이지별 20·20·13·0건, 게시글·PR 33건은 웹 경로에서 15·15·3·0건을 검증했다. Hibernate entity 통계의 본문 엔티티 로딩도 각각 같은 건수였다. 특수문자·제목 중간 머리말 제외, 정렬 tie, 머리말 변경·삭제, 라벨·검색어 조건을 함께 확인했다.

이번 추가 변경은 **조회량을 검증한 결과**다. 머리말 필터의 p50/p95를 새로 측정했다고 주장하지 않는다. 아래 시간·메모리 수치는 머리말 경로 추가 변경 이전 일반 전문 검색의 측정 기록이다. 정확한 후보 제목 검사는 여전히 전체 후보 ID·제목을 읽으며, 본문 전체 로딩을 제거한 것이다. 다음 페이지 동작과 요청 간 변경 시 한계는 [PoC 문서](search-poc.md#다음-페이지와-머리말-필터)에 기록했다.

## 일반 전문 검색 실측 — DB 권한 조건과 페이지 단위 이슈 조회

일반 Lucene 검색에서 `AccessControl.readableIssues`로 기존 이슈 READ 권한을 SQL 조건에 포함했다. DB 정렬은 DB에서 count·page를 계산한다. 관련도순은 권한·기존 조건을 통과한 **ID만** projection으로 읽고, Lucene 순서에서 페이지를 정한 뒤 그 페이지의 이슈 엔티티를 조회한다. 페이지 이슈를 조회할 때도 같은 권한·검색 조건을 적용한다. 신규 테이블·캐시는 추가하지 않았다. 텍스트의 지연 반영 및 페이지 snippet 지문 검증 정책은 그대로다.

**2,105개 hit 중 20건 페이지에서 실제 이슈 엔티티 로딩이 20건**임을 Hibernate entity 통계로 검증했다. DB 정렬과 관련도순 모두 해당하며 댓글 조회도 페이지 기준 1회다. H2 회귀 **497개 통과**. SQL 권한 조건은 익명·게스트·관리자·작성자·담당자·공유자·부모 공유·프로젝트 멤버·조직 관리자/멤버를 공개·보호·비공개 프로젝트와 조합해 기존 판정과 대조했다. 실제 검색의 권한별 count와 두 정렬의 page, 공유 철회, 익명 접근 차단도 검증했다.

같은 평가 데이터(이슈 4,882건, 댓글 6,335건), 동일 JVM·20건 관련도 페이지·5회 예열/20회 관측으로 Lucene → DB → Lucene을 새 JVM/빈 DB에서 순차 실행했다. 원시 결과는 [JSON의 `pagedRuns`](search-resource-results.json)에 추가했다. 이전 결과는 보존했다.

| 검색어 | hit 수(DB / Lucene) | 직전 Lucene p50 | 이번 Lucene p50 / p95 | 이번 DB p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 오류 | 445 / 445 | 18.52–22.35ms | **16.20–20.34 / 47.36–47.44ms** | 38.90 / 41.66ms |
| 수정 | 1,539 / 1,533 | 34.50–56.54ms | **23.21–24.92 / 25.72–51.37ms** | 35.82 / 39.17ms |
| 불일치 검색어 | 0 / 0 | 0.54–0.87ms | **0.60–1.02 / 1.41–1.48ms** | 27.72 / 28.78ms |

두 Lucene 실행 모두 p50은 DB 기준선보다 낮았다. 다만 **p95 개선은 일관되지 않았다**. `오류`의 p95는 두 번 모두 DB보다 높고, `수정`도 두 번째 실행은 DB보다 높았다. 20회 관측·JVM 2회 범위는 운영 지연 분포나 통계적 신뢰구간이 아니다. 조회량 감소는 회귀 테스트로 확인했고 시간의 정확한 감소 비중은 개별 구간 profiler로 분리하지 않았다.

초기 색인 완료 관찰은 2.06–3.12초, 같은 JVM의 색인 전후 live heap 증가는 16.96–17.11 MiB였다. 색인 파일 **2.82 MiB**, 배포 의존성 **14.95 MiB**는 변함없다. 유휴 10초 신규 job은 0건, 10건 수정은 1개 job으로 10개 ID만 갱신했고 2.56–2.58초 후 완료를 관찰했다.

검색 후 live heap은 117.68–119.22 MiB, RSS는 676.89–786.03 MiB였다. 이번 DB 실행은 98.35 MiB / 594.94 MiB다. archive 복원과 같은 JVM이므로 이 차이를 Lucene의 고정 요구량이나 최적화의 메모리 절감량으로 일반화하지 않는다.

범위와 남은 제한:

- 실측은 관리자 권한·관련도순이다. 일반 사용자의 복잡한 권한 SQL, 날짜순의 성능, 동시 부하는 별도 측정이 필요하다. 두 정렬의 결과와 엔티티 로딩 제한은 H2 회귀로 확인했다.
- 관련도순은 허용된 전체 ID를 읽고 Lucene도 전체 hit를 반환한다. 본문 전체 로딩은 제거했지만 대규모 ID 목록 비용은 남는다.
- 이 측정 당시 leading-title-head와 DB 대체 경로에는 전체 후보 엔티티 조회가 남아 있었다. 후속 변경에서 제목 projection 및 DB 페이징으로 개선했다(문서 상단). unpaged 내보내기는 전체 내용을 읽는다.
- H2와 평가 데이터를 복원한 MariaDB를 검증했다. 나머지 지원 DB의 회귀는 별도다. 권한 정책이 바뀌면 기존 판정과 SQL 조건 및 대조 테스트를 함께 갱신해야 한다.

## 2차 후속 결과 — stale 정책 분리, 이슈 전체 로딩 당시

[사례 조사](search-stale-document-research.md) 후 전체 hit의 본문·댓글 지문 비교를 제거했다. **텍스트 일치·관련도는 마지막 게시된 색인**, 존재 여부·권한·기존 메타 조건은 현재 DB를 따른다. 따라서 수정 직후 이전 단어의 hit가 잠시 남거나 새 단어의 hit가 늦을 수 있다. total은 DB 조건·권한을 통과한 index hit 수다. 표시할 페이지에만 댓글을 읽고 digest를 비교해, 내용이 바뀌었으면 snippet·댓글 링크를 생략한다. 결과 자체를 페이지 분할 후 제거하지 않으므로 페이지 길이와 total의 기준은 같다.

같은 평가 데이터의 이슈 **4,882건·댓글 6,335건**으로 Lucene → DB → Lucene의 새 JVM/빈 DB 3회 측정을 수행했다. 조건은 아래 방법과 동일하다. 색인 완료 후에는 이전 측정과 hit 수가 같았다. 원시 결과는 [JSON의 `stalePolicyRuns`](search-resource-results.json)에 보관했다. 두 Lucene 실행의 최소–최대이며 신뢰구간이 아니다.

| 검색어 | hit 수(DB / Lucene) | 직전 구현 Lucene p50 | 이번 Lucene p50 / p95 | 이번 DB p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 오류 | 445 / 445 | 27.3–27.8ms | **18.52–22.35 / 30.62–37.59ms** | 44.49 / 51.13ms |
| 수정 | 1,539 / 1,533 | 60.6–74.2ms | **34.50–56.54 / 39.21–74.83ms** | 39.39 / 42.47ms |
| 불일치 검색어 | 0 / 0 | 0.49–0.60ms | **0.54–0.87 / 0.94–1.16ms** | 30.42 / 35.01ms |

`오류`는 이번 두 실행에서 DB보다 빨랐지만, `수정`은 p50이 34.50–56.54ms로 DB 39.39ms의 양쪽에 걸쳤다. **모든 검색이 DB보다 빨라졌다는 결과는 아니다.** 댓글 조회와 hash 비용은 최종 페이지 크기로 제한했으나, 현재 ACL과 DB 정렬·건수를 위해 모든 매칭 이슈 엔티티를 읽는 비용은 남는다. 검색어별 20회·Lucene JVM 2회의 소규모 관측이므로 운영 p95나 대규모 동시 부하를 대표하지 않는다.

이번에도 초기 색인 완료 관찰은 2.61–2.61초, 같은 JVM의 색인 전후 live heap 증가는 16.96–17.17 MiB, 색인 파일은 **2.82 MiB**였다. 유휴 10초에 신규 job은 0건, 10건 수정은 1개 job으로 10개 ID만 갱신했고 2.58–2.59초 후 완료를 관찰했다. 첫 변경 후 2초 창과 500ms polling 정책은 그대로다.

검색 후 live heap은 115.65–191.69 MiB, RSS는 704.92–985.03 MiB였다. DB 실행의 같은 값은 96.68 MiB / 562.09 MiB다. 두 번째 Lucene JVM은 이미 색인 전부터 heap이 높았으므로 복원 후 잔여 상태와 Lucene 비용을 분리할 수 없다. 메모리 절감량으로 해석하지 않는다. 추가 의존성 크기 **14.95 MiB**와 단일 노드 제한도 변함없다.

H2 검색·큐·백업·웹/API 회귀 **496개 통과**. 2,105개 hit의 20건 페이지에서 댓글 쿼리가 11회에서 **1회**로 줄었음을 검증했다. 별도 회귀로 수정 전후 old/new 검색어, 현재 제목 표시, 삭제 댓글의 snippet·anchor 생략, 상태·권한 변경과 이슈 삭제, 색인 갱신 후 수렴을 확인했다.

## 1차 후속 최적화 결과 — 전체 hit 지문 검증 당시

2026-10-02 후속 변경은 [IssueSearchService](https://github.com/Clickin/yona-spring/blob/4e7fb579504c39907bdf70f7a71cb4beb0abb5f3/src/main/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchService.kt)의 DB 후보 조회를 Lucene hit ID로 제한하고, [댓글 조회](https://github.com/Clickin/yona-spring/blob/4e7fb579504c39907bdf70f7a71cb4beb0abb5f3/src/main/kotlin/com/github/yonaprojects/yona/domain/issue/IssueCommentRepository.kt)를 200개 이슈씩 묶으며, snippet을 최종 페이지에만 생성하도록 바꿨다. 0 hit이면 후보·댓글 DB 조회를 하지 않는다. 명시적 DB 정렬을 전체 hit에 적용하고 권한·현재 내용을 페이지 분할 전에 검증하므로 페이지별 순서와 정확한 전체 건수를 유지한다. 숫자 ID는 Criteria literal로 전달하고 IN 목록을 500개씩 나누어 hit 수가 SQL Server의 바인딩 매개변수 제한을 소진하지 않게 했다. H2와 MariaDB에서 실행했으며 나머지 DB의 실행 검증은 별도다.

같은 평가 데이터·JDK·heap·검색어·20건 페이지·예열/관측 횟수로 **Lucene → DB → Lucene** 순서의 새 JVM/DB 3회 측정을 수행했다. 원시 결과는 [JSON의 `optimizedRuns`](search-resource-results.json)에 추가했고 최적화 전 수치는 그대로 보존했다. 아래 시간은 네트워크·렌더링을 제외한 실제 서비스 호출이다.

| 검색어 | hit 수(DB / Lucene) | 최적화 전 Lucene p50 | 최적화 후 Lucene p50 / p95 | 재측정 DB p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 오류 | 445 / 445 | 244.3–287.4ms | **27.3–27.8 / 39.6–50.6ms** | 42.5 / 44.7ms |
| 수정 | 1,539 / 1,533 | 745.2–822.5ms | **60.6–74.2 / 73.0–102.6ms** | 35.0 / 36.0ms |
| 불일치 검색어 | 0 / 0 | 42.9–44.1ms | **0.49–0.60 / 0.66–0.67ms** | 28.9 / 30.3ms |

앞의 두 검색은 초기 Lucene 서비스보다 중앙값 기준 약 **9–14배 빨라졌다**. 다만 `수정`처럼 많이 매칭되는 검색은 여전히 DB 기준선보다 약 **1.7–2.1배** 느리다. `오류`도 p50 개선과 달리 p95는 DB보다 큰 실행이 있다. 모든 검색이 DB보다 빠르다는 결론은 아니다. 두 Lucene 실행 모두 변경 전과 같은 hit 수(445 / 1,533 / 0)를 유지했다.

이 단계에서는 내용이 변경된 hit를 전체 건수에서도 제외하기 위해 **모든 매칭 후보의 본문·댓글을 읽고 digest를 확인**했다. 매칭 수가 매우 크면 이 비용과 ID 목록 크기가 커진다. 이번 변경은 DB 전체 후보 조회, 이슈별 댓글 N+1, 페이지 밖 snippet 생성을 제거한 것이며, 후속 사례 조사에서는 텍스트 지연 반영과 현재 DB 권한 검사를 분리하기로 했다. 최신 정책은 문서 상단을 참고한다.

후속 실행에서도 초기 색인은 2.08–2.58초, index 전후 live heap 증가는 16.95–17.08 MiB, 색인 파일은 2.82 MiB였다. 유휴 10초 새 job은 0건이고, 10건 수정은 1개 job으로 10개 ID만 갱신해 2.58–2.59초 뒤 완료를 관찰했다. 검색 후 live heap은 117.71–191.90 MiB, RSS는 717.28–965.59 MiB로 여전히 편차가 컸다. 특히 두 번째 실행은 검색 전부터 heap이 높았다. 대형 archive 복원과 같은 JVM에서 측정한 값이므로 **이 최적화로 메모리가 일정량 줄었다고 해석하지 않는다**. 두 실행은 모두 `-Xmx512m`에서 완료됐다.

검증은 검색·큐·백업·컨트롤러 H2 회귀 **495개 통과**다. 새 2,105건 회귀는 DB 정렬과 페이지, 페이지 밖 stale 댓글의 total 제외, 댓글 배치 조회 11회(200개 이슈 단위), 0 hit에서 저장소 호출 없음, snippet 키가 현재 페이지에만 속하는 것을 확인한다. 기존 권한 변경·삭제·본문/댓글 변경·검색 조건·웹/REST·snippet HTML escape 검증도 유지했다.

## 초기 구현 결과 — 후처리 최적화 이전

**초기 구현에서는 색인 유지 비용보다 검색 후처리의 비용이 더 큰 문제였다.** 두 실행 모두 같은 `-Xmx512m`에서 완료했다. 초기 색인 자체로 증가한 live heap은 약 17 MiB였지만, 검색 후 전체 애플리케이션의 추가 live heap은 22–42 MiB, 같은 시점 RSS 차이는 128–298 MiB로 관측됐다. 이 RSS 범위를 Lucene의 고정 메모리 요구량으로 일반화하지 않는다.

원시 수치는 [집계 JSON](search-resource-results.json)에 보관한다. 실행 순서는 DB → Lucene → Lucene → DB로 뒤집었고 각 모드 2회다. 아래 범위는 두 번의 최솟값–최댓값이며 통계적 신뢰구간이 아니다. macOS 26.5.2 arm64, JVM에 보이는 CPU 10개, Temurin 21.0.6, MariaDB 10.11 컨테이너를 사용했다.

| 지표 | DB 모드 | Lucene 모드 |
| --- | ---: | ---: |
| 색인 대상 텍스트 | 8.94 MiB | 동일 |
| 초기 색인 완료 관찰 | 없음 | 2.12–2.61초 |
| 초기 색인 구간 앱 CPU time | 없음 | 4.20–5.17 CPU초 |
| 색인 전후 live heap 증가(동일 JVM) | 약 0 | 16.84–17.09 MiB |
| 검색 후 live heap | 95.97–96.70 MiB | 118.28–138.48 MiB |
| 검색 후 RSS | 556.58–761.91 MiB | 854.19–889.58 MiB |
| 10초 유휴 구간 앱 CPU time | 32.7–73.0ms | 88.7–106.3ms |
| 유휴 구간 신규 색인 job | 0 | **0** (`lastSuccess`도 불변) |
| 10건 수정 이후 색인 완료 관찰 | 해당 없음 | 2.58–2.60초 |
| 증분 색인 job / 처리 ID | 해당 없음 | **1개 / 10개** |
| 10건 쓰기 + 반영 대기 구간 앱 CPU time | 56.7–82.3ms | 387.4–515.3ms |
| Lucene 색인 파일(증분 갱신 후) | 0 | **2.82 MiB** (2,957,403 bytes) |

애플리케이션 배포물에는 Lucene 9.12.3 및 Nori 사전 등 전이 의존성 9개 JAR, 합계 **14.95 MiB**(15,680,332 bytes)가 추가된다. 현재 빌드는 이 JAR들을 선택적으로 패키징하지 않으므로 `backend=db`라도 배포 파일 크기는 늘어난다. 위 DB/Lucene 실행 비교는 동일 빌드에서 backend만 바꾼 것이므로 JAR 추가 전의 바이너리와 비교한 메모리 수치가 아니다.

초기 색인 전 live heap 자체가 실행별로 달랐다(특히 첫 Lucene 실행). 5 GiB archive 복원 이후 남은 객체·캐시·GC 시점의 영향과 엔진 비용을 분리하기 위해 동일 JVM의 색인 전후 증가량과 검색 후 전체 값을 함께 공개한다. 검색 중 순간 할당량, peak RSS, MariaDB 프로세스 CPU/RAM은 별도로 측정하지 않았다. 이 결과만으로 운영 서버의 최소 RAM을 확정하지 않는다.

### 색인 완료 후의 조회

아래 값은 **색인 완료 및 검색 예열 이후**의 실제 서비스 p50/p95 범위다. 별도의 읽기 전용 엔진 진단에서는 같은 완료된 색인과 동일 query parser를 사용해 모든 hit를 500개씩 읽고 ID·digest stored field도 읽었다. 엔진 단독 진단은 별도 JVM의 warm 조회이며, 서비스 시간에서 단순히 빼서 정확한 단계별 CPU 시간으로 해석하면 안 된다.

| 검색어 | DB hit / Lucene hit | DB 서비스 p50 / p95 | Lucene 서비스 p50 / p95 | Lucene 엔진 단독 p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 오류 | 445 / 445 | 39.0–42.5 / 41.2–45.5ms | 244.3–287.4 / 267.3–341.5ms | **3.31 / 4.68ms** |
| 수정 | 1,539 / 1,533 | 36.0–40.2 / 40.5–42.7ms | 745.2–822.5 / 824.0–1,055.3ms | **8.41 / 9.21ms** |
| 불일치 검색어 | 0 / 0 | 28.6–29.6 / 29.3–30.9ms | 42.9–44.1 / 45.2–54.2ms | **0.18 / 0.24ms** |

최적화 전 [서비스 구현](https://github.com/Clickin/yona-spring/blob/4e7fb579504c39907bdf70f7a71cb4beb0abb5f3/src/main/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchService.kt)은 다음 비용을 매 요청마다 지불한다. 아래 원인 구분은 코드 경로와 위 측정에 근거한 해석이며, 모든 단계를 독립 profiler로 계측한 결과는 아니다.

1. Lucene hit가 있어도 DB 조건에 맞는 후보 전체를 가져온 뒤 메모리에서 ID를 거른다. 0 hit 검색도 이 후보 조회를 수행한다.
2. 매칭된 이슈마다 댓글 쿼리를 실행하고 현재 문서 digest를 계산한다. 이는 내용이 변경된 문서를 제외하기 위한 비용이었다. 삭제된 이슈와 권한 변경의 제외는 별도의 현재 DB 조회·ACL 검사에서 처리한다.
3. 전체 매칭 결과에 snippet을 만든 다음에야 20건 페이지를 자른다.

**당시 확인한 개선 대상**은 hit ID를 DB 조회에 반영하기, 댓글 조회를 배치로 묶기, snippet을 최종 페이지에만 만들기였다. 위 후속 변경에서 이 세 가지를 적용했다. 권한·존재 여부·DB 조건은 계속 확인한다. 당시에는 내용 변경까지 모든 hit에서 재검증했으나, 후속 사례 조사에서 이 정책을 별도로 재검토했다. 이 절은 최적화 이전의 기록이다. 후속 변경과 같은 fixture의 재측정 결과는 문서 상단에 구분해서 기록한다.

### 운영상 해석

- 변경 없는 시간에는 새 검색 job·Lucene 갱신·commit을 요청하지 않는다. 그러나 DB window 한 행 조회는 약 2회/초 남고 기존 queue worker도 활동하므로 전체 CPU/DB 작업이 0은 아니다. 프로세스 시작과 수동 요청의 전체 복구 색인은 별도다.
- 변경마다 dirty ID·generation을 원본 트랜잭션에서 기록하는 DB 비용은 남는다. 작업 실행 수를 줄이는 것과 변경 기록 자체를 없애는 것은 다르다.
- 이 데이터에서 색인 파일은 원문 UTF-8 크기의 약 32%였다. 어휘·문서 길이·댓글 수와 segment 병합 상태에 따라 달라지므로 문서 수나 5 GiB 백업 크기만으로 디스크를 산정하지 않는다. 복구·재색인·merge 중 임시 디스크 여유는 이 표에 포함하지 않았다.
- 단일 검색 클라이언트, 전체 프로젝트를 볼 수 있는 관리자, 첫 페이지 20건 기준이다. 동시 읽기 부하·권한 복잡도·다중 노드·재시작 재색인·검색 품질의 운영 검증을 대체하지 않는다.

## 데이터와 복원

평가 입력을 기존 `DataBackupService.importSite`로 **새 임시 DB와 파일 경로**에 복원했다. 입력은 변경하지 않는다. 원본 텍스트·사용자·첨부파일·자격증명·전체 로그는 이 문서나 저장소에 포함하지 않는다.

- archive 기준: 이슈 4,882건, 댓글 6,335건, 프로젝트 4개.
- 첨부파일·저장소는 Lucene 이슈 색인 대상이 아니다. 제목·본문·댓글의 UTF-8 바이트를 별도로 측정한다.
- H2 복원은 알림 이력의 `new_value`가 100만 자 제한을 초과하여 실패했다. 해당 실행은 결과에서 제외한다. 데이터를 잘라내거나 측정용으로 importer 검증을 완화하지 않고 MariaDB 10.11의 별도 컨테이너를 사용한다.
- 예비 측정은 기존 `yona2-migrator`의 `behaviorCheck`로 생성한 1건 fixture 및 명시적 1,000건 합성 확장으로 진행했다. 이것을 본 측정 또는 운영 규모 결과로 간주하지 않는다.

## 방법

[측정 드라이버](../src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchResourceProbe.kt)와 [Gradle init script](../support-script/search-poc/resource-probe.gradle)는 기존 Yona 애플리케이션을 실행한다. 별도 검색 엔진 모형이 아니다. 테스트 runtime classpath에서 실행하지만 CI 테스트에는 포함되지 않는 opt-in main이다.

- 각 모드는 별도 JVM·DB·출력 디렉터리에서 순차 실행. JDK 21, G1, `-Xms256m -Xmx512m`. 측정 중 다른 모드의 부하를 동시에 걸지 않는다.
- 복원·앱 시작은 측정 구간 밖이다. 초기 색인은 큐 제출부터 완료 관찰까지 wall time과 해당 구간의 **애플리케이션 프로세스 CPU time**을 기록한다. CPU time은 여러 스레드의 합이며 외부 MariaDB CPU를 포함하지 않는다.
- heap은 명시적 GC 후의 live heap 관측값, RSS는 같은 시점의 `ps` 값이다. 최대 RSS·순간 할당량·전체 머신 메모리나 컨테이너 한계가 아니다. JVM·JIT·OS 페이지 회수에 따라 RSS 편차가 크다.
- 실제 `IssueSearchService`를 관리자 권한으로 호출한다. DB 조건·권한 확인·snippet·20건 페이지를 포함하며(지문 검증 범위는 각 측정 단계의 설명 참고) HTTP·템플릿 렌더링 시간은 제외한다. 검색어별 5회 warm-up 후 20회 관측, nearest-rank p50/p95.
- SQL LIKE와 Nori 검색은 의미가 달라 동일 검색어의 hit 수가 다를 수 있다. 결과 수를 함께 기록하며 동일 작업량의 엔진 속도 비교라고 주장하지 않는다.
- 검색 admission 스케줄러의 자동 실행만 늦추고 드라이버에서 동일 `schedule()`을 500ms 간격으로 호출한다. 실제 durable queue worker가 작업을 수행한다. DB 모드에는 검색 스케줄러 bean이 없다.
- 유휴 10초 동안 검색 job 수와 index `lastSuccess`가 변하지 않는지 assert한다. Lucene 모드는 이 시간에도 변경 시각 한 행을 20회 확인한다. 기존 queue worker 등 애플리케이션의 다른 활동은 양쪽 모두 유지한다.
- 첫 10건의 본문을 한 트랜잭션에서 수정하고 commit 이후 큐 완료까지 관찰한다. 이 값에는 2초 창, polling 오차, queue 대기, DB 조회와 Lucene commit이 포함된다. 완료 관찰 해상도는 500ms이다.

## 재현

기존 서버를 대상으로 실행하지 않는다. `YONA_PROBE_JDBC`에는 **전용 컨테이너에 새로 만든 빈 DB**만 지정한다. 출력 디렉터리도 존재하지 않아야 하며 드라이버가 0700으로 생성한다. archive는 읽기만 한다.

```sh
# 전용 MariaDB 10.11 컨테이너/빈 DB 준비 후, 실제 경로와 전용 DB 자격증명을 지정
export YONA_PROBE_ARCHIVE=/absolute/path/to/migration.zip
export YONA_PROBE_OUTPUT=/tmp/yona-search-measurement-lucene-r1
export YONA_PROBE_BACKEND=lucene  # 별도 빈 DB/경로에서 db 모드도 실행
export YONA_PROBE_JDBC=jdbc:mariadb://127.0.0.1:PORT/EMPTY_PROBE_DB
export YONA_PROBE_DB_PASSWORD=DISPOSABLE_DB_PASSWORD
export YONA_PROBE_QUERIES=오류,수정,missingneedle92817
./gradlew searchResourceProbe --init-script support-script/search-poc/resource-probe.gradle
```

`metrics.json`은 각 출력 디렉터리에 저장된다. `YONA_PROBE_ISSUES`의 기본값은 0이며 원본 그대로 사용한다. 명시적으로 양수를 주면 첫 이슈를 바탕으로 합성 이슈와 각각 댓글 2건을 추가하므로 원본 측정과 구분해야 한다. H2 소형 fixture 측정은 `YONA_PROBE_JDBC`와 `YONA_PROBE_DB_PASSWORD`를 지정하지 않는다.

엔진 단독 읽기 진단은 애플리케이션 writer가 종료된 뒤 실행한다. 기존 색인을 덮어쓰지 않으며 출력 파일도 새 파일만 허용한다.

```sh
YONA_PROBE_INDEX_ONLY=/absolute/probe/data/search/issues \
YONA_PROBE_ENGINE_OUTPUT=/tmp/new-engine-metrics.json \
YONA_PROBE_QUERIES=오류,수정,missingneedle92817 \
./gradlew searchResourceProbe --init-script support-script/search-poc/resource-probe.gradle
```

최초 고정 창·큐·검색·백업 관련 H2 회귀 테스트는 494개가 통과했다. 후처리 최적화에서 대량 hit 회귀 테스트를 추가했다. MariaDB 측정에서는 복원 후 이슈/댓글 수 동일, Lucene 실제 backend 사용, 유휴 신규 job 0, 증분 10건/1개 job 및 갱신 내용의 색인 hit를 검증했다.

측정 전용 MariaDB 컨테이너와 4회 복원한 임시 파일 복사본은 측정 후 정리했다. 집계 JSON과 각 실행의 측정 결과는 보관했고, 엔진 진단용 색인 사본은 접근 권한을 제한해 별도로 보관했다. 평가 입력은 변경하지 않았다.

후속 최적화 측정의 전용 DB 컨테이너와 복원 파일 사본도 정리했다. 각 실행의 `/tmp/yona-search-optimized-{lucene-r1|db-r1|lucene-r2}/metrics.json`과 저장소 집계 JSON은 보관한다.

이번 stale 정책 측정의 전용 컨테이너와 복원 파일 사본도 정리했다. `/tmp/yona-search-stale-{lucene-r1|db-r1|lucene-r2}/metrics.json`과 저장소 집계 JSON은 보관하며, 평가 입력은 변경하지 않았다.

페이지 조회 최적화 측정의 전용 컨테이너·복원 파일 사본도 정리했다. `/tmp/yona-search-page-{lucene-r1|db-r1|lucene-r2}/metrics.json`과 저장소 집계 JSON은 보관한다. 평가 입력은 변경하지 않았다.
