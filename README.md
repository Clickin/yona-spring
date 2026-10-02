# Yona #843 Lucene 검색 PoC — fork 전용 기록

이 브랜치는 **문서와 벤치마크 재현 자료만 보관하는 독립된 root 이력**이다. 구현 브랜치의 부모나 조상이 아니며 upstream에 merge하지 않는다. 향후 upstream PR에는 구현 브랜치만 사용한다. 구현 커밋에는 이 문서·측정 JSON·측정 드라이버가 포함되어 있지 않다.

- Fork: [Clickin/yona-spring](https://github.com/Clickin/yona-spring)
- 구현 브랜치: [`poc/issue-843-lucene-search`](https://github.com/Clickin/yona-spring/tree/poc/issue-843-lucene-search)
- 문서 브랜치: `docs/issue-843-lucene-poc`
- 문서가 설명하는 구현: [`923fd6411`](https://github.com/Clickin/yona-spring/commit/923fd641106467f56d85bfecf9bca2da688a270c) (속도·자원 측정은 직전 [`4e7fb5795`](https://github.com/Clickin/yona-spring/commit/4e7fb579504c39907bdf70f7a71cb4beb0abb5f3))
- 기반 커밋: `8c6675e64118fc0fef927f49f418136a30e88cde`
- 검증일: 2026-10-03. H2 통합·회귀 **500개 통과**.

## 읽을 문서

1. [검색 동작·설정·운영·페이징](docs/search-poc.md)
2. [검색 품질 측정: LIKE로 못 찾는 검색어](docs/search-quality-measurement.md) · [집계 JSON](docs/search-quality-results.json)
3. [속도·리소스 측정](docs/search-resource-measurement.md) · [집계 JSON](docs/search-resource-results.json)
4. [배치/refresh 정책 사례 조사](docs/search-indexing-policy-research.md)
5. [stale 문서 처리 사례 조사](docs/search-stale-document-research.md)

## 현재 동작

기본값은 DB 검색이며 `yona.search.backend=lucene`을 설정하면 단일 노드의 공유 디스크 색인을 사용한다. 공개·비공개 프로젝트의 이슈 제목·본문·댓글을 모두 색인한다. 검색 시 현재 DB 권한·라벨·담당자·작성자 등 기존 조건을 적용한다. DB 정렬은 DB에서 count/page를 계산하고, 관련도순은 허용된 ID를 Lucene 순서로 정렬한 뒤 해당 페이지의 본문만 읽는다.

원본 쓰기와 같은 DB 트랜잭션에 변경 ID와 generation을 기록한다. 최초 변경 후 2초의 전역 창으로 묶고 기존 durable queue가 최신 DB 내용을 읽어 색인을 갱신한다. 추가 변경으로 deadline을 연장하지 않으며, 성공적으로 게시한 배치의 generation만 ack한다. 유휴 시 window 한 행 polling은 남지만 전체 이슈 대조·Lucene commit·새 색인 job은 발생하지 않는다. 전체 대조는 시작·수동 재색인·백업 복원에 사용한다. 상태·실패·재시도는 기존 `/site/admin/queue`에 통합되어 있다.

텍스트 일치는 마지막 게시된 색인을 따른다. 수정 후 이전 검색어로 잠시 남거나 새 검색어로 늦게 검색될 수 있다. 현재 DB에 없는 이슈와 접근 불가 이슈는 제외한다. 스니펫은 현재 페이지의 DB 내용으로 만들며, 색인과 지문이 다르면 강조문·댓글 링크를 생략한다. 여러 HTTP 페이지에 걸친 snapshot/cursor는 제공하지 않는다. 요청 사이 데이터·권한·색인이 바뀌면 total과 순서도 바뀔 수 있다.

머리말은 이슈·게시글·PR 모두 후보 ID·제목만 읽어 정확히 판정하고 페이지의 본문만 읽는다. 제목 중간의 머리말 표기는 제외한다. SQL 권한과 기존 판정의 일치, 연속 페이지·마지막·범위 밖 페이지 및 본문 로딩량은 회귀 테스트에 포함한다.

## 검색 품질 요약

평가 데이터의 이슈 300개에서 원문을 변형한 검색어 1,731개로 원래 이슈를 찾는지 쟀다. 원래 이슈를 찾은 비율이다.

| 검색어 유형 | 현행 LIKE | 단어별 LIKE AND | Lucene |
| --- | ---: | ---: | ---: |
| 원문 그대로 연속 두 단어 | 100% | 100% | 95.6% |
| 떨어진 두 단어 / 어순 바꿈 | 0% | 100% | 96.5% / 93.6% |
| 활용형 / 조사 바꿈 | 0% | 1.2% / 8.0% | 97.5% / 96.7% |
| 띄어 쓴 두 단어를 붙임 | 0% | 0% | 93.0% |
| 식별자 구분자를 공백으로 | 0% | 100% | 100% |
| 식별자 일부 (8건) | 100% | 100% | 0% |
| **전체** | 17.7% | 63.3% | **95.3%** |

첫 페이지 p50은 LIKE 29.48ms, 단어별 LIKE AND 43.26ms, Lucene 5.16ms다. Lucene은 결과가 넓어(중앙값 30건) 원래 이슈가 첫 20건에 드는 비율은 63.3%다. 이 측정에서 검색어를 단어마다 따로 분석하던 결함을 찾아 `923fd6411`에서 고쳤다(전체 93.1% → 95.3%).

## 속도·자원 요약

평가 데이터(약 5 GiB)을 별도 MariaDB 10.11에 복원했다. 이슈 4,882건·댓글 6,335건, 색인 대상 텍스트 8.94 MiB다. 아래는 관리자 권한·관련도순·20건 페이지, JDK 21/G1/`-Xms256m -Xmx512m`, 검색어별 5회 예열 후 20회 관측의 서비스 p50이다. Lucene은 새 JVM 2회 범위이고 DB는 같은 순차 측정의 기준선이다.

| 검색어 | Lucene p50 | DB p50 | hit 수 Lucene / DB |
| --- | ---: | ---: | ---: |
| 오류 | 16.20–20.34ms | 38.90ms | 445 / 445 |
| 수정 | 23.21–24.92ms | 35.82ms | 1,533 / 1,539 |
| 불일치 검색어 | 0.60–1.02ms | 27.72ms | 0 / 0 |

색인 파일은 2.82 MiB, 초기 색인 전후 live heap 증가는 약 17 MiB, 추가 배포 JAR는 14.95 MiB였고 `923fd6411`에서 queryparser 의존성을 빼 13.92 MiB가 됐다. 고정 메모리 요구량이나 운영 최소 RAM을 뜻하지 않는다. 유휴 10초 신규 색인 job 0건, 10건 수정은 1개 job/10개 ID로 반영했다. p95는 DB보다 높은 실행도 있었고, 일반 사용자의 복잡한 ACL·동시 부하·대규모 데이터에 일반화하지 않는다.

이 시간 측정은 LIKE로도 찾을 수 있는 한 단어 검색어로, **머리말 projection과 검색어 분석 수정 전 개발 스냅샷**에서 쟀다. 최종 구현의 머리말 경로는 53개 이슈의 20·20·13·0건, 게시글·PR 33건의 15·15·3·0건 페이지와 같은 수의 엔티티 로딩을 검증했다. 머리말 경로의 평가 데이터 p50/p95를 새로 측정한 것은 아니다. 이전 단계 수치와 측정별 조건은 상세 문서·JSON에 보존했다.

## 독립 작업 디렉터리에서 재현

벤치마크 도구는 이 브랜치에만 있다. 구현 브랜치를 별도 임시 worktree로 열고 측정 파일만 가져온다. 원본 DB를 대상으로 실행하지 않고, 빈 전용 DB와 읽기 전용 archive를 사용한다.

```sh
git fetch origin docs/issue-843-lucene-poc poc/issue-843-lucene-search
git worktree add --detach /tmp/yona-search-benchmark 923fd641106467f56d85bfecf9bca2da688a270c
git -C /tmp/yona-search-benchmark restore --source=origin/docs/issue-843-lucene-poc --worktree -- \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchResourceProbe.kt \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchQualityProbe.kt \
  support-script/search-poc/resource-probe.gradle
cd /tmp/yona-search-benchmark
# 전용 DB 및 YONA_PROBE_* 설정은 상세 측정 문서 참고
./gradlew searchQualityProbe --init-script support-script/search-poc/resource-probe.gradle   # 검색 품질
./gradlew searchResourceProbe --init-script support-script/search-poc/resource-probe.gradle  # 속도·자원
```

측정 도구는 기존 Yona 빌드와 서비스를 실행하며 독립 구현이 아니다. 가져온 파일은 재현용 임시 worktree의 미추적 파일이다. 구현 PR에 추가하지 않는다. 원본 백업·사용자/본문·첨부파일·자격증명·검색어 원문·전체 실행 로그는 이 브랜치에 포함하지 않는다.
