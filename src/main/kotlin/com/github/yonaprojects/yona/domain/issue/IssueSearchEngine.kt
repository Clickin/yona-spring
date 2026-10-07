package com.github.yonaprojects.yona.domain.issue

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import java.security.MessageDigest
import java.time.Instant

/** `yona.search.backend`가 가질 수 있는 값. `db`는 색인 없이 DB LIKE 검색만 쓴다. */
object SearchBackends {
    const val DB = "db"
    const val LUCENE = "lucene"
    const val ELASTICSEARCH = "elasticsearch"
    const val OPENSEARCH = "opensearch"
    val ALL = setOf(DB, LUCENE, ELASTICSEARCH, OPENSEARCH)
    private val INDEXED = ALL - DB

    /** 색인을 쓰는 backend인지. 알 수 없는 값은 색인을 쓰지 않는 것으로 본다. */
    fun isIndexed(backend: String) = backend in INDEXED
}

/** `db`가 아닌 모든 backend에서만 색인 동기화 bean을 만든다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${yona.search.backend:db}' != 'db'")
annotation class ConditionalOnIndexedSearch

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${yona.search.backend:db}' == 'lucene'")
annotation class ConditionalOnLuceneSearch

/** Elasticsearch와 OpenSearch는 같은 구현을 쓰고 point-in-time API 경로만 다르다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${yona.search.backend:db}' == 'elasticsearch' or '\${yona.search.backend:db}' == 'opensearch'")
annotation class ConditionalOnElasticSearch

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

data class IssueIndexStatus(val ready: Boolean = false, val indexed: Long = 0, val scanned: Long = 0,
    val running: Boolean = false, val lastSuccess: Instant? = null, val error: String? = null)

/**
 * 검색 요청 하나가 읽는 고정된 색인 시점. 요청 중에 색인이 갱신되어도 같은 결과 집합을 본다.
 * 한 번에 다루는 ID와 결과는 [BATCH_SIZE]를 넘지 않는다.
 */
interface IssueIndexSearch {
    fun isEmpty(): Boolean

    /** 관련도 순서(동점은 ID 순)로 hit를 [BATCH_SIZE]개씩 넘긴다. */
    fun forEachBatch(consume: (List<IssueIndexHit>) -> Unit)

    /** 주어진 ID 중 질의에 일치하는 것만 돌려준다. */
    fun matching(ids: List<Long>): Map<Long, IssueIndexHit>

    /**
     * 현재 DB 내용([sources])에서 일치한 곳을 강조한 스니펫. 호출자가 색인 내용과 같음을 이미 확인한 문서만 넘긴다.
     * 일치 부분을 찾지 못한 문서는 결과에서 빠진다.
     */
    fun snippets(sources: List<IssueSearchDocument>): Map<Long, IssueSearchSnippet>

    companion object { const val BATCH_SIZE = 200 }
}

/**
 * 이슈 전문 검색 색인. 쓰기는 큐 워커만 하고 요청은 마지막으로 게시된 색인을 읽는다.
 * 구현은 색인 형식과 질의 의미(분석기, 필드, 점수 정책)를 스스로 소유한다.
 * 접근 권한·존재 여부·메타 조건은 엔진이 아니라 호출한 서비스가 현재 DB로 판정한다.
 */
interface IssueSearchEngine {
    /** `yona.search.backend` 값. 응답 헤더 `X-Yona-Search-Backend`에 그대로 노출된다. */
    val name: String
    val status: IssueIndexStatus

    /** 준비되지 않았으면 null. 불러올 수 없으면 [java.io.IOException]을 던져 DB 검색으로 돌아가게 한다. */
    fun <T> withSearch(text: String, titleHead: String? = null, action: (IssueIndexSearch) -> T): T?

    /** DB 전체를 처음부터 읽어 색인을 새로 만든다. 끝까지 성공했을 때만 게시한다. */
    fun synchronize(batch: (Long) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit = {})

    /** 바뀐 ID만 갱신한다. [load]에 없는 ID는 삭제된 것으로 보고 색인에서 지운다. */
    fun update(ids: List<Long>, load: (List<Long>) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit = {})

    fun close()
}
