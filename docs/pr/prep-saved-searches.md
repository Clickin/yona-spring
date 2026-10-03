# Saved issue views — preparation branch

Branch: `prep/saved-searches`. Base: upstream `next` at `6dae7982a242f672d3132feeeb32a369d21e8d1f`.

Status: implemented locally on 2026-10-02 and review fixes verified on 2026-10-04; prepared for issue discussion, not approved for PR. Local preparation commits only; no push, PR or issue comments.

## Implemented scope

- Issue-list link carries the current supported filters, sort and page size to a server-rendered saved-views page. Native forms create, rename and delete; opening rechecks authorization before returning to the project issue list.
- `PERSONAL` views belong only to their owner. `PROJECT` views are shared with signed-in users who can read that project; project managers and the existing organization/site administrator overrides may modify shared views.
- Supported parameters: `state`, `filter`, `titleHead`, `literalFilter`, `authorId`, `assigneeId`, `milestoneId`, `commenterId`, repeated/comma-separated `labelIds`, `dueDate`, `orderBy`, `orderDir`, `itemsPerPage`. Sort fields are `createdDate`, `updatedDate`, `dueDate`, `numOfComments`, and Lucene's `relevance`. `literalFilter` accepts exactly `true` or `false`. Reopening starts at page one; pagination, selected details and export format are not saved.
- On this standalone DB-backed branch, `orderBy=relevance` uses `createdDate DESC`, matching the Lucene controller's DB/no-search fallback regardless of `orderDir`. The stored parameters, redirect and list model retain `relevance`; merging Lucene must keep its existing `Sort.unsorted()` path for nonblank Lucene searches.
- Inputs are validated and encoded as query parameters, never stored as a URL. URL-shaped search text remains search text. Links use the current project identity, supporting project renames/transfers. Every management-page form/link and API response URL respects the servlet context path. Unknown parameter names and invalid bounds are rejected.
- New `saved_issue_view` entity/table follows the existing Hibernate `ddl-auto: update` schema convention. Indexed project/owner references have database delete cascades. Soft user deletion explicitly removes personal views; shared views have no user owner and remain with the project.
- REST API at `/api/v1/projects/{owner}/{projectName}/issues/saved-views`: GET list, POST create, GET `/{id}`, PATCH `/{id}` rename, DELETE `/{id}`, GET `/{id}/open` redirect. Existing issues token scopes and session CSRF protection apply. Web mutations are POST forms with Thymeleaf CSRF support.
- Prerequisite `fix/session-api-csrf` protects all ambient-session mutations under `/api/v1/**` using the shared cookie/SPA handler; there is no feature-specific endpoint matcher. Stateless scoped PAT clients remain supported. Invalid bearer credentials fail authentication rather than gaining a session-header exemption.

Create example:

```json
{"name":"Unassigned by due date","visibility":"PERSONAL","parameters":{"state":["open"],"assigneeId":["-1"],"orderBy":["dueDate"],"orderDir":["asc"]}}
```

Rename body: `{"name":"New name"}`. API responses include visibility, normalized parameters, generated local list URL and whether the viewer can edit the view.

## Decisions requiring issue approval

Both visibility levels are included, independently. Shared visibility follows project read permission, including signed-in readers of public projects, rather than limiting shared views to direct project membership. Anonymous users cannot browse saved views. Visibility is immutable; create another view to change it. Names need not be unique and are limited to 100 characters. Search text and title heads are each limited to 1,000 characters with control characters rejected; encoded parameter storage is limited to 4,096 characters. Page size retains the existing maximum of 45. No query DSL, cross-project views or frontend architecture change.

## Verification

### Initial verification — 2026-10-02

Passed: production/test compilation and 40 tests across four scoped specs (0 failures/errors/skips; final run `BUILD SUCCESSFUL in 27s`). H2 integration tests exercised actual visibility queries, user soft-delete cleanup and project FK cascades.

Targeted command (JDK 21, one Gradle worker; H2 integration database is isolated in memory):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew --no-daemon --max-workers=1 test -Dyona.it.db=h2 --tests com.github.yonaprojects.yona.web.SavedIssueViewControllerSpec --tests com.github.yonaprojects.yona.domain.issue.SavedIssueViewRepositorySpec --tests com.github.yonaprojects.yona.domain.site.SiteServiceSpec --tests com.github.yonaprojects.yona.config.SavedIssueViewSecuritySpec
```

The HTTP regression spec covers query round-trip/encoding, CRUD, personal privacy even from another manager, project isolation, revoked access, manager-only shared writes, web mutation authorization and malicious parameter rejection. The security integration spec verifies session POST/PATCH/DELETE without CSRF are rejected; encoded routes and spoofed PAT headers cannot bypass protection; malformed Bearer authentication is rejected; genuine stateless issues-write PAT requests work and read-only PAT writes fail.

Runtime command used (restart added `--spring.jpa.show-sql=false` to reduce log noise):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew --no-daemon --max-workers=1 bootRun --args='--spring.profiles.active=h2 --server.port=18105 --yona.data=/tmp/yona-prep-saved-searches-smoke --yona.git.base-dir=/tmp/yona-prep-saved-searches-smoke/git --yona.svn.base-dir=/tmp/yona-prep-saved-searches-smoke/svn --yona.hg.base-dir=/tmp/yona-prep-saved-searches-smoke/hg --yona.ssh.relay.enabled=false'
```

Observed browser/runtime smoke on port 18105 with isolated file-backed H2 and VCS paths:

- Created an administrator and project through the real UI; seeded two differently titled issues through the API. Saved `filter=needle`, `orderBy=updatedDate`, `orderDir=asc`, `itemsPerPage=30` through the issue-list link and native form. Reopening retained those parameters and displayed only the matching issue.
- Renamed and deleted views through native forms. Created a project-shared view through the UI; a separately registered/logged-in reader saw only the shared view (no edit buttons) and successfully opened the full issue list. The personal open endpoint returned 404; shared create/rename/delete returned 403 for that reader.
- Made the project private as administrator; the reader's saved-view list/open requests returned 403, then restored public visibility.
- API rejected an arbitrary `url` parameter with 400. Saved and reopened URL-shaped Unicode search text with repeated label IDs; it remained on the local issue list with the exact search text and both label parameters.
- Stopped/restarted the application against the same isolated H2 file; personal and shared views, names and filters persisted.
- Raw HTTP requests carrying the actual browser session but no CSRF token now return 403 for POST/PATCH/DELETE (before the scoped fix, POST returned 201). A spoofed `Yona-Token` returned 403; malformed Bearer returned 401; valid session CSRF PATCH returned 200.

Limits: browser evidence is DOM interaction/navigation, not screenshot/pixel verification (the supervising session reported screenshot-tool timeouts and accepted DOM proof). Non-H2 database backends and the project-wide suite were not run. The pre-existing H2 `property.value` DDL warning appeared at startup; it did not block saved-view schema creation or the exercised flows.

### Review-fix verification — 2026-10-04

Passed 10 tests across the controller, repository and security specs, with zero failures/errors/skips (`BUILD SUCCESSFUL in 53s`):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew --no-daemon --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g test -Dyona.it.db=h2 --tests com.github.yonaprojects.yona.web.SavedIssueViewControllerSpec --tests com.github.yonaprojects.yona.domain.issue.SavedIssueViewRepositorySpec --tests com.github.yonaprojects.yona.config.SavedIssueViewSecuritySpec
```

Added regression coverage for every management-page action/link under `/yona`, context-prefixed API URLs and redirects, Lucene parameter round trips, and invalid boolean/title-head values.

Runtime command (current-source `bootRun`, not an existing jar):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew --no-daemon --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g bootRun --args='--spring.profiles.active=h2 --server.port=18105 --server.servlet.context-path=/yona --spring.jpa.show-sql=false --yona.data=/tmp/yona-review-saved-context-20261003-repair --yona.git.base-dir=/tmp/yona-review-saved-context-20261003-repair/git --yona.svn.base-dir=/tmp/yona-review-saved-context-20261003-repair/svn --yona.hg.base-dir=/tmp/yona-review-saved-context-20261003-repair/hg --yona.ssh.relay.enabled=false --logging.level.root=ERROR'
```

- Browser DOM/native-form smoke created, renamed and deleted a personal view carrying `filter`, `titleHead`, `literalFilter=true` and `orderBy=relevance`. All three forms included CSRF inputs and `/yona` actions; open/back links retained the prefix.
- A second view with `orderBy=updatedDate` opened the rendered issue list with its saved filter intact. The issue-list save link returned to management successfully; the management back link returned to `/yona/admin/saved-context/issues`.
- Raw HTTP using the browser session exercised API create (201), get/list (200), rename (200), open (302), and delete (204). Unicode, literal brackets, plus signs and ampersands survived parameter storage; the open `Location` and every response URL included `/yona` exactly once. Invalid `literalFilter=yes` returned 400.
- Raw session POST/PATCH/DELETE without a CSRF token each returned 403. Page `fetch` is automatically CSRF-decorated, so the negative check deliberately used an out-of-page HTTP client rather than that wrapper.

Limits: this branch accepts/preserves Lucene parameters; Lucene result execution belongs to the separate Lucene worktree and was not exercised here. The initial administrator was created using the same isolated H2 database without a context path because the existing bootstrap redirect drops `/yona`. Existing context-unsafe static assets and login redirect also required native form submission/direct navigation during setup; those unrelated paths were not changed. Runtime evidence is DOM/HTTP, not visual parity. The pre-existing H2 `property.value` DDL warning remained. Owned smoke services and browser tab were closed after verification.

### Standalone relevance regression — 2026-10-04

Fixed the saved-view open target passing `relevance` to JPA as if it were an `Issue` property. The correction is only in the issue-list DB sort; the saved-view codec still accepts and preserves relevance.

Passed 11 tests across `SavedIssueViewControllerSpec`, `SavedIssueViewRepositorySpec` and `SavedIssueViewSecuritySpec`, with zero failures/errors/skips (`BUILD SUCCESSFUL in 27s`). The new H2 integration regression creates saved views through the secured API, opens them and follows the redirect through the real controller and Thymeleaf renderer. It asserts the DOM's newest/middle/oldest order using fixed creation timestamps deliberately different from insertion order, both without search text and with `filter=Needle`, including `orderDir=asc`. Stored parameters, redirect and rendered model retain `orderBy=relevance`.

Exact Gradle invocation (`SMOKE_INIT` was a temporary external init script registering `savedSmokeClasspath` to write `sourceSets.test.runtimeClasspath.asPath` to `build/saved-smoke-classpath.txt`):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew --no-daemon --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g -I "$SMOKE_INIT" -Dyona.it.db=h2 test --tests com.github.yonaprojects.yona.web.SavedIssueViewControllerSpec --tests com.github.yonaprojects.yona.domain.issue.SavedIssueViewRepositorySpec --tests com.github.yonaprojects.yona.config.SavedIssueViewSecuritySpec savedSmokeClasspath
```

Replayed the unchanged standalone `SavedRelevanceProbe.java` against the freshly compiled application, not a mocked repository or existing jar:

```sh
/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem/bin/java -cp "$(cat build/saved-smoke-classpath.txt)" "$SAVED_PROBE" --spring.profiles.active=h2 --server.port=0 '--spring.datasource.url=jdbc:h2:mem:saved-relevance-fixed;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE' --spring.jpa.show-sql=false --yona.data=/tmp/yona-saved-relevance-fixed-20261004 --yona.git.base-dir=/tmp/yona-saved-relevance-fixed-20261004/git --yona.svn.base-dir=/tmp/yona-saved-relevance-fixed-20261004/svn --yona.hg.base-dir=/tmp/yona-saved-relevance-fixed-20261004/hg --yona.ssh.relay.enabled=false --logging.level.root=ERROR
```

`SAVED_PROBE` names the original external repro source. Observed: `create=201`, `open=302 target=/review-owner/saved-probe/issues?orderBy=relevance`, `followed=200`; process exit 0. Before the correction, this same follow threw `PropertyReferenceException: No property 'relevance' found for type 'Issue'`. The Spring context/server closed on completion. `NON_KEYWORDS=VALUE` avoids the unrelated H2 reserved-column warning.

Limits: only the scoped H2 tests and standalone actual-path smoke ran; Lucene ranking and non-H2 databases were not executed in this worktree. The combined controller's existing Lucene dispatch remains a merge requirement, not code to replace with the standalone fallback.

## Before any PR

Obtain issue approval and reconcile the decisions above. Then in this worktree:

```sh
git fetch upstream next
git rebase upstream/next
```

Resolve conflicts and rerun targeted and project-wide checks plus runtime/browser smoke against the then-current upstream. Record fresh results before preparing the final PR. No push or external comments are authorized by this preparation task.
