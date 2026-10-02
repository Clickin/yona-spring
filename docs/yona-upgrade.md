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

Issue REST requests now use `assigneeIds` and responses use `assignees`. Omit the update
field to preserve assignments, or send `[]` to clear all. Coordinate external clients
before deployment: the separate `yona-cli` repository is not migrated by this branch.
See [the preparation record](pr/prep-multiple-assignees.md) for the unapproved
compatibility decision, executed checks and database/visual verification limits.

#### Migrating from legacy Yona

This isn't a version upgrade — it's moving to a **different application with a different
architecture** (Play/Java/Ebean → Spring Boot/Kotlin/JPA; different DB driver and schema mapping
too). Pointing yona at a legacy Yona database directly and starting it up is not a tested path.
Keeping screens, data model, and behavior equivalent to legacy is this project's goal, but an
actual data-migration procedure (legacy DB → yona) isn't documented yet — check
`docs/parity/index.md` for porting status, and always back up and test in a non-production
environment before touching real data.
