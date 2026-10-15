# Forgejo repository administration: Yona comparison

Research date: 2026-10-07. Primary sources only: official Forgejo documentation/source, Git's manual, and local Yona source/history. Forgejo's `latest` documentation currently identifies v16.0; the webhook reference below is v15.0. Source links use Forgejo's development branch and can change; these are observed behaviors, not a promise for every release or installation.

## Scope and baseline

Compare repository administration, not Forgejo's issue-board feature also named “Projects”. Yona baseline is `feat/queue-05-admin-ui`, inherited by `feat/queue-git-maintenance`. **The selected work is periodic idle Git GC and manager-triggered project GC. This report does not select or implement another expansion.** Recommendations below require separate scope approval.

Do not count already implemented settings or independent preparation as new gaps:

- **Default branch, visibility/code-access restrictions, feature toggles, transfer, project roles and branch protection already exist.** See the local implementation evidence below.
- **Durable execution, job administration and SSE already exist in the queue lineage.** [queue-05 scope](../pr/queue-05.md) documents status/history/errors/results and cancel/retry/close/priority actions. This is not a proposal to build another queue or monitoring UI.
- **Archive/read-only is already prepared separately** on `prep/project-archive` (inspected tip `da51e12f9`). `git show prep/project-archive:docs/pr/prep-project-archive.md` records reversible persisted archive state, web/API controls and Git/wiki/LFS/SVN/Hg write gates. This is unapproved local preparation, not merged behavior on the comparison baseline and not missing work to implement again here.
- **Selectable comment webhooks are already prepared separately** on `prep/comment-webhooks` (inspected tip `0ead5f789`; implementation commit `2afbbd855`). `git show prep/comment-webhooks:docs/pr/prep-comment-webhooks.md` records independent committed-comment dispatch and four subscription choices. It explicitly does **not** add delivery history, resend or a retry queue. Do not relabel comment delivery/event selection as a wholly absent feature.

## Comparison

“Candidate” means an observed difference worth discussing, not an approved requirement. Negative findings are limited to the inspected settings, domain models, transport and delivery implementation, not all possible deployment-side tooling.

| Area | Verified Forgejo behavior | Yona evidence and disposition |
| --- | --- | --- |
| GC and repack | Optional all-repository `git gc` cron, administrator manual task launch, bounded command execution, repository notices on errors and repository-size refresh after success. [GC source][f-check], [cron][f-extended], [admin trigger][f-admin]. Git GC performs packing/repacking as part of housekeeping; a separate repack UI was not established by these sources. [Git GC][git-gc] | Periodic idle and manager-triggered GC are the **selected work on this branch**, not an expansion candidate. Baseline `GitRepository` is JGit-based; its inspected implementation and settings do not expose the Forgejo-style maintenance task. Do not add a second repack button merely because Git has a separate command. |
| Integrity (`fsck`) | Enabled health-check cron defaults to midnight and not at startup; runs `git fsck` for repositories with `is_fsck_enabled`, records failures as repository notices. Site administrators can toggle health checks in repository settings. [basic cron][f-basic], [checks][f-check], [settings][f-settings] | No fsck action/schedule was found in the inspected baseline. **Candidate:** bounded, administrator-visible integrity checking if corruption diagnostics are needed. Do not imply GC is an integrity check or repairs corrupt objects. |
| Statistics | GC refreshes stored repository size. `check_repo_stats` runs at startup and midnight by default; `CheckRepoStats` reconciles application/database counters (watches, stars, labels, milestones, comments, forks). Repository settings separately expose site-admin stats/code/issues reindex requests. [checks][f-check], [basic cron][f-basic], [counter repair][f-stats], [settings][f-settings] | These are three different operations, not one “Git stats” feature. Existing Yona issue/member/activity counts are not missing merely because Forgejo has a reconciliation job. **Candidate:** repository storage/loose-object/pack diagnostics and last maintenance outcome; decide which measurements operators actually need before adding counter-repair or reindex controls. |
| Default branch | Branch settings update it; the service rejects a nonexistent branch, updates DB/Git HEAD and handles Actions schedule changes. New repositories default to `main`. [handler][f-default], [service][f-branch], [configuration][f-config] | **Already implemented:** settings select an existing branch; `UpdateProjectParam.defaultBranch` reaches `setDefaultBranch`; repository creation uses the configured initial branch. [settings template][y-settings], [save implementation][y-project-service], [Git repository][y-git]. A possible **reliability refinement**, not a new feature: Yona catches and suppresses default-branch save exceptions, whereas Forgejo reports errors. Actions behavior is not a Yona requirement. |
| Archived/read-only | Owner-only archive/unarchive in repository danger-zone settings; the permissions guide lists archive as owner-only. The inspected handler refuses archiving a pull mirror and stops scheduled Actions. [settings][f-settings], [permissions][f-permissions] | **Prepared separately**, not absent: `prep/project-archive` covers project-wide read-only operation across multiple VCS transports. Reuse that work only after its product decisions are approved; do not add a second archive implementation on this branch. Its stricter watch/favorite/membership/fork policy is not automatic Forgejo parity. |
| Ownership transfer | Owner-only transfer and cancellation; validates target owner and conflicts, checks quota, starts a pending transfer when needed. [settings][f-settings], [permissions][f-permissions] | **Already implemented:** request/accept flow, confirmation key and 24-hour validity, destination-user/org-admin authorization, repository relocation and role changes. [service][y-project-service], [transfer UI][y-transfer]. Pending-transfer cancellation/status could be considered separately, but “add transfer” is not a gap. |
| Pull/push mirrors | Periodic sync and “Synchronize Now”; pull mirror is selected during migration, not conversion of an existing repository. Push mirrors support branch filters, credentials and optional sync-on-push; unfiltered push uses `git push --mirror` and overwrites remote changes. SSH authentication has documented LFS limitations. [mirror guide][f-mirrors] | No persistent mirror configuration or managed synchronization workflow was found in the inspected project model/settings or Git implementation. Forking/importing is not mirroring. **Candidate only with a concrete replication need:** choose pull or push first; define credential storage, remote-network restrictions and overwrite policy before implementation. |
| Webhook delivery management | Repository-admin configuration, Test Delivery and Recent Deliveries; source exposes history and replay of a specified delivery. History cleanup defaults to midnight and 168-hour age retention (or configurable per-hook count). [guide][f-webhooks], [handler][f-hook], [basic cron][f-basic] | **Hooks already exist**, with URL/secret/type/push configuration, JSON/chat payloads and manager-authorized web/API access. HTTP dispatch is asynchronous best-effort with metrics and failure logging. `WebhookThread` stores Hangout conversation identity, **not** request/response delivery history. [controller][y-hook-controller], [delivery][y-hook-service], [thread model][y-hook-thread]. **Candidate:** persisted bounded delivery history, safe request/response inspection, test and explicit redelivery; selectable comments are already covered by their prep branch. Generic queue retry is not proof that webhooks are durably queued. |
| Permissions | Collaborators: Read, Write, Administrator, Owner; danger-zone operations are owner-only. Organization teams can set No Access/Read/Write per repository unit or administrator access. [permissions][f-permissions] | **Authorization already exists:** project managers/members/guests, organization/site administrators, project visibility and code-access restrictions. Project-level updates/deletion use manager or org-admin authorization with site-admin override; branch protection has a management UI. [roles][y-roles], [access control][y-access], [protection][y-protection]. Forgejo's separate owner/admin distinction and per-unit teams are **optional policy expansion**, not missing basic permissions. Avoid changing role semantics as a side effect of maintenance. |

## Forgejo GC defaults and safety: what is actually verified

1. **Periodic Git GC is disabled by default.** `cron.git_gc_repos`: `Enabled=false`, `RunAtStart=false`, `Schedule=@every 72h`. Its timeout derives from `[git.timeout] GC`, documented as 60 seconds; `Args` inherits `[git] GC_ARGS`, whose default is empty. These defaults agree between the [cron registration][f-extended], [example configuration][f-example] and [configuration guide][f-config]. The guide's `--aggressive --auto` is an **example**, not the default.
2. **The verified command is plain `git gc` plus configured arguments**, not unconditional `--aggressive`, `--auto`, `--force` or `--prune=now`. [GitGcRepo][f-check] also refreshes repository size and records command/size-update failures. One failed repository GC is logged/noticed and does not stop the all-repository scan; a globally finished task is therefore not proof that every repository succeeded.
3. **Retention safety comes from Git, not a Forgejo guarantee.** Git's documented default prune grace is two weeks, configurable through `gc.pruneExpire`; `--prune=now` increases concurrent-writer corruption risk. Git explicitly says its mitigations do not remove all concurrency risk. Empty Forgejo arguments do not override a site's Git configuration. These Git defaults must not be presumed identical to JGit GC behavior. [Git GC options/configuration/notes][git-gc]
4. **No traffic-idle or repository-inactivity gate is established by the verified Forgejo path.** Cron uses a time schedule, then iterates repositories and invokes GC. The generic runner prevents the **same cron task** from running twice concurrently; that is not exclusion against pushes, other tasks or filesystem mutation. [registration][f-extended], [execution][f-task], [repository scan][f-check]. Scheduling at midnight or every 72 hours does not prove the server is idle.
5. Consequently, Yona's requested **idle** scheduling is a Yona-specific operating policy. State which activity it observes (queue activity, recent repository pushes, or broader request traffic); a queue-idle check alone is not a repository-write lock. Preserve conservative object retention and do not present idle heuristics as complete concurrency protection.

Health-check timeout deserves separate care: the cheat sheet/example says 60 seconds, while the inspected `registerRepoHealthCheck` inherits `setting.Git.Timeout.Default` (the example's general Git timeout is 360 seconds). This report therefore does not assert a verified effective fsck timeout across releases/configurations. [basic cron][f-basic], [example][f-example]

## Suggested order after scope selection

- **Now — only the selected GC work:** reuse the inherited queue/admin surfaces, authorize project-manager launches and show the actual job outcome. Keep automatic scheduling's idle definition explicit. No aggressive/immediate-prune switch or arbitrary user-supplied Git arguments.
- **First candidate — maintenance diagnostics:** if operators need them, add storage/pack/object measurements and bounded fsck results through existing job/status surfaces. Repacking alone is already covered by GC; application counter repair is a separate decision.
- **Second candidate — webhook delivery management:** history, test and explicit redelivery address failures that today's logging/metrics cannot inspect per delivery. Scope payload/secret redaction and retention alongside persistence; do not duplicate the prepared comment-event work.
- **Defer — mirrors and fine-grained team/owner permissions:** implement only for a selected replication or delegated-administration use case. Do not rebuild existing default-branch/transfer/archive controls. A default-branch failure-reporting fix can remain a small independent reliability change.

No build, test, formatter, server or performance experiment was run for this research. No claim is made that GC fixes mention-processing latency: that needs separate measurement.

## Primary-source links

### Forgejo and Git

[f-config]: https://forgejo.org/docs/latest/admin/config-cheat-sheet/
[f-example]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/custom/conf/app.example.ini
[f-extended]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/services/cron/tasks_extended.go
[f-basic]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/services/cron/tasks_basic.go
[f-task]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/services/cron/tasks.go
[f-check]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/services/repository/check.go
[f-admin]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/routers/web/admin/admin.go
[f-stats]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/models/repo.go
[f-settings]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/routers/web/repo/setting/setting.go
[f-default]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/routers/web/repo/setting/default_branch.go
[f-branch]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/services/repository/branch.go
[f-mirrors]: https://forgejo.org/docs/latest/user/repo-mirror/
[f-webhooks]: https://forgejo.org/docs/v15.0/user/repository/webhooks/
[f-hook]: https://codeberg.org/forgejo/forgejo/src/branch/forgejo/routers/web/repo/setting/webhook.go
[f-permissions]: https://forgejo.org/docs/latest/user/collaboration/repo-permissions/
[git-gc]: https://git-scm.com/docs/git-gc

### Yona local implementation

Line ranges describe the inspected baseline; ongoing GC changes may shift them.

- [Project settings][y-settings]: default branch at lines 107–114; visibility/code restrictions and feature flags in the same form.
- [ProjectServiceImpl][y-project-service]: default-branch update at 183–190; transfer request/accept at 283–450.
- [GitRepository][y-git]: JGit bare repository creation and configured initial branch at 57–89.
- [AccessControl][y-access]: project authority at 278–308; organization membership visibility elsewhere in the same class.
- [BranchProtectionController][y-protection]: manager-authorized rule list/create/update/delete; shared push/merge enforcement described in its source.
- [WebhookController][y-hook-controller]: project-scoped configuration and list API; [WebhookServiceImpl][y-hook-service]: dispatch/filtering and HTTP outcome handling at 82–114 and 496–532.
- [WebhookThread][y-hook-thread]: conversation/thread mapping only.

[y-settings]: ../../src/main/resources/templates/project/setting.html
[y-project-service]: ../../src/main/kotlin/com/github/yonaprojects/yona/domain/project/ProjectServiceImpl.kt
[y-git]: ../../src/main/kotlin/com/github/yonaprojects/yona/domain/vcs/GitRepository.kt
[y-transfer]: ../../src/main/resources/templates/project/transfer.html
[y-roles]: ../../src/main/kotlin/com/github/yonaprojects/yona/domain/role/RoleType.kt
[y-access]: ../../src/main/kotlin/com/github/yonaprojects/yona/config/security/AccessControl.kt
[y-protection]: ../../src/main/kotlin/com/github/yonaprojects/yona/web/BranchProtectionController.kt
[y-hook-controller]: ../../src/main/kotlin/com/github/yonaprojects/yona/web/WebhookController.kt
[y-hook-service]: ../../src/main/kotlin/com/github/yonaprojects/yona/domain/webhook/WebhookServiceImpl.kt
[y-hook-thread]: ../../src/main/kotlin/com/github/yonaprojects/yona/domain/webhook/WebhookThread.kt
