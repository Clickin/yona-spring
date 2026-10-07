# Lucene PoC 리소스 측정

2026-10-02. 첫 변경 후 2초의 전역 고정 배치 창을 적용한 코드로 측정했다. 후속 변경은 deadline을 연장하지 않는다. `yona.search.batch-window-millis=2000`, `poll-millis=500`이다. 기준선은 같은 빌드에서 `backend=db`로 실행한 DB 검색이다.

이 측정은 코드 이름 보조 필드를 추가하기 전 코드로 했다. 색인 파일 크기는 [품질 측정](search-quality-measurement.md)에서 다시 쟀고, 초기 색인 시간과 heap은 다시 재지 않았다.

## 결과

### 검색 응답 시간

같은 평가 데이터(이슈 4,882건, 댓글 6,335건), 동일 JVM, 20건 관련도 페이지, 검색어별 5회 예열 후 20회 관측이다. Lucene → DB → Lucene 순서로 새 JVM·빈 DB에서 실행했다. Lucene 칸은 두 실행의 최솟값–최댓값이며 신뢰구간이 아니다. 원시 결과는 [JSON의 `pagedRuns`](search-resource-results.json)에 있다.

| 검색어 | hit 수(DB / Lucene) | Lucene p50 / p95 | DB p50 / p95 |
| --- | ---: | ---: | ---: |
| 오류 | 445 / 445 | **16.20–20.34 / 47.36–47.44ms** | 38.90 / 41.66ms |
| 수정 | 1,539 / 1,533 | **23.21–24.92 / 25.72–51.37ms** | 35.82 / 39.17ms |
| 불일치 검색어 | 0 / 0 | **0.60–1.02 / 1.41–1.48ms** | 27.72 / 28.78ms |

두 Lucene 실행 모두 p50은 DB보다 낮았다. 그러나 **p95는 일관되지 않다**. `오류`의 p95는 두 번 모두 DB보다 높고, `수정`도 한 번은 DB보다 높았다. 20회 관측과 JVM 2회는 운영 지연 분포나 통계적 신뢰구간이 아니다. 시간이 줄어든 비중은 구간별 profiler로 나누지 않았다.

2,105개 hit 중 20건 페이지에서 이슈 엔티티 로딩은 DB 정렬과 관련도순 모두 20건이고 댓글 조회는 페이지당 1회임을 Hibernate entity 통계로 검증했다.

### 리소스

| 지표 | DB 모드 | Lucene 모드 |
| --- | ---: | ---: |
| 색인 대상 텍스트 | 8.94 MiB | 동일 |
| 초기 색인 완료 관찰 | 없음 | 2.06–3.12초 |
| 색인 전후 live heap 증가(동일 JVM) | 약 0 | 16.96–17.11 MiB |
| 검색 후 live heap | 98.35 MiB | 117.68–119.22 MiB |
| 검색 후 RSS | 594.94 MiB | 676.89–786.03 MiB |
| 유휴 10초 신규 색인 job | 0 | **0** |
| 10건 수정 후 색인 | 해당 없음 | **1개 job / 10개 ID**, 2.56–2.58초에 완료 관찰 |
| Lucene 색인 파일 | 0 | 4.72 MiB (4,944,547 bytes) |

검색 후 heap과 RSS는 대형 archive 복원과 같은 JVM에서 잰 값이다. Lucene의 고정 메모리 요구량으로 일반화하지 않는다.

애플리케이션 배포물에는 Lucene 9.12.3 및 Nori 사전 등 전이 의존성 6개 JAR, 합계 **13.92 MiB**(14,601,137 bytes)가 추가된다. 현재 빌드는 이 JAR들을 선택적으로 패키징하지 않으므로 `backend=db`라도 배포 파일 크기는 늘어난다.

### 운영상 해석

- 변경 없는 시간에는 새 검색 job·Lucene 갱신·commit을 요청하지 않는다. 그러나 DB window 한 행 조회는 약 2회/초 남고 기존 queue worker도 활동하므로 전체 CPU/DB 작업이 0은 아니다. 프로세스 시작과 수동 요청의 전체 복구 색인은 별도다.
- 변경마다 dirty ID·generation을 원본 트랜잭션에서 기록하는 DB 비용은 남는다. 작업 실행 수를 줄이는 것과 변경 기록 자체를 없애는 것은 다르다.
- 이 데이터에서 색인 파일은 원문 UTF-8 크기의 약 53%였다. 어휘·문서 길이·댓글 수와 segment 병합 상태에 따라 달라지므로 문서 수나 5 GiB 백업 크기만으로 디스크를 산정하지 않는다. 복구·재색인·merge 중 임시 디스크 여유는 포함하지 않았다.
- 단일 검색 클라이언트, 전체 프로젝트를 볼 수 있는 관리자, 첫 페이지 20건 기준이다. 동시 읽기 부하·권한 복잡도·다중 노드·재시작 재색인·검색 품질의 운영 검증을 대체하지 않는다.

### 범위와 남은 제한

- 실측은 관리자 권한·관련도순이다. 일반 사용자의 복잡한 권한 SQL, 날짜순의 성능, 동시 부하는 별도 측정이 필요하다. 두 정렬의 결과와 엔티티 로딩 제한은 H2 회귀로 확인했다.
- 관련도순은 허용된 전체 ID를 읽고 Lucene도 전체 hit를 반환한다. 본문 전체 로딩은 없지만 대규모 ID 목록 비용은 남는다.
- 머리말 검색은 후보 ID·제목 전체를 읽어 판정하고 최종 페이지의 본문만 읽는다. unpaged 내보내기는 전체 내용을 읽는다. 다음 페이지 동작과 요청 간 변경 시 한계는 [PoC 문서](search-poc.md#다음-페이지와-머리말-필터)에 있다.
- H2와 평가 데이터를 복원한 MariaDB를 검증했다. 나머지 지원 DB의 회귀는 별도다. 권한 정책이 바뀌면 기존 판정과 SQL 조건 및 대조 테스트를 함께 갱신해야 한다.

## 데이터와 복원

평가 입력을 기존 `DataBackupService.importSite`로 **새 임시 DB와 파일 경로**에 복원했다. 입력은 변경하지 않는다. 원본 텍스트·사용자·첨부파일·자격증명·전체 로그는 이 문서나 저장소에 포함하지 않는다.

- archive 기준: 이슈 4,882건, 댓글 6,335건, 프로젝트 4개.
- 첨부파일·저장소는 Lucene 이슈 색인 대상이 아니다. 제목·본문·댓글의 UTF-8 바이트를 별도로 측정한다.
- H2 복원은 알림 이력의 `new_value`가 100만 자 제한을 초과하여 실패했다. 데이터를 잘라내거나 측정용으로 importer 검증을 완화하지 않고 MariaDB 10.11의 별도 컨테이너를 사용한다.

## 방법

[측정 드라이버](../src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchResourceProbe.kt)와 [Gradle init script](../support-script/search-poc/resource-probe.gradle)는 기존 Yona 애플리케이션을 실행한다. 별도 검색 엔진 모형이 아니다. 테스트 runtime classpath에서 실행하지만 CI 테스트에는 포함되지 않는 opt-in main이다.

- 환경: macOS 26.5.2 arm64, JVM에 보이는 CPU 10개, Temurin 21.0.6, MariaDB 10.11 컨테이너.
- 각 모드는 별도 JVM·DB·출력 디렉터리에서 순차 실행. JDK 21, G1, `-Xms256m -Xmx512m`. 측정 중 다른 모드의 부하를 동시에 걸지 않는다.
- 복원·앱 시작은 측정 구간 밖이다. 초기 색인은 큐 제출부터 완료 관찰까지 wall time을 기록한다.
- heap은 명시적 GC 후의 live heap 관측값, RSS는 같은 시점의 `ps` 값이다. 최대 RSS·순간 할당량·전체 머신 메모리나 컨테이너 한계가 아니다. JVM·JIT·OS 페이지 회수에 따라 RSS 편차가 크다.
- 실제 `IssueSearchService`를 관리자 권한으로 호출한다. DB 조건·권한 확인·snippet 생성·페이지의 댓글 digest 비교·20건 페이지를 포함하며 HTTP·템플릿 렌더링 시간은 제외한다. 검색어별 5회 warm-up 후 20회 관측, nearest-rank p50/p95.
- SQL LIKE와 Nori 검색은 의미가 달라 동일 검색어의 hit 수가 다를 수 있다. 결과 수를 함께 기록하며 동일 작업량의 엔진 속도 비교라고 주장하지 않는다.
- 검색 admission 스케줄러의 자동 실행만 늦추고 드라이버에서 동일 `schedule()`을 500ms 간격으로 호출한다. 실제 durable queue worker가 작업을 수행한다. DB 모드에는 검색 스케줄러 bean이 없다.
- 유휴 10초 동안 검색 job 수와 index `lastSuccess`가 변하지 않는지 assert한다. Lucene 모드는 이 시간에도 변경 시각 한 행을 20회 확인한다. 기존 queue worker 등 애플리케이션의 다른 활동은 양쪽 모두 유지한다.
- 첫 10건의 본문을 한 트랜잭션에서 수정하고 commit 이후 큐 완료까지 관찰한다. 이 값에는 2초 창, polling 오차, queue 대기, DB 조회와 Lucene commit이 포함된다. 완료 관찰 해상도는 500ms이다.
- MariaDB 측정에서는 복원 후 이슈/댓글 수 동일, Lucene 실제 backend 사용, 유휴 신규 job 0, 증분 10건/1개 job 및 갱신 내용의 색인 hit를 검증했다.

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

측정 후 전용 MariaDB 컨테이너와 임시 복원본은 삭제했다. 각 실행의 `metrics.json`과 저장소 집계 JSON은 보관한다. 평가 입력은 변경하지 않았다.
