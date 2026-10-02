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

data class IssueSearchId(val id: Long)

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
        // Legacy [head] URLs are normalized by controllers. Explicit text is otherwise ordinary text.
        val fullText = backend == "lucene" && (!text.isNullOrBlank() || titleHead != null)
        val hits = if (fullText && index?.status?.ready == true) runCatching { index!!.search(text.orEmpty(), titleHead) }.getOrNull() else null
        val usedBackend = if (hits != null) "lucene" else "db"
        val spec = if (hits == null) conditions.and(IssueSpecification.textSearch(text)) else conditions
        if (!fullText && titleHead == null) {
            val page = if (pageable.isUnpaged) PageImpl(issues.findAll(spec)) else issues.findAll(spec, pageable)
            return IssueSearchPage(page.content, pageable, page.totalElements, usedBackend)
        }
        if (hits != null && hits.isEmpty()) return IssueSearchPage(emptyList(), pageable, 0, usedBackend)
        val hitMap = hits?.associateBy { it.id }
        val matchedSpec = if (hitMap == null) spec else spec.and(TitleHeads.withIds(hitMap.keys))
        var allowed = matchedSpec.and(accessControl.readableIssues(user))
        // Exact head parsing needs only ID/title, including when Lucene is unavailable.
        val headIds = titleHead?.let { TitleHeads.matchingIds(issues, allowed, it) }
        if (headIds != null) {
            if (headIds.isEmpty()) return IssueSearchPage(emptyList(), pageable, 0, usedBackend)
            allowed = allowed.and(TitleHeads.withIds(headIds))
        }
        val result = if (pageable.sort.isSorted || hits == null) {
            issues.findAll(allowed, TitleHeads.stablePage(pageable))
        } else {
            // Relevance belongs to Lucene. Only IDs, not issue bodies, are read for all matches.
            val allowedIds = headIds?.toHashSet() ?: issues.findBy<Issue, List<IssueSearchId>>(allowed) {
                it.`as`(IssueSearchId::class.java).all()
            }.map { it.id }.toHashSet()
            val ordered = hits.map { it.id }.filter { it in allowedIds }
            val selected = if (pageable.isUnpaged) ordered else ordered.drop(
                pageable.offset.coerceAtMost(ordered.size.toLong()).toInt()).take(pageable.pageSize)
            val byId = if (selected.isEmpty()) emptyMap() else
                issues.findAll(allowed.and(TitleHeads.withIds(selected))).associateBy { it.id!! }
            PageImpl(selected.mapNotNull { byId[it] }, pageable, ordered.size.toLong())
        }
        val page = result.content
        val snippets = if (hits == null || text.isNullOrBlank()) emptyMap() else page.chunked(200).flatMap { batch ->
            val byIssue = comments.findForSearch(batch.map { it.id!! }).groupBy { it.issue.id }
            batch.mapNotNull { issue ->
                val source = IssueSearchDocument(issue.id!!, issue.title, issue.body.orEmpty(),
                    byIssue[issue.id].orEmpty().map { it.id!! to it.contents })
                // A stale match may remain until sync; never attach its old snippet or comment link.
                // Auxiliary fragments have no Nori offsets: omit rather than highlight unrelated terms or raw HTML.
                val hit = hitMap!!.getValue(issue.id!!)
                if (source.digest() != hit.digest || hit.auxiliaryOnly) null
                else snippet(source, text)?.let { issue.id!! to it }
            }
        }.toMap()
        return IssueSearchPage(page, pageable, result.totalElements, usedBackend, snippets)
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
