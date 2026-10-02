package com.github.yonaprojects.yona.domain.issue

import jakarta.annotation.PreDestroy
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.AnalyzerWrapper
import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.queryparser.simple.SimpleQueryParser
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class IssueSearchDocument(val id: Long, val title: String, val body: String, val comments: List<Pair<Long, String>>) {
    fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        (listOf(title, body) + comments.flatMap { listOf(it.first.toString(), it.second) }).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
}

data class IssueIndexHit(val id: Long, val digest: String)
data class IssueIndexStatus(val ready: Boolean = false, val indexed: Long = 0, val scanned: Long = 0,
    val running: Boolean = false, val lastSuccess: Instant? = null, val error: String? = null)

/** Single-node index. Only queue workers write; requests share the last committed reader. */
@Component
@ConditionalOnProperty(name = ["yona.search.backend"], havingValue = "lucene")
class IssueSearchIndex(@Value("\${yona.search.index-dir:\${yona.data:data}/search/issues}") path: String) {
    private val path = Path.of(path)
    private val readers = ReentrantReadWriteLock()
    private var directory: FSDirectory? = null
    private var writer: IndexWriter? = null
    private var reader: DirectoryReader? = null
    private val nori = KoreanAnalyzer()
    val analyzer = object : AnalyzerWrapper(Analyzer.PER_FIELD_REUSE_STRATEGY) {
        override fun getWrappedAnalyzer(fieldName: String): Analyzer = nori
        override fun wrapComponents(fieldName: String, components: Analyzer.TokenStreamComponents) = components
        override fun getPositionIncrementGap(fieldName: String) = 100
    }
    @Volatile final var status = IssueIndexStatus()
        private set

    fun query(text: String): Query = if (text.isBlank()) MatchAllDocsQuery() else SimpleQueryParser(analyzer,
        mapOf("title" to 3f, "body" to 1f, "comments" to 1f),
        SimpleQueryParser.PHRASE_OPERATOR or SimpleQueryParser.WHITESPACE_OPERATOR
    ).apply { defaultOperator = BooleanClause.Occur.MUST }.parse(text)

    fun search(text: String, titleHead: String? = null): List<IssueIndexHit> = readers.read {
        check(status.ready) { "Search index is not ready" }
        val query = query(text)
        val filtered = if (titleHead == null) query else BooleanQuery.Builder()
            .add(query, BooleanClause.Occur.MUST)
            .add(TermQuery(Term("titleHead", titleHead)), BooleanClause.Occur.FILTER).build()
        hits(checkNotNull(reader), filtered)
    }

    private fun hits(reader: DirectoryReader, query: Query): List<IssueIndexHit> {
        val searcher = IndexSearcher(reader)
        val result = mutableListOf<IssueIndexHit>()
        var after: ScoreDoc? = null
        do {
            val page = searcher.searchAfter(after, query, 500).scoreDocs
            page.forEach {
                val document = searcher.storedFields().document(it.doc)
                result.add(IssueIndexHit(document.get("id").toLong(), document.get("digest")))
            }
            after = page.lastOrNull()
        } while (after != null)
        return result
    }

    /** Each retry scans the current DB, never a historical payload. Publish only a complete pass. */
    @Synchronized
    fun synchronize(batch: (Long) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit = {}) {
        status = status.copy(running = true, scanned = 0, error = null)
        try {
            val dir = directory ?: FSDirectory.open(path).also { directory = it }
            val output = writer ?: IndexWriter(dir, IndexWriterConfig(analyzer)).also { writer = it }
            val previous = if (DirectoryReader.indexExists(dir)) DirectoryReader.open(dir).use {
                hits(it, MatchAllDocsQuery()).associate { hit -> hit.id to hit.digest }.toMutableMap()
            } else mutableMapOf()
            var cursor = 0L
            var scanned = 0L
            while (true) {
                checkpoint(scanned)
                val documents = batch(cursor)
                if (documents.isEmpty()) break
                documents.forEach { source ->
                    val digest = source.digest()
                    if (previous.remove(source.id) != digest) {
                        output.updateDocument(Term("id", source.id.toString()), document(source))
                    }
                }
                cursor = documents.last().id
                scanned += documents.size
                status = status.copy(scanned = scanned)
            }
            previous.keys.forEach { output.deleteDocuments(Term("id", it.toString())) }
            checkpoint(scanned)
            output.commit()
            readers.write {
                val next = DirectoryReader.open(dir)
                reader?.close()
                reader = next
                status = IssueIndexStatus(true, next.numDocs().toLong(), scanned, false, Instant.now())
            }
        } catch (failure: Exception) {
            status = status.copy(ready = false, running = false, error = "INDEX_SYNC_FAILED")
            // Discard partial writes so an older failed attempt cannot be published by a retry.
            runCatching { writer?.rollback() }
            writer = null
            throw failure
        }
    }

    private fun document(source: IssueSearchDocument): Document = Document().apply {
        add(StringField("id", source.id.toString(), Field.Store.YES))
        add(StoredField("digest", source.digest()))
        add(TextField("title", source.title, Field.Store.NO))
        add(TextField("body", source.body, Field.Store.NO))
        source.comments.forEach { add(TextField("comments", it.second, Field.Store.NO)) }
        TitleHeads.extract(source.title).forEach { add(StringField("titleHead", it, Field.Store.NO)) }
    }

    /** Update only the durable dirty IDs. Missing rows are tombstones, not stale payloads. */
    @Synchronized
    fun update(ids: List<Long>, load: (List<Long>) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit = {}) {
        status = status.copy(running = true, scanned = 0, error = null)
        try {
            val dir = checkNotNull(directory) { "Initial index is not built" }
            val output = writer ?: IndexWriter(dir, IndexWriterConfig(analyzer)).also { writer = it }
            var scanned = 0L
            for (batch in ids.chunked(200)) {
                checkpoint(scanned)
                val documents = load(batch).associateBy { it.id }
                batch.forEach { id ->
                    val source = documents[id]
                    if (source == null) output.deleteDocuments(Term("id", id.toString()))
                    else output.updateDocument(Term("id", id.toString()), document(source))
                }
                scanned += batch.size
                status = status.copy(scanned = scanned)
            }
            checkpoint(scanned)
            output.commit()
            readers.write {
                val next = DirectoryReader.open(dir)
                reader?.close()
                reader = next
                status = IssueIndexStatus(true, next.numDocs().toLong(), scanned, false, Instant.now())
            }
        } catch (failure: Exception) {
            status = status.copy(ready = false, running = false, error = "INDEX_SYNC_FAILED")
            runCatching { writer?.rollback() }
            writer = null
            throw failure
        }
    }

    @PreDestroy
    @Synchronized
    fun close() = readers.write {
        status = status.copy(ready = false)
        reader?.close()
        reader = null
        writer?.rollback()
        writer = null
        directory?.close()
        directory = null
        analyzer.close()
        nori.close()
    }
}

interface TitleHeadCandidate {
    val id: Long
    val title: String
}

/** Same leading-bracket rule for indexing, filters and title-head maintenance. */
object TitleHeads {
    @JvmStatic
    fun extract(title: String): List<String> = Regex("\\G\\s*\\[([^]\\r\\n]+)]").findAll(title).map { it.groupValues[1] }.toList()
    @JvmStatic
    fun legacy(filter: String?): String? = filter?.let { Regex("^\\[([^]\\r\\n]+)]$").matchEntire(it)?.groupValues?.get(1) }
    fun <T : Any> matchingIds(repository: org.springframework.data.jpa.repository.JpaSpecificationExecutor<T>,
                             conditions: org.springframework.data.jpa.domain.Specification<T>, head: String): List<Long> =
        repository.findBy<T, List<TitleHeadCandidate>>(conditions.and(contains(head))) {
            it.`as`(TitleHeadCandidate::class.java).all()
        }.filter { head in extract(it.title) }.map { it.id }

    fun <T : Any> page(repository: org.springframework.data.jpa.repository.JpaSpecificationExecutor<T>,
                       conditions: org.springframework.data.jpa.domain.Specification<T>, head: String,
                       pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<T> {
        val ids = matchingIds(repository, conditions, head)
        if (ids.isEmpty()) return org.springframework.data.domain.Page.empty(pageable)
        return repository.findAll(conditions.and(withIds(ids)), stablePage(pageable))
    }

    fun <T : Any> withIds(values: Collection<Long>): org.springframework.data.jpa.domain.Specification<T> =
        org.springframework.data.jpa.domain.Specification { root, _, cb ->
            // Numeric literals avoid SQL Server's total bind-parameter limit; also split IN lists.
            cb.or(*values.chunked(500).map { batch ->
                cb.`in`(root.get<Long>("id")).also { clause -> batch.forEach { clause.value(cb.literal(it)) } }
            }.toTypedArray())
        }

    /** Stable ties for offset pages. Call only for DB ordering, never Lucene relevance ordering. */
    fun stablePage(pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Pageable {
        val sort = if (pageable.sort.getOrderFor("id") != null) pageable.sort else
            pageable.sort.and(org.springframework.data.domain.Sort.by("id"))
        return if (pageable.isUnpaged) org.springframework.data.domain.Pageable.unpaged(sort) else
            org.springframework.data.domain.PageRequest.of(pageable.pageNumber, pageable.pageSize, sort)
    }

    fun <T : Any> contains(head: String): org.springframework.data.jpa.domain.Specification<T> {
        // SQL Server treats [] as a character class; escape it along with LIKE wildcards on all DBs.
        val literal = "[$head]".map { if (it in "\\%_[]") "\\$it" else it.toString() }.joinToString("")
        return org.springframework.data.jpa.domain.Specification { root, _, cb -> cb.like(root.get("title"), "%$literal%", '\\') }
    }

}
