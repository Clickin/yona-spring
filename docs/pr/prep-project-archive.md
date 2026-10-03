# Project archive preparation

Branch: `prep/project-archive`. Base: upstream/next at `6dae7982a242f672d3132feeeb32a369d21e8d1f`.
Proposal: `docs/technical/github-forgejo-feature-gaps-2026-10-02.md`, “종료 프로젝트 읽기 전용 보관”. This is local preparation, not approved issue scope or a submitted PR. No push, PR, or issue comment has been made.

## Implemented behavior

- `Project.archivedAt` is a nullable timestamp; `isArchived` derives from it. Archiving is idempotent and retains the original timestamp. Unarchiving clears it. Visibility and menu flags are not changed.
- Managers, organization administrators, and site administrators can archive/unarchive. Guest accounts cannot. The settings page has a normal CSRF-protected Thymeleaf form and archive banner; no frontend framework was introduced.
- `PATCH /api/v1/projects/{owner}/{project}/settings/archive` accepts `{"archived": true}` or `{"archived": false}` and returns `archived` and `archivedAt`. The `settings` path uses the existing fine-grained ADMINISTRATION write token scope. Project metadata APIs also expose the state.
- The new archive REST route requires CSRF for authenticated ambient sessions, using the existing cookie repository and SPA/form token handler. PathPattern matching also covers encoded path segments. Stateless PAT and signed Bearer calls remain supported; forged PAT headers do not waive session CSRF, and malformed Bearer credentials are rejected by Spring's resource-server decoder. Legacy REST routes keep their prior CSRF policy.
- `POST /{owner}/{project}/settings/archive` handles the web form. This controller alone is exempt from the read-only HTTP gate, and performs its own manager authorization.
- AccessControl rejects non-READ operations before author/member/administrator bypasses. A single MVC interceptor also guards controller-local authorization paths in web, numeric APIs, legacy APIs, and v1 REST APIs. Resource ownership checks in AccessControl consider the actual resource's project as well as the route's project.
- Code, issues, comments, board posts, PRs, wiki pages, attachments, project settings, membership and repository administration remain readable under their existing access rules. Mutating controls are removed or disabled; existing settings read pages retain manager-only access. PR pages do not run the mutating merge preview when archived.
- Git HTTP and SSH reject receive-pack while allowing upload-pack. A shared pre-receive hook rechecks the persisted archive state after authentication, protecting branches, tags, and other refs even with a writable deploy key. Git wiki HTTP and LFS object uploads follow the same project gate.
- SVN DAV writes and Mercurial HTTP/SSH writes are also blocked. Their protocol reads are not inferred from HTTP POST alone: SVN REPORT/PROPFIND and Mercurial read commands retain their existing behavior.
- MVC protocol and attachment paths exclude the servlet context path. A deployment under `/yona`, `/hg`, or `/svn` still blocks archived project-logo deletion through `/files/{id}`; the context prefix cannot grant a protocol exemption.

## Schema and deployment

This repository uses Hibernate `ddl-auto: update`, not Flyway/Liquibase. The nullable `archived_at` column follows that existing convention: existing projects receive NULL and remain writable. There is no NOT NULL/default backfill that can fail against populated tables. H2/MySQL/MariaDB/PostgreSQL/SQL Server/CUBRID mapping is delegated to the existing Instant dialect mapping.

Old backups without this column restore projects as active. New backups include the timestamp through the existing table export. Normal application-driven database replacement is refused while any existing project is archived, rather than bypassing the archive via a destructive restore. Validate schema upgrade and backup compatibility on the deployment database before approval.

## Mutation-path inventory

| Surface | Entrypoints / ownership | Enforcement |
| --- | --- | --- |
| Web and all project REST forms | `/{owner}/{projectName}`, `/projects/{owner}/{projectName}`, `/api/projects/{projectId}`, `/api/v1/projects/{owner}/{project}`, `/-_-api/v1/owners/{owner}/projects/{projectName}`, `/api/{ownerName}/{projectName}` | MVC route-variable resolution rejects non-read methods for archived projects, including old-name aliases. Read-only POST exceptions are limited to `MarkdownController.render`, `IssueController.detectChange`, and `IssueController.commentNotiReceivers`; matching method names in other controllers are not exempt. |
| Issues | IssueController, IssueRestApiController, IssueApiController, IssueViewController, IssueShareController | Creation, update, delete, state changes, publish, bulk update, labels, assignee/sharer changes, weights and votes use HTTP gate plus AccessControl. IssueService move checks both source and destination, and state changes guard the actual project. |
| Comments and board | CommentController, BoardController, BoardRestApiController, BoardApiController, BoardViewController | HTTP gate and AccessControl; CommentService creation checks actual issue/post ownership. Author/sharer shortcuts cannot bypass archive. |
| Milestones and labels | MilestoneController/Api/View, LabelRestApiController, ProjectViewController label/category/copy actions | HTTP gate and resource AccessControl. Copying labels requires writable destination; reading source is unchanged. |
| PRs, reviews and code comments | PullRequestController/Api/View, ReviewApiController/View, CodeHistoryController, `/threads/{id}/open|close`, `/comments/{type}/{id}` | HTTP gate; global ID routes use actual-resource AccessControl. CodeReviewService checks existing thread/PR owners. PR merge and source-branch deletion/restoration guard affected projects. |
| Cross-project deletion | ProjectService delete cascade, deletion of source branches and pushed-branch records | Deleting an active origin cannot detach an archived fork, delete PRs owned by an archived target, or delete an archived source branch. A pushed-branch ID cannot bypass archive through an active project's URL. |
| Wiki web/API | WikiViewController, WikiRestApiController | HTTP gate and archive-aware wiki write permission; list/page/history/diff remain reads. |
| Attachments | `/files`, `/files/{id}`, project logo upload | Unattached uploads remain user-owned temporary resources. Attaching them requires a writable target. Existing issue/post/milestone/review attachments delegate to resource authorization. Global project-logo deletion resolves its container project in the interceptor. Downloads remain reads. |
| Membership and settings | ProjectMemberController, ProjectController, ProjectViewController | HTTP gate includes enrollment, acceptance/rejection, leaving, role changes, logos, settings, labels, transfers, forks and VCS changes. Service guards cover transfer acceptance's legacy GET route, direct project destruction, and fork source. |
| Repository administration | BranchApiController, TagApiController, TagRestApiController, BranchProtectionController, DeployKeyController, WebhookController | HTTP gate plus existing permission checks. Existing manager settings GET/API reads use manager identity independent of archived writability. |
| Watch/favorite/vote | WatchController (including GET notification toggle and global `/unwatch`), FavoriteController, VoteController | AccessControl WATCH gate; route gate for project/favorite issue IDs and votes. |
| Git HTTP | GitAuthorizationFilter, GitServletConfig | Receive-pack advertisement and execution denied; fresh pre-receive check; upload-pack GET/POST untouched. |
| Git SSH | SshAuthServiceImpl, MINA YonaSshGitCommand, SshRelayServer, shared GitSshProtocolHandler | Both user keys and deploy keys denied writes; shared persisted pre-receive check. Existing SSH code-read support remains. SSH wiki support is not added by this branch. |
| Git wiki / LFS | `.wiki.git` project mapping, GitAuthorizationFilter, LfsStorageController | Wiki uses parent archive state. LFS PUT is denied by transport filter and MVC gate. Download/batch read flow remains. |
| SVN HTTP | SvnAuthorizationFilter | GET, HEAD, OPTIONS, PROPFIND, REPORT retain existing read permission. Other DAV methods, including LOCK, MKACTIVITY, CHECKOUT, PUT, PROPPATCH, MERGE and DELETE, are denied. |
| Mercurial | HgAuthorizationFilter, SshAuthServiceImpl, HgSshProtocolHandler, HgPushHooks | HTTP write-command gate; SSH pre-changegroup and pre-pushkey checks, including fresh persisted archive checks on long-lived connections. Read commands remain supported. |
| MCP | McpScopeGuard and existing issue/PR/wiki adapters | Shared scope guard rejects WRITE on archived projects before calling controllers; READ is unchanged. |
| Incoming mail | IncomingMailProcessingService.processTarget | Rejects archived project before any issue/comment/review creation, attachment storage or HTML postprocessing, with an existing rejected-mail outcome. |
| Delayed push and merge events | Git/HgPostReceiveEventListener, PullRequestMergeEventListener | Push handlers query current persisted archive state; PR handlers skip archived target projects; auto-closing issues is guarded. Active fork pushes do not alter archived target PRs. |
| Push metadata | Git/Hg post-push hooks, ProjectRepository.recordPush | Archived projects are skipped. Timestamp persistence updates only lastPushedDate with an `archivedAt IS NULL` condition, avoiding merging a detached pre-archive Project over the archive timestamp. |
| Webhook callbacks | WebhookServiceImpl, WebhookThreadRecorder | Archived project dispatch is skipped; delayed thread-recording callbacks re-read the webhook and skip archived owners. |
| Organization / site destructive paths | OrganizationServiceImpl.renameProjects, SiteService.deleteUser/deleteProject, DataBackupServiceImpl.importAll | Organization rename cannot rename archived project repositories. Account deletion cannot erase archived project memberships. Site project deletion uses the guarded service. Organization deletion already rejects any organization containing projects. Database replacement requires unarchiving first. |
| Non-project housekeeping | Recent-project/issue history, mail delivery queues, notification retention, temporary attachment cleanup, account authentication | These are user/operational records, not edits of project content. Read requests may still record personal visit history; existing notification delivery/retention and unattached-file cleanup continue. |

## Decisions awaiting issue approval

- Archive is stronger than “no content edits”: watch/unwatch, favorite/star toggles in either direction, enrollment, leaving project membership, settings, fork creation, and deletion are frozen as well, even when an action only affects the current user. These actions require unarchiving first; reads and the non-project housekeeping listed above retain their existing policy. This conservative policy is implemented, but still awaits product-scope approval.
- Archive retains visibility, members, feature toggles, open issue/PR state, and existing data. Unarchive restores normal authorization without reconstructing those values.
- No automatic replay of background work skipped during archive. No archive notification/webhook event is introduced.
- Already admitted operations are not force-cancelled or distributed-locked. New HTTP/mail/MCP requests check archive; Git rechecks before ref update and Hg SSH rechecks commands. Archive is not a filesystem/DB transactional snapshot and is not a substitute for revoking OS/database access. Concurrent archive-versus-in-flight operations require an explicit stronger consistency decision if linearizable freezing is required.

## Verification status

### 2026-10-03 pre-PR review fixes

After the interceptor fixes and shared CSRF update were merged locally, `ProjectArchiveSpec` passed **11 tests, zero failures/errors/skips** with JDK 21.0.6. The Gradle invocation completed successfully in 1m 4s:

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem \
  ./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  -Pkotlin.daemon.jvmargs=-Xmx4g \
  --tests com.github.yonaprojects.yona.config.ProjectArchiveSpec
```

A separate Java 21 source-mode smoke invoked the compiled `ProjectArchiveInterceptor.preHandle` directly with Spring servlet request/response objects and in-memory repository proxies. All **19 checks** passed:

- Archived attachment POSTs returned 403 under the root, `/yona`, `/hg`, `/svn`, and `/files` contexts. Attachment GETs and unarchived POSTs passed the interceptor in all five contexts.
- `/yona/hg/owner/repo` POST and `/yona/svn/owner/repo` REPORT/PROPFIND passed the MVC interceptor for protocol-filter classification.
- An unrelated handler named `render` returned 403 instead of gaining the preview exemption.

This smoke exercised the changed interceptor, not a live HTTP server, persistent attachment deletion, or the protocol filters themselves. The focused spec also covers the intended preview-controller exceptions and the watch/unwatch/star/leave/fork policy. Temporary smoke sources and extracted dependencies were removed; no service or database was started.

### Earlier branch verification


Verified locally on 2026-10-02 with JDK 21.0.6 and isolated H2. Production and test compilation succeeded. All 29 selected affected specs passed across the batches below; the final archive-policy/security rerun passed **15 tests, zero failures/errors/skips** in 31 seconds. Actual browser/API and Git HTTP/SSH/SVN/Mercurial smoke also passed as detailed below. This is verified local preparation, not issue approval or PR readiness.

New `ProjectArchiveSpec` covers visibility across scopes, site-manager/author bypass prevention, manager-only reversible state, web/API route guards, attachment ownership, Git/wiki/LFS write rejection, SVN read/write classification, and fresh Git pre-receive state. Existing event and post-push specs were adjusted for the added persistence guards. Removed an existing self-equality/mock-echo timestamp test rather than re-pinning implementation wiring.

Use JDK 21 and one Gradle worker (the host has 16 GiB RAM). Do not run another app/Gradle job concurrently with the parent's verification slot:

```sh
export JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem
./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.config.ProjectArchiveSpec \
  --tests com.github.yonaprojects.yona.config.security.AccessControlSpec \
  --tests com.github.yonaprojects.yona.config.security.AccessControlPullRequestSpec \
  --tests com.github.yonaprojects.yona.domain.vcs.GitPushHooksSpec \
  --tests com.github.yonaprojects.yona.domain.vcs.HgPushHooksSpec \
  --tests com.github.yonaprojects.yona.domain.event.GitPostReceiveEventListenerSpec \
  --tests com.github.yonaprojects.yona.domain.event.HgPostReceiveEventListenerSpec \
  --tests com.github.yonaprojects.yona.domain.sshkey.SshAuthServiceImplSpec \
  --tests com.github.yonaprojects.yona.web.ProjectViewControllerSpec \
  --tests com.github.yonaprojects.yona.web.BranchProtectionControllerSpec \
  --tests com.github.yonaprojects.yona.web.WebhookControllerSpec \
  --tests com.github.yonaprojects.yona.domain.site.DataBackupServiceImplSpec
```

Run the real-filter-chain security regression in the same exclusive slot:

```sh
./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.config.ProjectArchiveCsrfIntegrationSpec
```

This real-chain regression passed. It verifies persisted state remains unchanged for missing/forged/encoded-path session requests, successful cookie/header CSRF, and real scoped PAT/signed OAuth Bearer writes. The first run caught a missing required expiry in the new PAT fixture; adding its one-hour expiry fixed the fixture. Live smoke then caught a lazy Project proxy in the global favorite-issue interceptor path (500 before OpenEntityManagerInView). The resolver now re-reads the project by its ID; the added real-database regression and repeated live request both return 403.

The affected-domain batch also passed (61 seconds):

```sh
./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.domain.project.ProjectServiceImplSpec \
  --tests com.github.yonaprojects.yona.domain.comment.CommentServiceImplSpec \
  --tests com.github.yonaprojects.yona.domain.comment.CommentServiceSpec \
  --tests com.github.yonaprojects.yona.domain.comment.CommentServiceExtraSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueServiceImplSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueServiceImplExtraSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueServiceSpec \
  --tests com.github.yonaprojects.yona.domain.pullrequest.CodeReviewServiceSpec \
  --tests com.github.yonaprojects.yona.domain.pullrequest.PullRequestServiceSpec \
  --tests com.github.yonaprojects.yona.domain.event.PullRequestMergeEventListenerSpec \
  --tests com.github.yonaprojects.yona.domain.mail.IncomingMailProcessingServiceSpec \
  --tests com.github.yonaprojects.yona.domain.organization.OrganizationServiceSpec \
  --tests com.github.yonaprojects.yona.domain.site.SiteServiceSpec \
  --tests com.github.yonaprojects.yona.domain.webhook.WebhookServiceSpec \
  --tests com.github.yonaprojects.yona.domain.webhook.WebhookThreadRecorderSpec \
  --tests com.github.yonaprojects.yona.mcp.McpScopeGuardSpec
```

Observed live evidence:

| Scenario | Observed result |
| --- | --- |
| Manager settings form | Archive persisted; banner appeared; all 21 settings controls disabled, unarchive remained enabled. Unarchive removed banner and restored controls. |
| Archived issue page | Original issue body remained visible, comment submission disabled, new-subtask link absent, watch button disabled. |
| REST/web mutations | Issue create/update/comment/delete, PR create, wiki create, settings update/delete, numeric membership write, and global issue favorite all returned 403 while archived. Original issue title/body remained unchanged. |
| Visibility and authority | Public reads returned 200 anonymously and for outsider; private reads returned 200 for authorized admin and 403 anonymously/for outsider. Outsider unarchive returned 403. Session unarchive without CSRF returned 403. |
| Attached file | Uploaded and attached through the actual web issue form before archive; archived global file deletion returned 403 and download returned 200 with original bytes. |
| Git HTTP and wiki | Archived code/wiki clone and code fetch succeeded; pushes returned HTTP 403. Code push succeeded again after unarchive. |
| Git SSH | Actual isolated MINA server on port 18227: archived clone succeeded; push failed with “Archived project is read-only”; push succeeded after unarchive. |
| Writable deploy credentials | Both HTTPS token and SSH deploy key could read archived refs but could not push; both successfully pushed new refs after unarchive. |
| SVN | Baseline commit revision 1; archived checkout/update succeeded at revision 1; mutation failed E175013 forbidden; restored write committed revision 2. |
| Mercurial | Baseline push succeeded; archived clone/pull succeeded; new changeset push failed HTTP 403; the same pending push succeeded after unarchive. |
| Persistence/destruction | Restart retained exact archive timestamps for four projects. Archived manager deletion returned 403; deletion of an unarchived private project returned 200. |

Smoke used `/tmp/yona-project-archive.P5xq9z` for every DB/VCS/LFS/upload/key path, HTTP 18107 and MINA 18227, with relay disabled. Both owned app processes and the managed browser were stopped; neither listener remained. No shared repositories or existing containers were used.

Limits: screenshots were not attempted after the harness's known screenshot failure; browser evidence is actual DOM/control state, not pixel parity. Full repository suite, non-H2 schema upgrades, external OpenSSH relay/Hg SSH, and a truly simultaneous in-flight archive/write race were not exercised. Fresh pre-receive archive checks have dedicated regression coverage; existing inbound/background specs passed. This does not establish linearizable cancellation of previously admitted operations.

Use fully qualified test names, not wildcard filters: unrelated Kotest discovery can initialize Docker integration databases. Relevant broader follow-up checks are the existing GitSmartHttpProtocolIntegrationSpec, YonaMinaSshServerIntegrationSpec, project deletion/transfer tests, inbound mail tests, and settings template tests.

```sh
SMOKE_DIR="$(mktemp -d /tmp/yona-archive-smoke.XXXXXX)"
./gradlew bootRun --no-daemon --max-workers=1 --args="--spring.profiles.active=h2 --server.port=18107 --yona.data=${SMOKE_DIR}/data --spring.datasource.url=jdbc:h2:file:${SMOKE_DIR}/data/h2/yona;AUTO_SERVER=TRUE;NON_KEYWORDS=VALUE --yona.git.base-dir=${SMOKE_DIR}/git --yona.svn.base-dir=${SMOKE_DIR}/svn --yona.hg.base-dir=${SMOKE_DIR}/hg --yona.lfs.base-dir=${SMOKE_DIR}/lfs --yona.ssh.relay.enabled=false --yona.ssh.mina.enabled=false"
```

Repeatable pre-PR smoke checklist (includes deployment/concurrency checks beyond the local observations above):

1. Bootstrap manager, create public and private projects with issues, attachments and a wiki page; create a nonmanager and a scoped administration token.
2. Archive through settings. Observe banner, disabled existing write forms, removed create/author-edit actions, and available manager unarchive form. Read public pages anonymously; confirm private outsider denial is unchanged; read private pages as member.
3. Read v1 metadata. Attempt issue/comment/PR/wiki/settings/member/logo writes by manager, author and site administrator; expect 403 with existing data unchanged. Attempt legacy numeric/global attachment and transfer-accept routes too.
4. Git HTTP clone/fetch must work; authenticated receive-pack/push, tag changes and wiki pushes must fail. Enable an isolated MINA port for equivalent actual SSH clone/push checks, including writable deploy keys. For SVN, perform checkout/update and rejected commit; for Mercurial, clone/pull and rejected unbundle/pushkey.
5. Unarchive via scoped PATCH or form, confirm normal writes/push resume. Confirm project deletion is refused while archived and only succeeds after unarchive. Exercise archive during a delayed pre-receive/async event for the recheck guards.
6. Restart the isolated application and confirm archive timestamps persist. Exercise an existing populated schema upgrade before deployment.

## Before any PR

Wait for issue approval; record approved decisions and actual verification evidence here. Then, in this worktree:

```sh
git fetch upstream next
git rebase upstream/next
```

Resolve conflicts preserving archive guarantees, rerun focused checks plus live web/HTTP/SSH/SVN/Hg smoke against the then-current upstream/next, and review the complete mutation inventory again. Commit only after verification. Pushing and opening a PR require the later authorized workflow; neither is part of this preparation.
