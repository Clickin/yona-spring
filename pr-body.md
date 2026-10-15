**Private local review only. No origin push or PR publication is authorized for this branch.**

## Summary

Fixes #ISSUE_02_NUMBER

Stacked on the separate server-confirmation PR, commit `fbb97f612`. Review this branch's archive changes relative to that tip; rebase after the prerequisite PR lands. The visible issue-number placeholder must be replaced by the supplied public issue number before human publication.

- Replace destructive repository reset deletion with an atomic move to a private, collision-free archive outside Git/SVN/Hg/LFS/upload/static serving roots. Keep the current project ID and collaboration data; disconnect child forks only as part of the successful reset transaction.
- Create the new repository without first changing the managed project's VCS. Reserve its target and track directory ownership. Compensation atomically moves only this operation's failed target to private quarantine before restoring the original; it never recursively deletes repository data or removes a pre-existing/substituted target.
- Own the database transaction through completion. A confirmed rollback compensates filesystem changes; a successful commit retains the archive. An indeterminate commit outcome or failed compensation preserves recovery data and reports an operator-required error, never a false success or blind overwrite.
- Drain actual HTTP/SSH and native repository operations. Reset pauses repository sessions globally while isolating files shared with existing forks, then archives independent inodes. Normal lifecycle operations retain project-scoped gates. Keep Mercurial HTTP handles request-scoped and preserve cross-project pull-request behavior.
- Keep confirmation, checkbox and dialog behavior from the first PR; explain that old code/history disappears from the active repository and is retained only for operator-managed recovery.

## Operating prerequisites and limits

Reset is disabled until the operator explicitly sets `yona.repository-archive.single-serving-process=true`. This acknowledges a single serving application JVM; external SSH must relay to it, and external svnserve/web servers, another serving node, or concurrent OS-level repository access are not covered by the in-JVM gate.

Configure persistent `yona.repository-archive.base-dir`, or per-VCS `git-dir`, `svn-dir`, `hg-dir` for separate mounts. Source archives and failed targets use private roots on their respective filesystems, outside every serving root. Required: owner-only POSIX directories, atomic rename, stable file keys and hard-link counts. Both storage locations are validated before movement. Shared files require copy space; use a maintenance window. Archive-root permissions are checked, never silently rewritten.

There is no database migration, archive ledger, automatic expiry, public archive URL, or restore UI. Process/host crashes and indeterminate commits require operator reconciliation. Normal project deletion still deletes its active repository; reset-style archive retention applies only to VCS reset.

## Regression coverage

Real Git/SVN/Hg committed-data archive/recovery; partial creation and real database flush/commit failures; cleanup/restore failures and preserved foreign files; concurrent/sequential resets; public/private HTTP and supported embedded/relay SSH marker nonexposure, traversal and symlinks; real open-session draining; browser/raw/history/ZIP and LFS nonexposure. Existing unsupported embedded Hg and SVN ZIP creation are not presented as newly supported features.

## Verification

- Full H2 JVM suite: **472 suites, 6,862 tests, 0 failures, 0 errors, 2 PostgreSQL-only skips**.
- Focused confirmation/archive integration suite on MariaDB, PostgreSQL, MySQL, SQL Server and CUBRID: **38 tests per profile, 0 failures/errors/skips**.
- Full existing Chromium suite on isolated fixtures: **228 passed, 0 failed, 2 configuration-dependent skips**. Local SMTP capture only; no external mail delivery. The fixture used the suite's standard bootstrap email, matching localhost WebAuthn origin, and both loopback address families.
- Real application/browser smoke: Git→SVN reset preserved the Project ID, issue and board bodies, exact Git commit and README in private storage; the new SVN repository checked out at revision 0.
- Real failed-create smoke: denying target-parent writes produced an error after the archive move; original Git VCS, commit and bytes were restored and could be cloned again over HTTP. No new SVN target remained.
- Native regression scenarios cover Git/SVN/Hg rollback and commit failures, retained partial targets, old hard-linked forks, concurrent access, HTTP and embedded/relay SSH isolation, wiki compatibility, and a mounted separate-filesystem rejection case.
- Browser DOM/form actions were verified. The managed-browser screenshot API timed out; no screenshot success is claimed.

Public issue numbers have not been supplied. Keep this file and the local patch private until the user decides the disclosure path; do not publish the placeholder or this draft.
