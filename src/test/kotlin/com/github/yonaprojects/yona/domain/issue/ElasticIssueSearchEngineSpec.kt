package com.github.yonaprojects.yona.domain.issue

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** 실제 엔진 없이 REST 요청의 순서·경로·본문을 검증한다. 검색 의미는 [IssueSearchEngineConformanceSpec]이 확인한다. */
class ElasticIssueSearchEngineSpec : DescribeSpec({
    val json = JsonMapper.builder().build()

    data class Call(val method: String, val path: String, val body: String, val authorization: String?)

    class Stub(private val respond: (Call) -> Pair<Int, String>? = { null }) : AutoCloseable {
        val calls = CopyOnWriteArrayList<Call>()
        private var indexed = 0
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val call = Call(exchange.requestMethod, exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
                    exchange.requestBody.readBytes().toString(Charsets.UTF_8), exchange.requestHeaders.getFirst("Authorization"))
                calls.add(call)
                val (status, body) = respond(call) ?: default(call)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"

        private fun default(call: Call): Pair<Int, String> = when {
            call.method == "POST" && call.path.startsWith("/_bulk") -> {
                indexed += call.body.lineSequence().count { it.startsWith("{\"index\"") }
                200 to """{"errors":false,"items":[]}"""
            }
            call.method == "GET" && call.path.startsWith("/_alias/") -> 404 to """{"error":"missing","status":404}"""
            call.method == "GET" && call.path.endsWith("/_count") -> 200 to """{"count":$indexed}"""
            call.method == "POST" && call.path.contains("/_pit?") -> 200 to """{"id":"pit-1"}"""
            call.method == "POST" && call.path.contains("/_search/point_in_time?") -> 200 to """{"pit_id":"pit-1"}"""
            call.method == "POST" && call.path == "/_search" -> 200 to """{"pit_id":"pit-2","hits":{"hits":[]}}"""
            else -> 200 to """{"acknowledged":true}"""
        }

        override fun close() = server.stop(0)
    }

    fun engine(stub: Stub, backend: String = SearchBackends.ELASTICSEARCH, apiKey: String = "", username: String = "",
               password: String = "") =
        ElasticIssueSearchEngine(backend, stub.url, "yona-issues", username, password, apiKey, "", 5_000, "1m")

    val documents = listOf(
        IssueSearchDocument(1, "[Bug] 로그인 오류", "본문", listOf(10L to "댓글")),
        IssueSearchDocument(2, "기록", "", emptyList()))

    fun IssueSearchEngine.build(source: List<IssueSearchDocument> = documents) =
        synchronize({ after -> source.filter { it.id > after } })

    describe("전체 동기화") {
        it("새 색인을 끝까지 채운 뒤 alias를 한 번에 옮기고 이전 색인을 지운다") {
            Stub { call ->
                if (call.method == "GET" && call.path.startsWith("/_alias/")) 200 to """{"yona-issues-old":{"aliases":{}}}""" else null
            }.use { stub ->
                val engine = engine(stub)
                engine.build()
                val sequence = stub.calls.map { it.method to it.path.substringBefore('?').replace(Regex("-\\d{10,}"), "-TS") }
                sequence shouldBe listOf(
                    "PUT" to "/yona-issues-TS", "POST" to "/_bulk", "POST" to "/yona-issues-TS/_refresh",
                    "GET" to "/_alias/yona-issues", "POST" to "/_aliases", "DELETE" to "/yona-issues-old", "GET" to "/yona-issues/_count")

                val definition = json.readTree(stub.calls.first().body)
                definition.path("settings").path("analysis").path("analyzer").has("yona_korean") shouldBe true
                definition.path("mappings").path("dynamic").asText() shouldBe "strict"
                definition.path("mappings").path("properties").path("comments").path("position_increment_gap").asInt() shouldBe 100

                val bulk = stub.calls[1].body.lines().filter { it.isNotBlank() }
                bulk.size shouldBe 4
                bulk[0] shouldContain "\"_id\":\"1\""
                val first = json.readTree(bulk[1])
                first.path("title").asText() shouldBe "[Bug] 로그인 오류"
                first.path("titleHead")[0].asText() shouldBe "Bug"
                first.path("comments")[0].asText() shouldBe "댓글"
                first.path("digest").asText() shouldBe documents[0].digest()

                val actions = json.readTree(stub.calls[4].body).path("actions")
                actions[0].path("remove").path("index").asText() shouldBe "yona-issues-old"
                actions[1].path("add").path("alias").asText() shouldBe "yona-issues"
                actions[1].path("add").path("index").asText() shouldStartWith "yona-issues-"
                engine.status.ready shouldBe true
                engine.status.indexed shouldBe 2
                engine.status.scanned shouldBe 2
                engine.close()
            }
        }

        it("쓰기가 실패하면 alias를 건드리지 않고 만든 색인을 지운다") {
            Stub { call -> if (call.path.startsWith("/_bulk")) 500 to """{"error":"boom"}""" else null }.use { stub ->
                val engine = engine(stub)
                shouldThrow<IOException> { engine.build() }.message shouldContain "HTTP 500"
                stub.calls.none { it.path.startsWith("/_aliases") } shouldBe true
                stub.calls.last().method shouldBe "DELETE"
                stub.calls.last().path shouldStartWith "/yona-issues-"
                engine.status.ready shouldBe false
                engine.status.error shouldBe "INDEX_SYNC_FAILED"
                engine.close()
            }
        }

        it("bulk 응답이 일부 항목의 실패를 알리면 게시하지 않는다") {
            Stub { call ->
                if (call.path.startsWith("/_bulk")) 200 to """{"errors":true,"items":[{"index":{"status":400,"error":{"type":"mapper_parsing_exception"}}}]}""" else null
            }.use { stub ->
                val engine = engine(stub)
                shouldThrow<IOException> { engine.build() }.message shouldContain "mapper_parsing_exception"
                stub.calls.none { it.path.startsWith("/_aliases") } shouldBe true
                engine.close()
            }
        }

        it("색인을 만들 수 없으면 Nori 플러그인 설치를 안내한다") {
            Stub { call -> if (call.method == "PUT") 400 to """{"error":{"reason":"Unknown analyzer type [nori]"}}""" else null }.use { stub ->
                val engine = engine(stub)
                shouldThrow<IOException> { engine.build() }.message shouldContain "analysis-nori"
                engine.close()
            }
        }

        it("엔진에 연결할 수 없으면 IOException을 던져 DB 검색으로 돌아갈 수 있게 한다") {
            val stub = Stub()
            val engine = engine(stub)
            stub.close()
            shouldThrow<IOException> { engine.build() }
            engine.status.ready shouldBe false
        }
    }

    describe("증분 갱신") {
        it("바뀐 문서는 index로, 사라진 문서는 delete로 보내고 refresh를 기다린다") {
            Stub().use { stub ->
                val engine = engine(stub)
                engine.build()
                stub.calls.clear()
                engine.update(listOf(1L, 2L), { ids -> documents.filter { it.id in ids && it.id == 1L } })
                val bulk = stub.calls.first { it.path.startsWith("/_bulk") }
                bulk.path shouldBe "/_bulk?refresh=wait_for"
                val lines = bulk.body.lines().filter { it.isNotBlank() }
                lines.size shouldBe 3
                lines[0] shouldContain "\"index\""
                lines[2] shouldBe """{"delete":{"_index":"yona-issues","_id":"2"}}"""
                engine.close()
            }
        }
    }

    describe("검색") {
        it("준비되기 전에는 엔진을 호출하지 않는다") {
            Stub().use { stub ->
                val engine = engine(stub)
                engine.withSearch("로그인") { error("not ready") } shouldBe null
                stub.calls.size shouldBe 0
                engine.close()
            }
        }

        it("Elasticsearch는 _pit API로 시점을 고정하고 검색이 끝나면 닫는다") {
            Stub().use { stub ->
                val engine = engine(stub, SearchBackends.ELASTICSEARCH)
                engine.build()
                stub.calls.clear()
                engine.withSearch("", "Bug") { it.isEmpty() } shouldBe true
                stub.calls.map { it.method to it.path } shouldBe listOf(
                    "POST" to "/yona-issues/_pit?keep_alive=1m", "POST" to "/_search", "DELETE" to "/_pit")
                val search = json.readTree(stub.calls[1].body)
                search.path("pit").path("id").asText() shouldBe "pit-1"
                search.path("query").path("bool").path("filter")[0].path("term").path("titleHead").asText() shouldBe "Bug"
                // 응답이 알려 준 최신 pit id로 닫는다.
                json.readTree(stub.calls[2].body).path("id").asText() shouldBe "pit-2"
                engine.close()
            }
        }

        it("OpenSearch는 _search/point_in_time API와 pit_id 필드를 쓴다") {
            Stub().use { stub ->
                val engine = engine(stub, SearchBackends.OPENSEARCH)
                engine.build()
                stub.calls.clear()
                engine.withSearch("") { it.isEmpty() } shouldBe true
                stub.calls.map { it.method to it.path } shouldBe listOf(
                    "POST" to "/yona-issues/_search/point_in_time?keep_alive=1m", "POST" to "/_search", "DELETE" to "/_search/point_in_time")
                json.readTree(stub.calls[2].body).path("pit_id")[0].asText() shouldBe "pit-2"
                engine.close()
            }
        }

        it("검색 요청이 실패해도 시점을 닫고 IOException을 그대로 전달한다") {
            Stub { call -> if (call.method == "POST" && call.path == "/_search") 503 to """{"error":"unavailable"}""" else null }.use { stub ->
                val engine = engine(stub)
                engine.build()
                stub.calls.clear()
                shouldThrow<IOException> { engine.withSearch("") { it.isEmpty() } }.message shouldContain "HTTP 503"
                stub.calls.last().method shouldBe "DELETE"
                engine.close()
            }
        }

        it("결과를 BATCH_SIZE씩 search_after로 읽는다") {
            var page = 0
            val hits = { from: Int, count: Int ->
                (from until from + count).joinToString(",") { """{"_id":"$it","_score":1.0,"_source":{"digest":"d$it"},"sort":[1.0,$it]}""" }
            }
            Stub { call ->
                if (call.method == "POST" && call.path == "/_search") {
                    val body = when (page++) {
                        0 -> hits(1, 200)
                        1 -> hits(201, 5)
                        else -> ""
                    }
                    200 to """{"pit_id":"pit-1","hits":{"hits":[$body]}}"""
                } else null
            }.use { stub ->
                val engine = engine(stub)
                engine.build()
                stub.calls.clear()
                val sizes = mutableListOf<Int>()
                engine.withSearch("로그인") { search -> search.forEachBatch { sizes.add(it.size) } }
                sizes shouldBe listOf(200, 5)
                val searches = stub.calls.filter { it.path == "/_search" }.map { json.readTree(it.body) }
                searches[0].has("search_after") shouldBe false
                searches[1].path("search_after")[1].asInt() shouldBe 200
                searches[1].path("size").asInt() shouldBe 200
                engine.close()
            }
        }
    }

    describe("연결 설정") {
        it("API 키와 기본 인증 헤더를 보낸다") {
            Stub().use { stub ->
                engine(stub, apiKey = "abc123").also { it.build(); it.close() }
                stub.calls.all { it.authorization == "ApiKey abc123" } shouldBe true
            }
            Stub().use { stub ->
                engine(stub, username = "yona", password = "secret").also { it.build(); it.close() }
                stub.calls.all { it.authorization == "Basic eW9uYTpzZWNyZXQ=" } shouldBe true
            }
        }

        it("지원하지 않는 backend나 잘못된 색인 이름을 거절한다") {
            Stub().use { stub ->
                shouldThrow<IllegalArgumentException> { engine(stub, "quickwit") }
                shouldThrow<IllegalArgumentException> {
                    ElasticIssueSearchEngine("opensearch", stub.url, "Upper_Case", "", "", "", "", 5_000, "1m")
                }
            }
        }
    }
})
