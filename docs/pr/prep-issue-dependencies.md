# Issue dependencies preparation

Branch: `prep/issue-dependencies`. Preparation base: upstream `next` at
`6dae7982a242f672d3132feeeb32a369d21e8d1f`. This is an unapproved implementation,
not an approved issue or a submitted PR. No push, PR, or issue comment is part of
this preparation.

## Implemented scope and decisions awaiting approval

- A directed predecessor → successor relationship means “A blocks B”. It is
  separate from `Issue.parent`; neither relation creates or changes the other.
- Initial edges are **same-project only**. Both endpoints must be readable and
  editable under the existing issue authorization rules. This avoids granting
  write authority over another issue merely by owning the first issue.
- Existing edges remain visible after closure. An edge actively blocks only when
  neither endpoint is `CLOSED` or `RESOLVED`. Reopening restores its effect. There
  is no automatic state change, scheduling engine, due-date propagation, or new
  notification contract.
- Each response filters neighboring issues independently, including draft-author
  visibility and private issue sharing. Hidden titles, numbers, states, and counts
  are not returned; the blocked indicator describes **visible** dependencies only.
- Moving an issue to another project removes its incoming and outgoing edges.
  The existing move of direct subtasks removes their edges too. This deliberately
  does not carry a dependency across a project/privacy boundary, even when both
  endpoints are moved together. A same-project no-op move preserves edges.
- English and Korean labels are provided; other locales use the existing root
  bundle fallback.

## Web and API

The issue detail page has “Is blocked by” / “Blocks” lists, current neighboring
issue states, active/inactive status, an issue-number add form with direction,
and per-edge removal. Native server-rendered forms use the existing CSRF
protection and redirects; no frontend framework or new JavaScript is required.

The prerequisite `fix/session-api-csrf` requires CSRF for all `/api/v1/**`
mutations carrying an authenticated ambient session, including dependency routes.
Feature branches no longer replace the API chain with competing endpoint matchers.
Forged PAT headers do not exempt a session. Stateless PAT/OAuth requests retain
their existing authentication.

After integrating the shared policy, `IssueDependencyIntegrationSpec` and
`SessionApiCsrfSpec` passed on JDK 21/H2 (2026-10-03, `BUILD SUCCESSFUL in 1m 22s`).

The scoped REST API uses the existing `ISSUES` read/write and repository token
scope authorization:

- `GET /api/v1/projects/{owner}/{project}/issues/{number}/dependencies`
  returns `blockedBy`, `blocking`, `blocked`, and `canEdit`. Each visible item
  contains `number`, `title`, `state`, `active`, and `canRemove`.
- `POST /api/v1/projects/{owner}/{project}/issues/{number}/dependencies/{successorNumber}`
  adds `{number} → {successorNumber}`. No request body; success is 201.
- `DELETE` at the same edge URL removes that edge; success is 204.
- Self-edge: 400. Duplicate or cyclic edge: 409. Readable but noneditable
  endpoint: 403. Missing or unreadable endpoint: the same 404 without metadata.

## Schema, concurrency, and cleanup

This repository uses Hibernate `ddl-auto=update`, not a numbered migration
runner. The additive `IssueDependency` entity creates `issue_dependency` on
upgrade with two nonnullable issue foreign keys, a unique predecessor/successor
pair, a no-self-edge check, and an index for incoming links. Existing issues and
parent relations are not transformed or backfilled. No hand-maintained duplicate
SQL migration is introduced.

Mutations acquire a transaction-scoped **database project-row mutex** through an
UPDATE before checking or changing the graph. The graph and endpoint refreshes
use current locking reads, including under MariaDB/MySQL's REPEATABLE_READ
isolation, rather than an earlier snapshot. The mutex is database-backed, not a
single-process JVM lock. Cycles are checked across all edges, including inactive
ones, so reopening cannot introduce a cycle. The scan is O(E) per project edit.

Issue deletion and project deletion use the existing `deleteIssueCascade` path,
which now removes dependency rows before deleting the issue. Project moves take
ordered source/destination locks before edge cleanup. Foreign keys reject orphan
edges even if a caller bypasses the service; database cascade paths are not used,
so SQL Server's multiple-cascade-path restriction is avoided.

## Verification observed on 2026-10-02

- Production and test compilation passed with JDK 21.
- H2 scoped batch: **127 checks passed** — 6 dependency integration scenarios,
  1 additive-schema migration check, and 120 existing issue-view controller checks.
  The first run exposed test teardown retaining this fixture's notification
  recipients; fixture-specific notification cleanup was added and the complete
  batch passed on rerun (54 seconds).
- MariaDB 10.11: **all 6 integration scenarios passed**, including concurrent
  graph mutations (1 minute 45 seconds). A fresh Testcontainers database was used
  with reuse disabled; connection logs confirm the MariaDB JDBC driver.
- The schema check preserved existing issues and parent relations when adding
  the table and rejected duplicate, self, and orphan rows.
- Security checks passed through the real filter chain: scoped PAT READ/WRITE,
  session CSRF requirements, forged token headers, and valid cookie/header CSRF.
  Private/shared and draft visibility, both-endpoint edit authorization, project
  move cleanup, and issue deletion cleanup passed in both integration runs.

Commands executed from this worktree:

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem \
DOCKER_HOST=unix:///Users/senghyunjo/.orbstack/run/docker.sock \
./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.domain.issue.IssueDependencyIntegrationSpec \
  --tests com.github.yonaprojects.yona.config.IssueDependencySchemaTest \
  --tests com.github.yonaprojects.yona.web.IssueViewControllerSpec

JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem \
DOCKER_HOST=unix:///Users/senghyunjo/.orbstack/run/docker.sock \
TESTCONTAINERS_REUSE_ENABLE=false \
./gradlew test -Dyona.it.db=mariadb --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.domain.issue.IssueDependencyIntegrationSpec
```

Use fully qualified test names, not wildcard selectors: unrelated Kotest
initializers can otherwise start Docker even with the H2 flag. PostgreSQL,
MySQL, SQL Server, and CUBRID were not run for this branch.

### Running browser/API smoke

A real headless Chromium session bootstrapped a disposable administrator and
created project `admin/dependency-smoke` plus issues A/B. Native detail forms
added A as B's predecessor; both details rendered the correct issue link, state,
and active status. Closing A showed the retained but inactive edge on B without
the blocked banner; reopening restored blocking. Reverse and self-edge attempts
displayed their localized errors. Removing from A cleared both details.

Real session API results: missing CSRF **403**, forged PAT header **403**, valid
CSRF add **201**, duplicate **409**, self-edge **400**, cycle **409**, delete
**204**, anonymous write **401**. A linked draft predecessor was visible to its
author but absent from the anonymous response, including its title, number,
state, and blocked indicator. The last edge was removed and the empty detail
section was observed before closing the tab.

DOM text, accessible controls, server responses, and persisted state were
observed. **No screenshot/pixel-level visual verification** was attempted because
the coordinator reported the screenshot tool unavailable.

Smoke launch used a newly allocated `/tmp/yona-dep-smoke.IXWbfK` directory:

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem \
./gradlew bootRun --no-daemon --max-workers=1 \
  --args='--spring.profiles.active=h2 --server.port=18103 --yona.base-url=http://localhost:18103 --yona.data=/tmp/yona-dep-smoke.IXWbfK --yona.git.base-dir=/tmp/yona-dep-smoke.IXWbfK/git --yona.svn.base-dir=/tmp/yona-dep-smoke.IXWbfK/svn --yona.hg.base-dir=/tmp/yona-dep-smoke.IXWbfK/hg --yona.ssh.relay.enabled=false'
```

Allocate a fresh private directory before repeating, including all three VCS
base paths. Startup emitted an existing, unrelated H2 `property.value` reserved
identifier DDL warning; no code outside this feature was changed to hide it, and
the dependency lifecycle above worked. The owned app and browser were stopped;
the test container cleaned itself up. Before/after running-container lists were
identical, and no production/demo data or unrelated containers were touched.

## Approval and later PR gate

Obtain issue/scope approval first. Then, in this worktree:

```sh
git fetch upstream next
git rebase upstream/next
```

Resolve against then-current upstream, rerun the targeted checks, relevant
existing issue move/delete tests, database concurrency checks, and real web/API
smoke. Update this document with observed results. A local verified preparation
commit is allowed; pushing or opening a PR still requires explicit publication
approval. No remote publication was performed during this preparation.
