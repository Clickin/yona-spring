# T4 — Git/Hg commit→issue 참조 기록 공통화 계획

## 상태·기준

- **2026-10-08 사용자 승인(“개발 시작”)에 따라 구현 진행. 추출 전 characterization baseline은 주 실행자가 통과 확인했으며 추출 후 검증 및 runtime smoke는 별도 조율한다.**
- 브랜치: `prep/scm-t4-commit-refs-20261008`
- worktree: `/Users/senghyunjo/github/yona-scm-t4-commit-refs`
- 초안 기준: fetch된 upstream `next`의 `1b6b6e04761cc390c242c183d3a31a533ea75001`. 기존 조사 기준 `5928b5ba5a293fde9902817e8502d0052ab9e4fc`의 후손이며 2 commits 이후다.
- 생성 명령: `git -C /Users/senghyunjo/github/yona worktree add -b prep/scm-t4-commit-refs-20261008 /Users/senghyunjo/github/yona-scm-t4-commit-refs 1b6b6e04761cc390c242c183d3a31a533ea75001`
- 입력 자료: `/Users/senghyunjo/github/yona-scm-findings-20261008/issues/04-share-commit-issue-reference.md`, `findings.md` H6, `queue-decision.md`의 초기 이력/증분 분리 논의. 공개 이슈 번호는 아직 없으며 생성하거나 추정하지 않는다.
- 원문 `yona-scm-agent-prompts.md` 및 §0/PR-G는 제공 자료와 기존 검색에서 발견되지 않았다. 원문 대조를 완료했다고 주장하지 않으며, 사용자 승인에 따라 확인 가능한 범위를 구현한다.
- 아래 현재 동작은 위 SHA의 소스 확인이다. 실행 결과가 아니며, 중복 생성·처리 시각 등은 승인 후 기존 코드에 behavior test를 먼저 추가해 관찰·고정한다.
- 이번 계획 단계에서는 어떤 remote로도 push하지 않는다. upstream 이슈 생성/편집/댓글, PR 생성, 메일, upstream push는 이후에도 에이전트가 하지 않는다. 승인 후 구현·검증이 끝나면 아래 P7 산출 절차에 따라 fork(`origin`) 브랜치만 push한다. 로컬 push smoke는 승인 후 격리된 로컬 앱/저장소만 대상으로 한다.

## 목표와 비목표

두 listener에 중복된 `IssueReferenceParser → 현재 프로젝트의 이슈 조회 → IssueEvent 생성 → save`만 작은 공통 모듈로 추출한다. **코드 중복 제거이지 이벤트 중복 제거가 아니다.**

보존할 동작:

- `senderLoginId`/`senderEmail`은 push 이벤트의 `User`(pusher)에서 가져온다. commit author로 바꾸지 않는다.
- `created`는 이벤트를 생성하는 처리 시각(`Instant.now()`)이다. commit 시각이나 push 수신 시각으로 바꾸지 않으며 이벤트별 호출 위치를 유지한다.
- `newValue`는 listener가 넘긴 Git commit ID/Hg node ID 문자열 그대로다. `oldValue`는 기존 기본값 `null`, `eventType`은 `ISSUE_REFERRED_FROM_COMMIT`이다.
- 메시지 하나의 같은 `#번호`는 기존 parser의 Set으로 한 번 처리한다. 같은 commit의 재처리, 여러 Git ref 또는 Hg bookmark에 걸친 재등장은 그대로 추가 저장한다.
- 없는 이슈는 건너뛰며 다른 프로젝트의 같은 번호를 참조하지 않는다.
- Git/Hg의 commit 선택·순서, ref/bookmark 의미, 비동기 실행 위치, transaction, 예외 전파를 유지한다.

범위 밖: dedup/멱등 키/unique index, SVN post-commit 또는 mirror 지원, author 매핑, 과거 이력 재색인, 시각 정책 변경, `NEW_COMMIT` 알림/웹훅 변경, `gitPush` 이름 변경, queue/outbox/재시도, VCS 분기 registry, 단일 구현 interface, 범용 task나 commit DTO. 테스트 추가를 명분으로 무관한 오류를 고치지 않는다.

## 현재 event flow와 seam

아래 경로는 모두 worktree 기준이다. `K = src/main/kotlin/com/github/yonaprojects/yona`, `T = src/test/kotlin/com/github/yonaprojects/yona`로 표기한다.

### Git

1. `K/domain/vcs/GitPushHooks.kt:174-219`: `YonaPostReceiveHook.onPostReceive`에서 `lastPushedDate` 갱신 후 `GitPostReceiveEvent(project, pusher, commands.toList())`를 publish한다. PR 관련 후속 호출은 T4에서 건드리지 않는다.
2. `K/domain/event/GitPostReceiveEvent.kt`: project/user/commands를 전달한다.
3. `K/domain/event/GitPostReceiveEventListener.kt:44-81`: `@Async("taskExecutor")`, `@EventListener`, `@Transactional`인 handler가 CREATE/UPDATE/UPDATE_NONFASTFORWARD command별 commits를 목록에 합친다. ID 기준 distinct는 없다.
4. 같은 파일 `:89-111`: CREATE(oldId=zero)는 tip 하나, 그 외에는 RevWalk old..new 범위를 사용한다. 이 동작을 이번 추출에서 고치지 않는다.
5. `:76-80,114-154`: 알림 기록/publish 및 직접 웹훅 호출이 이슈 참조보다 먼저다. 해당 경로는 그대로 둔다.
6. `:156-179`: commit마다 `fullMessage`, `name`, project, sender를 `recordReferredIssues`에 전달하고 이슈별로 직접 저장한다.

### Hg

1. `K/domain/vcs/HgPushHooks.kt:232-286`: `HgYonaPostPushkeyHook.run`이 bookmark별 이동을 계산하고 `lastPushedDate` 갱신 후 `HgPostReceiveEvent(project, pusher, move)`를 publish한다. 여러 bookmark는 여러 event가 된다.
2. `K/domain/event/HgPostReceiveEvent.kt`: project/user/move를 전달한다.
3. `K/domain/event/HgPostReceiveEventListener.kt:48-100`: 같은 async/listener/transaction 설정에서 DELETE는 제외한다. Revlog ancestry로 새 revision을 구하고 내림차순 정렬한다.
4. `:77-79,103-136`: 알림/publish/직접 웹훅 후 이슈 참조를 처리한다.
5. `:138-161`: commit마다 `message ?: ""`, `nodeId.toHex()`, project, sender를 넘겨 Git과 같은 직접-save 로직을 실행한다.

### 기존 공통 코드·주의점

- `K/domain/issue/IssueReferenceParser.kt:7-14`: `#(\d+)` → `toLongOrNull` → `toSet`. 그대로 재사용한다.
- `K/domain/issue/IssueEvent.kt:11-35`: 이슈 FK, sender 문자열, old/new value, created, eventType을 가진다. T4에서는 모델을 수정하지 않는다.
- `K/domain/issue/IssueEventRepository.kt:10-32`: `JpaRepository.save`를 사용한다. T4에서 추가 lookup이나 dedup query를 만들지 않는다.
- `K/domain/issue/IssueEventRecorder.kt:26-59`의 `recordWithDraftMerge`는 최근 이벤트를 병합/상쇄한다. **이름이 비슷하다는 이유로 재사용하지 않는다.** 기존 direct-save 의미가 바뀐다.
- 현재 `IssueEvent` 저장은 별도 Spring event publish가 아니다. 공통화하면서 publish/알림 동작을 새로 넣지 않는다.

## 승인 후 구현 순서

### 1. 기존 동작을 먼저 고정

기존 두 listener spec에 아래 characterization test를 추가하고, 공통 모듈 추출 전 기존 코드로 실행한다. 실패 또는 소스 추론과 다른 결과가 나오면 현재 관찰을 기록하고 승인 범위를 다시 대조한다. 기대 건수를 몰래 변경해 green으로 만들지 않는다.

| 사례 | 고정할 결과 |
|---|---|
| 존재하는/없는 이슈, 참조 없는 메시지 | 프로젝트 내 존재하는 이슈만 저장; 참조 없으면 조회/save 없음 |
| 같은 프로젝트 #1/#2, 다른 프로젝트 #1 | 이슈 조회는 전달받은 project에만 한정, 이슈별 1건 |
| `#42 ... #42` 한 메시지 | 기존 parser대로 1건 |
| 동일 commit ID와 메시지를 두 번 전달 | 같은 이슈에 2건; 중복 제거하지 않음 |
| 같은 commit을 가리키는 Git CREATE ref 2개 | 기존 handler를 통해 같은 이슈에 2건 저장하는지 먼저 고정 |
| 같은 commit을 가리키는 Hg CREATE bookmark 이벤트 2개 | 기존 handler를 2번 호출해 같은 이슈에 2건 저장하는지 먼저 고정 |
| commit author와 pusher가 서로 다름 | 실제 JGit/hg4j 임시 저장소를 읽는 handler 경로에서 sender login/email이 pusher 것임 |
| 과거 commit 시각과 현재 처리 시각이 다름 | 호출 전후 `Instant.now()` 범위 안의 created. commit 시각으로 대체되지 않음; sleep 없이 검증 |
| Git/Hg 문자열 변환 | Git name/Hg `nodeId.toHex()`가 newValue에 그대로 저장; Hg null message는 빈 문자열 취급 |
| 저장 실패 | 직접-save 예외를 공통 모듈이 삼키거나 별도 transaction/재시도로 바꾸지 않음 |

기존 Kotest `DescribeSpec`, MockK capture/verify, JGit 및 hg4j 로컬 저장소 helper를 재사용한다. 이슈 이벤트는 list capture로 건수와 각 필드를 비교한다. created의 정확한 동일 값 비교나 고정 대기 시간을 쓰지 않는다.

### 2. 최소 공통 모듈 추출

- 새 concrete Spring 모듈 `K/domain/issue/CommitIssueReferenceService.kt`에 생성자 주입으로 `IssueRepository`, `IssueEventRepository`만 둔다.
- 단일 진입점 계획: `record(project: Project, sender: User, commitId: String, commitMessage: String): Unit`.
- JGit/Hg 타입, SHA 길이 검증, enum VCS 입력 없이 기존 애플리케이션 타입과 문자열만 받는다. 문자열 ID이므로 미래 SVN revision도 표현할 수 있지만 SVN 지원/의미 정책을 지금 구현하는 것은 아니다.
- parser→프로젝트/번호 조회→`IssueEvent` 생성→직접 `save` 순서를 그대로 이동한다. created는 이슈 이벤트 생성마다 현재 시각으로 설정한다.
- 자체 `@Async`, 이벤트 publisher, 새로운 transaction propagation, clock interface, author/created 선택 옵션을 추가하지 않는다. 현재 listener의 transaction 안에서 호출한다.
- 두 listener의 생성자에서 이슈 repository 두 개를 공통 모듈 한 개로 바꾸고, 기존 per-commit 반복문 안에서 호출한다. Git `fullMessage`/`name`, Hg `message ?: ""`/`nodeId.toHex()` 변환은 listener에 남긴다.
- 기존 `recordReferredIssues` 두 메서드를 삭제한다. forwarding wrapper/호환 alias를 남기지 않는다. 참조 기록 로그도 공통 구현 안으로 이동하되 VCS별 로그 문구만을 위한 추가 인자는 만들지 않는다.
- 별도 commit 목록 변환/복사 또는 전처리 pass를 만들지 않는다.

### 3. 테스트 surface 정리

- 추출 전 양쪽 listener에서 고정한 조회/저장 행렬을 새 `T/domain/issue/CommitIssueReferenceServiceSpec.kt`로 옮긴다. 같은 모듈 동작을 양쪽에 복제한 테스트는 남기지 않는다.
- 두 listener spec에는 실제 저장소를 통해 commit 선택·문자열 변환·pusher·중복 ref/bookmark·알림 이후 호출을 확인하는 연결 테스트를 남긴다. 필요하면 실제 공통 모듈과 mock repository를 조합해 생성 필드까지 확인한다.
- `GitPostReceiveEventListener(` / `HgPostReceiveEventListener(` 검색에서 확인된 수동 생성 caller는 아래 두 spec뿐이다. 양쪽 생성자를 모두 갱신한다. Spring DI는 실제 기동 smoke에서 확인한다.

## 변경 파일·스키마

| 경로 | 계획 |
|---|---|
| `K/domain/issue/CommitIssueReferenceService.kt` | 새 공통 구현 한 파일 |
| `K/domain/event/GitPostReceiveEventListener.kt` | 주입 및 참조 기록 호출 이동, 중복 메서드/import 제거 |
| `K/domain/event/HgPostReceiveEventListener.kt` | 동일; Hg 변환은 유지 |
| `T/domain/issue/CommitIssueReferenceServiceSpec.kt` | 새 공통 동작 테스트 |
| `T/domain/event/GitPostReceiveEventListenerSpec.kt` | 선행 behavior test, 추출 후 생성자/연결 테스트 정리 |
| `T/domain/event/HgPostReceiveEventListenerSpec.kt` | 동일 |
| `plan.md` | 승인 사항 및 구현 후 실제 검증/제약을 기록하는 문서 |
| `docs/parity/tickets/p1-06.md` | 구현 후 공통 참조 기록 모듈과 유지한 sender/created/중복 의미를 짧은 후속 기록으로 추가; 기존 역사 기록은 유지 |
| `pr-title.txt`, `pr-body.md` | 승인 후 구현·검증 결과와 실제 공개 이슈 연결을 담는 게시 준비 파일 |

**DB 스키마 변경 없음.** `IssueEvent.kt`, `IssueEventRepository.kt`, `IssueReferenceParser.kt`, Issue 모델, EventType, migration/config는 의도적으로 그대로 둔다. 이벤트 값, API/payload, null/default 의미도 바꾸지 않는다. `GitPushHooks.kt`, `HgPushHooks.kt`, event data class, notification/webhook 코드도 변경 대상이 아니다. 사용자 기능 변경이 없으므로 무관한 README 설명을 덧붙이지 않는다.

## 테스트·실행 검증 계획 — 지금 실행하지 않음

주 실행자가 승인 후 구현 단계를 순서대로 검증한다. 별도 worktree라도 전체 테스트를 병렬 실행하지 않는다.

1. **추출 전 baseline**: characterization test 추가 후 아래 기존 두 listener spec와 parser spec만 실행한다.

   ```sh
   ./gradlew test --tests 'com.github.yonaprojects.yona.domain.event.GitPostReceiveEventListenerSpec' --tests 'com.github.yonaprojects.yona.domain.event.HgPostReceiveEventListenerSpec' --tests 'com.github.yonaprojects.yona.domain.issue.IssueReferenceParserSpec'
   ```

2. **추출 후 focused regression**: 위 명령에 `--tests 'com.github.yonaprojects.yona.domain.issue.CommitIssueReferenceServiceSpec'`를 더해 동일 행렬을 비교한다. 기존 `T/domain/vcs/GitPushHooksSpec.kt`, `T/domain/vcs/HgPushHooksSpec.kt`도 각각 fully-qualified `--tests`로 포함해 hook event 발행을 회귀 확인한다. 변경하지 않는 draft-merge 행위는 `T/domain/issue/IssueEventRecorderKtSpec.kt`를 포함해 별도 의미가 보존됨을 확인한다.
3. **로컬 실행 smoke**: 테스트 전용 DB와 저장소 루트, loopback으로 한정된 앱을 띄워 Spring 주입/async listener/transaction까지 확인한다. 서로 다른 author와 pusher로 임시 Git/Hg 프로젝트의 기존 이슈를 참조하는 commit을 로컬로 push한다. Hg는 bookmark pushkey가 발생하는 경로를 사용한다. async 완료를 제한 시간 내 관찰하고 이슈 타임라인/DB의 sender, created, newValue, 타입, 건수를 확인한다. 같은 commit을 2 ref/bookmark에 연결할 때 건수도 baseline과 비교한다. Git/Hg `NEW_COMMIT` 알림과 직접 webhook 순서/횟수는 로컬 수신기로 확인하고 외부 메일·웹훅 발송은 차단한다. 기존 다른 프로젝트의 같은 이슈 번호에는 새 기록이 없어야 한다. SVN은 smoke 대상이 아니다.
4. **전체 회귀**: 모든 구현이 완료된 다음 `./gradlew test`를 한 번 실행한다. 기본 MariaDB Testcontainers 환경이 필요하다. 필요 시 지원 DB 확인은 `./gradlew test -Dyona.it.db=h2` 등 README의 선택 방식으로 별도 순차 실행하며 H2 결과를 6개 DB 전체 통과로 표현하지 않는다. 스키마 변경이 없으므로 새 DB migration matrix는 만들지 않는다.
5. **환경 조건과 보고**: `build.gradle.kts`는 Java toolchain 21, Kotest/MockK/JUnit Platform, 기본 테스트 DB mariadb를 사용한다. README상 Gradle resource 단계는 Node.js 22+/npm 10+ 및 잠금 기반 npm 작업을 내장한다. T4에서 의존성을 설치하거나 package manager/lockfile을 바꾸지 않는다. pnpm 사용 규칙과 내장 npm 단계의 실행 양립 여부는 승인 시 별도 확인하며 단계 우회로 검증을 성공했다고 표시하지 않는다. 실행한 명령·결과·환경 미충족·미실행 범위를 구분해 기록한다.

## 승인 후 P7 산출·전달

- 구현 및 위 검증이 끝나면 `docs/parity/tickets/p1-06.md`의 기존 참조 기록 문서에 변경 요약과 검증 근거를 최소한으로 추가한다. 이 계획 기록만으로 영구 변경 문서화를 대체하지 않으며 무관한 README나 새 문서 체계는 만들지 않는다.
- `pr-title.txt`와 `pr-body.md`를 작성한다. 본문에는 변경 목적/범위, 동작 보존, 스키마 없음, 실제 실행한 테스트와 미실행 제약, T5 통합 접점을 적는다. 실제 공개 번호를 사용자가 제공한 뒤 해당 번호를 `Fixes`(해결) 또는 `Refs`(관련)로 연결한다. 번호를 추정하거나 가짜 번호를 채우지 않는다.
- 승인된 구현·검증·문서 변경을 commit한 뒤 push 대상이 fork인 `origin`인지 확인하고 `prep/scm-t4-commit-refs-20261008` 브랜치만 push한다. upstream에는 push하지 않는다.
- upstream PR은 **사람이** 준비 파일을 이용해 생성한다. 에이전트는 PR 생성/이슈 게시/댓글/메일을 수행하지 않는다. 이번 세션에서는 위 파일 작성, commit, push도 모두 실행하지 않는다.

## 다른 트랙과의 겹침·통합 순서

- T1(private VCS guard), T2(SVN README), T3(changeVCS)는 T4가 수정할 파일을 필요로 하지 않는다. 보호 정책이나 README/reset 동작을 여기로 끌어오지 않는다.
- **T5(mirror) 직접 겹침**:
  - `K/domain/event/GitPostReceiveEventListener.kt`, `HgPostReceiveEventListener.kt`: 생성자 주입, `processIssueReferredFromCommit`, 삭제될 `recordReferredIssues`; 기존 알림 처리 순서도 의미상 접점이다.
  - `T/domain/event/GitPostReceiveEventListenerSpec.kt`, `HgPostReceiveEventListenerSpec.kt`: 생성자 fixture와 실제 저장소/참조 이벤트 기대값.
  - `K/domain/issue/IssueEvent.kt`: T4는 수정하지 않지만 `senderLoginId`, `senderEmail`, `created`, `newValue`, `eventType`이 T5의 초기 SVN 이력 author/commit 시각, revision ID, 멱등성 설계와 의미상 겹친다. `IssueEventRepository.kt`와 새 공통 모듈/그 spec도 T5의 색인 경로와 접점이 된다.
- **T4가 먼저 merge되면 T5는 최신 기반으로 맞춘 뒤 T4 공통 모듈을 이용/이관한다.** Git/Hg의 pusher/처리 시각은 그대로 유지하고, mirror 초기 이력의 author/commit 시각/멱등성이 승인된 시점에 실제 추가 caller 요구만큼 인터페이스를 확장하거나 공통 저장 부분을 이관한다. 현재 모듈을 그대로 호출하면 SVN 초기 이력의 author/commit 시각 요구를 만족하지 못하므로, 이용한다는 이유로 처리 시각을 원본 시각처럼 취급하지 않는다.
- T5가 먼저 해당 부분을 변경하면 구현 전 차이를 재대조해 기존 구현을 재사용하고 중복 모듈을 만들지 않는다. 두 트랙에서 동일 메서드를 각각 유지하거나 기계적으로 conflict만 제거하지 않는다.
- T4는 mirror의 필수 선행조건이 아니다. 독립적으로 검토/merge할 수 있으며 T5 작업을 기다리기 위해 SVN 옵션을 미리 추가하지 않는다. T6 전환은 보류 상태이며 T4에 포함하지 않는다.

## 승인 기준과 정지점

사용자 승인에 따라 구현했다. 완료 기준은 (1) 기존 sender/created/중복 건수의 선행 고정, (2) Git/Hg caller 모두 공통 구현으로 전환하고 중복 메서드 제거, (3) 스키마·SVN·dedup·시각 정책 불변, (4) focused/smoke/full 검증의 실제 결과 또는 정확한 환경 제약 기록, (5) T5 접점의 소유권/기반 정리다.

## 2026-10-08 구현 기록

- 주 실행자가 추출 전 기존 Git/Hg listener 및 parser 3개 spec을 `JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew test -x npmCi -Dyona.it.db=h2`의 `--tests` 필터로 실행: **BUILD SUCCESSFUL, 51s**. sender/처리 시각/중복 ref·bookmark 저장 건수/저장 예외 전파를 고정한 뒤에만 추출했다.
- concrete `CommitIssueReferenceService.record(project, sender, commitId, commitMessage)`로 기존 직접 저장 루프 이동. 양쪽 listener 생성자 및 테스트 fixture를 갱신하고 기존 두 메서드는 삭제했다. 공유 동작 행렬은 service spec 한 곳으로 옮겼다.
- Git/Hg 실제 저장소 연결 테스트는 유지한다. 추출 후 Hg fixture도 2000년 commit date를 사용해 처리 시각과 구분한다(`hg4j CommitCommand.setDate(long, int)` 공개 API 확인).
- T5 구현 담당자에게 finalized seam과 SVN 초기 이력에는 sender/date 정책을 그대로 재사용할 수 없음을 전달했다.
- `pr-title.txt`/`pr-body.md`는 로컬 준비 파일이며 `#ISSUE_04_NUMBER`를 실제 번호로 바꾸기 전 공개하지 않는다. plan/PR 초안은 commit하지 않는다.
- 추출 후 focused: 주 실행자가 service·Git/Hg listener·parser·Git/Hg hooks·IssueEventRecorderKt spec 실행, **BUILD SUCCESSFUL 3m49s**.
- 실제 runtime smoke: 격리된 loopback Spring 앱/H2에서 HTTP bootstrap/login/project/issue 생성 후 Git 두 ref/Hg 두 bookmark push. DB 2/2/다른 프로젝트 0건, pusher/처리 시각/null oldValue/원래 ID/type 모두 SQL 검증. HTTP 타임라인에서도 각 2건 확인. 로컬 웹훅 Git 1회/Hg 2회, 삭제 이후 추가 기록 없음. watcher 미설정으로 notification 행은 0건이며 호출 순서는 focused spec 근거다.
- 실행 증거: `/tmp/yona-t4-smoke/evidence.txt`; 앱/수신기 종료. H2 AUTO_SERVER 호스트 접근 제약으로 DB 직접 조회는 앱 정상 종료 후 실시했다. Hg 빈 remote 최초 push는 `--new-branch` 및 명시적 인증 URL이 필요했다.
- full H2 회귀: 주 실행자 확인 **BUILD SUCCESSFUL 5m55s, 466 suites/6,735 tests/실패 0/오류 0/skipped 2**. `PostgresOAuthLobMigrationSpec` 두 PostgreSQL 전용 사례가 H2에서 비활성화됨을 XML 및 소스로 확인했다. 주 실행자는 지정된 구현/test/doc 7개 파일의 local commit만 승인했으며 push는 직접 담당한다.
