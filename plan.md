# T1 — Git 저장소 접근 시 프로젝트 VCS 검사 계획 / 구현·검증 완료

## 상태와 기준

- 별도 세션·worktree: `/Users/senghyunjo/github/yona-scm-t1-private`
- 로컬 브랜치: `local/scm-t1-access-validation-20261008`
- PR 브랜치: `pr/git-vcs-access-check` (base `upstream/next` 1b6b6e047, 커밋 2개, 문서 미포함)
- 기준: upstream `next` `1b6b6e04761cc390c242c183d3a31a533ea75001`. 이전 조사 기준 `5928b5ba5a293fde9902817e8502d0052ab9e4fc` 이후 2 commits를 포함합니다.
- 사용자의 개발 승인 이후 아래 계획 범위를 구현했습니다. 아래 “승인 후/실행 금지/대기” 문구는 승인 전 계획 기록이며, 현재 상태는 이 절과 실행 결과를 따릅니다. push와 PR·이슈 생성은 사용자 승인 후 사람이 수행합니다.
- 원문 `yona-scm-agent-prompts.md`의 §0/PR-G는 제공 자료에서 발견하지 못했습니다. 원문 대조 미완료이며 준수를 확인했다고 주장하지 않습니다. 사용자의 개발 승인을 근거로 제공된 범위의 구현을 진행했습니다.
- plan.md는 내부 작업 문서이며 커밋에 포함하지 않습니다.

### 2026-10-08 실행 결과

- 수정 전 유효한 회귀 실행: 43 tests, 의도한 실패 3개. 주 담당자 수정 후 집중 검증: 10 suites, 128 tests, 실패/오류/skip 0. 전체 H2: 465 suites, 6,761 tests, 실패/오류 0, skip 2.
- 별도 loopback 서버에서 실제 Git clone/push, 5개 VCS wiki 지연 생성·push·재clone, 비인가 wiki 미생성, Git/LFS 거부 및 저장소/객체 불변성을 확인했습니다. 실행 서버와 임시 fixture는 종료·제거했습니다.
- 필요한 embedded SSH 실제 전송 회귀를 기존 `YonaMinaSshServerIntegrationSpec.kt`에 추가하는 테스트 전용 범위 확장은 주 담당자가 승인했습니다.
- 로컬 승인 커밋: `dbb01cc34bc1becf9f7a0c9aa03d4af80b319678` (`fix: validate repository access`), 소스/테스트 10개 파일만 포함. 이 plan은 미포함입니다.
- 패치·실행 결과 JSON(내부 보관, 커밋 대상 아님): `/Users/senghyunjo/github/yona-scm-findings-20261008/private-delivery/`. 패치 SHA-256: `4c122546c2576a43f12a6845d6c75bac22228f776b6d6e43aaa7e651d38723b4`.
- remote push와 PR·이슈 생성 없음. 별도 `build` 수행 결과는 미확인입니다. T6의 upstream merge gate는 열리지 않았습니다.

## 목표와 제외 범위

주 코드 Git 전송이 프로젝트의 저장소 종류와 일치할 때만 진행되게 하고, 기존 프로젝트 권한 및 독립 wiki 저장소 동작을 유지합니다. 인증 우회나 실제 데이터 노출/수정을 확정한 상태가 아닙니다. 저장소 열기·생성·LFS 객체 쓰기 전 거부 경계를 검증합니다.

mirror, changeVCS의 파괴적 동작, SVN README, refs 문법, 권한 정책 재설계, 저장소 변환, DB 데이터 정리는 범위 밖입니다. 새 의존성·범용 프로토콜 추상화는 추가하지 않습니다. DB schema/migration/API payload 변경은 **없음**입니다.

## 소스 확인과 변경 파일

경로는 worktree 상대 경로입니다.

| 파일 | 확인한 동작 / 승인 후 계획 |
| --- | --- |
| `src/main/kotlin/com/github/yonaprojects/yona/config/git/GitAuthorizationFilter.kt` | 22행 패턴은 `/git`와 `/git-lfs`를 처리. 37–49행은 `.wiki` 제거 후 프로젝트 조회, 51–96행은 기존 인가. 주 코드와 wiki 식별을 보존한 뒤 주 코드에 VCS 검증을 추가하고 기존 멤버십·guest·deploy key 검사를 유지. HTTP 거부는 정보 노출을 늘리지 않는 404를 제안. |
| `src/main/kotlin/com/github/yonaprojects/yona/domain/sshkey/SshAuthServiceImpl.kt` | 102–121행 Git 인가는 VCS 검사 없이 사용자/deploy key 분기, 236–249행은 `owner/name.git` 해석. 동일한 주 코드 VCS 기준을 저장소 경로 반환 전에 적용. 기존 SSH wiki 지원 여부를 먼저 명확히 하며 새 SSH wiki 기능을 만들지 않음. HTTP `.wiki.git` 예외를 SSH 주 코드 우회로 옮기지 않음. |
| `src/main/kotlin/com/github/yonaprojects/yona/config/vcs/RepoAccessPolicy.kt` | 기존 공유 인가 클래스. HTTP/SSH가 같은 VCS 판정을 필요로 하면 최소 판정 메서드만 여기 두는 후보. 현재 멤버십 의미는 바꾸지 않음. 별도 프레임워크/새 정책 클래스는 만들지 않음. |
| `src/main/kotlin/com/github/yonaprojects/yona/config/svn/SvnAuthorizationFilter.kt` | 47–48행의 “Git/SSH는 VCS를 확인하지 않는다” 주석은 수정 후 사실과 달라지므로 해당 문구만 갱신. SVN 동작 변경 없음. |
| `src/test/kotlin/com/github/yonaprojects/yona/config/git/GitAuthorizationFilterSpec.kt` | 기존 Kotest DescribeSpec·MockK 및 494–526행 wiki 회귀에 중립 이름의 접근 검증 케이스 추가. |
| `src/test/kotlin/com/github/yonaprojects/yona/config/git/GitAuthorizationFilterIntegrationSpec.kt` | 실제 security chain을 통과하는 HTTP 경계 검증 보강. |
| `src/test/kotlin/com/github/yonaprojects/yona/config/git/DeployKeyGitAuthorizationIntegrationSpec.kt` | 저장소 scope/read-only 정상 정책 회귀 유지 및 필요한 경계 케이스 보강. |
| `src/test/kotlin/com/github/yonaprojects/yona/config/GitSmartHttpProtocolIntegrationSpec.kt` | 임시 bare 저장소로 실제 Git HTTP read/write 및 wiki 전송 회귀 보강. |
| `src/test/kotlin/com/github/yonaprojects/yona/domain/sshkey/SshAuthServiceImplSpec.kt` | 기존 인가 fixture를 사용해 사용자/deploy key와 Git 명령 경계를 검증. |
| `plan.md` | 로컬 승인/실행 기록만 갱신. upstream 패치에는 제외. |

읽기 전용 경계 확인 대상: `config/GitServletConfig.kt`(85–120행 repository resolver, 158–217행 LFS dispatch), `config/SecurityConfig.kt`(필터 적용 순서), `web/LfsStorageController.kt`(객체 GET/PUT), `config/ssh/GitSshProtocolHandler.kt`(44행 repository open), `domain/wiki/WikiServiceImpl.kt`, `domain/vcs/RepositoryService.kt`. 필요 변경이 승인 파일 범위를 벗어나면 구현을 확대하지 않고 계획을 다시 승인받습니다.

`RepositoryService.getRepository` 33–70행은 null을 GIT로, SVN/SUBVERSION과 HG/MERCURIAL을 각 구현으로, 그 외 값을 Git으로 해석합니다. `nextVcsInCycle`도 null을 GIT로 정규화합니다. 이 때문에 단순 `project.vcs == "GIT"`로 기존 데이터를 차단하지 않습니다. null/대소문자/별칭/알 수 없는 값의 현재 의미를 테스트로 고정하고, 기존 repository resolver와 판정이 일치하는 최소 변경을 제안합니다. 미지원 값 전면 거부나 DB 정규화는 별도 승인 없이는 추가하지 않습니다.

## 승인 후 구현 순서와 수용 기준

1. 원문 §0/PR-G와 승인 범위를 대조하고 기존 테스트·SecurityConfig 연결을 읽습니다. 승인된 로컬 환경 외에는 접근하지 않습니다.
2. 기존 fixtures를 재사용해 중립 이름의 회귀 테스트를 작성하고 HTTP/SSH 주 코드 VCS 경계를 최소 수정합니다. 테스트 이름은 `repository access validation case 01` 같은 형식, 커밋 제목은 `fix: validate repository access`처럼 재현 조건을 노출하지 않는 이름으로 합니다. 테스트 내용과 patch 자체는 여전히 비공개입니다.
3. 정상 Git read/write, `.git` 유무, 프로젝트 이전 경로의 현행 동작을 유지합니다. 비일치 주 코드 요청은 인가된 사용자라 하더라도 저장소를 열거나 변경하기 전에 종료하고, 필터 다음 단계가 호출되지 않음을 검증합니다.
4. **`.wiki.git` 필수 회귀:** GIT/SUBVERSION(SVN)/MERCURIAL(HG) 프로젝트별 HTTP wiki clone/read 및 허용된 push/write가 동작해야 합니다. private/protected/member-only 비멤버, guest, 다른 프로젝트 deploy key, read-only key 쓰기는 기존처럼 거부합니다. wiki가 아직 없어 지연 생성되는 경우도 허용된 요청에서만 동작하는지 확인합니다. 실제 SSH wiki는 현행 지원 여부에 따라 기존 동작 보존만 검증하며 기능 확장은 하지 않습니다.
5. HTTP LFS batch 진입점과 `/git-lfs/.../objects` GET/PUT도 같은 주 코드 판정 및 기존 권한을 따르는지 security chain을 통해 확인합니다. 컨트롤러 단위 테스트만으로 보호된다고 결론내리지 않습니다. LFS의 별도 쓰기 의미나 이름 해석에서 추가 문제가 발견되면 은폐하지 않고 비공개로 분리 기록하고 승인 범위를 갱신합니다.
6. SSH 사용자 키/deploy key read/write의 저장소 반환 이전 거부를 확인합니다. 서비스의 단위 테스트와 기존 embedded/relay integration 중 적용 가능한 경계를 검증하고, 읽지 않은 경로의 영향을 같은 문제라고 단정하지 않습니다.

## 테스트 / 실행 검증 계획 — 지금 실행 금지

JDK 21 및 기존 Gradle wrapper/Kotest를 사용합니다. 운영 DB·실제 고객 저장소·upstream에 쓰지 않습니다. 전용 임시 디렉터리, 무해한 fixture와 격리 DB만 사용하고 결과 로그는 로컬에만 보관합니다.

- 승인 후 구현이 모두 끝난 뒤 주 담당자가 아래 대상 검증을 한 번 수행합니다:
  ```sh
  ./gradlew test --tests '*GitAuthorizationFilterSpec' --tests '*GitAuthorizationFilterIntegrationSpec' --tests '*DeployKeyGitAuthorizationIntegrationSpec' --tests '*GitSmartHttpProtocolIntegrationSpec' --tests '*SshAuthServiceImplSpec' --tests '*LfsStorageControllerSpec' --tests '*WikiServiceImplSpec'
  ```
- SSH transport 변경이 필요하다고 승인된 경우 기존 `YonaMinaSshServerIntegrationSpec`, `SshRelayServerIntegrationSpec`, `SshInternalControllerIntegrationSpec`를 포함합니다. 사용 가능한 git 바이너리, 임시 저장소 및 테스트 DB 전제 조건부터 확인하고, 실제 clone/read·push 전후 refs와 객체가 의도대로 유지되는지 검사합니다. 실패 요청은 HTTP 상태뿐 아니라 저장소와 LFS 파일이 생성/변경되지 않는지 검사합니다.
- 대상 검증 뒤 통합 시점에만 전체 `./gradlew test`와 `./gradlew build`를 계획합니다. `build.gradle.kts`는 테스트 DB 기본값 mariadb 및 `-Dyona.it.db` 전달을 사용합니다. DB/컨테이너가 없으면 통과로 기록하지 않고 전제 미충족으로 남깁니다.
- 현재 Gradle `processResources -> copyTurbo -> npmCi` 연결은 npm을 호출하므로, pnpm 사용 원칙과 충돌하는 실행 전제입니다. 해당 task를 무심코 실행하거나 이 T1에서 build tooling을 임의 수정하지 않습니다. 실행 승인 시 기존 frontend 자원 준비 방식/사용자 승인된 검증 절차를 먼저 정합니다.
- 현재 실행 결과: **없음**. 소스 읽기만으로 runtime 영향·보안 수정 완료를 주장하지 않습니다.

## 다른 트랙과 순서

- T2는 SVN README UI/server, T3는 changeVCS입니다. Project/RepositoryService 및 VCS 정규화 의미는 공유 경계이므로 T3와 조율하되 T1에서 전환 기능을 수정하지 않습니다. T4는 Git/Hg postreceive listeners와 reference service만 담당하며 SSH 인가·ref protocol 변경 트랙이 아닙니다.
- T5 mirror의 3중 write guard는 `GitAuthorizationFilter.kt`, `RepoAccessPolicy.kt`, SSH 인가 및 protocol integration tests와 실제로 겹칠 수 있습니다. T1의 기존 VCS 검증과 T5의 MIRROR 쓰기 금지를 독립 판정으로 보존하고, 파일 소유권·작업 선후·통합 순서를 협의하여 같은 파일 동시 수정을 피합니다. private patch나 재현 조건을 공개 트랙에 노출하지 않는 비공개 통합 절차를 먼저 합의합니다.
- T6 conversion은 **T1 upstream merge 전 금지**입니다. 계획 승인·로컬 검증·로컬 commit·패치 전달만으로 이 gate가 열리지 않습니다. upstream merge 근거 확인 후 새 계획으로 재개합니다.

## 산출물과 승인 경계

remote push, PR·이슈·댓글 게시, 메일 발송은 사용자 승인 후 사람이 수행합니다. PR 제목·본문은 `docs/pr/fix-git-vcs-access-check.md`에 있으며 커밋에 포함하지 않습니다. 이 PR은 이슈 번호가 필요하지 않습니다.
