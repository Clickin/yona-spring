package com.github.yonaprojects.yona.domain.issue

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files

class IssueSearchIndexSpec : DescribeSpec({
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
