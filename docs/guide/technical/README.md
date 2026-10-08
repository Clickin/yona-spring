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

## 읽기 전용 저장소 미러

### 공통 구조

외부 SVN·Git·Mercurial 저장소를 주기적으로 가져와 Yona 프로젝트로 제공하고, 커밋의 이슈 참조는
Yona에서 관리한다. VCS와 무관한 부분(모드와 쓰기 보호, 이름 예약, 임대·fence, 물리 잠금, 원본 접속
정책, 이슈 참조·알림·웹훅, 작성자 매핑, 관리 화면)은 공통이고, VCS별 부분은 `MirrorEngine`
구현이 담당한다. 엔진은 원본에서 가져와 검증한 커밋을 `MirrorCommitIndexer`에 넘기며, 이슈 참조와
알림을 직접 쓰지 않는다.

- 로컬 저장소는 해당 VCS의 hosted 경로를 그대로 쓴다: `<svn>/owner/name`, `<git>/owner/name.git`,
  `<hg>/owner/name`. 기존 읽기 경로가 그대로 서빙하고 쓰기는 모드로 거부된다. 단 Mercurial 미러는
  HTTP로만 clone·pull할 수 있다(SSH 미지원, 아래 참고).
- 진행 위치는 SVN·Mercurial은 선형 커서(`lastVerifiedRevision`, `lastIndexedRevision`, 고정 목표 R),
  Git은 ref별 색인 위치(`repository_mirror_ref`)다. Git의 새 커밋은 가져온 ref에서 닿고 색인한 어느
  위치에서도 닿지 않는 커밋이다.
- `initialImportDone`은 첫 가져오기의 검증·색인이 끝나면 켜지고 되돌리지 않는다. 그 전의 커밋은
  참조만 만들고, 이후 커밋부터 알림과 웹훅을 보낸다. 준비 중 판정(503)도 이 값을 쓴다.
- 작성자 매핑 키는 SVN 사용자 이름, Git·Mercurial 커밋 이메일이다.

### 저장소 모드와 업그레이드

`Project.repositoryMode`는 VCS와 별개인 `HOSTED`/`MIRROR`이며 일반 프로젝트 JSON에는
노출하지 않는다. 기존 행은 Hibernate 스키마 갱신 전에 `HOSTED`로 채우고, 컬럼의 기본값과
NOT NULL을 확인한다. 대상 DB는 H2, MariaDB, PostgreSQL, MySQL, SQL Server, CUBRID다.
`RepositoryModeMigrationSpec`은 이전 스키마와 컬럼만 추가된 중단 상태를 만들고 재실행을 검사한다.

`yona.repository-mirror.enabled`의 기본값은 `false`다. 꺼도 이미 저장된 `MIRROR`의 쓰기
거부는 유지된다. HTTP/SSH, 웹/API, 실제 저장소 writer가 최신 DB 모드를 확인하며 관리자도
우회하지 못한다. 별도 위키 저장소와 일반 게시글은 계속 쓸 수 있다. 새 미러는 등록된 엔진이 있는
VCS만 만들 수 있다.
hg4j 1.0.0은 v1 batch와 v2 안의 pushkey에서 hook을 거치지 않는다. 그래서 Mercurial MIRROR의 HTTP
batch는 하위 명령이 모두 읽기(`heads`, `known`, `lookup`, `listkeys`, `branchmap` 등)일 때만 허용하고,
하위 명령을 읽을 수 없거나 `pushkey`·`unbundle`이 섞이면 거부한다. `cmd`가 여러 번 오면 모두 검사한다.
v2 multirequest와 SSH 세션은 같은 검사를 할 수 없어 Mercurial MIRROR에서 거부한다. HOSTED 분류는
바꾸지 않는다.

프로젝트 이름에는 DB unique 제약이 없다. 생성·개명·이전·포크는 이름별 파일 잠금을 DB
transaction 완료까지 유지하고 READ_COMMITTED에서 목적지를 다시 확인한다. Git clone도 같은
이름 잠금을 사용하며 기존 목적지를 덮어쓰지 않는다. 미러 예약 표식은 모든 VCS 루트 밖인
`yona.data/.mirror-owners/<이름 해시>.owner`에 먼저 기록하고 디렉터리를 만든다. DB rollback 뒤 표식이나
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

### Git 미러

`GitMirrorEngine`은 `<git>/owner/name.git`에 bare 저장소를 만들고(디렉터리가 비어 있을 때만) JGit으로
`+refs/heads/*:refs/heads/*`, `+refs/tags/*:refs/tags/*`를 가져온다. Forgejo pull mirror처럼 강제 갱신과
삭제된 ref를 그대로 따르고, `refs/pull/*` 등 다른 이름 공간은 무시한다. 비어 있지 않은데 저장소가
아닌 디렉터리, 또는 색인 위치가 있는데 사라진 저장소는 `TARGET_INCOMPLETE`(attention)이며 자동으로
다시 만들지 않는다. 길이가 255자를 넘는 ref는 건너뛴다.

원본 접속은 `TransportHttp`마다 `GitMirrorHttpConnectionFactory`를 설정하여 SVN과 같은 보장을 준다.
JVM 전역 상태(`HttpTransport.setConnectionFactory`, `HttpsURLConnection` 기본값)는 바꾸지 않는다.
모든 연결은 정규화한 원본 origin과 저장소 경로만 허용하고, 연결 전에 `checkHost`를 호출하며,
JGit이 요청한 프록시와 무관하게 `policy.proxy()`를 쓴다. TLS는 `policy.trustManager(host)`로만 검증하고
JGit의 trust manager·hostname verifier 변경은 무시한다. 3xx 응답은 `SOURCE_REDIRECT`, 타임아웃은 연결 10초,
읽기 30초이며 JGit이 늘릴 수 없다. 자격 증명은 `credentialRef`가 있을 때만 읽고 가져오기가 끝나면 지운다.

진행 위치는 ref별 행(`repository_mirror_ref`)이고 선형 커서는 -1로 둔다. ref 이름은 대소문자를 구분하지만
MariaDB/MySQL 기본 collation은 그렇지 않으므로, 유일성과 조회는 이름의 SHA-256(`ref_key`)으로 한다. 새 커밋은 가져온 ref의 끝에서
닿고 저장된 어느 위치에서도 닿지 않으며 이번 실행에서 앞선 ref가 가져가지 않은 커밋이다(annotated tag는
커밋으로 벗긴다). 부모가 자식보다 먼저 오도록 색인하고, 커밋이 `batchSize`보다 많은 ref는 조각 단위로
색인한다. 각 조각의 checkpoint가 ref 행을 그 조각의 마지막 커밋으로 옮기고 마지막 조각이 끝(tip)으로
옮긴다. 병합으로 마지막 커밋의 조상이 아닌 앞선 커밋이 조각에 들어가면 그 커밋은 `mirror-pending/<id>`
임시 행으로 함께 기록하고 ref의 마지막 조각이나 실행 끝에 지운다. 새 커밋이 없는 ref(이미 색인한
커밋으로의 fast-forward나 강제 갱신)도 행은 옮기고, 사라진 ref의 행은 실행 끝에서 지운다. ref가 이전
위치의 후손이 아닌 곳으로 옮겨졌거나 사라지면, 이전 위치는 먼저 `mirror-retired/<id>` 행으로 남긴다.
그래서 다른 ref가 나중에 그 기록에 닿아도 다시 색인하지 않는다. 이미 만든 이슈 참조는 기록을 다시 써도
지우지 않는다. 저장소 정리(gc)가 어느 ref에서도 닿지 않는 커밋을 지워 은퇴·임시 위치의 커밋이 없으면
그 행만 지우고 계속한다. 살아 있는 ref 위치의 커밋이 없으면 `TARGET_INCOMPLETE`(attention)다.
첫 가져오기 동안에는 알림이 없고 `initialImportDone`은
`store.complete`가 첫 완료 실행 뒤 켠다. 작성자 키는 커밋 author 이메일이다(빈 값이면 없음).

### Mercurial 미러

`HgMirrorEngine`(`MirrorVcs.MERCURIAL`)은 SVN과 같은 선형 커서를 쓴다. Mercurial의 로컬 revision
번호는 pull로만 늘어나므로 번호 0부터 연속이며, `lastVerifiedRevision`·`lastIndexedRevision`·R은 로컬
revision 번호다. source UUID와 local UUID에는 모두 루트 changeset(revision 0)의 node를 기록한다.
참조·웹훅의 commit ID는 40자리 changeset node다. 작성자 키는 author 문자열의 e-mail(`Name <email>`)이고
e-mail이 없으면 키도 없다.

- 접속: hg4j의 HTTP 클라이언트는 redirect를 따르고 JVM 기본 TLS를 쓰며 연결별 설정 지점이 없고,
  `PullCommand.call()`은 전역 `HgRemoteConnectionFactory`를 쓴다. 그래서 읽기 전용 wire 명령
  (`heads`, `lookup`, `known`, `getbundle`, `listkeys`)을 `HgHttpsMirrorSource`가 연결마다 직접
  보내고, 받은 bundle만 hg4j `PullCommand.applyBundle`로 반영한다. 모든 요청은 `checkHost` 뒤
  정규화한 origin으로 `policy.proxy()`를 거쳐 나가고, TLS는 그 요청의 `SSLContext`에 `trustManager(host)`를
  넣어 검증한다. redirect는 따르지 않고 `SOURCE_REDIRECT`로 실패한다. 연결 10초·읽기 30초, bundle은
  `max-bundle-bytes`(기본 100MB), 그 외 응답은 4MB가 상한이다. JVM 기본 `Authenticator`는 401/407에
  응답하지 못하게 막는다. JVM 전역 기본값이나 hg4j 전역 등록은 바꾸지 않는다.
  인증 정보는 요청마다 `credential(ref)`로 읽어 Basic 헤더를 만든 직후 비밀번호 배열을 지운다.
- 실행: 원본 head와 루트를 읽고, 로컬 저장소가 없으면(디렉터리가 비어 있을 때만) hg4j로 만든다. 비어 있지
  않은데 저장소가 아니면 `TARGET_INCOMPLETE`다. 기록된 루트와 원본 루트가 다르거나 로컬 루트와 다르면
  bundle을 받기 전에 `SOURCE_IDENTITY_CHANGED`/`TARGET_IDENTITY_CHANGED`로 멈춘다(다른 루트의 history가
  두 번째 루트로 섞이는 것을 막는다). 로컬 head를 원본이 모르면 history가 다시 쓰인 것이므로
  `REVISION_HISTORY_CHANGED`로 멈추며 로컬 history는 지우지 않는다. 그 뒤 원본 head와 로컬 head의 차이만
  `getbundle`로 받아 반영하고, 반영 뒤 모든 원본 head가 로컬에 있는지 확인한다.
- 커서: 반영 뒤 `observe`에 루트 node와 로컬 tip revision을 넘기고, revision마다 `verified` → `index`
  순서로 처리한다. `index`의 검증은 SVN과 같고(순서 `lastIndexedRevision + 1`), 체크포인트는
  `lastIndexedRevision`이다. 한 pull이 여러 revision을 가져와도 색인과 알림은 revision 단위다.
  pull 직후 중단되어도 다음 실행이 로컬 tip까지 이어서 검증·색인한다.
- 빈 원본: changeset이 없으면 `observe`·`index` 없이 끝나고(저장소만 만들고 R과 커서는 -1/없음),
  R이 없는 실행은 `initialImportDone`을 켠다. 이후 처음 생긴 changeset은 `initialImportDone`이 켜져
  있으므로 알림을 보낸다. 반대로 changeset이 있던 원본이 다시 비면 history가 바뀐 것으로 본다.
- 북마크: 실행마다 원본의 `bookmarks`를 따라 로컬 북마크를 만들고 옮기고 지운다(Git ref처럼 강제 이동과
  삭제를 따른다). 로컬에 없는 node를 가리키거나 저장할 수 없는 이름은 건너뛴다.
- 한계: hg4j가 changegroup 전체를 메모리에서 반영하므로 한 번의 pull은 `max-bundle-bytes`(기본 100MB,
  최대 1GiB)를 넘을 수 없다. 넘으면 `SOURCE_RESPONSE_TOO_LARGE`(FAILED)이며, 운영자가 JVM 힙과 함께
  값을 올린 뒤 재시도한다. 반영 중에는 bundle과 파싱한 changegroup이 함께 메모리에 있으므로 힙은 이 값의
  몇 배가 필요하다.

### 이슈 참조와 알림

검증한 revision의 이슈 참조, `>R`인 경우의 DB NEW_COMMIT 알림, 색인 커서 갱신은 같은
fenced transaction에서 처리한다. rollback이면 전부 rollback하며 재실행은 현재 커서 다음부터
이어간다. 같은 revision의 이슈 번호는 중복 제거한다. 별도 이벤트 ledger/unique 컬럼은 없다.

`<=R`에서는 원본 시각으로 참조만 만들고 알림을 보내지 않는다. 원본 svn:author는 이름이
같더라도 Yona 계정이 아니다. site-admin이 미러별 작성자 매핑(`repository_mirror_author`:
미러, 원본 작성자 → Yona 계정)을 등록한 경우에만 참조의 발신자로 기록하고 코드 화면에서 그 계정으로
표시한다. 매핑이 없으면 발신자 없이 저장하며 원본 author는 SVN 로그와 웹훅 payload에만 남는다.
원본 작성자는 정확히 일치해야 하며 이름으로 자동 매칭하지 않는다.

매핑 추가/삭제는 인덱서와 같은 미러 행 잠금 아래에서 실행한다. 추가하면 그 작성자의 기존
미귀속 참조를 해당 계정으로 소급 갱신하고(이미 다른 계정으로 기록된 참조는 그대로), 삭제하면
그 매핑으로 귀속된 참조를 미귀속으로 되돌린다. 참조에는 revision 번호만 있으므로 작성자는 검증된
로컬 저장소에서 다시 읽는다. 관리 화면은 검증된 revision의 미매핑 작성자 목록을 보여 준다. `>R`의 알림은 inbox/mail이 프로젝트를 찾도록 PROJECT와
project ID를 대상으로 하며, 참조·웹훅의 commit ID는 숫자 문자열이다. 화면에서만 r 접두사를 쓴다.
기존 watcher 정책에 따라 수신자가 없으면 DB 알림도 없다. Git/Hg의 기존 발신자·payload는
바꾸지 않는다.

미러 웹훅은 DB commit 뒤 기존 `gitPush` 설정에 따라 best-effort로 보낸다. JSON은 Git push와 같은
형식이며 sender·pusher는 null이다. crash나 네트워크
응답 유실에 따른 유실/중복 가능성이 있으며, 색인 커서는 원격 수신 완료를 뜻하지 않는다.
웹훅 실패 때문에 복제나 색인을 되돌리지 않는다.

### 실행 제한과 물리 배타성

짧은 dispatcher가 due 행을 찾고 slot 확보 → 조건부 claim → 전용 executor 제출 순서로
동작한다. 기본 worker 2개(최대 8), revision batch 50개, 임대 60초, 성공 후 재동기화 간격
60초다. 긴 SVN I/O는 공용 scheduler나 DB transaction 안에서 실행하지 않는다.
claim/heartbeat/checkpoint/완료는 DB 시각, owner, 증가하는 fence로 확인한다.

파일 writer는 `yona.data/.mirror-locks/<projectId>.lock`을 작업 전체 동안 보유한다. 임대 상실은
기존 스레드가 멈췄다는 증거가 아니므로, 취소를 요청해도 스레드가 반환하기 전에는 잠금을
해제하지 않는다. 종료도 새 작업을 막고 실제 writer 종료를 기다린다. 이름 잠금은
`yona.data/repository-names/`를 사용한다. 두 종류의 잠금 파일은 실행 중 rename/delete/recreate하지 않는다.

기본 운영 형태는 지정된 단일 writer와 로컬 파일시스템이다. 여러 노드가 같은 canonical
저장소·예약 표식·잠금 inode를 공유하고 OS locking을 보장하는지 검증하지 않은 상태에서
자동 failover를 활성화하지 않는다. 독립 디스크의 다중 노드를 DB 임대만으로 지원하지 않는다.

일시적 네트워크 오류는 5초부터 지수 backoff(상한 15분), 연속 5회 후 FAILED다.
인증/TLS/원본 정책 오류는 즉시 FAILED, 정합성이나 물리 배타성 문제는 NEEDS_ATTENTION이다.
오류에는 원격 예외 원문이나 secret을 기록하지 않는다.

### 관리자 조작과 검증

`/site/repository-mirrors`는 site-admin 세션 전용이다. 프로젝트 관리자나 API token만으로는
접근하지 못하며 POST는 CSRF 보호를 받는다. 새 미러는 현재 관리자 소유의 비공개 프로젝트로
만들며 VCS를 고른다. 기존 Git 프로젝트 전환이나 저장소 덮어쓰기는 지원하지 않는다.
원본 경로·비밀 값은 숨기고, 고정 R·커서·마지막 관측 youngest·임대·안전한 오류를 표시한다.

pause/retry는 DB 조작이며 HTTP 요청에서 동기 복제하지 않는다. pause는 다음 안전한
경계의 중단 요청이지 강제 종료가 아니다. 초기 목표까지 검증/색인되지 않은 저장소의 browse는
503 준비 중 응답을 주며 프로젝트 홈에도 안내한다. 기능을 꺼도 관리자 상태 조회와 기존
MIRROR 쓰기 거부는 유지된다.

검증은 Java 21에서 mirror/guard/notification 회귀와 `RepositoryModeMigrationSpec`을 실행하고,
`-Dyona.it.db=h2|mariadb|postgres|mysql|mssql|cubrid`를 DB별로 선택하여 순차 수행한다.
create-drop 결과만으로 upgrade를 확인하지 않는다. 물리 잠금 spec에는 별도 JVM의 보유·충돌·
process 종료 후 해제 검사가 있다. 실제 HTTPS SVN·제한 프록시·관리자 브라우저에서는 인증
실패/재시도, commit 후 속성 실패/복구, 복제 중 고정 R, 이후 알림과 링크, pause,
권한/CSRF/redirect 거부, 위키 쓰기, 기능 off 재기동 및 내용/속성 일치를 별도로 확인한다.
