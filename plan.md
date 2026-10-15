# T3 — VCS 초기화 안전성: 구현 상태

## 현재 결정 — 이전 계획의 승인 대기 문구보다 우선

- 첫 범위(서버 확인·초기화 확인)의 PR 헤드는 `pr/vcs-reset-server-confirmation`(base `upstream/next`, 커밋 1개, 문서 미포함)이다. 제목·본문은 `docs/pr/fix-vcs-reset-server-confirmation.md`에 있다. `origin/prep/scm-t3-vcs-reset-20261008`은 문서 변경을 포함한 이전 브랜치다.
- 두 번째 범위(아카이브 보존)는 `local/scm-t3-vcs-reset-archive-20261008`(`b9590f56c`)에만 있다. push하지 않았다. 기능 추가이므로 이슈를 먼저 연다.
- 보관은 공유된 fork 파일을 독립 inode로 분리하는 동안 저장소 세션을 전역으로 멈춘다. 실패한 새 저장소는 재귀 삭제하지 않고 별도 위치로 옮긴다.
- 첫 범위의 전체 H2 테스트와 UI 검증, 두 번째 범위의 전체 H2 6,862건(실패 0, skip 2), 브라우저 228건(실패 0, skip 2), 실제 전환·실패 복구를 확인했다. 상세 결과와 운영 조건은 각 전달 자료에 기록한다.
- 이슈·댓글·PR 생성/편집과 외부 메일 발송은 사람이 한다. 아래 계획 본문은 착수 당시 기록이며, 이 절의 상태가 우선한다.

## 기준과 작업 경계

- 기준: upstream `next`, `1b6b6e04761cc390c242c183d3a31a533ea75001` (기존 조사 기준 `5928b5ba5a293fde9902817e8502d0052ab9e4fc`의 후손, 2 commits).
- 전용 worktree: `/Users/senghyunjo/github/yona-scm-t3-vcs-reset`.
- 현재 branch: `prep/scm-t3-vcs-reset-20261008`.
- 근거: `/Users/senghyunjo/github/yona-scm-findings-20261008/issues/02-safer-vcs-reset.md`.
- 이번 세션의 산출물은 worktree와 이 계획뿐이다. **승인 전 구현·commit·push·build·lint·tests·formatter를 하지 않는다.** upstream 이슈/댓글/PR/메일도 게시하지 않는다. 공개 이슈 번호는 아직 없다.
- 원문 `yona-scm-agent-prompts.md` 및 §0/PR-G는 제공 자료와 nearby 검색에서 발견되지 않았다. **원문 대조 미완료**이며 아래 계획이 해당 원문을 모두 충족한다고 주장하지 않는다. 원문 확보 후 승인 전에 차이를 대조한다.
- VCS 초기화만 다룬다. hosted 이력 변환, MIRROR 코드/설정/엔드포인트, 자동 복원 UI, archive 자동 만료/삭제 작업은 추가하지 않는다.

## 소스로 확인한 현재 동작

| 근거 파일 | 확인 내용 |
| --- | --- |
| `src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectServiceImpl.kt:566-586` | fork 자식의 `originalProject` 해제 → 기존 repo `delete()`(예외 무시) → VCS 순환 → `create()` → Project 저장. DB 트랜잭션이 파일 삭제를 되돌리지 않는다. |
| `src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectService.kt:16` | 현재 인터페이스는 `changeVCS(projectId: Long)`이다. |
| `src/main/kotlin/com/github/yonaprojects/yona/web/ProjectViewController.kt:355-412` | GET 설정 화면 및 POST manager 검사. POST는 별도 확인값 없이 service 호출. |
| `src/main/resources/templates/project/change_vcs.html:23-54` | **기존 코드·이력 삭제 경고, 동의 체크박스, 재확인 dialog가 이미 있다.** 이를 제거하거나 경고가 없었다고 서술하지 않는다. |
| `src/main/resources/static/javascripts/service/yona.project.ChangeVCS.js:50-79` | 공용 checkbox-gated dialog를 사용하고 body 없는 POST를 보낸다. |
| `src/main/kotlin/com/github/yonaprojects/yona/domain/vcs/RepositoryService.kt:19-71` | Git/SVN/Hg 별도 base-dir, 프로젝트 VCS에 따른 repository 선택. |
| `src/main/kotlin/com/github/yonaprojects/yona/domain/vcs/{GitRepository,SvnRepository,HgRepository}.kt` | `create`, `delete`, `getDirectory` 구현이 있다. Git은 `<owner>/<name>.git`, SVN/Hg는 `<owner>/<name>` 경로. 기존 `getDirectory()`를 재사용해 중복 경로 계산을 피한다. |
| `src/main/kotlin/com/github/yonaprojects/yona/config/GitServletConfig.kt:84-120` | Git HTTP resolver가 Git base-dir 아래 파일을 연다. 단순히 이름을 숨긴 같은-root archive는 안전성 근거가 아니다. |
| `src/main/kotlin/com/github/yonaprojects/yona/web/SvnController.kt:45-69` | DAVServlet의 `SVNParentPath`는 SVN base-dir/owner다. |
| `src/main/kotlin/com/github/yonaprojects/yona/config/ssh/GitSshProtocolHandler.kt:38-69` | SSH는 인가 결과의 repoDir로 저장소를 연다. 내장 SSH와 relay 경로 모두 검증 대상이다. |
| `src/main/kotlin/com/github/yonaprojects/yona/web/LfsStorageController.kt:22-74` | 별도 LFS base-dir의 object 다운로드 경로가 있으므로 archive가 LFS 경로로 해석되지 않는지도 검증한다. |

위 내용은 소스 확인이다. 실제 reset/복구/프로토콜 smoke는 실행하지 않았다.

## PR1 — 기존 경고 유지 + fork 영향 안내 + 서버 확인

현재 branch에서 **승인 후 PR1만** 구현한다. 물리 저장소 삭제/생성 순서는 PR1에서 바꾸지 않는다.

1. 화면 및 dialog에 “이력 변환이 아닌 저장소 초기화”, fork 자식 연결 해제, Project ID·이슈·게시판 등 협업 데이터 유지의 차이를 한국어/영어로 안내한다. 기존 삭제 경고, 동의 checkbox, 재확인 dialog와 manager 검사는 그대로 유지한다.
2. 현재 프로젝트명을 직접 입력받는다. POST는 프로젝트 ID, 확인 프로젝트명, 화면에서 본 현재 VCS, 명시적 동의값을 전송한다. 경로로 찾은 실제 Project와 서버에서 비교한다. 프로젝트명은 실제 현재 이름과 정확히 일치해야 하며 클라이언트 검사만 신뢰하지 않는다.
3. 누락/다른 프로젝트명/동의 거부/대상 ID 불일치는 400, 화면 이후 현재 VCS가 달라졌으면 409로 거부한다. 기존 미인증 401, 권한 없음 403, 없는 프로젝트 404를 유지한다. 거부 시 파일시스템과 fork 관계는 변경하지 않는다.
4. 확인 직후 service 진입 사이에 상태가 바뀌는 문제를 피하도록 service의 실제 변경 직전에 확인된 이름/ID/예상 VCS를 재검증한다. 기존 `changeVCS(projectId)`의 확인 우회 overload는 남기지 않고 모든 호출부와 직접 호출하는 테스트를 함께 갱신한다. PR2에서도 이 확인을 재사용한다.
5. null 기본 GIT 및 기존 VCS 순환/alias 관례를 유지하되, 화면 표시와 서버의 예상 VCS 비교가 같은 기준을 사용한다. 오래된 body 없는 POST가 실패하는 것은 의도적인 호환성 변경이며 문서에 적는다.

### PR1 변경 예정 파일

- `src/main/kotlin/com/github/yonaprojects/yona/web/ProjectViewController.kt`
- `src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectService.kt`
- `src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectServiceImpl.kt` (입력 재확인 부분만)
- `src/main/resources/templates/project/change_vcs.html`
- `src/main/resources/static/javascripts/service/yona.project.ChangeVCS.js`
- `src/main/resources/messages/messages.properties`
- `src/main/resources/messages/messages_ko.properties`
- `src/test/kotlin/com/github/yonaprojects/yona/web/ProjectViewControllerSpec.kt`
- `src/test/kotlin/com/github/yonaprojects/yona/web/TemplateEquivalenceSpec.kt`
- `src/test/kotlin/com/github/yonaprojects/yona/domain/project/ProjectServiceImplSpec.kt`
- `e2e/specs/04-project/project-change-vcs.spec.ts`
- `e2e/specs/04-project/project-vcs-change-real.spec.ts`
- `docs/yona-backup-restore.md` (현재 초기화 동작의 데이터 손실/확인 안내; PR2 완료 전 복구 가능하다고 쓰지 않는다)

**Schema:** DB table/column/enum/migration 변경 없음. 확인값은 요청 데이터이며 DB에 새로 저장하지 않는다.

## PR2 — 물리삭제 대신 격리 archive 이동 + 실패 rollback

### 후속 분기 전략

- 별도 후속 branch 이름: `prep/scm-t3-vcs-reset-archive-20261008`.
- **지금 PR2 branch/worktree는 만들지 않는다.** PR1 구현·검토 후 승인된 PR1 tip을 기준으로 별도 세션에서 branch를 만들고 PR1 이후 변경만 검토한다. PR1이 upstream에 반영된 뒤 시작할 수 있다면 해당 변경이 포함된 새 upstream SHA에서 분기한다.
- PR1 미반영 상태에서 시작하면 PR1 tip 위의 stacked branch로 한정하고, PR1 변경 이후 rebase로 정렬한다. PR1 리뷰에 archive 구현을 섞지 않는다. PR2 기준 SHA는 실제 분기 시 계획에 기록한다.

2026-10-08 실행 기록: 사용자 개발 승인 후 PR1을 `fbb97f612`로 로컬 commit했다.
부모의 H2 집중 회귀 304 tests 및 실제 Chromium 초기화 smoke 3 tests가 통과한 tip이다.
이 tip에서 `prep/scm-t3-vcs-reset-archive-20261008` /
`/Users/senghyunjo/github/yona-scm-t3-vcs-reset-archive`를 생성했다.
전체 회귀와 origin push는 부모가 계속 관리하며, plan 및 PR draft는 commit하지 않는다.

### 구현 방향 및 완료 기준

1. 정상 reset에서 기존 repository를 물리 삭제하지 않는다. 실제 `getDirectory()` 경로를 source로 사용해 전용 archive root의 충돌 없는 작업 디렉터리로 이동한 다음 새 repository를 만든다. archive root는 Git/SVN/Hg/LFS/static/upload 등 모든 공개 serving root **밖**이어야 하며 실제 canonical/real path와 symlink를 고려해 경로 겹침을 거부한다. root 설정과 운영자 전용 파일 권한을 명시한다. 같은 base-dir 아래 `.archive`로 숨기는 방식을 사용하지 않는다.
2. 삭제 후 복사 같은 fallback은 하지 않는다. 같은 filesystem의 원자적 이동을 전제로 하고, cross-device/권한/대상 충돌/원본 누락 등 이동 불가능 상태에서는 기존 repo를 그대로 둔 채 작업을 실패시킨다. 운영자가 VCS별 다른 mount를 쓴다면 archive 위치도 해당 source와 같은 filesystem의 비공개 위치로 설정해야 한다. 입력값으로 임의 archive 경로를 지정할 수 없게 한다.
3. 작업 순서는 `최신 Project/확인값 재검증 → 기존 repo archive 이동 → 새 repo 생성 → fork 관계 해제와 VCS 저장 → DB commit`이다. 기존 `delete()` 예외 무시를 제거한다. 이미 다른 데이터가 있는 target repo 경로를 덮어쓰거나 rollback 때 삭제하지 않는다.
4. 새 repo 생성 실패(일부 파일 생성 후 예외 포함) 시 이번 작업이 만든 target만 정리하고 archive를 원위치로 복귀시킨다. DB 저장/flush/commit 실패도 동일한 보상 대상이다. `@Transactional` 함수 내부 catch만으로 commit 실패까지 처리됐다고 간주하지 말고 Spring 트랜잭션 완료 결과와 보상을 연결한다. 성공 commit 때만 archive를 확정 보존한다. 실패 시 VCS, Project ID, fork 연결 및 기존 commit/file 데이터가 원래와 같아야 한다.
5. 보상 자체 실패는 성공 응답으로 숨기지 않는다. 기존 archive를 보존하고 원래 예외와 복구 실패/보존 위치를 운영 로그에 남긴다. HTTP 응답으로 서버 파일 경로를 노출하지 않는다. archive는 자동 삭제하지 않으며 `docs/yona-backup-restore.md`에 수동 복구·보존 책임을 기술한다. 프로세스/호스트 비정상 종료는 DB+FS 원자성을 보장할 수 없으므로 중간 archive 식별 및 운영자 수동 복구 절차를 명시하고 자동 완전복구라고 표현하지 않는다.
6. 동일 프로젝트 reset 중복 실행은 직렬화하고 재검증해 archive가 서로 덮이거나 rollback이 타 작업 데이터를 삭제하지 못하게 한다. protocol 읽기/쓰기와 이동의 경쟁도 disposable 환경에서 검증한다. archive로 이동한 open handle을 통해 기존 내용이 전달되는 경우까지 포함해 실패 기준으로 삼으며, 단순 경로 변경만으로 진행 중 protocol 세션이 안전하다고 주장하지 않는다. 필요한 접근 제어는 기존 protocol 경로에 좁게 추가하고 별도 저장소 프레임워크를 만들지 않는다.
7. archive를 Project로 등록하거나 URL에 연결하지 않는다. manager를 포함한 모든 앱 사용자도 HTTP/SSH/DAV/Hg/LFS/브라우저에서 archive 내용을 읽거나 수정할 수 없어야 한다. 서버 파일시스템을 직접 관리하는 운영자의 수동 복구는 별도다. 별도 OS svnserve/웹서버가 archive 디렉터리를 export하지 않게 하는 배포 전제도 문서화한다.

### PR2 변경 예정 파일과 조건부 파일

- 필수: `src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectServiceImpl.kt`, `src/main/kotlin/com/github/yonaprojects/yona/domain/vcs/RepositoryService.kt`, `src/main/resources/application.yml`, `docs/yona-backup-restore.md`.
- 기존 `PlayRepository.getDirectory()`와 native NIO 이동을 우선 재사용한다. VCS별 구현을 일괄 재설계하지 않는다. `src/main/kotlin/com/github/yonaprojects/yona/domain/vcs/{PlayRepository,GitRepository,SvnRepository,HgRepository}.kt`는 확인된 lifecycle 문제/최소 seam 변경이 필요할 때만 수정한다.
- Project 단위 DB 잠금에 기존 repository 메서드가 부족한 경우만 `src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectRepository.kt`를 변경한다. 잠금 방식은 지원 DB 동작에 맞춰 결정하고 테스트한다.
- archive 비노출/진행 중 접근 차단 검증이 실패할 경우에만 기존 `config/GitServletConfig.kt`, `config/git/GitAuthorizationFilter.kt`, `config/svn/SvnAuthorizationFilter.kt`, `web/SvnController.kt`, `web/HgController.kt`, `config/ssh/{GitSshProtocolHandler,HgSshProtocolHandler,YonaSshGitCommand,SshRelayServer}.kt`의 필요한 구간을 최소 수정한다(각 경로는 `src/main/kotlin/com/github/yonaprojects/yona/` 기준). T1와 겹치는 인가 파일은 통합 담당자와 선후관계를 먼저 정한다.
- 테스트: 기존 `ProjectServiceImplSpec.kt`, `RepositoryServiceSpec.kt`, Git/SVN/Hg repository spec 및 아래 protocol 통합 spec에 회귀를 추가한다. DB+실제 FS reset rollback 검증은 `src/test/kotlin/com/github/yonaprojects/yona/domain/project/ProjectChangeVcsArchiveIntegrationSpec.kt`를 새로 작성한다. 기존 `ProjectDeletePhysicalRepositoryIntegrationSpec.kt`의 실제 repository+Spring 통합 패턴을 참고하되 전용 임시 base-dir를 사용한다.

**Schema:** 예정 없음. 기존 Project ID/VCS/fork 연결을 사용한다. archive 관리 테이블, 변환 상태 column, MIRROR 상태는 만들지 않는다. 파일 저장 위치/보존 정책은 운영 설정 변경이며 DB migration과 구분한다. 새로운 영속 상태가 실제로 필요하다는 근거가 생기면 이 계획 수정/재승인 전에 임의 구현하지 않는다.

## 기존 테스트와 추가 검증 계획 — 승인 후 구현이 끝난 다음에만 실행

### PR1 회귀

- `ProjectViewControllerSpec.kt:370-379,1897-1927`: 기존 성공/401/403/404와 직접 controller 호출을 새 요청 형식으로 갱신. 누락/오타/다른 ID/동의 false/이전 이름/예상 VCS 불일치 시 service 미호출 및 올바른 상태 확인.
- `ProjectServiceImplSpec.kt:2034-2129`: Git→SVN→Hg→Git, null 기본값, fork 해제의 기존 정상 동작 유지. 새 service 확인 실패 시 repo 조작·fork 저장이 없음을 확인. PR1에서는 기존 삭제 동작을 바꾸지 않으며 예외 무시 테스트의 의도 변경은 PR2에서 한다.
- `TemplateEquivalenceSpec.kt:1101`와 두 `project-*-vcs*.spec.ts`: 기존 경고/checkbox/dialog 유지, fork 영향과 보존 데이터 안내, 입력 없는 실행 거부, 정상 확인 POST를 검사. 실 reset e2e는 공유 seed 프로젝트가 아닌 기존처럼 disposable 프로젝트만 사용.
- 성공 시 Project ID, issue/게시판 등 협업 데이터 유지 확인. 구형 body 없는 POST의 명시적 거부와 새 UI의 정상 전송을 함께 검사.

### PR2 실제 FS/DB 실패 주입

- Git/SVN/Hg 각각 commit 또는 revision+고유 marker 파일을 만든다. 정상 reset 후 원래 이력이 archive에 보존되고 새 repo는 초기 상태인지 확인한다. Project ID/협업 데이터 유지, fork 관계는 성공 때만 해제되는지 확인한다.
- 실제 임시 디렉터리와 테스트 전용 repository create failure seam을 사용해 (a) archive 이동 전 실패 (b) 이동 후 create 시작 전 실패 (c) target 일부 생성 후 실패 (d) Project flush/commit 실패 (e) target 정리 실패 (f) archive 복귀 실패를 각각 주입한다. production 실패 스위치는 추가하지 않는다.
- 실패 이후 별도 트랜잭션으로 Project VCS/fork를 다시 읽고 원 repo의 commit ID/revision, refs, 파일 내용 및 archive/target 존재 상태를 비교한다. Mock 예외만 보고 파일 복구 성공이라고 판정하지 않는다.
- archive/target 충돌, cross-filesystem, 쓰기 권한 없음, 원본 없음, symlink/겹친 root, 연속 reset, 동시 reset, 진행 중 clone/fetch/push를 검증한다. 실패한 restore는 archive가 보존되고 운영자가 위치를 식별할 수 있어야 한다.
- 기존 `ProjectDeletePhysicalRepositoryIntegrationSpec.kt`의 프로젝트 삭제/재생성과 fork/transfer도 회귀 대상으로 남긴다. reset용 archive 동작을 일반 프로젝트 삭제로 확장하지 않는다.

### archive 비노출: 모든 지원 경로

아래는 실제 서버 또는 실제 handler를 통한 검증이며, root 문자열 비교만으로 대체하지 않는다. public/private 프로젝트, 익명/멤버/manager 각각에 대해 원래 URL·추측 archive URL·절대/상대/URL-encoded traversal·symlink 우회로 archive marker가 반환되지 않고 쓰기도 안 되는지 검사한다. rollback 후 원래 active repo의 정상 접근은 복원되어야 한다.

- Git smart HTTP clone/fetch/push: `src/test/kotlin/com/github/yonaprojects/yona/config/GitSmartHttpProtocolIntegrationSpec.kt`, `GitServletConfigSpec.kt`.
- SVN HTTP DAV checkout/update/REPORT/PROPFIND: `src/test/kotlin/com/github/yonaprojects/yona/web/SvnHttpProtocolIntegrationSpec.kt`, `SvnControllerSpec.kt`.
- Hg HTTP clone/pull/push: `src/test/kotlin/com/github/yonaprojects/yona/web/HgHttpProtocolIntegrationSpec.kt`.
- Git/Hg SSH 내장 및 외부 relay: `src/test/kotlin/com/github/yonaprojects/yona/config/ssh/{YonaMinaSshServerIntegrationSpec,SshRelayServerIntegrationSpec,SshInternalControllerIntegrationSpec}.kt`, `src/test/kotlin/com/github/yonaprojects/yona/domain/sshkey/SshAuthServiceImplSpec.kt`.
- 브라우저 code/raw/history/ZIP 다운로드 및 LFS object URL: `CodeViewController`, `CodeController`, `ProjectViewController.downloadCode` 경로와 `src/test/kotlin/com/github/yonaprojects/yona/web/LfsStorageControllerSpec.kt`를 대상으로 archive 비노출 회귀 추가. 별도 LFS 저장 정책 전체 개편은 범위 밖이지만 LFS 경로로 archive 본문이 노출되어서는 안 된다.
- 테스트에서 공개/비공개 archive URL의 실패 상태뿐 아니라 응답에 marker/이전 commit 내용이 없는지 확인한다. archive가 있어도 정상 active repo clone/push가 계속 동작해야 한다.

### 실행 순서와 환경

1. 승인/구현 완료 후 전용 임시 DB와 Git/SVN/Hg/LFS/archive 디렉터리, 별도 HTTP/SSH port를 준비한다. 서버/CLI 의존성은 실제 Git/SVN/Hg와 기존 DB Testcontainers 설정을 사용한다. production 데이터와 공유 seed에 파괴적 smoke를 실행하지 않는다.
2. 좁은 회귀: `./gradlew test --tests '*ProjectViewControllerSpec' --tests '*ProjectServiceImplSpec' --tests '*TemplateEquivalenceSpec'`.
3. PR2는 새 `*ProjectChangeVcsArchiveIntegrationSpec`와 위 protocol spec을 `./gradlew test --tests '<각 spec>'`로 실행하고 실패 주입 smoke의 요청/응답·파일 상태·DB 전후 값을 기록한다.
4. UI 회귀: `pnpm --dir e2e exec playwright test specs/04-project/project-change-vcs.spec.ts specs/04-project/project-vcs-change-real.spec.ts`. 기존 Playwright는 실행 중인 앱을 사용하며 `YONA_BASE_URL`로 전용 서버를 지정한다.
5. 해당 PR 최종 통합 뒤 전체 JVM suite `./gradlew test`, 전체 브라우저 suite `pnpm --dir e2e test`를 각각 실행한다. 변경된 트랜잭션/잠금 경로는 기존 `-Dyona.it.db=<DB>` 선택을 사용해 지원 DB별 회귀도 계획한다. 트랙마다 동시에 전체 suite를 돌리지 않는다.
6. 현재 Gradle `processResources`가 기존 `npmCi` task를 연결하고 있다는 점은 확인했다. 의존성 설치는 pnpm 원칙을 따르며 이 트랙에서 빌드 체계 자체를 바꾸지 않는다. 검증 세션은 기존 생성 리소스/의존성 준비 상태와 Gradle task 전제를 확인하고, 필요한 pnpm 준비 절차를 정한 뒤 실행한다.

**현재 실행 결과:** 테스트/build/lint/formatter/smoke 미실행. 위 명령은 향후 계획이지 통과 기록이 아니다.

## 다른 트랙과 충돌/통합 순서

- **T3 우선, T6 보류:** `ProjectService.kt`, `ProjectServiceImpl.kt`, VCS 파일은 T6 conversion과 겹친다. T3 PR1→PR2를 먼저 확정한다. **T6는 `changeVCS()`를 재사용하지 않는다.** 변환은 초기화와 별도 흐름이어야 한다.
- T1 VCS 검증 PR(`pr/git-vcs-access-check`)과는 파일이 겹치지 않는다. 두 PR은 따로 올린다.
- T2와 controller·messages·template 테스트 파일이 겹친다. T3는 changeVCS 구간/키만 변경하고 SVN README 동작은 건드리지 않는다.
- T4는 Git/Hg post-receive listener와 참조 기록 공통화만 다루므로 현재 직접 수정 파일의 겹침은 없다. T3는 커밋 선택이나 참조 기록 의미를 바꾸지 않는다.
- T5 mirror와 저장소 lifecycle 관심사가 겹치지만 **T3에 MIRROR 코드를 넣지 않는다.** archive는 hosted repo 초기화 안전성만 처리한다.

## 승인 요청 범위

PR1(확인 입력 형식·기존 UI 유지·fork 안내)은 PR 헤드 `pr/vcs-reset-server-confirmation`으로 준비되었다. PR2(격리 archive·보상 처리·비노출 검증)는 이슈를 먼저 열고 별도로 진행한다.

승인 후 각 PR 구현과 검증을 완료하면 fork `origin`의 해당 브랜치에만 push하고 `pr-title.txt`, `pr-body.md`를 작성한다. 본문에는 사용자가 전달한 공개 02 이슈 번호를 `Refs #N`으로 연결하며, 전체 범위를 닫는 최종 PR에만 `Fixes #N`을 사용한다. 번호 수신 전 실제 번호를 추정하지 않는다. 제출 직전 최신 upstream next와 선행 PR 반영 여부를 확인하고 rebase·회귀 검증한다. upstream PR 생성과 모든 upstream 게시·편집은 사람이 한다.
