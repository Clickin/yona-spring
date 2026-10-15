<!-- 제목: feat: 외부 저장소(SVN·Git·Mercurial) 읽기 전용 미러 -->
<!-- 브랜치: prep/scm-t5-svn-mirror-20261008 (T4 PR 위에 쌓임 — T4 머지 후 rebase) -->
<!-- 게시 시점: 미러 [제안] 이슈에 방향 합의가 난 뒤 -->

Refs #__MIRROR_ISSUE__

## 변경 요약
- 사이트 관리자가 외부 SVN·Git·Mercurial 저장소를 지정하면 Yona가 주기적으로 가져오는 읽기 전용 미러를 추가합니다. 기본값은 꺼짐이며, 원본은 외부에 두고 이슈 관리는 Yona에서 하는 흐름을 위한 기능입니다.
- **저장소 모드와 쓰기 보호:** `project.repository_mode`(`HOSTED`/`MIRROR`)를 추가합니다. MIRROR는 Git·SVN·Hg의 HTTP/SSH/LFS 쓰기, 웹 편집, PR 병합, 이름 변경·이전·포크·VCS 변경을 모두 거부하며, 기능을 꺼도 유지됩니다. 위키는 별도 저장소라 그대로 씁니다.
- **공통 계층:** 임대·fence와 저장소별 파일 잠금으로 같은 미러가 동시에 돌지 않게 하고, 원본 접속은 허용 origin, 사설 주소 차단, 목적지 제한 CONNECT 프록시, JVM truststore 기반 TLS, redirect 거부, 운영자 자격증명 파일 참조로 제한합니다.
- **VCS별 엔진:**
  - SVN: SVNKit replicator로 revision 속성까지 검증하며 복제하고, 중단되면 이어서 보정합니다.
  - Git: JGit fetch로 브랜치·태그를 원본과 같게 따라갑니다(강제 갱신·삭제 포함). 다시 써진 기록도 한 번만 색인합니다.
  - Mercurial: 읽기 전용 wire 명령으로 bundle을 받아 hg4j로 반영하고 bookmark를 따라갑니다. HTTP로만 제공합니다(hg4j SSH batch를 걸러낼 수 없음).
- **이슈 참조와 알림:** 새 커밋은 #__T4_PR__의 공통 참조 경로로 기록합니다. 첫 가져오기는 참조만 만들고, 이후 커밋부터 알림과 웹훅(Git push와 같은 JSON, sender 없음)을 보냅니다. 참조·알림·진행 위치는 한 트랜잭션입니다.
- **작성자 매핑:** 원본 작성자(SVN 사용자 이름, Git·Hg 이메일)는 이름이 같아도 Yona 계정으로 보지 않습니다. 관리자가 등록한 매핑만 쓰며, 등록·삭제 시 기존 참조를 소급 갱신합니다.
- **관리 화면:** `/site/repository-mirrors`에서 생성(VCS 선택), 상태·오류, 일시정지, 재시도, 자격증명 교체, 작성자 매핑.
- **스키마:** `project.repository_mode`(기존 행 HOSTED backfill), `repository_mirror`, `repository_mirror_ref`, `repository_mirror_author`.
- 운영 설정과 한계는 `docs/guide/technical/README.md`의 "읽기 전용 저장소 미러"에 정리했습니다.

## 테스트 계획
- [x] 전체 H2: 487 suites, 6,959 tests, 실패·오류 0, skip 2
- [x] 전체 PostgreSQL: 487 suites, 6,959 tests, 실패·오류·skip 0
- [x] 전체 MariaDB: 487 suites, 6,962 tests, 실패·오류 0, skip 2 (MariaDB에서만 도는 `DataBackupServiceIntegrationSpec` 포함)
- [x] 전체 MySQL: 487 suites, 6,959 tests, 실패·오류 0, skip 2
- [x] 전체 SQL Server: 487 suites, 6,959 tests, 실패·오류 0, skip 2
- [x] 전체 CUBRID: 487 suites, 6,959 tests, 실패·오류 0, skip 2
- [x] 실제 `git`·`hg` 클라이언트로 Git·Hg 미러 clone·pull 성공과 push 거부
- [x] 실제 Mercurial 7.2.4 `hg serve` 원본에서 첫 가져오기·증분 가져오기·bookmark
- [x] Git: 강제 갱신·브랜치 삭제 후 같은 기록에 닿는 ref 재생성 시 중복 참조 없음, 대소문자만 다른 ref, 조각 단위 색인 중단 후 재개
- [x] 원본 접속 정책: 프록시 강제·호스트 검사·redirect 거부·JVM 전역 상태 불변(Git·Hg), 기본 Authenticator 차단

🤖 Generated with [Claude Code](https://claude.com/claude-code)
