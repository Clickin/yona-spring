package com.github.yonaprojects.yona.domain.issue

import jakarta.annotation.PreDestroy
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.AnalyzerWrapper
import org.apache.lucene.analysis.core.FlattenGraphFilter
import org.apache.lucene.analysis.core.LowerCaseFilter
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter
import org.apache.lucene.analysis.util.CharTokenizer
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.util.QueryBuilder
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.Reader
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Pattern
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

data class IssueIndexHit(val id: Long, val digest: String, val auxiliaryOnly: Boolean = false)

/** One pinned reader; request working sets and SQL candidate lists never exceed BATCH_SIZE. */
class IssueIndexSearch internal constructor(private val searcher: IndexSearcher, private val query: Query) {
    companion object { const val BATCH_SIZE = 200 }

    fun isEmpty(): Boolean = searcher.search(query, 1).scoreDocs.isEmpty()

    fun forEachBatch(consume: (List<IssueIndexHit>) -> Unit) {
        var after: ScoreDoc? = null
        do {
            val page = searcher.searchAfter(after, query, BATCH_SIZE).scoreDocs
            if (page.isNotEmpty()) consume(page.map(::hit))
            after = page.lastOrNull()
        } while (after != null)
    }

    fun matching(ids: List<Long>): Map<Long, IssueIndexHit> {
        require(ids.size <= BATCH_SIZE)
        if (ids.isEmpty()) return emptyMap()
        val filtered = BooleanQuery.Builder().add(query, BooleanClause.Occur.MUST)
            .add(TermInSetQuery("id", ids.map { org.apache.lucene.util.BytesRef(it.toString()) }),
                BooleanClause.Occur.FILTER).build()
        return searcher.search(filtered, ids.size).scoreDocs.map(::hit).associateBy { it.id }
    }

    private fun hit(score: ScoreDoc): IssueIndexHit {
        val document = searcher.storedFields().document(score.doc, setOf("id", "digest"))
        return IssueIndexHit(document.get("id").toLong(), document.get("digest"), score.score == 0f)
    }
}
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
    private val identifiers = object : Analyzer() {
        override fun initReader(fieldName: String, reader: Reader): Reader =
            PatternReplaceCharFilter(ACRONYM_BOUNDARY, "$1 ", reader)

        override fun createComponents(fieldName: String): TokenStreamComponents {
            val tokenizer = CharTokenizer.fromTokenCharPredicate { code ->
                code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code || code in '0'.code..'9'.code ||
                    code == '.'.code || code == '_'.code || code == '/'.code || code == '-'.code
            }
            val parts = WordDelimiterGraphFilter(tokenizer,
                WordDelimiterGraphFilter.GENERATE_WORD_PARTS or WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS or
                    WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE or WordDelimiterGraphFilter.SPLIT_ON_NUMERICS or
                    WordDelimiterGraphFilter.PRESERVE_ORIGINAL, null)
            return TokenStreamComponents(tokenizer, LowerCaseFilter(parts))
        }
    }
    val analyzer = object : AnalyzerWrapper(Analyzer.PER_FIELD_REUSE_STRATEGY) {
        override fun getWrappedAnalyzer(fieldName: String): Analyzer =
            if (fieldName.endsWith("_ident")) identifiers else nori
        override fun wrapComponents(fieldName: String, components: Analyzer.TokenStreamComponents) =
            if (fieldName.endsWith("_ident")) TokenStreamComponents(components.source, FlattenGraphFilter(components.tokenStream))
            else components
        override fun getPositionIncrementGap(fieldName: String) = 100
    }
    private companion object {
        val FIELDS = listOf("title" to 3f, "body" to 1f, "comments" to 1f)
        const val VERSION_KEY = "yona.issue-search.version"
        const val VERSION = "3"
        val ACRONYM_BOUNDARY = Pattern.compile("([A-Z])(?=[A-Z][a-z])")
        val IDENTIFIER = Regex("""[A-Za-z0-9]+(?:[._/-][A-Za-z0-9]+)*""")
    }
    @Volatile final var status = IssueIndexStatus()
        private set

    /**
     * Every term and quoted phrase is required; each may match title, body or comments.
     * Unquoted text is analyzed as one string, like indexed text: Nori splits a word differently without its
     * neighbours (`길이 제한` indexes `길`, but `길이` alone yields `길이`), so per-word analysis misses exact text.
     */
    fun query(text: String): Query {
        if (text.isBlank()) return MatchAllDocsQuery()
        val builder = QueryBuilder(analyzer)
        val required = BooleanQuery.Builder()
        val fallback = BooleanQuery.Builder()
        fun anyField(target: BooleanQuery.Builder, field: (String) -> Query?) {
            val fields = FIELDS.mapNotNull { (name, boost) -> field(name)?.let { BoostQuery(it, boost) } }
            if (fields.isEmpty()) return
            target.add(BooleanQuery.Builder().apply { fields.forEach { add(it, BooleanClause.Occur.SHOULD) } }.build(),
                BooleanClause.Occur.MUST)
        }
        val phrase = Regex("\"([^\"]*)\"")
        phrase.findAll(text).forEach { match ->
            // Quoted phrases retain Nori semantics; never expand them into identifier fragments.
            anyField(required) { builder.createPhraseQuery(it, match.groupValues[1]) }
            anyField(fallback) { builder.createPhraseQuery(it, match.groupValues[1]) }
        }
        val unquoted = phrase.replace(text, " ").replace('"', ' ')
        val words = IDENTIFIER.findAll(unquoted).toList()
        val terms = linkedSetOf<String>()
        val contextualTerms = linkedSetOf<String>()
        var wordIndex = 0
        analyzer.tokenStream("body", unquoted).use { stream ->
            val term = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute::class.java)
            val offset = stream.addAttribute(OffsetAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                val value = term.toString()
                terms.add(value)
                // Keep Korean analysis from the entire original string, even in a mixed query.
                while (wordIndex < words.size && words[wordIndex].range.last < offset.startOffset()) wordIndex++
                val word = words.getOrNull(wordIndex)
                if (word == null || offset.startOffset() < word.range.first || offset.endOffset() > word.range.last + 1) {
                    contextualTerms.add(value)
                }
            }
            stream.end()
        }
        terms.forEach { value -> anyField(required) { TermQuery(Term(it, value)) } }
        val primary = required.build().let { if (it.clauses().isEmpty()) MatchNoDocsQuery("No searchable terms") else it }
        if (words.isEmpty()) return primary
        contextualTerms.forEach { value -> anyField(fallback) { TermQuery(Term(it, value)) } }
        val identifierBuilder = QueryBuilder(identifiers)
        words.forEach { word ->
            // A multi-part word must stay adjacent and ordered in one field, not match scattered fragments.
            anyField(fallback) { identifierBuilder.createPhraseQuery("${it}_ident", word.value) }
        }
        fallback.add(primary, BooleanClause.Occur.MUST_NOT)
        // ponytail: zero-score fallback preserves every Nori score/order, even arbitrarily small BM25 scores.
        return BooleanQuery.Builder()
            .add(primary, BooleanClause.Occur.SHOULD)
            .add(BoostQuery(ConstantScoreQuery(fallback.build()), 0f), BooleanClause.Occur.SHOULD).build()
    }

    fun <T> withSearch(text: String, titleHead: String? = null, action: (IssueIndexSearch) -> T): T? {
        val snapshot = readers.read {
            if (!status.ready) return null
            checkNotNull(reader).also { it.incRef() }
        }
        try {
            val query = query(text)
            val filtered = if (titleHead == null) query else BooleanQuery.Builder()
                .add(query, BooleanClause.Occur.MUST)
                .add(TermQuery(Term("titleHead", titleHead)), BooleanClause.Occur.FILTER).build()
            return action(IssueIndexSearch(IndexSearcher(snapshot), filtered))
        } finally { snapshot.decRef() }
    }

    /** Each retry scans the current DB, never a historical payload. Publish only a complete pass. */
    @Synchronized
    fun synchronize(batch: (Long) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit = {}) {
        status = status.copy(running = true, scanned = 0, error = null)
        try {
            val dir = directory ?: FSDirectory.open(path).also { directory = it }
            val output = writer ?: IndexWriter(dir, IndexWriterConfig(analyzer)).also { writer = it }
            // A recovery/rebuild is a full pass. Avoid retaining every old document's stored fields in memory.
            output.deleteAll()
            var cursor = 0L
            var scanned = 0L
            while (true) {
                checkpoint(scanned)
                val documents = batch(cursor)
                if (documents.isEmpty()) break
                documents.forEach { source -> output.addDocument(document(source)) }
                cursor = documents.last().id
                scanned += documents.size
                status = status.copy(scanned = scanned)
            }
            checkpoint(scanned)
            output.setLiveCommitData(mapOf(VERSION_KEY to VERSION).entries)
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
        add(TextField("title_ident", source.title, Field.Store.NO))
        add(TextField("body_ident", source.body, Field.Store.NO))
        source.comments.forEach { add(TextField("comments_ident", it.second, Field.Store.NO)) }
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
        identifiers.close()
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
