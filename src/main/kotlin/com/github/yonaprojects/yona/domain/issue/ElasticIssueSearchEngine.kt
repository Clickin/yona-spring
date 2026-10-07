package com.github.yonaprojects.yona.domain.issue

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Elasticsearch·OpenSearch REST API로 쓰는 외부 검색 엔진. 두 엔진은 같은 구현을 쓰고
 * point-in-time(PIT) API 경로와 응답 필드만 `yona.search.backend` 값에 따라 달라진다.
 *
 * 색인 이름 `yona.search.elasticsearch.index`는 alias다. 전체 동기화는 새 색인을 끝까지 채운 뒤
 * alias를 한 번에 바꾸므로 실패해도 이전 색인이 그대로 남는다.
 * 샤드·레플리카·refresh 주기 같은 클러스터 설정은 운영자가 정한다. 필드·분석기·질의는 이 클래스가 정하며,
 * 바꾸고 싶으면 `index-definition-file`로 색인 정의 전체를 교체하되 아래 이름을 유지해야 한다.
 *
 * - 분석기: `yona_korean`, `yona_identifier_index`, `yona_identifier_search`
 * - 필드: `issue_id`, `digest`, `titleHead`, `title`·`body`·`comments`와 각각의 `*_ident`
 */
@Component
@ConditionalOnElasticSearch
class ElasticIssueSearchEngine(
    @Value("\${yona.search.backend}") override val name: String,
    @Value("\${yona.search.elasticsearch.url:http://localhost:9200}") url: String,
    @Value("\${yona.search.elasticsearch.index:yona-issues}") private val index: String,
    @Value("\${yona.search.elasticsearch.username:}") username: String,
    @Value("\${yona.search.elasticsearch.password:}") password: String,
    @Value("\${yona.search.elasticsearch.api-key:}") apiKey: String,
    @Value("\${yona.search.elasticsearch.index-definition-file:}") definitionFile: String,
    @Value("\${yona.search.elasticsearch.request-timeout-millis:10000}") timeoutMillis: Long,
    @Value("\${yona.search.elasticsearch.pit-keep-alive:1m}") private val keepAlive: String
) : IssueSearchEngine {
    private val opensearch = name == SearchBackends.OPENSEARCH
    private val base = url.trimEnd('/')
    private val timeout = Duration.ofMillis(timeoutMillis)
    private val authorization = when {
        apiKey.isNotBlank() -> "ApiKey $apiKey"
        username.isNotBlank() -> "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        else -> null
    }
    private val definition: String = if (definitionFile.isBlank()) json.writeValueAsString(defaultDefinition())
        else Files.readString(Path.of(definitionFile)).also { json.readTree(it) }
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(name == SearchBackends.ELASTICSEARCH || opensearch) { "unsupported external backend: $name" }
        require(INDEX_NAME.matches(index)) { "yona.search.elasticsearch.index must be lowercase letters, digits, '.', '_' or '-'" }
        require(timeoutMillis > 0) { "yona.search.elasticsearch.request-timeout-millis must be positive" }
    }

    @Volatile final override var status = IssueIndexStatus()
        private set

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private class Reply(val status: Int, val body: String, val json: JsonNode)

    private fun send(method: String, path: String, body: String? = null, contentType: String = "application/json",
                     allow: Set<Int> = emptySet()): Reply {
        val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Accept", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        if (body != null) request.header("Content-Type", contentType)
        authorization?.let { request.header("Authorization", it) }
        val response = try {
            http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("$name $method $path interrupted", interrupted)
        }
        val code = response.statusCode()
        if (code !in 200..299 && code !in allow) {
            throw IOException("$name $method $path failed: HTTP $code ${response.body().take(500)}")
        }
        val parsed = try { json.readTree(response.body()) } catch (failure: JacksonException) {
            throw IOException("$name $method $path returned invalid JSON", failure)
        }
        return Reply(code, response.body(), parsed)
    }

    private fun post(path: String, body: Any) = send("POST", path, json.writeValueAsString(body))

    private fun encoded(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // ---- 질의 ---------------------------------------------------------------------------------------------

    private data class Token(val term: String, val start: Int, val end: Int)

    private fun analyze(text: String): List<Token> {
        if (text.isBlank()) return emptyList()
        val reply = post("/${encoded(index)}/_analyze", mapOf("analyzer" to "yona_korean", "text" to text))
        return reply.json.path("tokens").items().map { Token(it.path("token").asText(), it.path("start_offset").asInt(), it.path("end_offset").asInt()) }
    }

    private fun anyField(clause: (field: String, boost: Int) -> Map<String, Any>): Map<String, Any> =
        mapOf("bool" to mapOf("should" to FIELDS.map { (field, boost) -> clause(field, boost) }))

    /**
     * Lucene 구현과 같은 의미다. 따옴표 밖 텍스트는 한 문자열로 한국어 분석하고, 토큰마다 제목·본문·댓글 중
     * 하나에 있으면 되며 모든 토큰이 필요하다. 코드 이름 보조 일치는 점수 0으로 덧붙인다.
     */
    internal fun query(text: String): Map<String, Any> {
        if (text.isBlank()) return mapOf("match_all" to emptyMap<String, Any>())
        val required = mutableListOf<Map<String, Any>>()
        val fallback = mutableListOf<Map<String, Any>>()
        PHRASE.findAll(text).forEach { match ->
            val phrase = match.groupValues[1]
            if (analyze(phrase).isEmpty()) return@forEach
            val clause = anyField { field, boost -> mapOf("match_phrase" to mapOf(field to mapOf("query" to phrase, "boost" to boost))) }
            required += clause
            fallback += clause
        }
        val unquoted = PHRASE.replace(text, " ").replace('"', ' ')
        val words = IDENTIFIER.findAll(unquoted).toList()
        val terms = linkedSetOf<String>()
        val contextual = linkedSetOf<String>()
        var wordIndex = 0
        for (token in analyze(unquoted)) {
            terms.add(token.term)
            while (wordIndex < words.size && words[wordIndex].range.last < token.start) wordIndex++
            val word = words.getOrNull(wordIndex)
            if (word == null || token.start < word.range.first || token.end > word.range.last + 1) contextual.add(token.term)
        }
        fun term(value: String) = anyField { field, boost -> mapOf("term" to mapOf(field to mapOf("value" to value, "boost" to boost))) }
        terms.forEach { required += term(it) }
        val primary: Map<String, Any> = if (required.isEmpty()) mapOf("match_none" to emptyMap<String, Any>())
            else mapOf("bool" to mapOf("must" to required))
        if (words.isEmpty()) return primary
        contextual.forEach { fallback += term(it) }
        words.forEach { word ->
            // 여러 부분으로 나뉜 코드 이름은 한 필드에서 순서대로 인접해야 한다.
            fallback += anyField { field, boost -> mapOf("match_phrase" to mapOf("${field}_ident" to mapOf("query" to word.value, "boost" to boost))) }
        }
        val zeroScore = mapOf("constant_score" to mapOf(
            "filter" to mapOf("bool" to mapOf("must" to fallback, "must_not" to listOf(primary))), "boost" to 0))
        return mapOf("bool" to mapOf("should" to listOf(primary, zeroScore)))
    }

    private fun filtered(query: Map<String, Any>, titleHead: String?): Map<String, Any> =
        if (titleHead == null) query
        else mapOf("bool" to mapOf("must" to listOf(query), "filter" to listOf(mapOf("term" to mapOf("titleHead" to titleHead)))))

    // ---- 검색 ---------------------------------------------------------------------------------------------

    private class Pit(var id: String)

    private fun openPit(): Pit {
        val reply = if (opensearch) send("POST", "/${encoded(index)}/_search/point_in_time?keep_alive=${encoded(keepAlive)}")
            else send("POST", "/${encoded(index)}/_pit?keep_alive=${encoded(keepAlive)}")
        val id = reply.json.path(if (opensearch) "pit_id" else "id").asText()
        if (id.isBlank()) throw IOException("$name did not return a point-in-time id")
        return Pit(id)
    }

    private fun closePit(pit: Pit) {
        try {
            if (opensearch) send("DELETE", "/_search/point_in_time", json.writeValueAsString(mapOf("pit_id" to listOf(pit.id))), allow = setOf(404))
            else send("DELETE", "/_pit", json.writeValueAsString(mapOf("id" to pit.id)), allow = setOf(404))
        } catch (failure: IOException) {
            logger.debug("Unable to close search point-in-time; it expires after $keepAlive", failure)
        }
    }

    private fun search(pit: Pit, body: Map<String, Any?>): JsonNode {
        val reply = post("/_search", body + ("pit" to mapOf("id" to pit.id, "keep_alive" to keepAlive)))
        reply.json.path("pit_id").takeIf { it.isTextual }?.let { pit.id = it.asText() }
        return reply.json
    }

    private inner class Session(private val pit: Pit, private val query: Map<String, Any>, private val highlightQuery: Map<String, Any>) : IssueIndexSearch {
        private fun hits(response: JsonNode) = response.path("hits").path("hits")

        private fun hit(node: JsonNode): IssueIndexHit {
            val score = node.path("_score").takeIf { it.isNumber }?.asDouble()
            return IssueIndexHit(node.path("_id").asText().toLong(), node.path("_source").path("digest").asText(), score == 0.0)
        }

        override fun isEmpty(): Boolean =
            hits(search(pit, mapOf("size" to 1, "_source" to false, "track_total_hits" to false, "query" to query))).size() == 0

        override fun forEachBatch(consume: (List<IssueIndexHit>) -> Unit) {
            var after: JsonNode? = null
            do {
                val body = mutableMapOf<String, Any?>("size" to IssueIndexSearch.BATCH_SIZE, "_source" to listOf("digest"),
                    "track_scores" to true, "track_total_hits" to false, "query" to query, "sort" to SORT)
                if (after != null) body["search_after"] = after
                val page = hits(search(pit, body)).items()
                if (page.isNotEmpty()) consume(page.map(::hit))
                after = page.lastOrNull()?.path("sort")
            } while (after != null)
        }

        override fun matching(ids: List<Long>): Map<Long, IssueIndexHit> {
            require(ids.size <= IssueIndexSearch.BATCH_SIZE)
            if (ids.isEmpty()) return emptyMap()
            val body = mapOf("size" to ids.size, "_source" to listOf("digest"), "track_scores" to true, "track_total_hits" to false,
                "sort" to SORT, "query" to mapOf("bool" to mapOf("must" to listOf(query), "filter" to listOf(mapOf("terms" to mapOf("issue_id" to ids))))))
            return hits(search(pit, body)).items().map(::hit).associateBy { it.id }
        }

        override fun snippets(sources: List<IssueSearchDocument>): Map<Long, IssueSearchSnippet> {
            if (sources.isEmpty()) return emptyMap()
            require(sources.size <= IssueIndexSearch.BATCH_SIZE)
            val body = mapOf("size" to sources.size, "_source" to false, "track_total_hits" to false,
                "query" to mapOf("bool" to mapOf("must" to listOf(highlightQuery),
                    "filter" to listOf(mapOf("terms" to mapOf("issue_id" to sources.map { it.id }))))),
                "highlight" to mapOf("pre_tags" to listOf("<mark>"), "post_tags" to listOf("</mark>"), "encoder" to "html",
                    "fields" to linkedMapOf("title" to mapOf("number_of_fragments" to 0),
                        "body" to mapOf("number_of_fragments" to 1, "fragment_size" to 100),
                        "comments" to mapOf("number_of_fragments" to 1, "fragment_size" to 100))))
            // 스니펫은 보조 정보다. 강조에 실패해도 검색 결과 자체는 유지한다.
            val response = try { search(pit, body) } catch (failure: IOException) {
                logger.warn("Unable to highlight search results; returning them without snippets", failure)
                return emptyMap()
            }
            val bySource = sources.associateBy { it.id }
            return hits(response).items().mapNotNull { node ->
                val source = bySource[node.path("_id").asText().toLong()] ?: return@mapNotNull null
                val highlight = node.path("highlight")
                for (field in listOf("title", "body", "comments")) {
                    val fragment = highlight.path(field).items().firstOrNull()?.asText() ?: continue
                    val commentId = if (field != "comments") null else {
                        val plain = HtmlUtils.htmlUnescape(fragment.replace("<mark>", "").replace("</mark>", ""))
                        source.comments.firstOrNull { plain in it.second }?.first
                    }
                    return@mapNotNull source.id to IssueSearchSnippet(fragment, commentId)
                }
                null
            }.toMap()
        }
    }

    override fun <T> withSearch(text: String, titleHead: String?, action: (IssueIndexSearch) -> T): T? {
        if (!status.ready) return null
        val plain = query(text)
        val pit = openPit()
        try {
            return action(Session(pit, filtered(plain, titleHead), plain))
        } finally { closePit(pit) }
    }

    // ---- 색인 ---------------------------------------------------------------------------------------------

    private fun document(source: IssueSearchDocument): Map<String, Any> = mapOf(
        "issue_id" to source.id, "digest" to source.digest(), "title" to source.title, "body" to source.body,
        "comments" to source.comments.map { it.second }, "titleHead" to TitleHeads.extract(source.title))

    private fun bulk(target: String, lines: List<Pair<Map<String, Any>, Map<String, Any>?>>, refresh: String?) {
        if (lines.isEmpty()) return
        val chunk = StringBuilder()
        var bytes = 0
        fun flush() {
            if (chunk.isEmpty()) return
            val query = if (refresh == null) "" else "?refresh=$refresh"
            val reply = send("POST", "/_bulk$query", chunk.toString(), "application/x-ndjson")
            if (reply.json.path("errors").asBoolean(false)) {
                val failure = reply.json.path("items").items().map { it.properties().first().value }.firstOrNull { it.has("error") }
                throw IOException("$name bulk write failed: ${failure?.path("error")?.toString()?.take(500)}")
            }
            chunk.setLength(0)
            bytes = 0
        }
        for ((action, document) in lines) {
            val actionLine = json.writeValueAsString(action)
            val documentLine = document?.let { json.writeValueAsString(it) }
            chunk.append(actionLine).append('\n')
            if (documentLine != null) chunk.append(documentLine).append('\n')
            bytes += actionLine.length + (documentLine?.length ?: 0)
            if (bytes >= BULK_BYTES) flush()
        }
        flush()
    }

    private fun indexAction(target: String, id: Long) = mapOf("index" to mapOf("_index" to target, "_id" to id.toString()))

    private fun createIndex(target: String) {
        try {
            send("PUT", "/${encoded(target)}", definition)
        } catch (failure: IOException) {
            throw IOException("${failure.message} (Korean analysis needs the analysis-nori plugin installed on every node)", failure)
        }
    }

    private fun aliasedIndices(): List<String> {
        val reply = send("GET", "/_alias/${encoded(index)}", allow = setOf(404))
        if (reply.status == 404) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (json.readValue(reply.body, Map::class.java) as Map<String, Any?>).keys.toList()
    }

    private fun deleteIndex(target: String) {
        try { send("DELETE", "/${encoded(target)}", allow = setOf(404)) } catch (failure: IOException) {
            logger.warn("Unable to delete search index {}", target, failure)
        }
    }

    @Synchronized
    override fun synchronize(batch: (Long) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit) {
        status = status.copy(running = true, scanned = 0, error = null)
        val target = "$index-${System.currentTimeMillis()}"
        try {
            createIndex(target)
            var cursor = 0L
            var scanned = 0L
            while (true) {
                checkpoint(scanned)
                val documents = batch(cursor)
                if (documents.isEmpty()) break
                bulk(target, documents.map { indexAction(target, it.id) to document(it) }, null)
                cursor = documents.last().id
                scanned += documents.size
                status = status.copy(scanned = scanned)
            }
            checkpoint(scanned)
            send("POST", "/${encoded(target)}/_refresh")
            val previous = aliasedIndices()
            // 한 번의 요청으로 alias를 옮기므로 검색은 이전 색인 또는 새 색인 중 하나만 본다.
            post("/_aliases", mapOf("actions" to previous.map { mapOf("remove" to mapOf("index" to it, "alias" to index)) } +
                listOf(mapOf("add" to mapOf("index" to target, "alias" to index)))))
            previous.forEach(::deleteIndex)
            status = IssueIndexStatus(true, count(), scanned, false, Instant.now())
        } catch (failure: Exception) {
            status = status.copy(ready = false, running = false, error = "INDEX_SYNC_FAILED")
            // 끝까지 채우지 못한 색인은 게시하지 않고 지운다.
            deleteIndex(target)
            throw failure
        }
    }

    @Synchronized
    override fun update(ids: List<Long>, load: (List<Long>) -> List<IssueSearchDocument>, checkpoint: (Long) -> Unit) {
        status = status.copy(running = true, scanned = 0, error = null)
        try {
            check(status.lastSuccess != null || aliasedIndices().isNotEmpty()) { "Initial index is not built" }
            var scanned = 0L
            for (batch in ids.chunked(IssueIndexSearch.BATCH_SIZE)) {
                checkpoint(scanned)
                val documents = load(batch).associateBy { it.id }
                bulk(index, batch.map { id ->
                    val source = documents[id]
                    if (source == null) mapOf<String, Any>("delete" to mapOf("_index" to index, "_id" to id.toString())) to null
                    else indexAction(index, id) to document(source)
                }, "wait_for")
                scanned += batch.size
                status = status.copy(scanned = scanned)
            }
            checkpoint(scanned)
            status = IssueIndexStatus(true, count(), scanned, false, Instant.now())
        } catch (failure: Exception) {
            status = status.copy(ready = false, running = false, error = "INDEX_SYNC_FAILED")
            throw failure
        }
    }

    private fun count(): Long = send("GET", "/${encoded(index)}/_count").json.path("count").asLong()

    override fun close() {
        status = status.copy(ready = false)
        http.close()
    }

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
        val INDEX_NAME = Regex("[a-z0-9][a-z0-9._-]*")
        val FIELDS = listOf("title" to 3, "body" to 1, "comments" to 1)
        val SORT = listOf(mapOf("_score" to "desc"), mapOf("issue_id" to "asc"))
        const val BULK_BYTES = 8 * 1024 * 1024
        const val VERSION = "3"
        val PHRASE = Regex("\"([^\"]*)\"")
        val IDENTIFIER = Regex("""[A-Za-z0-9]+(?:[._/-][A-Za-z0-9]+)*""")

        /** 한국어 분석(Nori)과 코드 이름 분석은 Lucene 구현과 같은 필터 구성이다. */
        fun defaultDefinition(): Map<String, Any> {
            val korean = mapOf("type" to "text", "analyzer" to "yona_korean", "position_increment_gap" to 100)
            val identifier = mapOf("type" to "text", "analyzer" to "yona_identifier_index",
                "search_analyzer" to "yona_identifier_search", "position_increment_gap" to 100)
            return mapOf(
                "settings" to mapOf("analysis" to mapOf(
                    "char_filter" to mapOf("yona_acronym_boundary" to mapOf(
                        "type" to "pattern_replace", "pattern" to "([A-Z])(?=[A-Z][a-z])", "replacement" to "\$1 ")),
                    "tokenizer" to mapOf("yona_identifier_tokenizer" to mapOf("type" to "pattern", "pattern" to "[^A-Za-z0-9._/-]+")),
                    "filter" to mapOf("yona_word_delimiter" to mapOf(
                        "type" to "word_delimiter_graph", "generate_word_parts" to true, "generate_number_parts" to true,
                        "split_on_case_change" to true, "split_on_numerics" to true, "preserve_original" to true,
                        "stem_english_possessive" to false)),
                    "analyzer" to mapOf(
                        "yona_korean" to mapOf("type" to "nori"),
                        "yona_identifier_index" to mapOf("type" to "custom", "char_filter" to listOf("yona_acronym_boundary"),
                            "tokenizer" to "yona_identifier_tokenizer", "filter" to listOf("yona_word_delimiter", "lowercase", "flatten_graph")),
                        "yona_identifier_search" to mapOf("type" to "custom", "char_filter" to listOf("yona_acronym_boundary"),
                            "tokenizer" to "yona_identifier_tokenizer", "filter" to listOf("yona_word_delimiter", "lowercase"))))),
                "mappings" to mapOf(
                    "dynamic" to "strict",
                    "_meta" to mapOf("yona_issue_search_version" to VERSION),
                    "properties" to mapOf(
                        "issue_id" to mapOf("type" to "long"),
                        "digest" to mapOf("type" to "keyword", "index" to false, "doc_values" to false),
                        "titleHead" to mapOf("type" to "keyword"),
                        "title" to korean + ("copy_to" to listOf("title_ident")),
                        "body" to korean + ("copy_to" to listOf("body_ident")),
                        "comments" to korean + ("copy_to" to listOf("comments_ident")),
                        "title_ident" to identifier, "body_ident" to identifier, "comments_ident" to identifier)))
        }
    }
}
