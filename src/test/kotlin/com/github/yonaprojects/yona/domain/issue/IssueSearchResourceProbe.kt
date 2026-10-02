package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.YonaApplication
import com.github.yonaprojects.yona.domain.site.DataBackupService
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.SpringApplication
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime

/** Opt-in measurement driver, not a CI test. Imports only into a newly created disposable directory. */
object IssueSearchResourceProbe {
    @JvmStatic fun main(args: Array<String>) {
        System.getenv("YONA_PROBE_INDEX_ONLY")?.let { path ->
            // Read-only diagnostic after the application probe has closed its writer.
            val output = Path.of(requireNotNull(System.getenv("YONA_PROBE_ENGINE_OUTPUT")))
            val results = linkedMapOf<String, Any>()
            val engine = IssueSearchIndex(path)
            try {
                org.apache.lucene.store.FSDirectory.open(Path.of(path)).use { dir ->
                    org.apache.lucene.index.DirectoryReader.open(dir).use { reader ->
                        for (text in requireNotNull(System.getenv("YONA_PROBE_QUERIES")).split(",")) {
                            var count = 0
                            fun query() {
                                val searcher = org.apache.lucene.search.IndexSearcher(reader)
                                val parsed = engine.query(text)
                                var after: org.apache.lucene.search.ScoreDoc? = null
                                count = 0
                                do {
                                    val hits = searcher.searchAfter(after, parsed, 500).scoreDocs
                                    hits.forEach { hit ->
                                        val stored = searcher.storedFields().document(hit.doc)
                                        check(stored.get("id").toLong() > 0 && stored.get("digest") != null)
                                    }
                                    count += hits.size
                                    after = hits.lastOrNull()
                                } while (after != null)
                            }
                            repeat(5) { query() }
                            val times = List(20) { measureNanoTime { query() } / 1e6 }.sorted()
                            results[text] = mapOf("hits" to count, "p50Ms" to times[9], "p95Ms" to times[18])
                        }
                    }
                }
            } finally { engine.close() }
            Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(results),
                java.nio.file.StandardOpenOption.CREATE_NEW)
            return
        }
        val archive = Path.of(requireNotNull(System.getenv("YONA_PROBE_ARCHIVE"))).toAbsolutePath()
        val root = Path.of(requireNotNull(System.getenv("YONA_PROBE_OUTPUT"))).toAbsolutePath()
        val jdbc = System.getenv("YONA_PROBE_JDBC")
        val backend = System.getenv("YONA_PROBE_BACKEND") ?: "db"
        val scale = (System.getenv("YONA_PROBE_ISSUES") ?: "0").toInt()
        require(backend in setOf("db", "lucene") && scale >= 0 && Files.isRegularFile(archive))
        Files.createDirectory(root, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))) // Refuse reuse.
        val result = linkedMapOf<String, Any>("backend" to backend, "database" to if (jdbc == null) "h2" else "mariadb", "expandedIssues" to scale,
            "java" to System.getProperty("java.version"), "processors" to Runtime.getRuntime().availableProcessors(),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        val context = SpringApplication.run(YonaApplication::class.java,
            "--spring.profiles.active=${if (jdbc == null) "h2" else "mariadb"}", "--server.address=127.0.0.1", "--server.port=0",
            "--yona.data=$root/data", "--spring.datasource.url=${jdbc ?: "jdbc:h2:file:$root/database;NON_KEYWORDS=VALUE"}",
            "--spring.datasource.username=${if (jdbc == null) "sa" else "root"}",
            "--spring.datasource.password=${if (jdbc == null) "" else requireNotNull(System.getenv("YONA_PROBE_DB_PASSWORD"))}",
            "--yona.git.base-dir=$root/git", "--yona.svn.base-dir=$root/svn", "--yona.lfs.base-dir=$root/lfs",
            "--yona.upload.base-dir=$root/uploads", "--yona.search.backend=$backend",
            "--yona.search.initial-delay-millis=3600000", // Drive admission at the normal 500ms interval below.
            "--yona.mailbox.imap.enabled=false", "--yona.ldap.enabled=false", "--management.tracing.enabled=false",
            "--yona.notification.bymail.enabled=false", "--spring.jpa.show-sql=false", "--logging.level.root=WARN")
        try {
            val em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory::class.java))
            val tx = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
            tx.executeWithoutResult {
                val users = em.createQuery("from User", User::class.java).resultList
                require(users.isEmpty() && em.createQuery("select count(p) from Project p", Long::class.javaObjectType).singleResult == 0L) {
                    "Resource probe requires an empty disposable database"
                }
                em.persist(User(loginId = "admin", name = "Probe", email = "probe@example.invalid",
                    state = UserState.SITE_ADMIN, password = "not-a-login-credential"))
            }
            Files.newInputStream(archive).use { context.getBean(DataBackupService::class.java).importSite(it, null) }
            val initialCount = tx.execute { em.createQuery("select count(i) from Issue i", Long::class.javaObjectType).singleResult }!!
            check(initialCount > 0) { "Archive contains no issues" }
            result["originalIssues"] = initialCount
            if (scale > 0) tx.executeWithoutResult {
                val source = em.createQuery("from Issue i order by i.id", Issue::class.java).setMaxResults(1).singleResult
                val project = source.project
                val startNumber = em.createQuery("select max(i.number) from Issue i where i.project = :p", Long::class.javaObjectType)
                    .setParameter("p", project).singleResult ?: 0
                repeat(scale) { n ->
                    val issue = Issue(project = project, number = startNumber + n + 1,
                        title = "[Bug] 검색 동기화 sampletoken${n % 100} ${source.title}",
                        body = source.body.orEmpty() + "\n" + "사용자 로그인 오류와 담당자 변경을 확인합니다. 검색 색인 배치 작업 검증. ".repeat(16))
                    em.persist(issue)
                    repeat(2) { c -> em.persist(IssueComment(issue = issue,
                        contents = "댓글 $c sampletoken${n % 100} " + "로그인 오류 재현과 수정 결과를 확인했습니다. ".repeat(4))) }
                    if (n % 200 == 199) em.flush()
                }
            }
            tx.executeWithoutResult {
                result["issues"] = em.createQuery("select count(i) from Issue i", Long::class.javaObjectType).singleResult
                result["comments"] = em.createQuery("select count(c) from IssueComment c", Long::class.javaObjectType).singleResult
                result["sourceTextBytes"] = em.createQuery("select i.title, i.body from Issue i", Array<Any>::class.java).resultList.sumOf {
                    it.sumOf { value -> value?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
                } + em.createQuery("select c.contents from IssueComment c", String::class.java).resultList.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
            }
            val jobs = context.getBeanProvider(IssueSearchJobs::class.java).ifAvailable
            val changes = context.getBeanProvider(IssueSearchChanges::class.java).ifAvailable
            val index = context.getBeanProvider(IssueSearchIndex::class.java).ifAvailable
            fun jobCount() = tx.execute {
                em.createQuery("select count(j) from QueueJob j where j.taskType = 'search.issues.sync'", Long::class.javaObjectType).singleResult
            }!!
            fun drain() {
                val deadline = System.nanoTime() + 180_000_000_000L
                do {
                    jobs?.schedule()
                    Thread.sleep(500)
                    check(System.nanoTime() < deadline) { "Index queue did not drain" }
                } while (changes?.window()?.first != null || index?.status?.ready == false || tx.execute {
                    em.createQuery("select count(j) from QueueJob j where j.taskType = 'search.issues.sync' and j.finishedAt is null", Long::class.javaObjectType).singleResult
                }!! > 0)
            }
            val cpu = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            fun memory(label: String) {
                System.gc(); Thread.sleep(300)
                result["${label}HeapBytes"] = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                result["${label}RssKiB"] = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
                    .start().inputStream.bufferedReader().readText().trim().toLong()
            }
            memory("beforeIndex")
            val indexCpu = cpu.processCpuTime
            result["initialIndexWallMs"] = if (index != null) measureNanoTime { drain() } / 1e6 else 0.0
            result["initialIndexCpuMs"] = (cpu.processCpuTime - indexCpu) / 1e6
            memory("ready")
            val search = context.getBean(IssueSearchService::class.java)
            val user = tx.execute { em.createQuery("from User u where u.loginId = 'admin'", User::class.java).singleResult }
            val conditions = Specification<Issue> { _, _, cb -> cb.conjunction() }
            val queries = System.getenv("YONA_PROBE_QUERIES")?.split(",") ?: if (scale > 0) listOf("sampletoken42", "로그인", "missingneedle92817") else listOf("Synthetic", "invented", "missingneedle92817")
            for ((q, text) in queries.withIndex()) {
                var hits = 0L
                fun query() { val page = search.search(conditions, text, user, PageRequest.of(0, 20), null)
                    check((page as IssueSearchPage).searchBackend == backend); hits = page.totalElements }
                repeat(5) { query() }
                val startCpu = cpu.processCpuTime
                val times = List(20) { measureNanoTime { query() } / 1e6 }.sorted()
                result["query$q"] = mapOf("text" to text, "hits" to hits, "p50Ms" to times[9], "p95Ms" to times[18],
                    "cpuMs" to (cpu.processCpuTime - startCpu) / 1e6)
            }
            memory("searched")
            val beforeJobs = jobCount()
            val beforeSuccess = index?.status?.lastSuccess
            val idleCpu = cpu.processCpuTime
            val idleStart = System.nanoTime()
            repeat(20) { jobs?.schedule(); Thread.sleep(500) }
            result["idleWallMs"] = (System.nanoTime() - idleStart) / 1e6
            result["idleCpuMs"] = (cpu.processCpuTime - idleCpu) / 1e6
            result["idleNewJobs"] = jobCount() - beforeJobs
            check(index?.status?.lastSuccess == beforeSuccess && result["idleNewJobs"] == 0L)
            val updateCpu = cpu.processCpuTime
            result["updateWriteMs"] = measureNanoTime {
                tx.executeWithoutResult {
                    em.createQuery("from Issue i order by i.id", Issue::class.java).setMaxResults(10).resultList.forEach {
                        it.body = it.body.orEmpty() + " resourceprobechanged"
                    }
                }
            } / 1e6
            result["updateToVisibleMs"] = if (index != null) measureNanoTime { drain() } / 1e6 else 0.0
            result["updateCpuMs"] = (cpu.processCpuTime - updateCpu) / 1e6
            result["updateScanned"] = index?.status?.scanned ?: 0
            result["updateJobs"] = jobCount() - beforeJobs
            if (index != null) {
                val expected = minOf(10L, initialCount + scale)
                check(index.status.scanned == expected && result["updateJobs"] == 1L)
                check(index.search("resourceprobechanged").size.toLong() == expected)
            }
            val indexPath = root.resolve("data/search/issues")
            result["indexBytes"] = if (Files.exists(indexPath)) Files.walk(indexPath).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() } else 0
            memory("finished")
            Files.writeString(root.resolve("metrics.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(result))
            println("Search probe metrics: ${root.resolve("metrics.json")}")
        } finally { context.close() }
    }
}
