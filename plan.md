# T2 — SVN README 편집 UI/server 일치 계획

## 상태와 기준

- **구현·검증 완료.** PR 헤드는 `pr/svn-hg-readme-edit-guard`(base `upstream/next` 1b6b6e047, 커밋 1개, 문서 미포함)다. 제목·본문은 `docs/pr/fix-svn-hg-readme-edit-guard.md`에 있다. push와 PR 생성은 사용자 승인 후 사람이 수행한다.
- 전용 경로: `/Users/senghyunjo/github/yona-scm-t2-readme`
- 이전 작업 브랜치: `prep/scm-t2-svn-readme-20261008` (README.md 변경 포함, PR 헤드 아님)
- 기준: fetch된 upstream `next`, `1b6b6e04761cc390c242c183d3a31a533ea75001` (이전 조사 기준 `5928b5ba5a293fde9902817e8502d0052ab9e4fc`의 후손, 2 commits).
- 생성 명령: `git -C /Users/senghyunjo/github/yona worktree add -b prep/scm-t2-svn-readme-20261008 /Users/senghyunjo/github/yona-scm-t2-readme 1b6b6e04761cc390c242c183d3a31a533ea75001`
- 입력: `/Users/senghyunjo/github/yona-scm-findings-20261008/issues/03-svn-readme-edit.md`, `findings.md`, `queue-decision.md` 및 아래 코드. 새 이슈 없이 직접 버그 수정 PR로 제안하므로 공개 이슈 번호를 요구하지 않는다.
- `yona-scm-agent-prompts.md` 원문과 §0/PR-G는 제공 자료 및 현재까지의 주변 검색에서 발견되지 않았다. **원문 대조 미완료**이며 준수 확인을 주장하지 않는다. 원문 확보 시 승인/구현 착수 전에 차이를 대조한다.

## 목표와 제외

SVN 프로젝트에서 지원하지 않는 README 생성/편집 진입을 안내하지 않고, 직접 GET/POST/API/service 요청도 저장 전에 명확하게 거절한다. README 의도로 들어온 사용자가 일반 게시글을 저장하거나 성공처럼 redirect되는 일을 막는다. 일반 게시글 작성/수정과 저장소 README 읽기, 지원되는 Git README 동작은 유지한다. Mercurial도 현재 README writer가 지원하지 않는 경로이므로 같은 우회 여부를 회귀 검사하되 기능을 확장하지 않는다.

`SvnRepository.commitTextFile` 구현은 **이 트랙에서 제외**한다. SVN 온라인 README commit 지원은 이 버그 수정 PR 이후 별도 기능 제안/후속 PR로 남긴다. mirror, ISSUE_TEMPLATE writer 확장, 임의 코드 파일 writer, Git/Hg writer 전체 리팩터링, VCS 전환, queue 도입은 포함하지 않는다. 새 라이브러리나 범용 capability 계층을 만들지 않는다.

공개 이력: [upstream #493 — README.md 파일 수정이 안됩니다](https://github.com/yona-projects/yona/issues/493)는 CLOSED이며, [2019-01-24 @doortts의 답변](https://github.com/yona-projects/yona/issues/493#issuecomment-457115660)은 “SVN은 README 온라인에서 수정이 안됩니다”이다. 이는 기존 미지원 범위의 근거이지 최신 `next`의 재현 결과가 아니다. 이 계획은 SVN 지원 확대/과거 이슈 재개가 아니라 현재 `next`의 UI/server 불일치를 좁게 수정한다. 새 수정용 공개 이슈 번호는 아직 없다.

## 정적 확인 근거 — 실행 재현 아님

- `src/main/resources/templates/project/home.html:63-75`: README 생성/편집 링크가 `project.isCodeEnabled`만 검사한다.
- `src/main/kotlin/com/github/yonaprojects/yona/web/BoardViewController.kt:234-261,293-297`: README 내용을 준비하지만 `canReadmefy`는 Git+COMMIT 권한만 허용한다.
- `src/main/resources/templates/board/create.html:36-40`: 체크박스가 없으면 `readme` 자체가 제출되지 않는다. 따라서 SVN README 진입 후 일반 글로 저장될 가능성이 있다. 이는 아직 실제 화면으로 확인하지 않은 정적 흐름의 판단이다.
- `BoardViewController.kt:410-415,469-500`: 기존 README 위임 및 신규 게시글 저장 뒤 Git `BareCommit` 실행이 있으며 오류를 출력하고 홈으로 redirect한다.
- `src/main/kotlin/com/github/yonaprojects/yona/domain/board/PostingServiceImpl.kt:103-147,150-219`: 생성은 채번/DB 저장/알림을 하고, 수정은 entity 변경/저장 뒤 Git README commit을 시도하며 예외를 삼킨다. 서비스 검증은 이 부수효과들보다 앞에 있어야 한다.
- `src/main/kotlin/com/github/yonaprojects/yona/web/BoardController.kt:102-157`: API 생성/수정도 같은 서비스를 호출한다. `:165-191`의 본문 PATCH는 서비스를 우회하여 직접 저장하므로 기존 README 표식 게시글 검사 대상이다.
- `BoardViewController.kt:321-377`: 수정 폼에서 누락된 README 값은 false가 된다. SVN에 이미 `readme=true`인 게시글이 있을 때 의도치 않은 일반 글 전환도 막아야 한다.
- `src/main/resources/templates/error/badrequest.html`: `project`, `messageKey`, `menuType`를 받아 명확한 오류를 표시하는 기존 화면을 재사용할 수 있다. view 이름 반환만으로 HTTP 400이 설정된다고 가정하지 않는다.

## 승인 후 순서

1. **코드 수정에 앞서 실제 disposable SVN 프로젝트를 생성하고 재현한다.** 격리된 DB/저장소/업로드 경로와 로컬 바인딩을 사용한다. 빈 저장소와 SVN client로 README를 넣은 저장소 각각에서 홈 → README 생성/편집 → 폼 → 저장을 관찰한다. 제출 payload의 `readme`, HTTP 상태/redirect, SVN revision/README 본문, 게시글 수/본문을 전후 비교한다. 일반 폼 저장과 직접 `readme=true` 요청은 구분한다. 기존 README 표식 글 수정과 API도 따로 기록한다.
2. 실제 재현 결과를 전용 worktree의 `pr-body.md`에 기록한다. 기준 SHA, 환경/명령, SVN 프로젝트 준비, 최소 재현 절차, 관찰 결과와 기대 동작, 민감정보를 제거한 증거를 담는다. 정적 판단과 실행 확인을 분리하고 미실행도 그대로 적는다. 이후 수정·검증 결과를 추가해 최종 PR 본문으로 완성한다. 새 이슈나 이슈 댓글을 만들지 않는다. **upstream PR 생성은 사람이 한다.** 이번 계획 단계에서는 재현 결과를 선작성하거나 push하지 않는다.
3. 재현한 요청 경로에 회귀 검사를 먼저 추가한다. 비지원 VCS 요청에서 오류뿐 아니라 저장/채번/첨부 이동/알림/Git writer가 발생하지 않음을 검사한다.
4. 아래 최소 수정으로 UI와 서버를 일치시킨다. README 의도 GET을 일반 글 폼으로 내려주지 않는다. 권한 확인은 유지하고 그 이후 비지원 README를 HTTP 400과 저장 불가 메시지로 거절한다. 신규 요청과 기존 README 표식 게시글 모두 적용하되 일반 글은 유지한다.
5. 회귀/전체 테스트 및 같은 실제 SVN 화면 시나리오를 다시 실행하고 결과를 기록한다. 승인된 구현 세션에서만 수행하며 이번 세션의 통과 결과로 쓰지 않는다.

## 변경 파일과 처리 방침

아래 경로는 repository root 기준이다. 지금 변경하는 파일은 `plan.md` 하나다.

| 예정 파일 | 수정 내용 |
|---|---|
| `src/main/kotlin/com/github/yonaprojects/yona/web/ProjectViewController.kt` | `projectHome`의 README 편집 가능 모델값을 코드 사용/Git/COMMIT 권한 기준으로 계산. 기존 README 읽기 경로는 유지. |
| `src/main/resources/templates/project/home.html` | README 생성/편집 링크 두 곳을 같은 지원/권한 조건으로 제한. SVN README 본문은 계속 표시. |
| `src/main/kotlin/com/github/yonaprojects/yona/web/BoardViewController.kt` | `createPostForm`의 `readme=true`, `editPostForm`의 기존 README, `createPost`의 직접 README 요청, `editPost`의 신규/기존 README 요청을 부수효과 전에 검사. 비지원 요청을 일반 게시글로 대체하지 않음. HTML은 기존 `error/badrequest`와 명시적 HTTP 400, 구체적 messageKey 사용. |
| `src/main/kotlin/com/github/yonaprojects/yona/domain/board/PostingServiceImpl.kt` | `createPosting`은 채번/저장 전에, `updatePosting`은 managed entity 변경 전에 비지원 VCS README를 거절. `posting.readme` 기존 값과 제출 값 둘 다 고려하여 폼 필드 누락/false로 인한 암묵적 전환을 방지. UI를 통과하지 않는 호출에도 동일한 거절 보장. |
| `src/main/kotlin/com/github/yonaprojects/yona/web/BoardController.kt` | API create/update 및 서비스 우회 `updatePostingContent`를 검토·차단. 비지원 README 실패에 400+설명 body 반환; 일반 게시글 PATCH 유지. 기존 인증/권한 우선순위 유지. |
| `src/main/kotlin/com/github/yonaprojects/yona/web/BoardRestApiController.kt` | owner/project alias 경로가 BoardController로 위임하는 것을 확인하고 동일 오류 유지. 위임만으로 충족되면 변경하지 않음. |
| `src/main/resources/templates/board/create.html`, `src/main/resources/templates/board/edit.html` | 서버의 진입 차단과 체크박스 조건을 맞춤. 기존 조건이 그대로 충분하면 변경하지 않음. unsupported README를 hidden field만으로 일반 글 변환하는 우회는 만들지 않음. |
| `src/main/resources/messages/messages.properties`, `messages_ko.properties`, `messages_ko_KR.properties` | 지원하지 않는 VCS에서는 README를 저장할 수 없다는 명시적 메시지. 다른 locale은 기존 default fallback 관례 유지. |
| `src/test/kotlin/com/github/yonaprojects/yona/web/BoardViewControllerSpec.kt`, `BoardControllerSpec.kt`, `ProjectViewControllerSpec.kt` | 폼/홈/API 및 권한/응답 회귀 검사 추가. template 렌더링까지 실제 조건 확인. |
| `src/test/kotlin/com/github/yonaprojects/yona/domain/board/PostingServiceSpec.kt` | 서비스 직접 생성/수정, 기존 readme=true→누락/false 요청, DB/번호/알림 무변경 검사. |
| `README.md` | 구현 시 사용자 지원 범위에 SVN README 온라인 저장 미지원과 기존 읽기 유지 안내를 최소 추가. |
| `pr-body.md` | 승인 후 실제 재현과 수정·검증 결과를 담아 사람이 게시할 PR 본문 작성. |

서비스의 거절을 단순 예외로 추가하고 API를 500으로 끝내지 않는다. 이미 존재하는 오류 처리 관례를 재사용하되 README에 국한된 매핑을 사용하며, 모든 IllegalArgumentException을 전역으로 400 처리하는 범위 확장은 하지 않는다. 현재 `GlobalExceptionHandler`의 확인된 처리 대상은 업로드 크기 오류이므로 README 오류 매핑이 이미 있다고 가정하지 않는다.

**Schema: 없음.** entity/DDL/migration/backfill, 기존 SVN 파일/게시글 데이터 변환·삭제는 하지 않는다. 기존 비지원 README 표식 글은 읽기 유지, README 관련 수정은 명확히 거절한다. Git의 일반적인 commit 실패 원자성 전체를 이 SVN 지원 불일치 수정에 섞지 않는다.

## 승인 후 검증 계획

### 회귀 기준

- SVN 빈/기존 README 홈: 생성·편집 링크 없음, 기존 README 읽기 정상.
- SVN `?readme=true` 직접 GET: 일반 게시글 작성 폼 대신 HTTP 400과 저장 불가 안내.
- SVN 직접 POST `readme=true`: 신규/기존 README 분기 모두 400, 성공 redirect 없음, 게시글/번호/첨부/알림/저장소 변경 없음.
- SVN 기존 README 표식 글: 폼 edit, API PUT, 본문 PATCH, 서비스 직접 호출 모두 잘못된 저장이나 readme=false 전환 없음.
- SVN 일반 게시글 생성·수정·본문 PATCH는 정상. 권한 없는 사용자/비로그인 요청의 기존 차단 유지.
- Git README 생성/편집과 일반 게시글 정상; COMMIT 권한 없는 사용자에게 편집 링크 없음. 코드 비활성 프로젝트의 기존 읽기/일반 글 동작 유지.
- Mercurial 비지원 README 우회 거절 및 일반 글 정상. Hg 코드브라우저 파일 편집 지원에는 손대지 않음.
- 응답 문구/HTTP status/DB 부수효과를 함께 검사하며 mocked writer 호출 여부만으로 완료하지 않음.

### 명령과 실행 환경

승인 후 이 worktree에서 JDK 21 및 README에 기재된 빌드 전제를 확인한다. Gradle이 기존 frontend 빌드를 호출한다는 사실을 기록하고 이번 수정에서 패키지 관리 방식을 바꾸지 않는다. Node 관련 수동 작업이 필요한 경우 pnpm을 사용한다.

```sh
./gradlew test -Dyona.it.db=h2 --tests 'com.github.yonaprojects.yona.web.BoardViewControllerSpec' --tests 'com.github.yonaprojects.yona.web.BoardControllerSpec' --tests 'com.github.yonaprojects.yona.web.ProjectViewControllerSpec' --tests 'com.github.yonaprojects.yona.domain.board.PostingServiceSpec'
./gradlew test
```

첫 명령은 집중 회귀, 둘째는 repository 전체 테스트다. 실제 DB 실행 환경/미실행 사유를 결과와 함께 명시한다. 필요한 DB별 전체 회귀는 README의 `-Dyona.it.db=mariadb|postgres|mysql|mssql|cubrid|h2` 방식으로 순차 실행하며 다른 트랙과 동시에 전체 suite를 돌리지 않는다. 이번 턴에는 명령을 실행하지 않는다.

실제 화면 검증은 첫 단계와 동일한 SVN 프로젝트 시나리오를 수정 후 반복하고 Git 대조 프로젝트도 포함한다. UI 링크, 직접 URL, 저장 불가 메시지, network 상태, SVN HEAD와 게시글 목록/DB 전후를 확인한다. 스크린샷과 요청 증거에서 계정/토큰/원격 주소를 제거한다. 앱 시작 명령은 기존 `./gradlew bootRun`을 기반으로 격리 설정을 명시한 뒤 기록하며 현재 앱을 실행하지 않는다.

## 다른 트랙과 통합 순서

- **T2 먼저 merge 목표, T5는 T2가 포함된 최신 upstream 위로 rebase.** T5 mirror의 read-only 제한과 T2의 VCS 지원 여부 제한은 서로 대체하지 않고 함께 만족해야 한다.
- 구체적 겹침: `ProjectViewController.kt`/`templates/project/home.html`의 README 링크 가능 조건, `BoardViewController.kt`의 폼 진입 및 README POST, `PostingServiceImpl.kt`의 create/update writer 전 검증, `BoardController.kt`의 직접 API/PATCH, 대응 board/project 테스트. T5는 이 지점에서 mirror 상태에 따른 쓰기 금지를 합성해야 하므로 T2 변경을 덮어쓰지 않는다.
- T2는 mirror 모델/상태 필드를 미리 가정하거나 추가하지 않는다. T5 rebase 후 일반 Git 허용, 일반 SVN README 거절, Git/SVN mirror 쓰기 거절의 조합을 T5가 재검증한다. ISSUE_TEMPLATE/path writer와 protocol write 차단은 T5 범위이며 T2가 선행 확장하지 않는다.
- T1은 Git 접근 VCS 검사, T3은 changeVCS 서버 확인, T4는 참조 기록 공통화, T6은 보류된 conversion이다. 이 트랙은 해당 기능 파일을 수정하지 않으며 SVN writer/전환 구현을 끌어오지 않는다.

## 범위

재현, 회귀 검사, 최소 UI/server 수정, 집중 H2 검증의 결과는 PR 초안에 기록한다. 전체 `./gradlew test` 실행 여부도 PR 초안에 있는 그대로 적는다. **이슈 없이 PR로 진행한다.**

push 전 upstream next 기준 분기를 확인하고, 사용자 승인 후 fork `origin`에 새 브랜치로 push한다. `pr-title.txt`, `pr-body.md`를 완성하며 새 이슈 번호나 `Fixes #N`을 요구하지 않는다. 기존 #493은 관련 논의로만 인용하고, RFC가 게시됐다면 `Refs #N`을 덧붙일 수 있지만 선행조건은 아니다. 본문에는 SVN README 실제 쓰기 지원은 별도 후속임을 명시한다. upstream PR 생성은 사람이 한다. 내부 계획과 비공개 트랙 정보는 공개 커밋/PR에 포함하지 않는다.
