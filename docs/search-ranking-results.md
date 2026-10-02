# #843 검색 순위 개선

기준선: `1a85fc396`, 표본 기준: docs/issue-843-lucene-poc의 `identifierAfter` (seed 843).
기본 DB 검색과 `yona.search.backend=db`는 변경하지 않는다.

## 측정 환경

`YONA_PROBE_ARCHIVE`, `YONA_PROBE_JDBC`, `YONA_PROBE_DB_PASSWORD`, `YONA_PROBE_OUTPUT`이 모두 없어 실제 데이터 품질 측정은 실행하지 않았다. 대체 데이터로 품질 수치를 만들지 않았다. 측정 도구는 docs 브랜치에서 복원했지만 커밋하지 않는다. 컨테이너나 archive 복원본은 생성하지 않았고 평가 입력은 건드리지 않았다.

기준선 전체: MRR 0.375937, 상위 5/10/20건 47.878968% / 56.748739% / 66.004153%, 찾은 비율 96.143578%, 결과 건수 p50/p90 21/448, 첫 페이지 p50/p95 10.586375/42.350834ms, 색인 8,375,012 bytes. exact MRR은 0.341329 (LIKE 0.661872).

## 1단계: 보조 결과 BM25

- Nori 검색 후 Nori 일치를 제외한 보조 검색을 실행하여 이어 붙인다. 보조 결과끼리는 자체 BM25 순서를 따른다.
- `auxiliaryOnly`는 점수가 아니라 검색 단계로 결정한다. `query()`는 Nori 쿼리만 반환하며 기존 스니펫 생략 처리는 유지한다.
- H2 회귀 506개 통과(기존 505개와 신규 1개), 실패·오류·건너뜀 0. 기존 Nori 점수와 순서 보존도 검증했다.
- 별도 JVM에서 실제 디스크 색인·검색 실행: Nori ID 3 다음 보조 ID 2, 1 및 단계 플래그 확인.
- 실제 표본의 Nori 순위 하락 건수와 모든 품질·성능 지표는 미측정. 효과 없음 또는 악화를 판정할 근거가 없어 되돌리지 않았다.

## 재현

H2 대상과 init script는 docs 브랜치 `docs/search-poc.md`의 검증 절을 따른다.

```sh
./gradlew test -Dyona.it.db=h2 --init-script /tmp/yona-843-ranking-tests.gradle --console=plain
```

실제 데이터 측정은 docs 브랜치 `docs/search-quality-measurement.md`의 재현 절을 따른다. 매 실행 빈 MariaDB 10.11 DB와 worktree 밖 출력 경로가 필요하다. 검색어 원문이나 검색어별 결과는 커밋하지 않는다.
