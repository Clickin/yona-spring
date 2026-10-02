package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.YonaApplication
import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.site.DataBackupService
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.SpringApplication
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Known-item quality and latency comparison of LIKE and Lucene. Opt-in measurement driver, not a CI test.
 *
 * Queries are derived from sampled issues' own text by fixed rewrite rules; the source issue is the expected
 * result. The aggregate output holds no query text. `YONA_PROBE_CASES_OUTPUT` writes the cases for private review.
 */
object IssueSearchQualityProbe {
    data class Case(val type: String, val issueId: Long, val text: String)

    /**
     * Rule order is the report order. `exact` and `identifierPart` are substrings, so LIKE finds them.
     * No word-splitting rule: halving a word without a dictionary produces queries nobody types (`업데 이트`).
     */
    val types = listOf("exact", "separated", "reordered", "inflected", "particle", "joined",
        "identifierSplit", "identifierPart", "missing")

    private val hangul = Regex("[가-힣]{2,}")
    private val verbEnding = Regex("^([가-힣]{2,})(하였습니다|했습니다|합니다|하였다|했다|한다|하는|하고|해서|하여|했고|하면|해야|" +
        "되었습니다|됐습니다|되었다|됩니다|된다|되는|되어|돼서|된)$")
    private val particle = Regex("^([가-힣]{2,}?)(에서|으로|까지|부터|에게|한테|은|는|이|가|을|를|에|로|의|와|과|도|만)$")
    private val particleSwap = mapOf("은" to "이", "이" to "을", "을" to "은", "는" to "가", "가" to "를", "를" to "는",
        "에" to "에서", "에서" to "에", "으로" to "에", "로" to "에", "의" to "에", "와" to "도", "과" to "도", "도" to "만",
        "만" to "도", "까지" to "부터", "부터" to "까지", "에게" to "한테", "한테" to "에게")
    private val ending = Regex("(니다|어요|아요|세요|지만|는데|면서|으면|하면|해서|었다|았다|였다|겠다|해야|되어)$")
    /** Noun-like word: no particle or verb ending. Only these are joined. */
    private fun bare(word: String) = particle.matchEntire(word) == null && verbEnding.matchEntire(word) == null &&
        !ending.containsMatchIn(word)
    private val separated = Regex("^[A-Za-z][A-Za-z0-9]*(?:[_.\\-/][A-Za-z0-9]+)+$")
    private val camel = Regex("^[A-Z]?[a-z0-9]+(?:[A-Z][a-z0-9]+){2,}$")

    private fun words(sentence: String) = sentence.split(Regex("\\s+"))
        .map { it.trim { c -> !c.isLetterOrDigit() && c != '_' } }.filter { it.isNotEmpty() }

    private fun sentences(field: String) = field
        .replace(Regex("```[\\s\\S]*?```"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace(Regex("https?://\\S+"), " ").replace(Regex("[`*#>|\\[\\]()!~]"), " ")
        .split(Regex("[\\r\\n]+|(?<=[.!?。])\\s+")).map(::words).filter { it.isNotEmpty() }

    /** At most one case per rule for an issue. LIKE compares each field separately, case-insensitively. */
    fun cases(issueId: Long, fields: List<String>, seed: Long): List<Case> {
        fun likeFinds(text: String) = fields.any { it.contains(text, ignoreCase = true) }
        val candidates = linkedMapOf<String, MutableList<String>>()
        fun add(type: String, text: String, like: Boolean) {
            if (likeFinds(text) == like) candidates.getOrPut(type) { mutableListOf() }.add(text)
        }
        for (words in fields.flatMap(::sentences)) {
            val korean = words.map { hangul.matches(it) }
            for (i in words.indices) {
                val word = words[i]
                if (i + 1 < words.size && korean[i] && korean[i + 1]) {
                    add("exact", "$word ${words[i + 1]}", true)
                    add("reordered", "${words[i + 1]} $word", false)
                    if (bare(word) && bare(words[i + 1])) add("joined", word + words[i + 1], false)
                }
                for (j in i + 3 until minOf(words.size, i + 8)) {
                    if (korean[i] && korean[j]) add("separated", "$word ${words[j]}", false)
                }
                if (!korean[i]) {
                    if (separated.matches(word)) add("identifierSplit", word.split(Regex("[_.\\-/]")).joinToString(" "), false)
                    // NullPointerException -> PointerException: a substring, but not a whole token.
                    if (camel.matches(word)) add("identifierPart", word.substring(word.indexOfFirst { it.isUpperCase() }
                        .let { first -> if (first > 0) first else word.withIndex().first { it.index > 0 && it.value.isUpperCase() }.index }), true)
                    continue
                }
                val previous = words.getOrNull(i - 1)?.takeIf { korean[i - 1] }
                verbEnding.matchEntire(word)?.let { m ->
                    val swapped = m.groupValues[1] + if (m.groupValues[2][0] in "되됐됩된돼") "하는" else "된"
                    add("inflected", listOfNotNull(previous, swapped).joinToString(" "), false)
                }
                // `휴가`, `정형외과`, `하므로` end in particle-like syllables. Require the stem elsewhere without that ending.
                particle.matchEntire(word)?.takeIf { m -> previous != null && verbEnding.matchEntire(word) == null &&
                    Regex(Regex.escape(m.groupValues[1]) + "(?!" + m.groupValues[2] + ")").let { stem -> fields.any(stem::containsMatchIn) }
                }?.let { m ->
                    add("particle", "$previous ${m.groupValues[1]}${particleSwap.getValue(m.groupValues[2])}", false)
                }
            }
        }
        // Independent choice per rule: adding or removing a rule leaves the other cases unchanged.
        return candidates.map { (type, texts) ->
            Case(type, issueId, texts.distinct().random(Random(java.util.Objects.hash(seed, issueId, type))))
        }
    }

    private fun nearest(values: List<Double>, p: Double) =
        values.sorted().let { if (it.isEmpty()) null else it[(Math.ceil(p * it.size).toInt() - 1).coerceIn(0, it.size - 1)] }

    @JvmStatic fun main(args: Array<String>) {
        val archive = Path.of(requireNotNull(System.getenv("YONA_PROBE_ARCHIVE"))).toAbsolutePath()
        val root = Path.of(requireNotNull(System.getenv("YONA_PROBE_OUTPUT"))).toAbsolutePath()
        val jdbc = System.getenv("YONA_PROBE_JDBC")
        val sample = (System.getenv("YONA_PROBE_SAMPLE") ?: "300").toInt()
        val seed = (System.getenv("YONA_PROBE_SEED") ?: "843").toLong()
        val asLogin = System.getenv("YONA_PROBE_AS")
        val casesOutput = System.getenv("YONA_PROBE_CASES_OUTPUT")?.let { Path.of(it) }
        require(sample > 0 && Files.isRegularFile(archive))
        Files.createDirectory(root, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))) // Refuse reuse.
        val context = SpringApplication.run(YonaApplication::class.java,
            "--spring.profiles.active=${if (jdbc == null) "h2" else "mariadb"}", "--server.address=127.0.0.1", "--server.port=0",
            "--yona.data=$root/data", "--spring.datasource.url=${jdbc ?: "jdbc:h2:file:$root/database;NON_KEYWORDS=VALUE"}",
            "--spring.datasource.username=${if (jdbc == null) "sa" else "root"}",
            "--spring.datasource.password=${if (jdbc == null) "" else requireNotNull(System.getenv("YONA_PROBE_DB_PASSWORD"))}",
            "--yona.git.base-dir=$root/git", "--yona.svn.base-dir=$root/svn", "--yona.lfs.base-dir=$root/lfs",
            "--yona.upload.base-dir=$root/uploads", "--yona.search.backend=lucene",
            "--yona.search.initial-delay-millis=3600000", // Index is driven below, then left idle.
            "--yona.mailbox.imap.enabled=false", "--yona.ldap.enabled=false", "--management.tracing.enabled=false",
            "--yona.notification.bymail.enabled=false", "--spring.jpa.show-sql=false", "--logging.level.root=WARN")
        try {
            val em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory::class.java))
            val tx = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
            tx.executeWithoutResult {
                require(em.createQuery("select count(u) from User u", Long::class.javaObjectType).singleResult == 0L &&
                    em.createQuery("select count(p) from Project p", Long::class.javaObjectType).singleResult == 0L) {
                    "Quality probe requires an empty disposable database"
                }
                em.persist(User(loginId = "admin", name = "Probe", email = "probe@example.invalid",
                    state = UserState.SITE_ADMIN, password = "not-a-login-credential"))
            }
            Files.newInputStream(archive).use { context.getBean(DataBackupService::class.java).importSite(it, null) }
            val jobs = context.getBean(IssueSearchJobs::class.java)
            val changes = context.getBean(IssueSearchChanges::class.java)
            val index = context.getBean(IssueSearchIndex::class.java)
            val deadline = System.nanoTime() + 600_000_000_000L
            do {
                jobs.schedule(); Thread.sleep(500)
                check(System.nanoTime() < deadline) { "Index queue did not drain" }
            } while (changes.window().first != null || !index.status.ready || tx.execute {
                em.createQuery("select count(j) from QueueJob j where j.taskType = 'search.issues.sync' and j.finishedAt is null",
                    Long::class.javaObjectType).singleResult
            }!! > 0)

            val issues = context.getBean(IssueRepository::class.java)
            val comments = context.getBean(IssueCommentRepository::class.java)
            val access = context.getBean(AccessControl::class.java)
            val user = tx.execute { em.createQuery("from User u where u.loginId = :l", User::class.java)
                .setParameter("l", asLogin ?: "admin").singleResult }!!
            val readable = access.readableIssues(user)
            val lucene = context.getBean(IssueSearchService::class.java)
            val db = IssueSearchService(issues, comments, access, "db", null)
            val everything = Specification<Issue> { _, _, cb -> cb.conjunction() }
            val byDate = Sort.by(Sort.Order.desc("createdDate"), Sort.Order.desc("id"))
            fun ids(spec: Specification<Issue>, sort: Sort) =
                issues.findBy<Issue, List<IssueSearchId>>(spec) { it.sortBy(sort).`as`(IssueSearchId::class.java).all() }.map { it.id }
            fun likeAnd(text: String) = text.split(' ').filter { it.isNotBlank() }
                .fold(readable) { spec, word -> spec.and(IssueSpecification.textSearch(word)) }

            val random = Random(seed)
            val cases = tx.execute {
                val pool = ids(readable, Sort.by("id")).shuffled(random)
                pool.asSequence().take(sample).flatMap { id ->
                    val issue = issues.findById(id).get()
                    val fields = listOf(issue.title, issue.body.orEmpty()) + comments.findForSearch(listOf(id)).map { it.contents }
                    cases(id, fields, seed)
                }.toList()
            }!! + List(20) { n -> Case("missing", 0, "zq" + List(10) { ('a' + random.nextInt(26)) }.joinToString("") + n) }

            // Ordered matches per engine: the full result list, not only the first page.
            val engines = linkedMapOf<String, (String) -> List<Long>>(
                "like" to { text -> ids(readable.and(IssueSpecification.textSearch(text)), byDate) },
                "likeAnd" to { text -> ids(likeAnd(text), byDate) },
                "lucene" to { text -> val allowed = ids(readable, Sort.by("id")).toHashSet()
                    index.search(text).map { it.id }.filter { it in allowed } },
                "luceneDate" to { text -> val hits = index.search(text).map { it.id }
                    if (hits.isEmpty()) emptyList() else ids(readable.and(TitleHeads.withIds(hits)), byDate) })
            // First page as served: DB uses the web's date order, Lucene relevance or the same date order.
            val pages = linkedMapOf<String, (String) -> Unit>(
                "like" to { text -> db.search(readable, text, user, PageRequest.of(0, 20, byDate)) },
                "likeAnd" to { text -> db.search(likeAnd(text), null, user, PageRequest.of(0, 20, byDate)) },
                "lucene" to { text -> check((lucene.search(everything, text, user, PageRequest.of(0, 20))
                    as IssueSearchPage).searchBackend == "lucene") },
                "luceneDate" to { text -> check((lucene.search(everything, text, user, PageRequest.of(0, 20, byDate))
                    as IssueSearchPage).searchBackend == "lucene") })

            data class Outcome(val rank: Int?, val hits: Int, val ms: Double)
            val outcomes = cases.map { case ->
                case to engines.mapValues { (name, engine) ->
                    val matched = tx.execute { engine(case.text) }!!
                    val page = pages.getValue(name)
                    tx.executeWithoutResult { page(case.text) } // Warm this query once before timing.
                    val ms = List(3) { measureNanoTime { tx.executeWithoutResult { page(case.text) } } / 1e6 }.sorted()[1]
                    Outcome(matched.indexOf(case.issueId).takeIf { it >= 0 }?.plus(1), matched.size, ms)
                }
            }

            val result = linkedMapOf<String, Any?>("database" to if (jdbc == null) "h2" else "mariadb",
                "java" to System.getProperty("java.version"), "seed" to seed, "sampledIssues" to sample,
                "user" to if (asLogin == null) "siteAdmin" else "archiveUser",
                "issues" to tx.execute { em.createQuery("select count(i) from Issue i", Long::class.javaObjectType).singleResult },
                "comments" to tx.execute { em.createQuery("select count(c) from IssueComment c", Long::class.javaObjectType).singleResult })
            for (type in types + "all") {
                val rows = outcomes.filter { if (type == "all") it.first.type != "missing" else it.first.type == type }
                if (rows.isEmpty()) continue
                result[type] = linkedMapOf<String, Any?>("cases" to rows.size).apply {
                    if (type != "missing") {
                        put("luceneOnly", rows.count { it.second.getValue("lucene").rank != null && it.second.getValue("like").rank == null })
                        put("likeOnly", rows.count { it.second.getValue("like").rank != null && it.second.getValue("lucene").rank == null })
                    }
                    for (name in engines.keys) {
                        val list = rows.map { it.second.getValue(name) }
                        put(name, linkedMapOf(
                            "recall" to if (type == "missing") null else list.count { it.rank != null }.toDouble() / list.size,
                            "recallAt20" to if (type == "missing") null else list.count { (it.rank ?: Int.MAX_VALUE) <= 20 }.toDouble() / list.size,
                            "mrr" to if (type == "missing") null else list.sumOf { r -> r.rank?.let { 1.0 / it } ?: 0.0 } / list.size,
                            "hitsP50" to nearest(list.map { it.hits.toDouble() }, 0.5),
                            "hitsP90" to nearest(list.map { it.hits.toDouble() }, 0.9),
                            "pageP50Ms" to nearest(list.map { it.ms }, 0.5),
                            "pageP95Ms" to nearest(list.map { it.ms }, 0.95)))
                    }
                }
            }
            val json = JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
            Files.writeString(root.resolve("quality.json"), json.writeValueAsString(result))
            casesOutput?.let { path ->
                Files.writeString(path, json.writeValueAsString(outcomes.map { (case, by) ->
                    mapOf("type" to case.type, "issueId" to case.issueId, "text" to case.text,
                        "rank" to by.mapValues { it.value.rank }, "hits" to by.mapValues { it.value.hits })
                }), StandardOpenOption.CREATE_NEW)
            }
            println("Search quality metrics: ${root.resolve("quality.json")}")
        } finally { context.close() }
    }
}
