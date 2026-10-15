> 이 영문 초안은 대체되었습니다. 최종 제목·본문은 `docs/pr/refactor-commit-ref-recording.md`을 사용합니다.

## Purpose

Refs #ISSUE_04_NUMBER — replace with the public issue number before publishing.

Extract the duplicated Git/Hg commit-to-issue recording into concrete `CommitIssueReferenceService.record(project, sender, commitId, commitMessage)` using the existing parser and direct repository save.

## Preserved behavior

- Sender remains the pusher; each event's created time remains processing time.
- Repeated issue numbers within one message collapse in the existing parser. Reprocessing a commit, multiple Git refs, or multiple Hg bookmarks still creates additional events.
- Lookup remains project-scoped; missing issues are skipped. IDs are stored unchanged.
- Commit selection/order, async listener transactions, notifications and webhooks remain unchanged.
- No schema changes, draft-event merging, deduplication, SVN integration, or clock-policy changes.

## Validation

- Before extraction: original Git/Hg listener characterization specs plus `IssueReferenceParserSpec`, **BUILD SUCCESSFUL in 51s** (coordinating runner).
- After extraction: service, Git/Hg listeners, parser, Git/Hg push hooks and `IssueEventRecorderKtSpec`, **BUILD SUCCESSFUL in 3m49s** (coordinating runner).
- Both runs used Java 21 and `./gradlew test -x npmCi -Dyona.it.db=h2` with focused `--tests` filters. Frontend dependencies were installed with `pnpm --dir frontend install --ignore-scripts --lockfile=false`; resource processing remained enabled.
- Actual local HTTP smoke passed on an isolated Spring app/H2 2.4.240 at loopback port 19204. Authenticated `admin` (`pusher@example.com`) pushed commits by `Historic Author <author@example.com>` dated 2000; issue #42 existed in each target and an unrelated project.
- Git `a345b9b9102b003e71bf5f7e50dc2de90e41681e` to two CREATE refs produced **2 persisted events** at 2026-10-08 02:40:20 UTC. Hg `363aa5eded2fa934e929d85665d557c4c2bdc9ca` to two bookmark pushkeys produced **2 persisted events** at 02:41:18 UTC. SQL assertions verified all four exact IDs, pusher fields, null oldValue, event type and push-processing time bounds; unrelated project's count was **0**. Both HTTP issue timelines contained the two references.
- Loopback receiver observed **1 Git / 2 Hg webhooks**. Deleting both refs/bookmarks left event counts unchanged. No watcher was configured, so no `NEW_COMMIT` notification rows were persisted; listener regression tests separately cover notification→webhook→save ordering. External mail was disabled.
- Smoke setup notes: empty Hg repository required `--new-branch`; the push explicitly supplied the authenticated URL because clone configuration omitted its password. Bookmark-only deletion returned Mercurial exit 1 (`no changes found`) but remote `listkeys` confirmed both bookmarks removed. H2 AUTO_SERVER advertised an unreachable host address, so persisted rows were queried after clean app shutdown, while HTTP timelines had already confirmed async completion.
- App and webhook receiver were stopped after smoke. Full H2 regression also passed (coordinating runner, **5m55s**): **466 suites, 6,735 tests, 0 failures, 0 errors, 2 skipped**. Both skipped cases are `PostgresOAuthLobMigrationSpec` migrations enabled only when `yona.it.db=postgres`; this is an H2 result, not a six-database claim.

## T5 integration

Both listeners replace their issue-repository constructor pair with the concrete service and retain VCS-to-string conversions. SVN historical indexing needs its own approved author/date/idempotency semantics; this processing-time service does not implement that policy.

### Local push smoke procedure

1. Use an isolated DB/repository root and loopback app; create authenticated pusher `pusher`, Git/Hg projects, and existing issue #42 in each. Create a different project with its own #42. Disable mail and configure only a loopback webhook receiver.
2. Git: clone the local project as pusher; commit `fix #42 #42` with author email different from pusher and author/committer date in 2000. Push that single commit to two new refs in one command: `git push origin HEAD:refs/heads/ref-a HEAD:refs/heads/ref-b`.
3. Hg: clone the local project as pusher; commit `fix #42 #42` with `hg commit -u 'author <author@example.com>' -d '2000-01-01 00:00 +0000' -m 'fix #42 #42'`; create `bookmark-a` and `bookmark-b` at that node and push both with `hg push --new-branch -B bookmark-a -B bookmark-b <authenticated-local-url>`. This traverses bookmark pushkey, not merely changeset transfer.
4. Bound the async wait, then inspect persisted `ISSUE_REFERRED_FROM_COMMIT` rows and issue timelines: exactly two per target issue, pusher login/email, null oldValue, full Git/Hg ID, and created within the processing interval rather than 2000. The other project's #42 must have no new rows.
5. Observe one Git notification/webhook containing both selected commits and two Hg notification/webhook deliveries (one per bookmark), before issue saving as fixed by listener regression tests. Delete refs/bookmarks and verify no new reference events. Record actual commands/results; do not count the synchronous fixture specs alone as HTTP/async smoke.
