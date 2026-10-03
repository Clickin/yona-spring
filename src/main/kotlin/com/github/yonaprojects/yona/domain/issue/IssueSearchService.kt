package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.user.User
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleHTMLEncoder
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class IssueSearchSnippet(val html: String, val commentId: Long? = null)
class IssueSearchPage(content: List<Issue>, pageable: Pageable, total: Long,
    val searchBackend: String, val snippets: Map<Long, IssueSearchSnippet> = emptyMap()
) : PageImpl<Issue>(content, pageable, total)

@Service
@Transactional(readOnly = true)
class IssueSearchService(
    private val issues: IssueRepository,
    private val comments: IssueCommentRepository,
    private val accessControl: AccessControl,
    @Value("\${yona.search.backend:db}") val backend: String = "db",
    private val index: IssueSearchIndex? = null
) {
    init { require(backend in setOf("db", "lucene")) { "yona.search.backend must be db or lucene" } }

    fun search(conditions: Specification<Issue>, text: String?, user: User?, pageable: Pageable,
               titleHead: String? = null): Page<Issue> {
        val fullText = backend == "lucene" && (!text.isNullOrBlank() || titleHead != null)
        if (fullText) {
            try {
                index?.withSearch(text.orEmpty(), titleHead) { search ->
                    matchedPage(conditions, text, user, pageable, titleHead, search)
                }?.let { return it }
            } catch (_: java.io.IOException) {
                // An unreadable index falls back to current DB text; SQL failures are not hidden.
            }
        }
        val spec = conditions.and(IssueSpecification.textSearch(text))
        if (titleHead != null) return matchedPage(spec, text, user, pageable, titleHead, null)
        val allowed = if (fullText) spec.and(accessControl.readableIssues(user)) else spec
        val page = if (pageable.isUnpaged) PageImpl(issues.findAll(allowed, pageable.sort)) else issues.findAll(allowed, pageable)
        return IssueSearchPage(page.content, pageable, page.totalElements, "db")
    }

    private fun matchedPage(conditions: Specification<Issue>, text: String?, user: User?, pageable: Pageable,
                            titleHead: String?, search: IssueIndexSearch?): IssueSearchPage {
        val backend = if (search == null) "db" else "lucene"
        if (search?.isEmpty() == true) return IssueSearchPage(emptyList(), pageable, 0, backend)
        val allowed = conditions.and(accessControl.readableIssues(user))
        val selected = mutableListOf<Long>()
        val selectedHits = mutableMapOf<Long, IssueIndexHit>()
        var total = 0L
        fun retain(id: Long, hit: IssueIndexHit?) {
            if (pageable.isUnpaged || (total >= pageable.offset && selected.size < pageable.pageSize)) {
                selected.add(id)
                if (hit != null) selectedHits[id] = hit
            }
            total++
        }
        fun exactHead(candidate: TitleHeadCandidate) = titleHead == null || titleHead in TitleHeads.extract(candidate.title)
        if (search != null && pageable.sort.isUnsorted) {
            // Stream relevance order. Only one bounded ID list crosses into SQL at a time.
            search.forEachBatch { hits ->
                val candidates = issues.findBy<Issue, List<TitleHeadCandidate>>(
                    allowed.and(TitleHeads.withIds(hits.map { it.id }))) {
                    it.`as`(TitleHeadCandidate::class.java).all()
                }.filter(::exactHead).map { it.id }.toHashSet()
                hits.forEach { hit -> if (hit.id in candidates) retain(hit.id, hit) }
            }
        } else {
            // Let the DB order arbitrary sort columns. Probe Lucene with each ID/title slice rather than
            // generating one unbounded IN expression or retaining every hit merely to sort a page.
            val sort = TitleHeads.stablePage(pageable).sort
            val candidates = if (titleHead == null) allowed else allowed.and(TitleHeads.contains(titleHead))
            var pageNumber = 0
            do {
                val batch = issues.findBy<Issue, org.springframework.data.domain.Slice<TitleHeadCandidate>>(candidates) {
                    it.`as`(TitleHeadCandidate::class.java).slice(
                        org.springframework.data.domain.PageRequest.of(pageNumber, IssueIndexSearch.BATCH_SIZE, sort))
                }
                val exact = batch.content.filter(::exactHead)
                val hits = search?.matching(exact.map { it.id })
                exact.forEach { candidate ->
                    if (search == null || hits!!.containsKey(candidate.id)) retain(candidate.id, hits?.get(candidate.id))
                }
                pageNumber++
            } while (batch.hasNext())
        }
        // Only the requested page is hydrated; an explicitly unpaged caller necessarily owns its full result.
        val byId = selected.chunked(IssueIndexSearch.BATCH_SIZE).flatMap { ids ->
            issues.findAll(allowed.and(TitleHeads.withIds(ids)))
        }.associateBy { it.id!! }
        val page = selected.mapNotNull { byId[it] }
        val snippets = if (search == null || text.isNullOrBlank()) emptyMap() else page.chunked(200).flatMap { batch ->
            val byIssue = comments.findForSearch(batch.map { it.id!! }).groupBy { it.issue.id }
            batch.mapNotNull { issue ->
                val source = IssueSearchDocument(issue.id!!, issue.title, issue.body.orEmpty(),
                    byIssue[issue.id].orEmpty().map { it.id!! to it.contents })
                // Never attach a stale snippet, comment link, or offsets from an identifier-only match.
                val hit = selectedHits.getValue(issue.id!!)
                if (source.digest() != hit.digest || hit.auxiliaryOnly) null
                else snippet(source, text)?.let { issue.id!! to it }
            }
        }.toMap()
        return IssueSearchPage(page, pageable, total, backend, snippets)
    }

    private fun snippet(source: IssueSearchDocument, text: String): IssueSearchSnippet? {
        if (text.isBlank()) return null
        val engine = checkNotNull(index)
        val query = engine.query(text)
        val fields = listOf(Triple("title", source.title, null), Triple("body", source.body, null)) +
            source.comments.map { Triple("comments", it.second, it.first) }
        for ((field, value, commentId) in fields) {
            val highlighter = Highlighter(SimpleHTMLFormatter("<mark>", "</mark>"), SimpleHTMLEncoder(), QueryScorer(query, field))
            highlighter.maxDocCharsToAnalyze = value.length
            val fragment = highlighter.getBestFragment(engine.analyzer, field, value)
            if (fragment != null) return IssueSearchSnippet(fragment, commentId)
        }
        return null
    }
}
