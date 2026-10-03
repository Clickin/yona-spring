# Saved issue views — preparation branch

Branch: `prep/saved-searches`. Base: upstream `next` at `6dae7982a242f672d3132feeeb32a369d21e8d1f`.

Status: implemented and verified locally on 2026-10-02; prepared for issue discussion, not approved for PR. Local preparation commit only; no push, PR or issue comments.

## Implemented scope

- Issue-list link carries the current supported filters, sort and page size to a server-rendered saved-views page. Native forms create, rename and delete; opening rechecks authorization before returning to the project issue list.
- `PERSONAL` views belong only to their owner. `PROJECT` views are shared with signed-in users who can read that project; project managers and the existing organization/site administrator overrides may modify shared views.
- Supported parameters: `state`, `filter`, `authorId`, `assigneeId`, `milestoneId`, `commenterId`, repeated/comma-separated `labelIds`, `dueDate`, `orderBy`, `orderDir`, `itemsPerPage`. Sort fields are the four existing UI choices (`createdDate`, `updatedDate`, `dueDate`, `numOfComments`). Reopening starts at page one; pagination, selected details and export format are not saved.
- Inputs are validated and encoded as query parameters, never stored as a URL. URL-shaped search text remains search text. Links use the current project identity, supporting project renames/transfers. Unknown parameter names and invalid bounds are rejected.
- New `saved_issue_view` entity/table follows the existing Hibernate `ddl-auto: update` schema convention. Indexed project/owner references have database delete cascades. Soft user deletion explicitly removes personal views; shared views have no user owner and remain with the project.
- REST API at `/api/v1/projects/{owner}/{projectName}/issues/saved-views`: GET list, POST create, GET `/{id}`, PATCH `/{id}` rename, DELETE `/{id}`, GET `/{id}/open` redirect. Existing issues token scopes and session CSRF protection apply. Web mutations are POST forms with Thymeleaf CSRF support.
- Browser smoke exposed that the existing REST security chain globally disabled CSRF despite accepting sessions. Saved-view mutations now specifically require CSRF for ambient sessions using the existing cookie/SPA handler and decoded `PathPatternRequestMatcher`; other REST routes are unchanged. Stateless scoped PAT clients remain supported. Invalid bearer credentials fail authentication rather than gaining a session-header exemption.

Create example:

```json
{"name":"Unassigned by due date","visibility":"PERSONAL","parameters":{"state":["open"],"assigneeId":["-1"],"orderBy":["dueDate"],"orderDir":["asc"]}}
```

Rename body: `{"name":"New name"}`. API responses include visibility, normalized parameters, generated local list URL and whether the viewer can edit the view.

## Decisions requiring issue approval

Both visibility levels are included, independently. Shared visibility follows project read permission, including signed-in readers of public projects, rather than limiting shared views to direct project membership. Anonymous users cannot browse saved views. Visibility is immutable; create another view to change it. Names need not be unique and are limited to 100 characters. Search text is limited to 1,000 characters and encoded parameter storage to 4,096 characters. Page size retains the existing maximum of 45. No query DSL, cross-project views or frontend architecture change.

## Verification

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

## Before any PR

Obtain issue approval and reconcile the decisions above. Then in this worktree:

```sh
git fetch upstream next
git rebase upstream/next
```

Resolve conflicts and rerun targeted and project-wide checks plus runtime/browser smoke against the then-current upstream. Record fresh results before preparing the final PR. No push or external comments are authorized by this preparation task.
