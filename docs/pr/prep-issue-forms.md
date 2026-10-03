# Issue forms preparation (unapproved)

Branch: `prep/issue-forms`. Base: upstream/next `6dae7982a242f672d3132feeeb32a369d21e8d1f`.
This is an isolated preparation branch, not an approved feature or a PR-ready claim. No issue comments, push, or PR creation are authorized.

## Implemented contract

Repository maintainers commit UTF-8 JSON at `HEAD/.yona/issue-templates.json`:

```json
[
  {
    "id": "bug",
    "name": "Bug report",
    "body": "Describe the context (optional Markdown).",
    "fields": [
      {"id": "steps", "label": "Steps to reproduce", "type": "text", "required": true},
      {"id": "platform", "label": "Platform", "type": "select", "required": true, "options": ["Linux", "macOS", "Windows"]}
    ]
  },
  {
    "id": "feature",
    "name": "Feature request",
    "body": "Describe the proposed feature.",
    "fields": [
      {"id": "reason", "label": "Problem to solve", "type": "text", "required": true}
    ]
  }
]
```

- No new parser dependency, database table, or frontend framework. Installed Jackson parses JSON; native HTML controls and Thymeleaf render the selector and fields.
- Each template requires `id` and `name`. `body` and `fields` are optional; absent fields make an ordinary Markdown template. Fields require `id`, `label`, `type`; `required` defaults to false. `select` requires a nonempty `options` array.
- Catalog maximum 64 KiB, 20 templates, 20 fields/template, 50 options/select. IDs are unique within their scope and match `[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}`. Names, single-line labels and options are at most 250 characters. Answers are at most 10,000 characters each.
- No selection or an unknown/deleted template ID retains `HEAD/ISSUE_TEMPLATE.md`, or an empty body when unavailable. Explicit `bodyText` retains its existing precedence. Missing/malformed catalogs do not remove the legacy form; malformed catalogs display a warning.
- GET `/{owner}/{project}/issueform?templateId=bug` selects a form. The chooser loads a new form and explicitly warns that it replaces current input. Parent issue/global-menu context is retained.
- POST `/{owner}/{project}/issues` accepts the existing fields plus optional `templateId` and `answer.<field-id>` parameters. Valid structured answers are appended to the submitted Markdown body, under labeled headings, as literal indented code. Web field validation errors return 400 with the complete submitted form state, including labels, milestone, assignee, due date, draft, parent/target project and temporary uploads. Unknown/deleted templates redisplay the default form with 400; answers for removed templates/fields are recovered as literal code in the editable body, so ordinary retry does not lose them. Invalid select values remain visible for correction.
- GET `/api/projects/{projectId}/issues/templates` returns `{templates, legacyBody, invalidConfiguration}` and requires the existing issue-create permission (not merely anonymous project read access).
- POST `/api/projects/{projectId}/issues` additionally accepts `templateId` and `answers: {"steps":"...", "platform":"Linux"}`. Existing clients omitting both remain unchanged. `body` is the submitted free-form portion; the API does not silently add template `body` defaults. API clients can copy defaults from the catalog. Invalid structured input returns 400 before persistence.
- Both submission paths reload the current HEAD catalog and validate required text, allowed selections, unknown fields and unknown templates on the server, including draft submissions. Repository changes between GET and POST are recoverable in the web form; API clients receive 400 and must refresh the catalog.
- Existing create authorization, normal issue persistence, attachment handling, assignee/milestone/label handling and issue display remain in use. Other creation APIs remain ordinary free-form creation; selecting a form is optional, not a repository-wide policy.
- Names/labels/options/errors use Thymeleaf escaped text/value attributes. Answers are encoded as literal Markdown code (including CR/LF normalization), and label punctuation is escaped; normal free-form bodies still use the existing sanitized Markdown renderer.
- Redisplay renders only the submitted temporary uploads owned by the current user, using existing attachment markers. The attachment widget seeds its hidden submit list with these IDs; removal updates that list, and final attachment moves still recheck ownership/container. Previously persisted or foreign attachments are not rehydrated.
- Submitted Markdown takes precedence over browser local drafts during validation redisplay. Web creation passes the submitted draft flag to the service explicitly, rather than letting the service's default overwrite it.

## Decisions requiring issue approval

JSON catalog path/schema rather than GitHub/Forgejo YAML compatibility; forms remain optional; literal answer formatting rather than rendered user Markdown; schema limits above; validation uses current HEAD instead of pinning a displayed revision. No auto-label assignment or required-form repository policy is implied.

## Verification status

Verified on 2026-10-02 under the coordinator's exclusive slot: production/test compilation succeeded and **218 scoped tests passed** (IssueTemplateServiceSpec 5, IssueControllerSpec 93, IssueViewControllerSpec 120; zero failures/errors). Installed Jackson 3.1.6 API was inspected with `javap`. Initial wildcard test selectors triggered unrelated Postgres Testcontainers static initialization and failed Docker discovery, even with H2 selected. Fully qualified class selectors below avoid that unrelated discovery and passed in 19 seconds; no unrelated tests were altered.

Coordinator command (JDK 21, no Docker needed for these scoped specs):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew test -Dyona.it.db=h2 --tests com.github.yonaprojects.yona.domain.issue.IssueTemplateServiceSpec --tests com.github.yonaprojects.yona.web.IssueControllerSpec --tests com.github.yonaprojects.yona.web.IssueViewControllerSpec --max-workers=1 --no-daemon
```

Real application smoke, only with coordinator permission, isolated data and port:

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew bootRun --args='--spring.profiles.active=h2 --server.port=18104 --yona.data=/tmp/yona-prep-issue-forms-data --yona.git.base-dir=/tmp/yona-prep-issue-forms-data/git --yona.svn.base-dir=/tmp/yona-prep-issue-forms-data/svn --yona.hg.base-dir=/tmp/yona-prep-issue-forms-data/hg --yona.ssh.relay.enabled=false --yona.base-url=http://localhost:18104 --spring.jpa.show-sql=false' --max-workers=1 --no-daemon
```

Actual Chromium/browser + API smoke against the H2 instance:

- Created an isolated admin/project; committed fixture templates directly into that instance's local Git repository (no push).
- The default editor contained `Legacy fallback body`; selected Feature request via the actual chooser and observed its `reason` field and `Proposed feature` body; switched to Bug and observed required text/select controls.
- Clicking Save with missing required text kept the form open and exposed native invalid state.
- Created normal issue #1 through the browser with both labeled answers, including `<script>alert(99)</script>` and Markdown image syntax. The issue displayed those literally; DOM inspection found zero injected scripts/evil images, and code blocks contained the complete answers. Malicious template names/labels also remained text.
- CSRF-authenticated direct web POSTs bypassing native validation returned **400** for blank required text and a forged select value, with the relevant validation messages.
- Catalog API returned **200** with both templates and legacy fallback; anonymous catalog request returned **403**.
- API create returned **400** for an empty required answer and **201** for a completed feature form. Persisted issue #2 contained `API context`, the `Problem to solve` heading and `Less repetitive work`.
- Final list API returned exactly **2** issues, confirming the rejected submissions created no issues.
- Both managed browser tabs were closed; the owned app and single-use Gradle process were stopped. Temporary fixture working files and the initially created empty repository under the default root were removed; the final smoke DB/repository remain isolated under `/tmp/yona-prep-issue-forms-data`.

**Visual evidence limit:** actual browser controls and rendered issue DOM were exercised/observed, but image capture timed out repeatedly through both the browser screenshot helper and direct Chromium capture (including a second visible tab). This tool failure was reported. No screenshot/pixel-level review is claimed. Existing H2 startup also warned about the unrelated `property.value` DDL; issue/form operations above succeeded. Missing/malformed catalogs and unknown IDs are covered by service tests; browser smoke covered the selectable legacy fallback, not a live malformed-catalog edit.

### Review fixes verified 2026-10-04

**241 scoped tests passed**, zero failures/errors/skips: IssueTemplateServiceSpec 5, IssueControllerSpec 93, IssueViewControllerSpec 121, IssueCreateRedisplaySpec 1, AttachmentServiceSpec 21. The new integration test renders real Thymeleaf controls, filters foreign/already-attached uploads, then retries through real issue/attachment persistence. It exposed and fixed the previously hidden service-default override of `isDraft`. Its parent-issue fixture now initializes the project issue counter correctly. Wildcard discovery initially hit the historical unrelated Docker failure described above; exact class selection avoids it.

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew test -Dyona.it.db=h2 -Pkotlin.daemon.jvmargs=-Xmx4g --max-workers=1 \
  --tests com.github.yonaprojects.yona.domain.issue.IssueTemplateServiceSpec \
  --tests com.github.yonaprojects.yona.web.IssueViewControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueCreateRedisplaySpec \
  --tests com.github.yonaprojects.yona.web.IssueControllerSpec \
  --tests com.github.yonaprojects.yona.domain.attachment.AttachmentServiceSpec
```

Actual Chromium verification used a fresh H2 application on port **18244**, with all storage under `/tmp/yona-forms-review.yulX2b` and SSH relay disabled. No remote push was used; template fixtures were committed directly into that isolated application's local Git repository.

- Playwright **3/3 passed** (admin bootstrap, public Git project creation, attachment redisplay regression). The regression uploads two real files, submits a deleted template, checks retained cards/submit IDs and draft/due date/title, removes one upload, retries, then reloads the saved issue's edit form and finds only the retained attachment. A stale local draft does not overwrite recovered answers.
- A separate actual-browser smoke loaded a live required-text/select template, uploaded a file, selected both labels, a milestone and assignee, set due date/draft and submitted blank required text directly to exercise server validation. POST returned **400**. The hydrated Tom Select controls, selected answer, title/body, draft/due date, attachment card and hidden upload ID all retained their values.
- Correcting the answer and clicking Save persisted issue **#2** as **DRAFT**, with both labels, milestone, assignee, due date, labeled answer text and its uploaded attachment, verified via issue and attachment APIs.
- With a template loaded in the browser, removing it in a new local repository commit caused POST to return **400** with a usable default form and both answers recovered. GET of the deleted ID returned **200** with the default form. Clicking Save persisted recovered answers in issue **#3** (normal browser CRLF encoding preserved).
- The owned server and managed browser were stopped. Disposable upload/working-tree fixtures were removed; the isolated H2 database/repository remain at the path above. Existing unrelated H2 `property.value` DDL and analytics DNS warnings were observed; no screenshot/pixel-parity claim is made.

Reproducible browser regression, against a separately started isolated server:

```sh
cd e2e
YONA_BASE_URL=http://localhost:18244 npx playwright test \
  specs/04-project/00-project-create.spec.ts specs/06-issue/issue-attachments.spec.ts \
  --project=chromium --grep 'PUBLIC Git|validation redisplay'
```

**Integration note:** this branch retains the existing scalar `assigneeLoginId` form contract. The separate assignees branch already uses plural `assigneeLoginIds`; when combining them, reconcile the new redisplay `IssueForm` construction, hidden-input binding and tests with that plural contract. The tracked attachment widget bundle contains the hydration fix; its external Vue source is not present in this repository and must retain the same `data-temporary-upload-files` contract when regenerated.

## Approval and rebase gate

After issue approval, fetch the then-current base and rebase this branch, resolving any overlapping controller/DTO/form changes:

```sh
git fetch upstream next
git rebase upstream/next
```

This verified preparation is saved as a local commit on `prep/issue-forms` for the later rebase. After rebasing, rerun scoped tests, coordinated project-wide checks and the real browser/API smoke before creating a PR. No push, PR, or issue comment without later authorization.
