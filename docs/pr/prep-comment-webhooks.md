# Comment webhook preparation

Branch: `prep/comment-webhooks`, based on upstream `next` at `6dae7982a242f672d3132feeeb32a369d21e8d1f`.

## Implemented scope

- New issue, posting, PR review, commit review and commit comments publish a separate webhook event. Delivery resolves the committed comment after transaction commit, independently of personal notification recipients. The notification listener ignores the corresponding personal creation event to avoid duplicate dispatch.
- The commit-comment JSON controller uses the same comment-creation service as the other entry points, retaining its existing authorization and commit-existence checks.
- Four settings (`issueComment`, `postingComment`, `reviewComment`, `commitComment`) select new comments by parent. PR reviews use `reviewComment`; reviews on standalone commits and ordinary commit comments use `commitComment`.
- Existing web settings create/list/delete workflow is retained. Select the desired types when registering a webhook; replace an existing registration to change its settings. The JSON list API exposes the effective choices, and `POST /api/v1/projects/{owner}/{project}/webhooks` accepts these booleans with the existing URL/secret/push/type fields and manager authorization.
- JSON retains existing envelope fields and adds `action: created`, `comment: {id, body, url, author: {id, login, name}}`, and `parent: {resourceType, id, url, number?, title?}`. Commit parent identity is its commit hash; commits have no issue/PR number or title. Slack detailed attachments contain the comment body once, not the parent PR body or a duplicate in top-level text. Comment bodies escape `&`, `<`, and `>` at the Slack attachment boundary, preventing injected special mentions and link syntax while preserving formatting and generated resource links. Simple text and JSON retain the original body; links point to the comment anchor and identify the parent.
- Slack destinations use `DETAIL_SLACK`. `SIMPLE` is a generic `{"text": ...}` payload, not a Slack-safe rendering format. `SIMPLE` and `JSON` preserve user text for receivers to interpret or escape. Configuring a Slack URL with `SIMPLE` does not apply Slack escaping.
- PR comment URLs target the changes page (including a selected commit where needed), rather than the overview without comment anchors. Ordinary commit comments created through the JSON API are now rendered on Git/Hg commit pages too, using the existing SVN comment fragment and a `commit-comment-` anchor prefix to avoid collisions with review-comment IDs.
- No new edit/delete events, retry queue, delivery history or resend infrastructure. Existing non-creation delivery, push filtering, secret header and project configuration permissions are unchanged.

## Schema and unapproved decisions

This repository uses Hibernate `ddl-auto: update`, not versioned migration scripts. Four nullable boolean columns are added to `webhook`. A null value (existing rows) means enabled; new registrations default to enabled. This avoids vendor-specific backfill SQL and preserves existing broad subscriptions.

Upstream has not approved the event selection names/defaults, grouping of commit reviews with commit comments, payload contract, or replacement-only editing UX. The implementation is a proposal, not an agreed public contract. Private-project hooks remain project-configured integrations; this change does not add access grants or expose payloads to personal notification recipients.

Exactly once here means one dispatch per committed creation through the application event paths. Network delivery remains best-effort and is not an exactly-once transport guarantee. The asynchronous listener can skip a resource deleted before it resolves it; durable delivery is intentionally the separate queue proposal.

## Verification

Review repair verified on 2026-10-04: `./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 --tests com.github.yonaprojects.yona.domain.webhook.WebhookServiceSpec` completed with `BUILD SUCCESSFUL in 1m 40s`. The new payload regression covers issue, posting, review, and commit comments, including unchanged SIMPLE/JSON bodies.

A separate temporary Java source-launcher smoke invoked the compiled `WebhookServiceImpl.sendWebhook` with a commit comment and captured its real HTTP POST on a loopback receiver returning 204. Repository dependencies supplied an in-memory webhook; no application database or external Slack endpoint was used. The captured attachment contained `*bold*\nA &amp; B &lt;!channel&gt; &lt;!here&gt; &lt;https://evil.example|label&gt; &amp;lt;literal&amp;gt;`, while top-level text retained `<https://yona.example.com/owner/smoke/commit/abc123#commit-comment-3|abc123>`. Assertions passed for escaped injected syntax, preserved formatting/link construction, and exactly one body occurrence. The receiver was stopped and temporary source/runtime files removed afterward.

Verified on 2026-10-02 with JDK 21 and isolated H2: **221 tests across seven specs passed** (`BUILD SUCCESSFUL in 31s`). An initial compilation error in the new test's property name was fixed. Obsolete tests requiring parent-PR bodies or exact legacy comment wording were removed rather than preserving the superseded payload contract.

Targeted command (JDK 21, one Gradle worker; H2 is isolated per test JVM):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.domain.webhook.CommentWebhookIntegrationSpec \
  --tests com.github.yonaprojects.yona.domain.webhook.WebhookServiceSpec \
  --tests com.github.yonaprojects.yona.domain.webhook.WebhookNotificationEventListenerSpec \
  --tests com.github.yonaprojects.yona.web.WebhookControllerSpec \
  --tests com.github.yonaprojects.yona.web.WebhookRestApiControllerSpec \
  --tests com.github.yonaprojects.yona.web.CodeHistoryControllerSpec \
  --tests com.github.yonaprojects.yona.web.CodeViewControllerSpec
```

`CommentWebhookIntegrationSpec` exercises real service creation, transaction commit/rollback and a local HTTP receiver for issue, posting, PR general review, PR diff review, standalone commit review and ordinary commit comments with no personal notification rows, checking selected type routing, unique delivery, author identity and parent/comment URLs. `WebhookServiceSpec` also exercises real outbound HTTP for Slack review and commit content. The existing controller tests cover authorization and response redaction.

Actual application smoke ran on port `18102`, with data under `/tmp/yona-comment-webhooks-smoke`, explicitly isolated Git/SVN/Hg roots, and the SSH relay disabled. Observed:

- Browser-created issue-only JSON hook persisted `issueComment=true` and the other three flags `false`; list API and settings page showed the selection.
- Personal-receiver preview returned `{"receivers":[]}`. A self-authored issue comment produced exactly one request at a local HTTP receiver; the unsubscribed posting comment produced none.
- A JSON hook created through the new API received one request each for a subscribed posting comment, general PR comment, ranged PR diff review, ordinary commit comment through the JSON API, and standalone commit review through the web form. Captured payloads contained their actual bodies, author identity, and parent identity/URLs.
- Following PR links rendered the general/diff bodies at `#comment-1` and `#comment-2` on `/pull/1/changes`. The commit page rendered both the ordinary comment at `#commit-comment-1` and the independent review at `#comment-3`.
- A live Slack-format hook received actual PR-review and ordinary-commit bodies plus their direct links. No parent PR body substitution.
- A separately registered, logged-in non-manager received HTTP 403 when creating a private project's webhook.
- After these smoke comments, the isolated database contained **zero `notification_event` rows**.
- Upgrade smoke stopped the app, removed only the four new columns from the isolated webhook table, and restarted it. Hibernate recreated the nullable columns; all three pre-feature registrations reported all four subscriptions enabled, and a posting comment delivered once to each hook.

Both owned application processes, their Gradle runners, the browser tab and HTTP receiver were stopped afterward. No shared database or unrelated process/container was touched.

Limits: screenshot capture timed out through both the browser helper and raw Chromium screenshot call (tool issue reported); actual DOM, controls, navigation and HTTP delivery were verified, but no screenshot artifact is claimed. H2 startup logged an unrelated existing `property.value` reserved-word DDL warning; it did not block the exercised feature paths. The full suite and other supported database engines have not been run for this branch.

Reproduce the isolated app launch (choose a fresh data directory for a new smoke):

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem ./gradlew bootRun --no-daemon --max-workers=1 --args='--spring.profiles.active=h2 --server.port=18102 --yona.base-url=http://127.0.0.1:18102 --yona.data=/tmp/yona-comment-webhooks-smoke --yona.git.base-dir=/tmp/yona-comment-webhooks-smoke/git --yona.svn.base-dir=/tmp/yona-comment-webhooks-smoke/svn --yona.hg.base-dir=/tmp/yona-comment-webhooks-smoke/hg --yona.ssh.relay.enabled=false --spring.jpa.show-sql=false'
```

## Before any PR

A local preparation commit is authorized after verification. No push, PR or issue comment is authorized. After issue approval:

```sh
git fetch upstream next
git rebase upstream/next
```

Resolve any contract changes, rerun the targeted checks and actual application/UI smoke against then-current `upstream/next`, and update this record with observed results before preparing a PR.
