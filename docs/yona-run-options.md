Additional options when running yona
===

Ported from legacy Yona's `docs/yona-run-options.md`, adapted for yona. Legacy's OS-specific
sections (`bin/yona` vs `bin/yona.bat`, Windows path-length issues) mostly don't apply anymore —
yona runs the same `java -jar` command on every OS. The Windows issues that do still apply
(base-dir path settings, NTFS-only Fork hard-links) are covered in
[README's "Deployment configuration"](../README.md#deployment-configuration-especially-on-windows).

### Memory allocation

Pass JVM options directly to `java` (legacy read a `JAVA_OPTS` environment variable through its
`bin/yona` wrapper script; yona has no such wrapper).

```bash
java -Xmx2048m -Xms2048m -jar build/libs/yona-0.0.1-SNAPSHOT.jar --spring.profiles.active=mariadb
```

### Changing the port

Legacy used `-Dhttp.port=80`; Spring Boot uses `server.port`.

```bash
java -jar build/libs/yona-0.0.1-SNAPSHOT.jar --server.port=80
# or
java -Dserver.port=80 -jar build/libs/yona-0.0.1-SNAPSHOT.jar
```

### Choosing a DB profile

Not a legacy concept — yona is a single jar that supports 5 DBs (MariaDB/PostgreSQL/MySQL/SQL
Server/CUBRID), selected with `--spring.profiles.active=<profile>`. See
[README's "Choosing a database"](../README.md#choosing-a-database).

### DB schema migration

Legacy used Play's evolutions and, after upgrading, you might hit:

```
[warn] play - Your production database [default] needs evolutions!
```

which required setting `-DapplyEvolutions.default=true`. yona uses JPA/Hibernate's
`ddl-auto: update` (already configured per DB profile in `application.yml`), which applies
schema changes automatically on startup — there's no manual flag to flip.

### Physical storage path options

`--yona.git.base-dir=...` and the other 3 path settings are covered in
[README's "How to change these settings"](../README.md#how-to-change-these-settings).

### Optional issue full-text search

The default `yona.search.backend=db` keeps database text search. Set
`--yona.search.backend=lucene` to index issue titles, bodies and comments with Nori and
identifier-part matching. The index is single-node derived data, stored under
`${yona.data}/search/issues` unless `yona.search.index-dir` overrides it. Do not share
the index directory between server processes. An unavailable index falls back to DB search.

Identifier matching splits acronym boundaries (`getHTTPResponse`) and adjacent Korean
text (`오류SocketTimeoutException처리`) consistently at indexing and query time.
Index format 3 requires rebuilding older derived indexes; startup already performs that
rebuild. Quoted Nori phrases, zero-score identifier fallback and 200-candidate paging
are unchanged. This does not implement arbitrary substring matching like `%interExcep%`.

The boundary-only transplant from `poc/issue-843-search-ranking` excludes proximity boosts
and auxiliary BM25 scoring. On 2026-10-07, 22 targeted search tests passed. A real MariaDB
10.11 run with the same 3,391 queries as the `814c069a9` baseline resolved all 57 identifier
misses (1,640/1,640 additional identifier cases found), with no previously found target lost.
Overall recall was 97.83%, MRR 0.383512 and top-20 recall 67.34%; 20 camel-case target ranks
declined despite aggregate improvements. First-page relevance p50/p95 was 4.291/18.329 ms
and the index occupied 4,944,547 bytes. These are single-client known-item measurements,
not arbitrary-substring or concurrent-load guarantees.

Issue/comment transactions append independent rows to `issue_search_change`; they do not
lock a global search-window row. The worker acknowledges only the exact event IDs visible
to its snapshot, after publishing the index. A transaction committing out of order stays
pending, even if its event timestamp precedes an already processed event. Rollbacks also
roll back their events. Workers drain at most 1,000 events per pass and deduplicate issue IDs.
The default batch window is 2,000 ms (`yona.search.batch-window-millis`), with a 500 ms poll
(`yona.search.poll-millis`). Idle polls inspect derived event metadata, not issue content.

Startup and **Site settings → Queue → Issue search index synchronization** perform a full
rebuild from current source rows. Incremental jobs update only affected issues, including
deleted-row tombstones. Rebuilds stream source batches rather than retaining all old digests.
Requests pin one published reader, apply current DB filters and permissions, and process
200 candidates at a time. Relevance queries stream index order; explicit DB sorts scan
ID/title slices in DB order. Counts cover all eligible matches without a hit cap; only the
requested page is hydrated. Explicitly unpaged callers still retain their requested full
result. Global, project and group search reuse that page's count instead of repeating search.

#### Upgrading the unpublished Lucene PoC schema

Stop the old process before upgrading. Hibernate creates the new `issue_search_change`
table; there is no conversion of the former numeric keys to UUID strings. Startup rebuilds
the index from authoritative issues/comments, so old pending markers need not be migrated.
Once the old process is stopped, the obsolete derived tables may be removed individually:

```sql
DROP TABLE issue_search_pending;
DROP TABLE issue_search_window;
```

Run these statements only for tables present in a pre-release Lucene installation. They
must not be replaced with a schema/database reset: issue, comment, attachment and queue
tables are not disposable. Leaving the two obsolete tables in place is harmless; no new
code reads them. All three search metadata table names and the index directory are excluded
from application archives. Imports request a rebuild instead of restoring derived state.

The targeted H2 regression suites (`IssueSearchIndexSpec`, `IssueSearchQueueSpec`,
`IssueSearchServiceSpec`, `SearchServiceSpec`) cover late commits, concurrent independent
writes, bounded SQL candidate lists, exact paging/counts/ACL, reader publication, snippets
and search-page reuse. Other database engines require their own verification.

#### Standalone runtime evidence (2026-10-04)

A temporary Java entry point launched the compiled `YonaApplication` on its **main runtime
classpath**, with Java 21, an isolated H2 database (`NON_KEYWORDS=VALUE`), a temporary data/index
directory and a random HTTP port. No test framework, mocks or test-profile beans were used.
The entry point used real JPA transactions and the registered change recorder/index beans:

1. Writer A changed an issue and flushed, then held its transaction open.
2. Writer B changed a different issue and committed while A remained blocked on a latch.
3. The worker snapshot contained only B's committed event; its index update was published
   and acknowledged before A was released.
4. After A committed, its event remained pending. Processing it made both updated titles
   searchable and left no pending events.

Observed output: `LIVE_SMOKE_PASS independent_writer_ms=1 held_transaction=true
late_event_retained=true published_matches=2 pending_events=0`. The 1 ms value records this
local run, not a latency guarantee. The application, temporary data and runner were cleaned
up afterward. This supplements the 68 passing targeted H2 tests; it does not certify other
database engines.

