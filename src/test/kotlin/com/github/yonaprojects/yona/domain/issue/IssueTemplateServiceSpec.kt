package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.vcs.PlayRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.io.FileNotFoundException

class IssueTemplateServiceSpec : DescribeSpec({
    val repositories = mockk<RepositoryService>()
    val repository = mockk<PlayRepository>()
    val project = Project(id = 1, owner = "owner", name = "forms")
    val service = IssueTemplateService(repositories, ObjectMapper())
    val json = """[
      {"id":"bug","name":"Bug report","body":"Describe the bug","fields":[
        {"id":"steps","label":"Steps <script>alert(1)</script>","type":"text","required":true},
        {"id":"platform","label":"Platform","type":"select","required":true,"options":["Linux","macOS"]}]},
      {"id":"feature","name":"Feature request","body":"Proposed feature","fields":[
        {"id":"reason","label":"Reason","type":"text","required":true}]}
    ]""".trimIndent().toByteArray()

    beforeTest {
        every { repositories.getRepository(project) } returns repository
        every { repository.getRawFile("HEAD", "ISSUE_TEMPLATE.md") } returns "Legacy body".toByteArray()
        every { repository.getRawFile("HEAD", IssueTemplateService.PATH) } returns json
    }

    it("selects independent templates and keeps the legacy default") {
        val catalog = service.catalog(project)
        service.select(catalog, "bug")!!.fields.map { it.id } shouldBe listOf("steps", "platform")
        service.select(catalog, "feature")!!.body shouldBe "Proposed feature"
        service.select(catalog, null) shouldBe null
        catalog.legacyBody shouldBe "Legacy body"
        service.submission(project, null, emptyMap(), "Unchanged **Markdown**") shouldBe "Unchanged **Markdown**"
    }

    it("preserves legacy fallback for missing and malformed catalogs") {
        every { repository.getRawFile("HEAD", IssueTemplateService.PATH) } throws FileNotFoundException()
        service.catalog(project) shouldBe IssueTemplateService.Catalog(emptyList(), "Legacy body")
        every { repository.getRawFile("HEAD", IssueTemplateService.PATH) } returns "not json".toByteArray()
        service.catalog(project) shouldBe IssueTemplateService.Catalog(emptyList(), "Legacy body", true)
    }

    it("rejects required blanks, forged choices, unknown fields and template paths") {
        listOf(
            mapOf("steps" to "  ", "platform" to "Linux"),
            mapOf("steps" to "Reproduce", "platform" to "Windows"),
            mapOf("steps" to "Reproduce", "platform" to "Linux", "admin" to "true"),
            mapOf("steps" to "x".repeat(10001), "platform" to "Linux")
        ).forEach { answers ->
            shouldThrow<ResponseStatusException> { service.submission(project, "bug", answers, "") }
                .statusCode.value() shouldBe 400
        }
        shouldThrow<ResponseStatusException> { service.submission(project, "../secret", emptyMap(), "") }
        shouldThrow<ResponseStatusException> { service.submission(project, null, mapOf("steps" to "text"), "") }
    }

    it("renders all labeled answers literally even with HTML, Markdown and CR newlines") {
        val answer = "<script>alert(2)</script>\r\n![image](https://evil.test/x)\r# forged heading"
        val body = service.submission(project, "bug", mapOf("steps" to answer, "platform" to "macOS"), "Context")
        body shouldContain "Context\n\n### Steps"
        body shouldContain "### Platform\n\n    macOS"
        val html = HtmlRenderer.builder().build().render(Parser.builder().build().parse(body))
        html shouldContain "&lt;script&gt;alert(2)&lt;/script&gt;"
        html shouldContain "# forged heading"
        html shouldNotContain "<script>"
        html shouldNotContain "<img"
        html shouldNotContain "<h1>"
    }

    it("rejects duplicate ids and unsupported field types instead of silently dropping validation") {
        listOf(
            """[{"id":"bug","name":"Bug"},{"id":"bug","name":"Duplicate"}]""",
            """[{"id":"bug","name":"Bug","fields":[{"id":"x","label":"X","type":"html"}]}]""",
            """[{"id":"bug","name":"Bug","fields":[{"id":"x","label":"X","type":"select","options":[]}]}]""",
            """[{"id":"bug","name":"Bug","fields":[{"id":"x","label":"X","type":"text","required":"true"}]}]"""
        ).forEach { invalid -> shouldThrow<IllegalArgumentException> { service.parse(invalid.toByteArray()) } }
    }
})
