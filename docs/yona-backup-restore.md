Backup and Restore
===

Ported from legacy Yona's `docs/yona-backup-restore.md`, adapted for yona.

Application archives through the durable queue
---
Use **Site settings → Data** while signed in as a site administrator. Exports are ZIP
archives containing a manifest, table-separated NDJSON, and Git/SVN/LFS/upload/application
files. Queue tables, queue storage, and the running file-based H2 database are excluded;
an input attempting to restore them is rejected rather than silently ignored.
Project export includes the selected project and its references; project import creates
a separate project, renaming it on collision. It does not merge issues into an existing project.

The native CSRF-protected forms submit `POST /site/export` (optional `project=owner/name`)
or multipart `POST /site/import` (`data` file). Both return **303** to the selected job in
`/site/admin/queue`. GET no longer starts an export. The request never generates an archive
or restores database rows. Import waits for upload and durable staging, not restoration.
Watch row/byte progress in the queue and download a successful export using its result link
(`GET /api/admin/queue/v1/jobs/{id}/result`). Closing the submission page does not cancel the job.

Imports run exclusively against other queue jobs. This does **not** stop ordinary HTTP,
Git pushes, or existing non-queue background writers: quiesce those writers for a consistent
backup/restore. Database and filesystem replacement are not one atomic transaction.
Interrupted/uncertain mutation requires operator reconciliation, not automatic replay.
Stop the old handler/process and inspect both DB and files before acknowledging recovery;
an unresolved exclusive recovery job blocks new queue execution.

Uploads are streamed and fsynced under `${yona.queue.data-dir}/import-inputs`; payloads
contain only an opaque filename, byte count and SHA-256. Inputs are intentionally retained,
including after success, because queue completion commits after the handler returns.
After inspecting the terminal job, an operator may remove its referenced input. Never
remove inputs for pending, running, retryable or unresolved recovery jobs. Provision disk
space for uploads, extracted staging, replacement backups, and queued output artifacts.
Application multipart/reverse-proxy upload limits still apply.

Archive compatibility and legacy migration
---
Exports use ZIP format 3. `manifest.json` must be the first entry, with:

```json
{
  "format": "yona-backup",
  "formatVersion": 3,
  "sourceVersion": "2.0",
  "targetVersion": "2.0",
  "producer": "yona",
  "producerVersion": "2.0",
  "scope": "site",
  "project": null,
  "createdAt": "2026-01-01T00:00:00Z",
  "requiredCapabilities": [],
  "integrity": "sha256-entries-v1"
}
```

Project archives use `"scope":"project"` and `"project":"owner/name"`. Database entries
are `db/<target_table>.ndjson`; site identity high-water marks are
`db/_sequences.json` (`{"n4user":42}` means the next generated ID is 42).
Files use `files/git/<owner>/<project>.git/`, Git wikis
`files/git/<owner>/<project>.wiki.git/`, `files/svn/<owner>/<project>/`,
`files/lfs/<owner>/<project>/`, and `files/uploads/<hash>`. Normal full backups may
also contain `files/data/`; uploads configured below application data live there.

`integrity.ndjson` contains one `{"path":"db/n4user.ndjson","size":123,"sha256":"…"}`
record per ZIP entry, including zero-byte directory entries. Only `manifest.json` and
`integrity.ndjson` are excluded. Missing/extra records, duplicate ZIP paths or integrity
records, byte-count/digest mismatches, unknown roots, and unsafe paths are rejected.
Integrity detects damaged bytes; it is **not** a signature or evidence that an archive's
producer is trustworthy. Treat site archives as credential-bearing secrets.

The importer rejects unsupported format/source/target versions and required capabilities
before restoration. It stages files and uses a temporary disk-backed H2 database to check
target table/column names, required values, scalar types, primary/unique keys, declared
foreign keys, numeric user references, attachment containers/files/sizes, and identity
high-water marks. MariaDB/MySQL enum values are checked against target metadata.
Project user references cannot contain passwords, tokens, 2FA state, or site-admin state.
Project file paths must belong to that project; an existing upload with different bytes
is rejected. Staging uses disk rather than retaining the table graph in memory; one JSON
row and filesystem-entry metadata still occupy heap. Budget disk space for extracted rows,
files, and the validation database. Validation does not run Git/SVN repository repair or
replace the need to quiesce writers. PostgreSQL restore currently requires permission to
set `session_replication_role`; H2 restores only actual identity columns, not every numeric PK.

The standalone [yona2-migrator](https://github.com/Clickin/yona2-migrator) converts **1.16**
schemas to the target **2.0** schema; this server never interprets raw legacy SQL/schema.
Its manifest changes `sourceVersion` to `"1.16"`, `producer` to `"yona2-migrator"` and
`producerVersion` to `"0.1.0"`, and requires:

```json
{
  "requiredCapabilities": ["legacy-credentials-sha256-1024"],
  "sourceAuthMode": "local",
  "bootstrapSourceLogin": "admin"
}
```

The operator explicitly opts into the CLI's credential export/admin mapping, then imports
through the existing site form. Migration is accepted only on a fresh target with exactly
the configured bootstrap `admin` in `SITE_ADMIN` state, no projects/content/other accounts,
and optional target-only bootstrap security, operational configuration, and seeded roles.
The source `admin` identity maps to that target administrator. The **entire target admin
row** (including its password, token, lock/2FA state and profile) is retained. Target session,
2FA, token, SSO/configuration/audit and queue rows are not restored from migration input.
Source-admin secondary emails, SSH keys, and linked login identities are not installed as
new bootstrap credentials; existing target rows are retained. Other accounts are never
matched/merged by email or login ID. Source site-admin accounts become `ACTIVE`, not newly
privileged site administrators. ID collisions forced by bootstrap mapping are remapped,
with database/user/attachment references following the new identity.

Legacy `project_user.role_id=3` rows are virtual site-manager authority, explicitly excluded by
1.16 membership lookup. They remain in the retained input ZIP but are not restored as project
memberships; explicit manager/member rows are preserved.

For other imported users, legacy passwords use Base64 SHA-256 (UTF-8 salt prefixed once,
1024 digest rounds); successful local login upgrades them to Argon2id. Legacy tokens,
remember-me state, automatic lock counters and 2FA flags are cleared. `sourceAuthMode`
must match target `yona.ldap.enabled` / `yona.ldap.fallback-to-local-login` exactly:
`local` = disabled, `ldap-only` = enabled without fallback, `ldap-fallback` = enabled
with fallback. LDAP-only users do not receive usable local passwords, and import never
enables local fallback. Configure/test the real LDAP service independently before cutover.

Migration intentionally excludes `api_token`, `api_token_project`, `api_token_scope`,
`user_known_device`, `user_setting`, `user_backup_code`, `user_totp_credential`,
`user_verification`, `user_webauthn_credential`, `oauth_authorization`,
`oauth_authorization_consent`, `oauth_registered_client`, `saml2_sso_settings`,
`oidc_sso_settings`, `audit_log`, `property`, `spring_session`, `spring_session_attributes`,
all queue tables, and `files/data/`. Such entries are rejected, not silently skipped.
Other legacy conversion/redaction policies are documented by the CLI. A format-3 target
backup is a normal backup and never advertises the legacy migration capability.

One optional provenance entry preserves legacy menu rows whose project no longer exists:
`migration/orphan-project-menu-settings.ndjson`. It is permitted only for the legacy
capability with the exact declaration
`"migrationProvenance":{"format":"yona-legacy-provenance","formatVersion":1,"entries":["migration/orphan-project-menu-settings.ndjson"]}`.
The index covers its bytes. Rows contain only the public legacy columns
`id,project_id,code,issue,pull_request,review,milestone,board`; identities/types are checked
and the project must be absent from the converted archive. Live project menu flags remain
on the target project row. Orphan provenance is **not** inserted into target tables or
installed as application files; it remains in the retained input ZIP for operator inspection.
Missing declarations/entries, unsupported provenance names/versions, and live-project
records mislabeled as orphans are rejected. Other dangling domain references are not
exempted by this narrowly scoped provenance policy.



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
