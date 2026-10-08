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
these four locations (see [README's "Deployment configuration"](../README.md#deployment-configuration-especially-on-windows)
for each key's default and purpose):

```
- yona.git.base-dir    - Git bare repositories
- yona.svn.base-dir    - SVN repositories
- yona.lfs.base-dir    - Git LFS objects
- yona.upload.base-dir - Attachment uploads
```

If left at their defaults (`/tmp/yona/...`), reconfigure them to a persistent directory first,
then back those directories up regularly. `application.yml` itself is part of the deployed
artifact and doesn't need separate backup, except for any production-only overrides it contains
(DB passwords, OAuth2 client secrets, etc.) — those should be backed up too.

Repository type changes are destructive resets
---
The repository-type setting initializes an empty repository of the next type
(Git → Subversion → Mercurial → Git). It does **not** convert code or history.
The existing code/history deletion warning, agreement checkbox, and confirmation
dialog remain in place. Managers must also type the exact current project name.

The project ID, issues, boards, and other collaboration data remain. Child forks
are disconnected from the project; their own repositories are not deleted.
Back up the database and repository files before resetting. At this stage, the
old repository is physically deleted, and a database rollback cannot restore its
files if repository creation or database persistence fails. This confirmation
step does not provide archive recovery.

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

