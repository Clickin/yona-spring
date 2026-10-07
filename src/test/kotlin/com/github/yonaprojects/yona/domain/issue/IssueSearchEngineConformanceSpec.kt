package com.github.yonaprojects.yona.domain.issue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 모든 [IssueSearchEngine] 구현이 같은 검색 동작을 내는지 확인한다.
 * Lucene은 항상 실행한다. 외부 엔진은 Docker 컨테이너가 필요해서 환경변수로 켠 것만 실행한다.
 *
 *     YONA_IT_SEARCH_ENGINES=opensearch,elasticsearch ./gradlew test -Dyona.it.db=h2 --tests '*IssueSearchEngineConformanceSpec'
 *
 * 컨테이너는 시작할 때 `analysis-nori` 플러그인을 설치하므로 네트워크가 필요하다.
 */
class IssueSearchEngineConformanceSpec : DescribeSpec({
    class Target(val label: String, val create: () -> IssueSearchEngine, val cleanup: () -> Unit = {})

    val enabled = System.getenv("YONA_IT_SEARCH_ENGINES").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val sequence = AtomicInteger()

    fun external(backend: String) = ElasticIssueSearchEngine(
        backend, SearchEngineContainers.url(backend), "yona-it-${sequence.incrementAndGet()}-${System.nanoTime()}",
        "", "", "", "", 30_000, "1m")

    val targets = buildList {
        val lucene = mutableListOf<java.nio.file.Path>()
        add(Target("lucene", { IssueSearchIndex(Files.createTempDirectory("yona-conformance-").also { lucene.add(it) }.toString()) },
            { lucene.forEach { it.toFile().deleteRecursively() } }))
        if ("opensearch" in enabled) add(Target("opensearch", { external(SearchBackends.OPENSEARCH) }))
        if ("elasticsearch" in enabled) add(Target("elasticsearch", { external(SearchBackends.ELASTICSEARCH) }))
    }

    fun IssueSearchEngine.search(text: String, titleHead: String? = null): List<IssueIndexHit> =
        checkNotNull(withSearch(text, titleHead) { search -> buildList { search.forEachBatch { addAll(it) } } })

    fun IssueSearchEngine.build(documents: List<IssueSearchDocument>) =
        synchronize({ after -> documents.filter { it.id > after }.take(IssueIndexSearch.BATCH_SIZE) })

    fun with(target: Target, block: (IssueSearchEngine) -> Unit) {
        val engine = target.create()
        try { block(engine) } finally { engine.close(); target.cleanup() }
    }

    afterSpec { if (enabled.isNotEmpty()) SearchEngineContainers.stopAll() }

    targets.forEach { target ->
        describe(target.label) {
            it("동기화가 끝나기 전에는 검색하지 않는다") {
                with(target) { engine ->
                    engine.status.ready shouldBe false
                    engine.withSearch("로그인") { error("not ready") } shouldBe null
                }
            }

            it("검색어를 한 문자열로 분석해 문맥이 달라지는 단어도 찾고 따옴표 구문과 불용어를 처리한다") {
                with(target) { engine ->
                    engine.build(listOf(
                        IssueSearchDocument(1, "[Bug][UI] 길이 제한 오류", "", emptyList()),
                        IssueSearchDocument(2, "화면 변경", "", listOf(10L to "교수님의 요청으로 변경합니다")),
                        IssueSearchDocument(3, "기록", "로그인 처리 중 인증 오류가 발생", emptyList())))
                    engine.search("길이 제한").map { it.id } shouldBe listOf(1L)
                    engine.search("교수님의 요청으로").map { it.id } shouldBe listOf(2L)
                    engine.search("\"길이 제한\" 오류").map { it.id } shouldBe listOf(1L)
                    engine.search("요청으로 길이").map { it.id } shouldBe emptyList()
                    engine.search("로그인 오류").map { it.id } shouldBe listOf(3L)
                    engine.search("의").map { it.id } shouldBe emptyList()
                }
            }

            it("제목에 일치한 문서를 본문에만 일치한 문서보다 앞에 둔다") {
                with(target) { engine ->
                    engine.build(listOf(
                        IssueSearchDocument(1, "기록", "배포 점검 결과", emptyList()),
                        IssueSearchDocument(2, "배포 점검", "", emptyList())))
                    engine.search("배포 점검").map { it.id } shouldBe listOf(2L, 1L)
                }
            }

            it("코드 이름의 부분을 점수 0 보조 결과로 Nori 결과 뒤에 붙인다") {
                with(target) { engine ->
                    engine.build(listOf(
                        IssueSearchDocument(1, "기록", "NullPointerException 발생", emptyList()),
                        IssueSearchDocument(2, "pointer exception 정리", "", emptyList()),
                        IssueSearchDocument(3, "기록", "refreshToken 만료", emptyList()),
                        IssueSearchDocument(4, "기록", "customer_id 누락", emptyList()),
                        IssueSearchDocument(5, "기록", "", listOf(10L to "HTTPResponse 처리")),
                        IssueSearchDocument(6, "기록", "api/v2/cache 경로", emptyList())))
                    // 띄어 쓴 `pointer exception`도 같은 코드 이름의 부분이므로 보조 결과로 찾는다.
                    engine.search("PointerException").map { it.id to it.auxiliaryOnly }.toSet() shouldBe setOf(1L to true, 2L to true)
                    engine.search("pointer exception").map { it.id to it.auxiliaryOnly }
                        .shouldContainExactly(listOf(2L to false, 1L to true))
                    engine.search("Token").map { it.id } shouldBe listOf(3L)
                    engine.search("id").map { it.id } shouldBe listOf(4L)
                    engine.search("Response").map { it.id } shouldBe listOf(5L)
                    engine.search("v2").map { it.id } shouldBe listOf(6L)
                    engine.search("\"PointerException\"").map { it.id } shouldBe emptyList()
                }
            }

            it("제목 머리말로 거르고 빈 검색어는 머리말에 일치한 모든 문서를 돌려준다") {
                with(target) { engine ->
                    engine.build(listOf(
                        IssueSearchDocument(1, "[Bug][UI] 로그인 오류", "", emptyList()),
                        IssueSearchDocument(2, "[Bug] 로그인 오류", "", emptyList()),
                        IssueSearchDocument(3, "로그인 [UI] 오류", "", emptyList())))
                    engine.search("로그인 오류", "UI").map { it.id } shouldBe listOf(1L)
                    engine.search("", "Bug").map { it.id }.sorted() shouldBe listOf(1L, 2L)
                }
            }

            it("결과를 제한된 배치로 읽고 요청 중 갱신에도 같은 시점을 유지한다") {
                with(target) { engine ->
                    engine.build((1L..405L).map { IssueSearchDocument(it, "batchneedle", "", emptyList()) })
                    engine.withSearch("batchneedle") { search ->
                        val sizes = mutableListOf<Int>()
                        search.forEachBatch { hits -> sizes.add(hits.size) }
                        sizes shouldBe listOf(200, 200, 5)
                        search.matching(listOf(1L, 405L, 999L)).keys shouldBe setOf(1L, 405L)
                        engine.update(listOf(405L), { emptyList() })
                        search.matching(listOf(405L)).keys shouldBe setOf(405L)
                    }
                    engine.withSearch("batchneedle") { it.matching(listOf(405L)) } shouldBe emptyMap()
                    engine.status.indexed shouldBe 404
                }
            }

            it("바뀐 문서만 갱신하고 사라진 문서는 지운다") {
                with(target) { engine ->
                    engine.build(listOf(
                        IssueSearchDocument(1, "예전 제목", "", emptyList()),
                        IssueSearchDocument(2, "삭제될 이슈", "", emptyList())))
                    engine.update(listOf(1L, 2L), { ids ->
                        ids.filter { it == 1L }.map { IssueSearchDocument(it, "새 제목", "", emptyList()) }
                    })
                    engine.search("예전").map { it.id } shouldBe emptyList()
                    engine.search("제목").map { it.id } shouldBe listOf(1L)
                    engine.search("삭제될").map { it.id } shouldBe emptyList()
                    engine.status.scanned shouldBe 2
                    engine.status.indexed shouldBe 1
                }
            }

            it("동기화가 중간에 실패하면 게시하지 않고 다시 실행하면 복구한다") {
                with(target) { engine ->
                    val documents = listOf(IssueSearchDocument(1, "복구 확인", "", emptyList()))
                    engine.build(documents)
                    shouldThrow<IllegalStateException> {
                        engine.synchronize({ after -> if (after == 0L) documents else error("boom") })
                    }
                    engine.status.ready shouldBe false
                    engine.status.error shouldBe "INDEX_SYNC_FAILED"
                    engine.build(documents)
                    engine.status.ready shouldBe true
                    engine.search("복구").map { it.id } shouldBe listOf(1L)
                }
            }

            it("현재 내용에서 일치한 곳을 이스케이프해 강조하고 댓글에서 일치하면 댓글 ID를 알려준다") {
                with(target) { engine ->
                    val title = IssueSearchDocument(1, "로그인 오류 보고", "", emptyList())
                    val comment = IssueSearchDocument(2, "기록", "", listOf(10L to "무관한 댓글", 11L to "<script>인증 오류</script> 확인"))
                    val auxiliary = IssueSearchDocument(3, "기록", "NullPointerException 발생", emptyList())
                    engine.build(listOf(title, comment, auxiliary))
                    engine.withSearch("오류") { search ->
                        val snippets = search.snippets(listOf(title, comment))
                        snippets.getValue(1L).html shouldContain "<mark>"
                        snippets.getValue(1L).commentId shouldBe null
                        val inComment = snippets.getValue(2L)
                        inComment.commentId shouldBe 11L
                        inComment.html shouldContain "<mark>"
                        inComment.html shouldNotContain "<script>"
                    }
                    engine.withSearch("PointerException") { search ->
                        search.snippets(listOf(auxiliary)) shouldBe emptyMap()
                    }
                }
            }
        }
    }
})
