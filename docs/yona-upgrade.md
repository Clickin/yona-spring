#### How to upgrade

Ported from legacy Yona's `docs/yona-upgrade.md`, adapted for yona.

- Stop yona (`Ctrl-C`, or `sudo systemctl stop yona`).
- Pull the new source (`git pull`, etc.) and rebuild:
  ```bash
  ./gradlew bootJar
  ```
- Run it again (`java -jar build/libs/yona-0.0.1-SNAPSHOT.jar ...`, or
  `sudo systemctl start yona`).

The DB schema is migrated automatically on startup via `ddl-auto: update` — see
[`yona-run-options.md`'s "DB schema migration"](yona-run-options.md#db-schema-migration). There's
no legacy-style evolutions warning to react to.

#### Multiple issue assignees

The preparation branch for multiple issue assignees migrates existing issue assignments
to `issue_assignee(issue_id, user_id)` before Hibernate updates the schema. Each existing
assignment is preserved as a one-user set; PR assignments are unchanged. The old
issue-only `assignee_id` column is removed after copying, so restarting cannot restore
a subsequently removed assignment. Take a full database backup first; reverting to the
old singular version requires restoring that backup.

This is a startup JDBC migration (`IssueAssigneesMigration`), not a Flyway migration
or a backup-format converter. A pre-upgrade application JSON backup containing
`issue.assignee_id` cannot be imported directly into the upgraded schema: restore
inserts the original column names, and that column no longer exists. Restore such
a backup with the matching pre-upgrade version/schema, stop that application, then
upgrade the restored database. Post-upgrade backups include `issue_assignee` and
can be restored to a matching post-upgrade schema. Keep the original database
backup until the restored-and-upgraded copy has been checked.

Issue REST requests accept both `assigneeIds` and legacy `assigneeId`. A non-null
`assigneeIds` list takes precedence, including `[]` to clear; otherwise a non-null
`assigneeId` selects one user. Omit both (or send null) on update to preserve the
set. Responses include `assignees` and compatibility `assignee` (the first list
entry, or null); this does not designate a primary assignee.
Assignment changes require the actor and every target to satisfy the existing
project `ASSIGN_ISSUE` permission. External users cannot gain private-project
issue rights by being assigned.
The separate `yona-cli` repository is not changed by this branch; legacy clients
remain compatible, while multi-assignee UI requires client support.
See [the preparation record](pr/prep-multiple-assignees.md) for checks and limits.

#### Migrating from legacy Yona

This isn't a version upgrade — it's moving to a **different application with a different
architecture** (Play/Java/Ebean → Spring Boot/Kotlin/JPA; different DB driver and schema mapping
too). Pointing yona at a legacy Yona database directly and starting it up is not a tested path.
Keeping screens, data model, and behavior equivalent to legacy is this project's goal, but an
actual data-migration procedure (legacy DB → yona) isn't documented yet — check
`docs/parity/index.md` for porting status, and always back up and test in a non-production
environment before touching real data.
