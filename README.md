# Yona #843 Lucene 검색 PoC 기록

[#843](https://github.com/yona-projects/yona/issues/843) 제안(이슈 전문 검색, 기본은 DB, 선택으로 Lucene)을 구현하고 측정한 기록이다. 구현 코드는 [`poc/issue-843-lucene-search`](https://github.com/Clickin/yona-spring/tree/poc/issue-843-lucene-search)에 있고, 이 브랜치에는 문서와 측정 도구만 둔다. upstream에 merge하지 않는다.

- 구현 커밋: [`2ee28a5`](https://github.com/Clickin/yona-spring/commit/2ee28a5a1510ff68d6a1fee7db606a04362b8053)
- 테스트: H2 통합·회귀 500개 통과 (2026-10-03)
- 측정 데이터: 평가 데이터. 이슈 4,882건, 댓글 6,335건, 검색 대상 텍스트 8.94 MiB

## 결과 요약

### 검색 품질

이슈 300개를 뽑아 본문 문장을 바꿔 쓴 검색어 1,731개를 만들고, 원래 이슈를 찾는지 쟀다.

| 검색어 | 현행 LIKE | 단어별 LIKE AND | Lucene |
| --- | ---: | ---: | ---: |
| 원문 그대로 두 단어 | 100% | 100% | 95.6% |
| 떨어진 두 단어 | 0% | 100% | 96.5% |
| 어순을 바꾼 두 단어 | 0% | 100% | 93.6% |
| 활용형을 바꿈 (`배포했습니다` → `배포된`) | 0% | 1.2% | 97.5% |
| 조사를 바꿈 (`오류가` → `오류를`) | 0% | 8.0% | 96.7% |
| 띄어 쓴 단어를 붙임 (`권한 설정` → `권한설정`) | 0% | 0% | 93.0% |
| 식별자 구분자를 공백으로 (`user_id` → `user id`) | 0% | 100% | 100% |
| 식별자 일부 (`PointerException`, 8건) | 100% | 100% | 0% |
| **전체** | 17.7% | 63.3% | **95.3%** |

Lucene의 약점은 두 가지다. 식별자 일부로는 찾지 못한다. 결과가 넓어서(중앙값 30건) 원래 이슈가 첫 페이지 20건 안에 드는 비율은 63.3%다.

### 속도

같은 검색어로 첫 페이지 20건을 가져오는 시간이다.

| | 현행 LIKE | 단어별 LIKE AND | Lucene |
| --- | ---: | ---: | ---: |
| p50 | 29.48ms | 43.26ms | 5.16ms |
| p95 | 48.37ms | 91.92ms | 21.93ms |
| 결과 없는 검색 p50 | 28.58ms | 28.64ms | 0.22ms |

### 자원

| 항목 | 값 |
| --- | --- |
| 초기 전체 색인 | 2.1–3.1초 |
| 색인 파일 | 2.82 MiB |
| 색인 후 heap 증가 | 약 17 MiB |
| 배포 JAR 추가 | 13.92 MiB (`backend=db`여도 포함) |
| 변경 없을 때 색인 작업 | 0건 (10초 관찰) |
| 이슈 10건 수정 반영 | 작업 1개, 약 2.6초 |

자원 수치는 관리자 권한, 단일 클라이언트로 잰 값이다. 운영 서버의 최소 RAM이나 동시 부하 성능을 나타내지 않는다.

## 동작

- 기본값은 DB 검색이다. `yona.search.backend=lucene`이면 Yona 프로세스 안의 Lucene 색인을 쓴다. 단일 노드 전용이다.
- 모든 프로젝트의 이슈 제목·본문·댓글을 색인한다. 검색할 때 권한, 라벨, 담당자 같은 조건은 현재 DB로 확인한다.
- 이슈나 댓글을 저장하면 같은 트랜잭션에 "이 이슈가 바뀌었다"는 기록을 남긴다. 첫 변경 후 2초가 지나면 기존 작업 큐가 모아서 한 번에 색인한다.
- 수정 직후 몇 초 동안은 이전 내용으로 검색될 수 있다. 화면에 보이는 제목과 본문은 항상 DB 기준이다.
- Lucene이 준비되지 않았거나 실패하면 DB 검색으로 돌아가고 화면에 표시한다.

자세한 내용은 [동작 문서](docs/search-poc.md)에 있다.

## 문서

| 문서 | 내용 |
| --- | --- |
| [search-poc.md](docs/search-poc.md) | 검색 동작, 설정, 색인 운영, 페이징 |
| [search-quality-measurement.md](docs/search-quality-measurement.md) | 검색 품질 측정 방법과 결과, 수정한 결함 |
| [search-resource-measurement.md](docs/search-resource-measurement.md) | 속도·자원 측정, 최적화 단계별 수치 |
| [search-indexing-policy-research.md](docs/search-indexing-policy-research.md) | Gitea, GitLab 등의 색인 갱신 방식 조사 |
| [search-stale-document-research.md](docs/search-stale-document-research.md) | 색인과 DB 내용이 다를 때의 처리 방식 조사 |

집계 수치는 `docs/*-results.json`에 있다. 검색어 원문, 이슈 본문, 사용자 정보는 넣지 않았다.

## 재현

구현 커밋을 임시 디렉터리에 열고 이 브랜치의 측정 파일을 가져와 실행한다. 빈 전용 DB가 필요하다. 환경변수는 각 측정 문서에 있다.

```sh
git fetch origin docs/issue-843-lucene-poc poc/issue-843-lucene-search
git worktree add --detach /tmp/yona-search-benchmark 2ee28a5a1510ff68d6a1fee7db606a04362b8053
git -C /tmp/yona-search-benchmark restore --source=origin/docs/issue-843-lucene-poc --worktree -- \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchResourceProbe.kt \
  src/test/kotlin/com/github/yonaprojects/yona/domain/issue/IssueSearchQualityProbe.kt \
  support-script/search-poc/resource-probe.gradle
cd /tmp/yona-search-benchmark
./gradlew searchQualityProbe --init-script support-script/search-poc/resource-probe.gradle   # 검색 품질
./gradlew searchResourceProbe --init-script support-script/search-poc/resource-probe.gradle  # 속도·자원
```
