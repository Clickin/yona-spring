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

### 원본 접속과 SVN 복제

원본은 정확히 허용된 HTTPS origin의 **저장소 루트**여야 한다. 하위 디렉터리, userinfo,
query/fragment, HTTP, file, svn+ssh, redirect는 허용하지 않는다. 모든 DNS 주소를 검사하고,
사설·loopback·link-local·multicast 등 비공개 주소는 명시적인 CIDR 예외가 없으면 거부한다.

DNS 사전 검사는 connect-time 주소 고정을 대신하지 않는다. 활성화에는 실제
**목적지 제한 CONNECT 프록시**가 필수다. SVNKit은 설정된 loopback 프록시로만 연결하며 직접
연결로 fallback하지 않는다. 운영자는 프록시가 매 CONNECT마다 정확한 호스트/포트와 실제 IP를
검사·고정하도록 설정하고, 직접 원본 egress도 차단해야 한다. 호스트 이름만 검사하고 나중에
다시 DNS 조회하는 프록시는 이 조건을 충족하지 않는다. `egress-restricted=true`는 그 배포
조건의 확인이지 애플리케이션 자체의 DNS pinning 구현을 뜻하지 않는다.

```yaml
yona:
  repository-mirror:
    enabled: false
    egress-restricted: false
    proxy-host: "127.0.0.1"
    proxy-port: 3128
    node-id: "writer-a"
    writer-node-id: "writer-a"
    allowed-origins: ["https://svn.example.org:443"]
    allowed-cidrs: []
    credentials:
      svn-reader: "/run/secrets/svn-reader.properties"
```

프록시 제한·TLS 신뢰·writer 경로를 검증한 뒤 두 boolean을 `true`로 바꾼다. 인증서는 JVM
truststore와 HTTPS 호스트명 검증을 통과해야 한다. 필요한 사설 CA는
`javax.net.ssl.trustStore`/`javax.net.ssl.trustStorePassword`로 제공하며 인증서 검증을 끄지 않는다.

credentialRef는 위 map의 논리 이름만 받는다. 파일은 `username`/`password` 두 키를 가진
UTF-8 Java properties 형식이며, 절대 real path, symlink 없음, 프로세스 사용자 또는 root 소유,
0400/0600 권한이어야 한다. properties의 backslash escape 규칙이 적용된다. 읽기 전용 SVN
계정을 사용하고 secret provisioning/rotation은 운영자가 수행한다. 화면·DB에는 비밀번호를
저장하지 않으며 SVNKit 디스크 자격증명 cache도 사용하지 않는다.

복제는 SVNKit `SVNRepositoryReplicator`를 사용하고 실행마다 목표 HEAD를 고정한다.
재시작하면 local youngest를 직접 읽어 이미 commit됐지만 미검증인 구간의 author/date/log와
custom 속성 및 삭제를 먼저 보정한다. 복제 함수의 성공만으로 검증 커서를 올리지 않는다.
SVNKit 1.10.11의 마지막 속성 삭제가 revprops 파일까지 지우는 동작을 피하도록 추가 후
삭제하며, 속성이 전혀 없는 유효한 r0은 FSFS 잠금 아래 빈 END hash 파일로 보존한다.

r1 이후 날짜는 유효한 SVN 날짜여야 하고 author는 최대 255 UTF-16 code units다.
초과·잘못된 날짜는 잘라내거나 현재 시각으로 대신하지 않고 검증 커서 이전에서
`NEEDS_ATTENTION`으로 멈춘다. 원본 운영자가 해당 속성을 고친 뒤 재시도하면 미검증
구간을 보정한다. UUID 변경, 원본 HEAD 감소, 대상 손상에는 자동 삭제/재생성을 하지 않는다.
이미 검증한 과거 속성 수정은 지속해서 탐지·재색인하지 않으며, 발견한 불일치는 운영자 확인으로
보낸다. 임의 cursor rewind는 지원하지 않는다.

### 이슈 참조와 알림

검증한 revision의 이슈 참조, `>R`인 경우의 DB NEW_COMMIT 알림, 색인 커서 갱신은 같은
fenced transaction에서 처리한다. rollback이면 전부 rollback하며 재실행은 현재 커서 다음부터
이어간다. 같은 revision의 이슈 번호는 중복 제거한다. 별도 이벤트 ledger/unique 컬럼은 없다.

`<=R`에서는 원본 author와 시각으로 참조만 만들고 알림을 보내지 않는다. 알 수 없는 author를
Yona 계정으로 가장하지 않는다. `>R`의 알림은 inbox/mail이 프로젝트를 찾도록 PROJECT와
project ID를 대상으로 하며, 참조·웹훅의 commit ID는 숫자 문자열이다. 화면에서만 r 접두사를 쓴다.
기존 watcher 정책에 따라 수신자가 없으면 DB 알림도 없다. Git/Hg의 기존 발신자·payload는
바꾸지 않는다.

SVN 웹훅은 DB commit 뒤 기존 `gitPush` 설정에 따라 best-effort로 보낸다. crash나 네트워크
응답 유실에 따른 유실/중복 가능성이 있으며, 색인 커서는 원격 수신 완료를 뜻하지 않는다.
웹훅 실패 때문에 복제나 색인을 되돌리지 않는다.

### 실행 제한과 물리 배타성

짧은 dispatcher가 due 행을 찾고 slot 확보 → 조건부 claim → 전용 executor 제출 순서로
동작한다. 기본 worker 2개(최대 8), revision batch 50개, 임대 60초, 성공 후 재동기화 간격
60초다. 긴 SVN I/O는 공용 scheduler나 DB transaction 안에서 실행하지 않는다.
claim/heartbeat/checkpoint/완료는 DB 시각, owner, 증가하는 fence로 확인한다.

파일 writer는 `.mirror-locks/<projectId>.lock`을 작업 전체 동안 보유한다. 임대 상실은
기존 스레드가 멈췄다는 증거가 아니므로, 취소를 요청해도 스레드가 반환하기 전에는 잠금을
해제하지 않는다. 종료도 새 작업을 막고 실제 writer 종료를 기다린다. 이름 잠금은
`yona.data/repository-names/`를 사용한다. 두 종류의 잠금 파일은 실행 중 rename/delete/recreate하지 않는다.

기본 운영 형태는 지정된 단일 writer와 로컬 파일시스템이다. 여러 노드가 같은 canonical
저장소·예약 표식·잠금 inode를 공유하고 OS locking을 보장하는지 검증하지 않은 상태에서
자동 failover를 활성화하지 않는다. 독립 디스크의 다중 노드를 DB 임대만으로 지원하지 않는다.

일시적 네트워크 오류는 5초부터 지수 backoff(상한 15분), 연속 5회 후 FAILED다.
인증/TLS/원본 정책 오류는 즉시 FAILED, 정합성이나 물리 배타성 문제는 NEEDS_ATTENTION이다.
오류에는 원격 예외 원문이나 secret을 기록하지 않는다.

