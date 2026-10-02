# Preparation: multiple issue assignees

Branch: `prep/multiple-assignees`. Base: upstream `next` at `6dae7982a242f672d3132feeeb32a369d21e8d1f`.

This is an unapproved preparation branch, not a release or PR-ready claim. No issue comment, push, or PR has been made.

## Implemented scope

- Issues hold a set of users through `issue_assignee(issue_id, user_id)` with a composite uniqueness constraint. There is no primary assignee, role, or arbitrary limit. Pull requests retain their existing singular assignment.
- Before Hibernate schema update, startup copies each old issue assignment into the relation, drops the issue-only old FK/column, and leaves PR assignment data untouched. Restart does not restore assignments removed after migration. Back up the database before upgrade; rolling back to the singular model requires restoring that backup.
- Creation/edit/detail support multiple selection, individual removal and clearing. Detail mutations keep the other assignees. Mass update supports replacement and clearing, while unrelated mass changes preserve assignments.
- Every assignee participates in my issues, search, counts, dashboards and existing issue permissions. Queries use membership/EXISTS or DISTINCT rather than multiplying issues by the number of assignees.
- Assignment changes record each added/removed person separately and do not coalesce unrelated assignment events. Creation notifies all assigned users through the new-issue notification. Later assignment deltas, issue changes and comments include all assignees; existing unwatch behavior remains in effect, and the initiating user does not receive their own notification.
- REST, legacy plural import/update, MCP, favorites, Excel, migration export/import mapping, project export and webhook assignment fields retain the whole collection. Database backup already discovers every table; the new join table is covered by a roundtrip regression.

## API contract and unapproved compatibility decision

This branch follows the requested clean cutover, **not** the proposal's temporary singular compatibility option:

- Issue create/update JSON: `assigneeIds: [12, 34]`.
- Update omission preserves the set; `assigneeIds: []` clears it. Unknown user IDs are rejected before mutation.
- Issue responses: `assignees: [{id, loginId, name}, ...]`; no singular `assignee` field.
- Numeric `/api/projects/{projectId}/issues/{number}` and owner/project issue REST routes support `POST /assignees/{userId}`, `DELETE /assignees/{userId}`, and `DELETE /assignees`.
- The single-user search selector remains `assignee`; it means membership, not a primary assignee.
- Legacy plural imports retain all `assignees: [{loginId}, ...]` entries. Unknown mappings fail rather than dropping users.
- MCP `create_issue` accepts optional `assigneeIds`; `add_issue_assignee`, `remove_issue_assignee`, and `clear_issue_assignees` use the existing issue WRITE permission scope.

**Approval required before release:** confirm the breaking API cutover/release coordination rather than retaining a temporary singular adapter. In-repository callers are migrated. `yona-cli` is a separate repository and its source is absent from this worktree; its implementation has not been changed or verified here. It must resolve repeatable issue `--assignee` values into `assigneeIds`, send `[]` to clear, omit the field to preserve assignments, and display every response `assignees` member without choosing a primary. PR CLI assignment remains singular. Other external REST consumers need the same coordinated update. This prerequisite is not hidden behind a compatibility shim.

## Verification status

Verified on 2026-10-02 with JDK 21 and isolated H2: **36 affected specs, 1,560 tests, zero failures/errors/skips**. Production and test Kotlin compiled. The final scoped Gradle run completed successfully in 1m 2s. Initial compiler failures exposed positional inbound-mail/PR fixture callers and were fixed; a new watcher test incorrectly deleted notifications without mail markers and was corrected before the passing run.

Targeted checks (JDK 21; one Gradle worker; H2 isolated test mode):

```sh
export JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem
./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.config.IssueAssigneesMigrationSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueServiceImplSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueServiceSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueRepositorySpec \
  --tests com.github.yonaprojects.yona.web.IssueControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueRestApiControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueApiControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueShareControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueViewControllerSpec \
  --tests com.github.yonaprojects.yona.web.LegacyIssueResponseIntegrationSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueExcelServiceSpec \
  --tests com.github.yonaprojects.yona.domain.site.DataBackupServiceH2IntegrationSpec \
  --tests com.github.yonaprojects.yona.domain.watch.WatchServiceSpec \
  --tests com.github.yonaprojects.yona.config.security.AccessControlIssuePostingSpec \
  --tests com.github.yonaprojects.yona.web.IssueListTemplateRenderingSpec \
  --tests com.github.yonaprojects.yona.mcp.IssueMcpToolsSpec \
  --tests com.github.yonaprojects.yona.mcp.McpToolsEndToEndSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueSpecificationSpec \
  --tests com.github.yonaprojects.yona.domain.support.StatisticsServiceImplSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueShareServiceImplSpec \
  --tests com.github.yonaprojects.yona.domain.webhook.WebhookServiceSpec \
  --tests com.github.yonaprojects.yona.config.security.AccessControlSpec \
  --tests com.github.yonaprojects.yona.config.security.AccessControlFinalSpec \
  --tests com.github.yonaprojects.yona.service.MigrationServiceSpec \
  --tests com.github.yonaprojects.yona.web.ProjectApiControllerSpec \
  --tests com.github.yonaprojects.yona.web.ProjectViewControllerSpec \
  --tests com.github.yonaprojects.yona.web.FavoriteControllerSpec \
  --tests com.github.yonaprojects.yona.web.WatchControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueFormSpec \
  --tests com.github.yonaprojects.yona.web.IssueMassUpdateFormSpec \
  --tests com.github.yonaprojects.yona.web.TemplateEquivalenceSpec \
  --tests com.github.yonaprojects.yona.domain.notification.NotificationMessageResolverSpec \
  --tests com.github.yonaprojects.yona.domain.notification.NotificationEventRecorderSpec \
  --tests com.github.yonaprojects.yona.domain.mail.IncomingMailProcessingServiceSpec \
  --tests com.github.yonaprojects.yona.domain.pullrequest.PullRequestServiceSpec
```


The actual app was launched with `./gradlew bootRun --no-daemon --max-workers=1` and the following `--args` (JDK 21):

```text
--spring.profiles.active=h2 --server.port=18101 --yona.base-url=http://localhost:18101
--yona.data=/tmp/yona-prep-multiple-assignees
--yona.git.base-dir=/tmp/yona-prep-multiple-assignees/git
--yona.svn.base-dir=/tmp/yona-prep-multiple-assignees/svn
--yona.hg.base-dir=/tmp/yona-prep-multiple-assignees/hg
--yona.ssh.relay.enabled=false --yona.ssh.mina.enabled=false
```

Observed runtime/browser evidence:

- Chromium selected Alice and Bob in the actual creation control. The submitted form contained both `assigneeLoginIds`; persisted REST detail returned both users.
- Detail chip removal retained Bob; adding Alice restored both; clear-all persisted an empty list after reload. Edit-form clear also persisted an empty list using the marker input.
- Both authenticated users saw exactly one assigned issue/open count and could update the issue without being its author. Removing Alice changed Alice's count to zero while Bob's remained one.
- REST add restored both; unknown ID returned 400; an update omitting `assigneeIds` retained both.
- Project export JSON retained both user entries. Actual Excel download returned HTTP 200, `application/vnd.ms-excel`, 13,824 bytes, and both names; the regression also parses workbook cells.
- The issue list DOM rendered both assignee avatars. Reloaded history showed the removed person's name. Notification audience, per-person event retention, legacy migration/restart and backup roundtrip were exercised by the passing integration specs.
- **Visual limit:** `tab.screenshot()` timed out after 20s despite successful browser navigation, controls and DOM assertions. No screenshot/visual-parity claim is made. The tool failure was reported.

Repeatable smoke checklist:

1. Start the app; create an admin, two users and a project in the isolated instance.
2. In the actual creation form select both users and submit. Confirm both persist on detail and list, and each user's my-issues view/count includes the issue once.
3. On detail remove one user, confirm the other remains, add the user again, then clear all. Reload after each mutation. Repeat clearing in edit and check the marker-only empty form submission.
4. Exercise REST membership add/remove/clear, unknown-user rejection, and omitted-update preservation. Verify non-author assignees have the existing issue update/comment authorization.
5. Inspect person-level history/notifications; export Excel/project JSON and verify the full collection. The database migration/backup regression covers old data preservation and restart behavior.
6. Verify the assignment controls visually in Chromium and close the smoke browser/app afterward.

H2 regression coverage is not evidence of MariaDB/PostgreSQL/MySQL/SQL Server/CUBRID migration execution. A real supported-database upgrade matrix remains necessary before release.

## After issue approval

Do not push this preparation branch now. Before a PR, fetch the then-current branch and rebase:

```sh
git fetch upstream next
git rebase upstream/next
```

Resolve actual overlap with other feature branches, rerun the affected tests and full runtime/browser smoke against the new base, record the outputs, and complete the external API/CLI release coordination. Only then prepare the approved PR.
