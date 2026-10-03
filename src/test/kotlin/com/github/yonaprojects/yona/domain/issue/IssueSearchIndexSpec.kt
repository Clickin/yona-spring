package com.github.yonaprojects.yona.domain.issue

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files

// Small fixtures collect results only in tests; production requests use the bounded reader API.
internal fun IssueSearchIndex.search(text: String, titleHead: String? = null): List<IssueIndexHit> =
    checkNotNull(withSearch(text, titleHead) { search -> buildList { search.forEachBatch { addAll(it) } } })

class IssueSearchIndexSpec : DescribeSpec({
    it("모든 결과를 제한된 배치로 읽고 게시 중에도 같은 reader를 유지한다") {
        val path = Files.createTempDirectory("yona-search-batches-")
        val index = IssueSearchIndex(path.toString())
        val documents = (1L..405L).map { IssueSearchDocument(it, "batchneedle", "", emptyList()) }
        try {
            index.synchronize({ after -> documents.filter { it.id > after }.take(200) })
            index.withSearch("batchneedle") { search ->
                val sizes = mutableListOf<Int>()
                var count = 0
                search.forEachBatch { hits -> sizes.add(hits.size); count += hits.size }
                sizes shouldBe listOf(200, 200, 5)
                count shouldBe 405
                search.matching(listOf(1L, 405L, 999L)).keys shouldBe setOf(1L, 405L)
                index.update(listOf(405L), { emptyList() })
                search.matching(listOf(405L)).keys shouldBe setOf(405L)
            }
            index.withSearch("batchneedle") { it.matching(listOf(405L)) } shouldBe emptyMap()
        } finally {
            index.close()
            path.toFile().deleteRecursively()
        }
    }

    it("전체 디스크 색인을 재사용하고 실패한 갱신을 버리며 삭제 후 재시도로 문서를 되살리지 않는다") {
        val path = Files.createTempDirectory("yona-lucene-")
        var documents = listOf(
            IssueSearchDocument(1, "[Bug][UI] 로그인 오류", "", emptyList()),
            IssueSearchDocument(2, "기록", "로그인 처리 중 오류", listOf(10L to "<script>인증 오류</script>"))
        )
        val index = IssueSearchIndex(path.toString())
        fun batch(after: Long) = documents.filter { it.id > after }.take(1)
        try {
            index.synchronize(::batch)
            index.status.indexed shouldBe 2
            index.search("로그인 오류").map { it.id }.toSet() shouldBe setOf(1L, 2L)
            index.search("로그인", "UI").map { it.id } shouldBe listOf(1L)
            shouldThrow<IllegalStateException> {
                index.synchronize({ after ->
                    if (after != 0L) error("simulated interruption")
                    listOf(documents.first().copy(title = "삭제 예정"))
                })
            }
            index.status.ready shouldBe false
            FSDirectory.open(path).use { dir -> DirectoryReader.open(dir).use { it.numDocs() shouldBe 2 } }
            documents = listOf(documents.last())
            index.synchronize(::batch)
            index.search("로그인 오류").map { it.id } shouldBe listOf(2L)
            index.synchronize(::batch)
            index.status.indexed shouldBe 1
        } finally { index.close() }
        val reopened = IssueSearchIndex(path.toString())
        try {
            reopened.status.ready shouldBe false // Verify against current DB before enabling after restart.
            reopened.synchronize(::batch)
            reopened.search("로그인 오류").map { it.id } shouldBe listOf(2L)
        } finally {
            reopened.close()
            path.toFile().deleteRecursively()
        }
    }

    it("검색어를 본문과 같은 문맥으로 분석해 원문 그대로의 단어를 찾는다") {
        val path = Files.createTempDirectory("yona-lucene-")
        val documents = listOf(
            IssueSearchDocument(1, "입력 검증", "필드 길이 제한을 확인합니다", emptyList()),
            IssueSearchDocument(2, "화면 변경", "", listOf(10L to "교수님의 요청으로 변경합니다"))
        )
        val index = IssueSearchIndex(path.toString())
        try {
            index.synchronize({ after -> documents.filter { it.id > after } })
            // Word-by-word analysis turned these into `길이` and `교수 님의`, which the indexed text never contains.
            index.search("길이 제한").map { it.id } shouldBe listOf(1L)
            index.search("교수님의 요청으로").map { it.id } shouldBe listOf(2L)
            index.search("\"길이 제한\" 확인").map { it.id } shouldBe listOf(1L)
            index.search("요청으로 길이").map { it.id } shouldBe emptyList()
            index.search("의").map { it.id } shouldBe emptyList() // Only stop tags: no terms, no match.
        } finally {
            index.close()
            path.toFile().deleteRecursively()
        }
    }

    it("식별자의 원형과 camel Pascal snake kebab dot slash 조각을 모든 검색 필드에서 찾는다") {
        val path = Files.createTempDirectory("yona-lucene-")
        val examples = listOf(
            "SocketTimeoutException" to listOf("socket", "timeout", "exception"),
            "refreshToken" to listOf("refresh", "token"),
            "customer_id" to listOf("customer", "id"),
            "request-id" to listOf("request", "id"),
            "org.example.Widget" to listOf("org", "example", "widget"),
            "api/v2/cache" to listOf("api", "v", "2", "cache")
        )
        val documents = examples.mapIndexed { n, (word, _) ->
            when (n % 3) {
                0 -> IssueSearchDocument(n + 1L, word, "", emptyList())
                1 -> IssueSearchDocument(n + 1L, "기록", word, emptyList())
                else -> IssueSearchDocument(n + 1L, "기록", "", listOf(10L to word))
            }
        }
        val index = IssueSearchIndex(path.toString())
        try {
            index.synchronize({ after -> documents.filter { it.id > after } })
            examples.forEachIndexed { n, (word, parts) ->
                (parts + word).forEach { query ->
                    index.search(query).any { it.id == n + 1L } shouldBe true
                }
            }
            index.search("timeout").single().auxiliaryOnly shouldBe true
            index.search("SocketTimeoutException").single().auxiliaryOnly shouldBe false
        } finally {
            index.close()
            path.toFile().deleteRecursively()
        }
    }

    it("복합 식별자는 같은 필드의 인접한 순서로 찾고 따옴표와 댓글 경계를 유지한다") {
        val path = Files.createTempDirectory("yona-lucene-")
        val documents = listOf(
            IssueSearchDocument(1, "SocketTimeout", "", emptyList()),
            IssueSearchDocument(2, "socket timeout", "", emptyList()),
            IssueSearchDocument(3, "socket unrelated timeout", "", emptyList()),
            IssueSearchDocument(4, "timeout socket", "", emptyList()),
            IssueSearchDocument(5, "socket", "timeout", emptyList()),
            IssueSearchDocument(6, "기록", "", listOf(10L to "socket", 11L to "timeout")),
            IssueSearchDocument(7, "기록", "", listOf(12L to "socket timeout"))
        )
        val index = IssueSearchIndex(path.toString())
        try {
            index.synchronize({ after -> documents.filter { it.id > after } })
            index.search("SocketTimeout").map { it.id }.toSet() shouldBe setOf(1L, 2L, 7L)
            index.search("\"socket timeout\"").map { it.id }.toSet() shouldBe setOf(2L, 7L)
            index.search("\"SocketTimeout\"").map { it.id } shouldBe listOf(1L)
            index.search("\"socket timeout\" unrelated").map { it.id } shouldBe emptyList()
            index.analyzer.getPositionIncrementGap("comments") shouldBe 100
            index.analyzer.getPositionIncrementGap("comments_ident") shouldBe 100
        } finally {
            index.close()
            path.toFile().deleteRecursively()
        }
    }

    it("한국어 문맥과 기존 Nori 점수 순서를 보존하고 보조 일치는 뒤에만 추가한다") {
        val path = Files.createTempDirectory("yona-lucene-")
        val documents = listOf(
            IssueSearchDocument(1, "오류 timeout", "", emptyList()),
            IssueSearchDocument(2, "기록", "오류 timeout", emptyList()),
            IssueSearchDocument(3, "오류 SocketTimeoutException", "", emptyList()),
            IssueSearchDocument(4, "기록", "오류 SocketTimeoutException", emptyList()),
            IssueSearchDocument(5, "오류 SocketTimeoutException timeout", "", emptyList()),
            IssueSearchDocument(6, "입력 검증", "필드 길이 제한을 확인합니다 SocketTimeoutException",
                listOf(10L to "교수님의 요청으로 변경합니다")),
            IssueSearchDocument(7, "그리고", "그리고", listOf(11L to "그리고"))
        )
        val index = IssueSearchIndex(path.toString())
        try {
            index.synchronize({ after -> documents.filter { it.id > after } })
            index.search("길이 제한 timeout").map { it.id } shouldBe listOf(6L)
            index.search("교수님의 요청으로 timeout").map { it.id } shouldBe listOf(6L)
            index.search("\"길이 제한\" timeout").map { it.id } shouldBe listOf(6L)
            index.search("그리고").map { it.id } shouldBe emptyList()
            index.search("\"그리고\"").map { it.id } shouldBe emptyList()
            // The previous public query for these known Nori terms: AND terms, OR weighted fields.
            val previous = BooleanQuery.Builder().apply {
                for (term in listOf("오류", "timeout")) {
                    add(BooleanQuery.Builder().apply {
                        for ((field, boost) in listOf("title" to 3f, "body" to 1f, "comments" to 1f)) {
                            add(BoostQuery(TermQuery(Term(field, term)), boost), BooleanClause.Occur.SHOULD)
                        }
                    }.build(), BooleanClause.Occur.MUST)
                }
            }.build()
            FSDirectory.open(path).use { dir ->
                DirectoryReader.open(dir).use { reader ->
                    val searcher = IndexSearcher(reader)
                    val before = searcher.search(previous, 20).scoreDocs.map { it.doc to it.score }
                    val after = searcher.search(index.query("오류 timeout"), 20).scoreDocs.map { it.doc to it.score }
                    after.take(before.size) shouldBe before
                    after.drop(before.size).map { it.second } shouldBe listOf(0f, 0f)
                }
            }
            index.search("오류 timeout").takeLast(2).map { it.id }.toSet() shouldBe setOf(3L, 4L)
        } finally {
            index.close()
            path.toFile().deleteRecursively()
        }
    }

    it("버전이 없거나 오래된 디스크 색인은 digest가 같아도 전부 재색인하고 실패 시 이전 commit을 유지한다") {
        for (oldVersion in listOf(null, "1")) {
            val path = Files.createTempDirectory("yona-lucene-")
            val source = IssueSearchDocument(1, "SocketTimeoutException", "", emptyList())
            KoreanAnalyzer().use { analyzer ->
                FSDirectory.open(path).use { dir ->
                    IndexWriter(dir, IndexWriterConfig(analyzer)).use { writer ->
                        for (id in listOf("1", "2")) {
                            writer.addDocument(Document().apply {
                                add(StringField("id", id, Field.Store.YES))
                                add(StoredField("digest", source.digest()))
                                add(TextField("title", source.title, Field.Store.NO))
                            })
                        }
                        if (oldVersion != null) writer.setLiveCommitData(mapOf("yona.issue-search.version" to oldVersion).entries)
                        writer.commit()
                    }
                }
            }
            val index = IssueSearchIndex(path.toString())
            try {
                shouldThrow<IllegalStateException> {
                    index.synchronize({ after -> if (after == 0L) listOf(source) else error("interrupted migration") })
                }
                index.status.ready shouldBe false
                FSDirectory.open(path).use { dir ->
                    DirectoryReader.open(dir).use { reader ->
                        reader.numDocs() shouldBe 2
                        reader.indexCommit.userData["yona.issue-search.version"] shouldBe oldVersion
                    }
                }
                index.synchronize({ after -> if (after == 0L) listOf(source) else emptyList() })
                index.search("timeout").map { it.id } shouldBe listOf(1L)
                index.status.indexed shouldBe 1
                FSDirectory.open(path).use { dir ->
                    DirectoryReader.open(dir).use { it.indexCommit.userData["yona.issue-search.version"] shouldBe "2" }
                }
            } finally { index.close() }
            val reopened = IssueSearchIndex(path.toString())
            try {
                reopened.synchronize({ after -> if (after == 0L) listOf(source) else emptyList() })
                reopened.search("timeout").map { it.id } shouldBe listOf(1L)
            } finally {
                reopened.close()
                path.toFile().deleteRecursively()
            }
        }
    }

    it("첫 변경에서 시작한 전역 창은 후속 변경으로 연장되지 않고 유휴 시 실행하지 않는다") {
        SearchChangeWindow(1000, 1000).due(2999, 2000) shouldBe false
        SearchChangeWindow(1000, 1000).due(3000, 2000) shouldBe true
        // Another issue changes immediately before the deadline: it must not postpone dispatch.
        SearchChangeWindow(1000, 2999).due(3000, 2000) shouldBe true
        SearchChangeWindow(null, null).due(Long.MAX_VALUE, 2000) shouldBe false
    }

    it("연속된 제목 머리말만 추출하고 본문의 언급과 구분한다") {
        TitleHeads.extract("[Bug][UI] 제목 [Mention]") shouldBe listOf("Bug", "UI")
        TitleHeads.extract("본문에서 [Bug] 언급") shouldBe emptyList()
        TitleHeads.legacy("[Bug]") shouldBe "Bug"
        TitleHeads.legacy("[Bug] text") shouldBe null
    }
})
