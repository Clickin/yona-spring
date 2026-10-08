# 기술 문서

legacy Yona의 `docs/ko/technical/` + `docs/technical/`(두 세트를 합침, 실제로는 겹치지 않는
서로 다른 11개 + 5개 문서였다)를 yona 기준으로 옮긴 기술 참고 문서. 성격이 다른 두 부류로
나뉜다.

## 지금도 그대로 적용되는 문서 (코드로 확인)

- [access-control.md](access-control.md) — 권한 규칙(비즈니스 로직, 프레임워크 무관)
- [javascript-module-guide.md](javascript-module-guide.md) — `yobi.*` JS 모듈 패턴
- [javascript-naming-convention.md](javascript-naming-convention.md) — JS 네이밍 규칙
- [views-naming-guide.md](views-naming-guide.md) — 템플릿 파일 명명 규칙
- [pagination.md](pagination.md) — Range 헤더 기반 페이지네이션
- [uploader-client.md](uploader-client.md) — 첨부파일 업로드 JS 클라이언트
- [uploader-server-internal.md](uploader-server-internal.md) — 첨부파일 업로드 서버 API
- [webhook-server-internal.md](webhook-server-internal.md) — 웹훅 등록/발동
- [label-typeahead.md](label-typeahead.md) — 라벨 자동완성 API
- [markdown.md](markdown.md) — 마크다운 렌더링 방식
- [mailbox.md](mailbox.md) — IMAP 메일함 처리 알고리즘
- [watch.md](watch.md) — Watch/알림 대상 결정 알고리즘
- [name-validation.md](name-validation.md) — 이름 검증 설계 지침

## legacy와 아키텍처 자체가 달라진 부분을 설명하는 문서

- [current-user.md](current-user.md) — Play 세션/토큰 방식 → Spring Security 세션/Remember-Me
- [validation-with-annotation.md](validation-with-annotation.md) — 애노테이션 기반 권한 검사
  → 컨트롤러 내 직접 호출
- [view-hierarchy.md](view-hierarchy.md) — `.scala.html` include 트리(파일명만 기계적으로
  치환, 전수 재검증은 아직 안 함 — `docs/TEMPLATE_BACKLOG.md`가 더 신뢰할 수 있는 소스)

## SVN 읽기 전용 미러

### 저장소 모드와 업그레이드

`Project.repositoryMode`는 VCS와 별개인 `HOSTED`/`MIRROR`이며 일반 프로젝트 JSON에는
노출하지 않는다. 기존 행은 Hibernate 스키마 갱신 전에 `HOSTED`로 채우고, 컬럼의 기본값과
NOT NULL을 확인한다. 대상 DB는 H2, MariaDB, PostgreSQL, MySQL, SQL Server, CUBRID다.
`RepositoryModeMigrationSpec`은 이전 스키마와 컬럼만 추가된 중단 상태를 만들고 재실행을 검사한다.

`yona.repository-mirror.enabled`의 기본값은 `false`다. 꺼도 이미 저장된 `MIRROR`의 쓰기
거부는 유지된다. HTTP/SSH, 웹/API, 실제 저장소 writer가 최신 DB 모드를 확인하며 관리자도
우회하지 못한다. 별도 위키 저장소와 일반 게시글은 계속 쓸 수 있다. 새 미러는 SVN만 지원한다.
hg4j의 중첩 batch/pushkey 경로를 안전하게 허용할 수 없어, 지원하지 않는 Hg MIRROR의 SSH
세션과 HTTP batch/v2 multirequest는 읽기 전용 요청을 포함해 거부한다. HOSTED 분류는 바꾸지 않는다.

프로젝트 이름에는 DB unique 제약이 없다. 생성·개명·이전·포크는 이름별 파일 잠금을 DB
transaction 완료까지 유지하고 READ_COMMITTED에서 목적지를 다시 확인한다. Git clone도 같은
이름 잠금을 사용하며 기존 목적지를 덮어쓰지 않는다. 미러 예약 표식은 SVN 루트의
`.mirror-owners/<이름 해시>.owner`에 먼저 기록하고 디렉터리를 만든다. DB rollback 뒤 표식이나
디렉터리가 남으면 일반 프로젝트 생성도 이를 인수하지 않는다. 운영자가 DB 행, 표식의
project ID/generation/경로, 실제 디렉터리를 대조해야 하며 자동 삭제하지 않는다.

업그레이드는 **구버전 writer 전부 중지 → DB·저장소·예약 표식 백업 → 한 신버전 노드에서
스키마 갱신 → 기본값/NULL/기존 데이터 확인 → 신버전 기동** 순서다. 혼합 버전 rolling upgrade는
지원하지 않는다. 기능을 끄는 것은 스키마나 모드의 rollback이 아니다. 구버전은 미러를 쓸 수
있으므로, 이전 버전으로 되돌릴 때는 writer를 중지하고 업그레이드 전 DB와 저장소 백업을 함께
복원한다. mirror 테이블만 삭제하거나 모드를 HOSTED로 바꾸지 않는다.

### 영속 상태와 체크포인트

프로젝트당 `RepositoryMirror` 행 하나가 실행의 근거다. 초기 원본 HEAD를 `R`로 저장한 뒤
바꾸지 않는다. source UUID와 local UUID는 각각 기록하며 둘이 같을 필요는 없다.
`lastIndexedRevision <= lastVerifiedRevision <= localYoungestRevision`을 유지하고, `-1`은
아직 확인하지 않았다는 뜻이다. r0도 속성을 검증하지만 데이터 참조는 r1부터 만든다.

상태는 `INITIAL_IMPORT`, `INCREMENTAL`, `FAILED`, `NEEDS_ATTENTION`이다. 일시정지는
`enabled=false`로 표현하여 복귀할 단계를 보존하고, 실행 여부는 임대로 표시한다.
재시도는 R·generation·검증/색인 커서를 초기화하지 않으며 유효한 임대를 빼앗지 않는다.
원본 URL/UUID를 다른 저장소로 바꾸는 기능은 없고, 다른 원본에는 새 미러를 만든다.

