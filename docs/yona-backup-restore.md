Backup and Restore
===

Ported from legacy Yona's `docs/yona-backup-restore.md`, adapted for yona.

Application data backup/restore (all DB tables)
---
Not present in legacy — yona provides a site-admin-only API that exports/imports every DB table
as JSON (`SiteApiController`, `DataBackupService`; tables are discovered automatically, so
nothing gets silently missed).

```bash
# Download a backup (requires admin auth)
curl -u <admin-loginId>:<password> http://127.0.0.1:8080/site/export -o yona-backup.json

# Restore — this is a full replace of every table, so test it in a non-production
# environment before pointing it at real data
curl -u <admin-loginId>:<password> -F "data=@yona-backup.json" http://127.0.0.1:8080/site/import
```

DB backup
---
Independent of the application-level export/import above, using the DB engine's own backup
tooling is the safer, more standard option for large/production data:

- MariaDB/MySQL: `mariadb-dump` (`mysqldump`)
- PostgreSQL: `pg_dump`/`pg_dumpall`
- SQL Server, CUBRID: each vendor's own backup procedure

Backing up settings and files
---
Legacy kept everything under one `YONA_DATA` directory (`conf`/`uploads`/`repo`/`logs`) that you
could just tar up. yona splits physical storage across independent settings instead. Back up
all configured locations (see [README's "Deployment configuration"](../README.md#deployment-configuration-especially-on-windows)
for each key's default and purpose):

```
- yona.git.base-dir    - Git bare repositories
- yona.svn.base-dir    - SVN repositories
- yona.hg.base-dir     - Mercurial repositories
- yona.lfs.base-dir    - Git LFS objects
- yona.upload.base-dir - Attachment uploads
- yona.repository-archive.base-dir - Retained repositories and failed-target quarantines
- yona.repository-archive.git-dir / svn-dir / hg-dir - Optional per-filesystem archive overrides
```

If left at their defaults (`/tmp/yona/...`), reconfigure them to a persistent directory first,
then back those directories up regularly. `application.yml` itself is part of the deployed
artifact and doesn't need separate backup, except for any production-only overrides it contains
(DB passwords, OAuth2 client secrets, etc.) — those should be backed up too.

Repository type changes retain a private archive
---
The repository-type setting initializes an empty repository of the next type
(Git → Subversion → Mercurial → Git). It does **not** convert code or history.
The warning, agreement checkbox, confirmation dialog, and exact project-name
confirmation remain. The project ID, issues, boards, and other collaboration
data remain; child forks are disconnected, not deleted.

The previous repository is moved into a private archive before creating the new
repository. A confirmed database rollback restores it. Any partial new repository
is moved into a private failed-target quarantine rather than recursively deleted.
Successful resets retain their archives; no automatic pruning or restore UI is provided.

Before enabling resets, configure persistent private locations:

```yaml
yona:
  repository-archive:
    single-serving-process: true
    base-dir: /var/lib/yona-private-archives
    # Optional: each override must be on the corresponding repository filesystem.
    # git-dir: /mnt/git-private-archives
    # svn-dir: /mnt/svn-private-archives
    # hg-dir: /mnt/hg-private-archives
```

Enable `single-serving-process` only when this JVM is the sole repository server
and writer. Other Yona instances, external Git/SVN/Hg servers, hooks that leave
background writers, and restore jobs must not mutate these directories concurrently.
The process drains active repository operations and pauses new repository sessions
across projects while resetting. Existing forks can share mutable files through hard
links, so shared files are copied to independent inodes before archiving. Plan a
maintenance window and sufficient space for these copies; ordinary unshared files
are moved without copying.

Archive roots must be disjoint from all repository, LFS, upload, static-resource,
and servlet-resource roots. Use an owner-only POSIX filesystem supporting stable
file identities, hard-link counts, and same-filesystem atomic moves. Cross-filesystem
moves are rejected without a copy/delete fallback. Configure the next VCS's archive
root on its filesystem too, so a failed target can be quarantined safely.
Do not export archives through another web/VCS server or place symlinks to them in
served directories. Local imports cannot adopt an archive as an ordinary repository.

Back up the database, active repositories, and every configured archive/quarantine
root together. Existing commit references are not translated to the new VCS.
If commit completion is unknown, a path was replaced, or compensation failed,
the server preserves the available data and reports operator recovery instead of
overwriting possibly committed state. Do not retry blindly. Stop all serving/writing
processes, back up the retained directories, inspect the project's current VCS and
fork relationships, and compare repository identities/history with the logged operation.
Restore only a confirmed rolled-back source into an unoccupied serving path. Preserve
unexpected files and failed targets separately until reconciliation is complete.

The POST endpoint `/{owner}/{projectName}/changeVCS` now requires a JSON body
and the usual authenticated manager session and CSRF token:

```json
{"projectId":123,"projectName":"example","expectedVcs":"GIT","accepted":true}
```

Old bodyless requests intentionally fail. Missing consent or confirmation,
a different project ID, or a name that does not exactly match the current name
returns 400. A repository type changed since the confirmation page was loaded
returns 409; reload the page before deciding whether to proceed. The server
rechecks the latest project under a database lock immediately before resetting.
Null VCS values are treated as GIT; existing SVN and HG aliases are preserved.

