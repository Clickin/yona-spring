package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.milestone.Milestone
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.User
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.transaction.annotation.Transactional
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.web.context.WebApplicationContext
import java.time.Instant

class IssueSearchSqlInspector : org.hibernate.resource.jdbc.spi.StatementInspector {
    companion object { val captured = ThreadLocal<MutableList<String>>() }
    override fun inspect(sql: String): String {
        captured.get()?.add(sql)
        return sql
    }
}

@Transactional
@TestPropertySource(properties = ["yona.search.backend=lucene",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.github.yonaprojects.yona.domain.issue.IssueSearchSqlInspector"])
class IssueSearchServiceSpec @Autowired constructor(
    private val issues: IssueRepository,
    private val comments: IssueCommentRepository,
    private val accessControl: AccessControl,
    private val em: EntityManager,
    private val wac: WebApplicationContext,
    private val index: IssueSearchIndex,
    private val tasks: IssueSearchTasks
) : AbstractIntegrationTest() {
    companion object {
        private val indexPath = java.nio.file.Files.createTempDirectory("yona-search-it-")
        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun indexProperties(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("yona.search.index-dir") { indexPath.toString() }
            registry.add("yona.search.initial-delay-millis") { "3600000" }
        }
    }
    init {
        it("DB 조건과 Nori 검색을 결합하고 권한 및 전체 건수를 페이지 전에 적용한다") {
            val project = Project(name = "search-poc", owner = "poc", projectScope = ProjectScope.PUBLIC)
            em.persist(project)
            val author = User(loginId = "poc-author", name = "author", email = "poc@example.com")
            val assignee = User(loginId = "poc-assignee", name = "assignee", email = "assignee@example.com")
            em.persist(author)
            em.persist(assignee)
            val milestone = Milestone(title = "poc", project = project)
            em.persist(milestone)
            val category = IssueLabelCategory(name = "type", project = project)
            em.persist(category)
            val label = IssueLabel(name = "bug", project = project, category = category)
            em.persist(label)
            val first = issues.save(Issue(
                title = "[Bug][UI] 로그인 오류", body = "첫 번째", project = project, number = 1,
                authorId = author.id, authorLoginId = author.loginId,
                assignee = Assignee(user = assignee, project = project), milestone = milestone,
                dueDate = Instant.parse("2026-01-01T00:00:00Z"), labels = mutableSetOf(label)
            ))
            val second = issues.save(Issue(title = "재현 기록", project = project, number = 2,
                authorId = author.id, authorLoginId = author.loginId))
            val comment = comments.save(IssueComment(issue = second, authorId = assignee.id,
                contents = "<script>로그인 처리 중 인증 오류가 발생 [Bug]</script>"))
            em.flush()
            index.synchronize(tasks::batch)
            val lucene = IssueSearchService(issues, comments, accessControl, "lucene", index)
            val db = IssueSearchService(issues, comments, accessControl)
            fun conditions(
                authorId: Long? = null, assigneeId: Long? = null, milestoneId: Long? = null,
                commenterId: Long? = null, labelIds: List<Long>? = null, dueDate: String? = null,
                state: State = State.OPEN
            ) = IssueSpecification.filterIssues(project, state, null, authorId, assigneeId,
                milestoneId, commenterId, labelIds, dueDate)
            val page = PageRequest.of(0, 1)

            db.search(conditions(), "로그인 오류", null, page).totalElements shouldBe 1
            val ready = index.status
            val result = lucene.search(conditions(), "로그인 오류", null, page)
            result.totalElements shouldBe 2
            index.status shouldBe ready // Requests read the shared index; they do not build one.
            val commentMatch = lucene.search(conditions(commenterId = assignee.id), "로그인 오류", null, page) as IssueSearchPage
            commentMatch.snippets[second.id]!!.commentId shouldBe comment.id
            commentMatch.snippets[second.id]!!.html.contains("<script>") shouldBe false
            commentMatch.snippets[second.id]!!.html.contains("<mark>") shouldBe true
            lucene.search(conditions(), "로그인 오류", null, page, "Bug").totalElements shouldBe 1
            db.search(conditions(), null, null, page, "UI").content.map { it.id } shouldBe listOf(first.id)
            lucene.search(conditions(), "로그인 오류", null, page, "Missing").totalElements shouldBe 0
            result.content.map { it.id } shouldBe listOf(first.id) // 제목 가중치
            lucene.search(conditions(), "로그인 오류", null, PageRequest.of(1, 1))
                .content.map { it.id } shouldBe listOf(second.id)
            lucene.search(conditions(), "\"로그인 오류\"", null, page).totalElements shouldBe 1
            lucene.search(conditions(), "로그인 없는단어", null, page).totalElements shouldBe 0
            lucene.search(conditions(author.id, assignee.id, milestone.id, null, listOf(label.id!!), "2026-01-02"),
                "로그인 오류", null, page).content.map { it.id } shouldBe listOf(first.id)
            lucene.search(conditions(assigneeId = -1), "로그인 오류", null, page)
                .content.map { it.id } shouldBe listOf(second.id)
            lucene.search(conditions(commenterId = assignee.id), "로그인 오류", null, page)
                .content.map { it.id } shouldBe listOf(second.id)
            lucene.search(conditions(authorId = assignee.id), "로그인 오류", null, page).totalElements shouldBe 0
            lucene.search(conditions(state = State.CLOSED), "로그인 오류", null, page).totalElements shouldBe 0
            lucene.search(conditions(), "로그인 오류", null, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "number")))
                .content.map { it.id } shouldBe listOf(second.id, first.id)

            val mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
            mvc.perform(get("/poc/search-poc/issues").param("filter", "[Bug]"))
                .andExpect(status().isOk).andExpect(model().attribute("titleHead", "Bug"))
            mvc.perform(get("/poc/search-poc/issues").param("filter", "[Bug]").param("literalFilter", "true"))
                .andExpect(status().isOk).andExpect(model().attribute("filter", "[Bug]"))
            mvc.perform(get("/search").param("keyword", "로그인 오류").param("searchType", "issue"))
                .andExpect(status().isOk).andExpect(model().attributeExists("searchResult"))
            val webResult = mvc.perform(get("/poc/search-poc/issues")
                .param("filter", "로그인 오류").param("authorId", author.id.toString())
                .param("assigneeId", assignee.id.toString()).param("labelIds", label.id.toString())
                .param("milestoneId", milestone.id.toString()).param("orderBy", "relevance"))
                .andExpect(status().isOk)
                .andExpect(model().attributeExists("issuePage")).andReturn()
            (webResult.modelAndView!!.model["issuePage"] as org.springframework.data.domain.Page<*>).totalElements shouldBe 1
            mvc.perform(get("/api/v1/projects/poc/search-poc/issues").with(user(author.loginId))
                .param("filter", "로그인 오류").param("author", author.loginId)
                .param("assignee", assignee.loginId).param("label", "bug"))
                .andExpect(status().isOk)
                .andExpect(header().string("X-Yona-Search-Backend", "lucene"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.id!!.toInt()))

            comment.contents = "해결 완료"
            em.flush()
            val awaitingSync = lucene.search(conditions(), "로그인 오류", null, PageRequest.of(0, 20)) as IssueSearchPage
            awaitingSync.totalElements shouldBe 2 // Text matches follow the last published index.
            awaitingSync.snippets.containsKey(second.id) shouldBe false
            first.body = "SocketTimeoutException customer_id"
            em.flush()
            index.synchronize(tasks::batch)
            lucene.search(conditions(), "SocketTimeoutException", null, page).totalElements shouldBe 1
            project.projectScope = ProjectScope.PRIVATE
            em.flush()
            lucene.search(conditions(), "로그인 오류", null, page).totalElements shouldBe 0
            project.projectScope = ProjectScope.PUBLIC
            issues.delete(first)
            em.flush()
            lucene.search(conditions(), "로그인 오류", null, page).totalElements shouldBe 0
        }

        it("보조 식별자 일치에는 안전하게 스니펫을 생략하고 기존 Nori 일치는 이스케이프한다") {
            val project = Project(name = "identifier-snippet", owner = "poc", projectScope = ProjectScope.PUBLIC)
            em.persist(project)
            val issue = issues.save(Issue(title = "진단 기록", project = project, number = 1))
            val comment = comments.save(IssueComment(issue = issue,
                contents = "<script>alert('unsafe')</script> 오류 SocketTimeoutException"))
            em.flush()
            index.synchronize(tasks::batch)
            val service = IssueSearchService(issues, comments, accessControl, "lucene", index)
            val conditions = IssueSpecification.filterIssues(project, State.OPEN, null, null, null, null, null, null, null)
            fun search(text: String) = service.search(conditions, text, null, PageRequest.of(0, 20)) as IssueSearchPage
            for (text in listOf("timeout", "오류 timeout")) {
                val result = search(text)
                result.content.map { it.id } shouldBe listOf(issue.id)
                result.snippets shouldBe emptyMap()
            }
            val original = search("SocketTimeoutException").snippets.getValue(issue.id!!)
            original.commentId shouldBe comment.id
            original.html.contains("<script>") shouldBe false
            original.html.contains("<mark>") shouldBe true
            comment.contents = "삭제된 식별자"
            em.flush()
            search("timeout").snippets shouldBe emptyMap()
            search("SocketTimeoutException").snippets shouldBe emptyMap()
        }

        it("대량 hit의 DB 정렬과 건수를 유지하며 댓글은 표시할 페이지만 조회한다") {
            val project = Project(name = "bulk-search", owner = "poc", projectScope = ProjectScope.PUBLIC)
            em.persist(project)
            var staleComment: IssueComment? = null
            repeat(2105) { n ->
                val issue = issues.save(Issue(title = "bulkneedle", body = "원본", project = project, number = n + 1L))
                val comment = comments.save(IssueComment(issue = issue, contents = "검증 댓글"))
                if (n == 0) staleComment = comment
            }
            issues.save(Issue(title = "unrelatedneedle", project = project, number = 3000))
            em.flush()
            index.synchronize(tasks::batch)
            // Off-page content edits do not change index matches until the next publication.
            staleComment!!.contents = "수정된 댓글"
            em.flush()
            em.clear()
            val lucene = IssueSearchService(issues, comments, accessControl, "lucene", index)
            val spec = IssueSpecification.filterIssues(project, State.OPEN, null, null, null, null, null, null, null)
            val statistics = em.entityManagerFactory.unwrap(org.hibernate.SessionFactory::class.java).statistics
            val enabled = statistics.isStatisticsEnabled
            statistics.isStatisticsEnabled = true
            val commentQuery = "select c from IssueComment c where c.issue.id in :issueIds order by c.id"
            val before = statistics.getQueryStatistics(commentQuery).executionCount
            val issueLoads = statistics.getEntityStatistics(Issue::class.java.name).loadCount
            val statements = mutableListOf<String>()
            IssueSearchSqlInspector.captured.set(statements)
            try {
                val result = lucene.search(spec, "bulkneedle", null,
                    PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "number"))) as IssueSearchPage
                result.totalElements shouldBe 2105
                result.content.map { it.number } shouldBe (2085L downTo 2066L).toList()
                result.snippets.keys shouldBe result.content.map { it.id!! }.toSet()
                // Only the 20 displayed issues, not all 2,105 matches, need their comments.
                (statistics.getQueryStatistics(commentQuery).executionCount - before) shouldBe 1L
                (statistics.getEntityStatistics(Issue::class.java.name).loadCount - issueLoads) shouldBe 20L
                em.clear()
                val beforeRelevance = statistics.getEntityStatistics(Issue::class.java.name).loadCount
                val relevance = lucene.search(spec, "bulkneedle", null, PageRequest.of(1, 20))
                relevance.totalElements shouldBe 2105
                relevance.content.map { it.id } shouldBe index.search("bulkneedle").drop(20).take(20).map { it.id }
                (statistics.getEntityStatistics(Issue::class.java.name).loadCount - beforeRelevance) shouldBe 20L
                val idLists = statements.flatMap { sql ->
                    Regex("""\bin\s*\(([^()]*)\)""", RegexOption.IGNORE_CASE).findAll(sql)
                        .map { it.groupValues[1] }.filter { !it.contains("select", ignoreCase = true) }.toList()
                }
                idLists.isNotEmpty() shouldBe true
                idLists.all { it.split(',').size <= IssueIndexSearch.BATCH_SIZE } shouldBe true
                // Strict repositories keep this assertion independent of background queue SQL.
                val unusedIssues = io.mockk.mockk<IssueRepository>()
                val unusedComments = io.mockk.mockk<IssueCommentRepository>()
                val emptySearch = IssueSearchService(unusedIssues, unusedComments, accessControl, "lucene", index)
                emptySearch.search(spec, "missingneedle", null, PageRequest.of(0, 20)).totalElements shouldBe 0
                io.mockk.confirmVerified(unusedIssues, unusedComments)
            } finally {
                IssueSearchSqlInspector.captured.remove()
                statistics.isStatisticsEnabled = enabled
            }
        }

        it("머리말 검색의 다음 페이지와 마지막 페이지에서 해당 이슈 본문만 읽는다") {
            val project = Project(name = "head-pages", owner = "poc", projectScope = ProjectScope.PUBLIC)
            em.persist(project)
            val head = "UI_%"
            val ids = (1L..53L).map { n ->
                issues.save(Issue(title = "[Bug][$head] headpagingneedle", body = "본문".repeat(2048),
                    project = project, number = n)).apply {
                    createdDate = Instant.parse("2026-01-01T00:00:00Z")
                }.id!!
            }
            for (title in listOf("headpagingneedle [$head]", "[Other] headpagingneedle [$head]", "[UI_AX] headpagingneedle")) {
                issues.save(Issue(title = title, project = project, number = 100L + title.length))
            }
            em.flush()
            index.synchronize(tasks::batch)
            val spec = IssueSpecification.filterIssues(project, State.OPEN, null, null, null, null, null, null, null)
            val statistics = em.entityManagerFactory.unwrap(org.hibernate.SessionFactory::class.java).statistics
            val enabled = statistics.isStatisticsEnabled
            statistics.isStatisticsEnabled = true
            try {
                for (backend in listOf("db", "lucene")) {
                    val search = IssueSearchService(issues, comments, accessControl, backend, if (backend == "lucene") index else null)
                    for (sort in listOf(Sort.unsorted(), Sort.by(Sort.Direction.DESC, "createdDate"))) {
                        val ordered = if (backend == "lucene" && sort.isUnsorted)
                            index.search("headpagingneedle", head).map { it.id } else ids.sorted()
                        val seen = mutableListOf<Long>()
                        for (page in 0..3) {
                            em.clear()
                            val before = statistics.getEntityStatistics(Issue::class.java.name).loadCount
                            val result = search.search(spec, "headpagingneedle", null, PageRequest.of(page, 20, sort), head)
                            val expected = ordered.drop(page * 20).take(20)
                            result.totalElements shouldBe 53L
                            result.content.map { it.id } shouldBe expected
                            (statistics.getEntityStatistics(Issue::class.java.name).loadCount - before) shouldBe expected.size.toLong()
                            seen.addAll(result.content.map { it.id!! })
                        }
                        seen.toSet().size shouldBe 53
                        seen.size shouldBe 53
                    }
                }
                // The indexed head is stale; current title and existence still control the count.
                em.find(Issue::class.java, ids[0]).title = "headpagingneedle [$head]"
                issues.deleteById(ids[1])
                em.flush()
                em.clear()
                val search = IssueSearchService(issues, comments, accessControl, "lucene", index)
                val last = search.search(spec, "headpagingneedle", null, PageRequest.of(2, 20, Sort.by("id")), head)
                last.totalElements shouldBe 51
                last.content.map { it.id } shouldBe ids.drop(42)
            } finally { statistics.isStatisticsEnabled = enabled }
        }

        it("DB READ 조건이 기존 권한 판정과 일치하고 공유 철회도 반영한다") {
            val org = com.github.yonaprojects.yona.domain.organization.Organization(name = "search-acl")
            em.persist(org)
            val users = (0..9).map { n ->
                User(loginId = "acl-$n", name = "acl-$n", isGuest = n == 1 || n == 9,
                    state = if (n == 2) com.github.yonaprojects.yona.domain.user.UserState.SITE_ADMIN
                        else com.github.yonaprojects.yona.domain.user.UserState.ACTIVE).also(em::persist)
            }
            val projects = listOf(null, org).flatMap { organization -> ProjectScope.entries.map { scope ->
                Project(name = "acl-${organization?.id}-$scope", owner = "poc", projectScope = scope,
                    organization = organization).also(em::persist)
            } }
            for (project in projects) {
                val issue = Issue(title = "aclneedle", project = project, number = 1, authorId = users[3].id,
                    assignee = Assignee(user = users[4], project = project))
                em.persist(issue)
                em.persist(IssueSharer(loginId = users[5].loginId, user = users[5], issue = issue))
                em.persist(IssueSharer(loginId = users[9].loginId, user = users[9], issue = issue))
                em.persist(Issue(title = "aclneedle", project = project, number = 2, parent = issue))
                em.persist(Issue(title = "aclneedle", project = project, number = 3))
                em.persist(com.github.yonaprojects.yona.domain.project.ProjectUser(user = users[6], project = project,
                    role = em.getReference(com.github.yonaprojects.yona.domain.role.Role::class.java, 2L)))
            }
            for ((n, role) in listOf(7 to 6L, 8 to 7L)) {
                em.persist(com.github.yonaprojects.yona.domain.organization.OrganizationUser(user = users[n],
                    organization = org, role = em.getReference(com.github.yonaprojects.yona.domain.role.Role::class.java, role)))
            }
            em.flush()
            val userIds = users.map { it.id!! }
            val projectIds = projects.map { it.id!! }
            em.clear()
            val scope = Specification<Issue> { root, _, cb ->
                root.get<Project>("project").get<Long>("id").`in`(projectIds)
            }
            val all = issues.findAll(scope)
            index.synchronize(tasks::batch)
            val lucene = IssueSearchService(issues, comments, accessControl, "lucene", index)
            for (id in listOf(null) + userIds) {
                val user = id?.let { em.find(User::class.java, it) }
                val expected = all.filter { accessControl.isAllowed(user, it.project, it,
                    com.github.yonaprojects.yona.domain.enumeration.Operation.READ) }.map { it.id }.toSet()
                issues.findAll(scope.and(accessControl.readableIssues(user))).map { it.id }.toSet() shouldBe expected
                val sortedPage = lucene.search(scope, "aclneedle", user, PageRequest.of(0, 3, Sort.by("id")))
                sortedPage.totalElements shouldBe expected.size.toLong()
                sortedPage.content.map { it.id } shouldBe expected.filterNotNull().sorted().take(3)
                val relevancePage = lucene.search(scope, "aclneedle", user, PageRequest.of(0, 3))
                relevancePage.totalElements shouldBe expected.size.toLong()
                relevancePage.content.map { it.id } shouldBe index.search("aclneedle").map { it.id }
                    .filter { it in expected }.take(3)
            }
            // Parent sharing grants child READ. Revocation must affect both without reindexing.
            val sharedUserId = userIds[5]
            em.createQuery("delete from IssueSharer s where s.user.id = :id").setParameter("id", sharedUserId).executeUpdate()
            em.clear()
            val sharedUser = em.find(User::class.java, sharedUserId)
            issues.findAll(scope.and(accessControl.readableIssues(sharedUser))).all { it.project.isPublic } shouldBe true
            val deniedAnonymous = AccessControl(io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(),
                io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(),
                io.mockk.mockk(), allowsAnonymousAccess = false)
            issues.count(scope.and(deniedAnonymous.readableIssues(null))) shouldBe 0L
        }

        it("색인 지연 중 텍스트 일치는 유지하되 현재 DB 조건과 삭제 및 권한을 적용한다") {
            val project = Project(name = "stale-search", owner = "poc", projectScope = ProjectScope.PUBLIC)
            em.persist(project)
            val issue = issues.save(Issue(title = "oldtitlemarker", project = project, number = 1))
            val comment = comments.save(IssueComment(issue = issue, contents = "oldcommentmarker"))
            em.flush()
            index.synchronize(tasks::batch)
            val lucene = IssueSearchService(issues, comments, accessControl, "lucene", index)
            fun search(text: String, state: State = State.OPEN) = lucene.search(
                IssueSpecification.filterIssues(project, state, null, null, null, null, null, null, null),
                text, null, PageRequest.of(0, 20)) as IssueSearchPage
            search("oldcommentmarker").snippets[issue.id]!!.commentId shouldBe comment.id

            issue.title = "newtitlemarker"
            comments.delete(comment)
            em.flush()
            val stale = search("oldcommentmarker")
            stale.totalElements shouldBe 1
            stale.content.single().title shouldBe "newtitlemarker" // Never return the indexed old title.
            stale.snippets shouldBe emptyMap() // No deleted comment text or dangling anchor.
            search("oldtitlemarker").totalElements shouldBe 1
            search("newtitlemarker").totalElements shouldBe 0

            issue.state = State.CLOSED
            em.flush()
            search("oldtitlemarker").totalElements shouldBe 0 // DB metadata changes apply before sync.
            search("oldtitlemarker", State.CLOSED).totalElements shouldBe 1
            project.projectScope = ProjectScope.PRIVATE
            em.flush()
            search("oldtitlemarker", State.CLOSED).totalElements shouldBe 0
            project.projectScope = ProjectScope.PUBLIC
            em.flush()
            index.synchronize(tasks::batch)
            search("oldcommentmarker", State.CLOSED).totalElements shouldBe 0
            search("oldtitlemarker", State.CLOSED).totalElements shouldBe 0
            search("newtitlemarker", State.CLOSED).totalElements shouldBe 1
            issues.delete(issue)
            em.flush()
            search("newtitlemarker", State.CLOSED).totalElements shouldBe 0 // Even before index deletion.
        }

        it("게시글과 PR에도 연속 머리말 필터와 기존 URL을 적용한다") {
            val project = Project(name = "head-poc", owner = "poc", projectScope = ProjectScope.PUBLIC)
            val author = User(loginId = "head-author", name = "author", email = "head@example.com")
            em.persist(project)
            em.persist(author)
            em.persist(com.github.yonaprojects.yona.domain.board.Posting(
                title = "[Bug][UI] 대상", body = "내용", project = project, number = 1, authorLoginId = author.loginId))
            em.persist(com.github.yonaprojects.yona.domain.board.Posting(
                title = "언급", body = "[Bug]", project = project, number = 2, authorLoginId = author.loginId))
            em.persist(com.github.yonaprojects.yona.domain.pullrequest.PullRequest(
                title = "[Bug][UI] 대상", toProject = project, fromProject = project, contributor = author, number = 1))
            em.persist(com.github.yonaprojects.yona.domain.pullrequest.PullRequest(
                title = "언급 [Bug]", toProject = project, fromProject = project, contributor = author, number = 2))
            em.flush()
            val mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
            for ((route, attribute) in listOf("posts" to "postingPage", "pulls" to "prPage")) {
                val result = mvc.perform(get("/poc/head-poc/$route").param("filter", "[Bug]"))
                    .andExpect(status().isOk).andExpect(model().attribute("titleHead", "Bug")).andReturn()
                (result.modelAndView!!.model[attribute] as org.springframework.data.domain.Page<*>).totalElements shouldBe 1
                val cleared = mvc.perform(get("/poc/head-poc/$route").param("titleHead", "").param("literalFilter", "true"))
                    .andExpect(status().isOk).andReturn()
                (cleared.modelAndView!!.model[attribute] as org.springframework.data.domain.Page<*>).totalElements shouldBe 2
            }
        }

        it("게시글과 PR 머리말도 제목만 판정하고 다음 페이지의 본문만 읽는다") {
            val project = Project(name = "head-board-pr-pages", owner = "poc", projectScope = ProjectScope.PUBLIC)
            val author = User(loginId = "head-pages-author", name = "author")
            em.persist(project); em.persist(author)
            val category = IssueLabelCategory(name = "page-labels", project = project)
            em.persist(category)
            val labels = listOf("a", "b").map { IssueLabel(name = it, project = project, category = category).also(em::persist) }
            val postingIds = mutableListOf<Long>()
            val prIds = mutableListOf<Long>()
            repeat(33) { n ->
                val posting = com.github.yonaprojects.yona.domain.board.Posting(title = "[Bug][UI] 대상",
                    body = "boardpagingneedle " + "본문".repeat(2048), project = project, number = n + 1L,
                    authorLoginId = author.loginId, labels = labels.toMutableSet())
                em.persist(posting); postingIds.add(posting.id!!)
                val pr = com.github.yonaprojects.yona.domain.pullrequest.PullRequest(title = "[Bug][UI] prpagingneedle",
                    body = "본문".repeat(2048), toProject = project, fromProject = project, contributor = author, number = n + 1L)
                em.persist(pr); prIds.add(pr.id!!)
            }
            em.persist(com.github.yonaprojects.yona.domain.board.Posting(title = "중간 [UI]", body = "boardpagingneedle",
                project = project, number = 100, labels = labels.toMutableSet()))
            em.persist(com.github.yonaprojects.yona.domain.board.Posting(title = "[UI] 라벨 없음", body = "boardpagingneedle",
                project = project, number = 101))
            em.persist(com.github.yonaprojects.yona.domain.board.Posting(title = "[UI] 검색어 없음", body = "otherword",
                project = project, number = 102, labels = labels.toMutableSet()))
            em.persist(com.github.yonaprojects.yona.domain.pullrequest.PullRequest(title = "중간 [UI] prpagingneedle",
                toProject = project, fromProject = project, contributor = author, number = 100))
            em.flush(); em.clear()
            val mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
            val statistics = em.entityManagerFactory.unwrap(org.hibernate.SessionFactory::class.java).statistics
            val enabled = statistics.isStatisticsEnabled
            statistics.isStatisticsEnabled = true
            try {
                for ((route, attribute, type) in listOf(
                    Triple("posts", "postingPage", com.github.yonaprojects.yona.domain.board.Posting::class.java),
                    Triple("pulls", "prPage", com.github.yonaprojects.yona.domain.pullrequest.PullRequest::class.java))) {
                    val ordered = (if (route == "posts") postingIds else prIds).sortedDescending()
                    for (page in 0..3) {
                        em.clear()
                        val before = statistics.getEntityStatistics(type.name).loadCount
                        val request = get("/poc/head-board-pr-pages/$route").param("titleHead", "UI")
                            .param("filter", if (route == "posts") "boardpagingneedle" else "prpagingneedle")
                            .param("page", page.toString()).param("orderBy", "id").param("orderDir", "desc")
                        if (route == "posts") request.param("labelIds", *labels.map { it.id.toString() }.toTypedArray())
                        val response = mvc.perform(request).andExpect(status().isOk).andReturn()
                        val result = response.modelAndView!!.model[attribute] as org.springframework.data.domain.Page<*>
                        result.totalElements shouldBe 33L
                        result.content.map { entity -> when (entity) {
                            is com.github.yonaprojects.yona.domain.board.Posting -> entity.id
                            is com.github.yonaprojects.yona.domain.pullrequest.PullRequest -> entity.id
                            else -> error("Unexpected result")
                        } } shouldBe ordered.drop(page * 15).take(15)
                        (statistics.getEntityStatistics(type.name).loadCount - before) shouldBe result.content.size.toLong()
                    }
                }
            } finally { statistics.isStatisticsEnabled = enabled }
        }

        it("별개 댓글 경계를 넘는 구문은 일치하지 않는다") {
            val project = Project(name = "search-boundary", owner = "poc", projectScope = ProjectScope.PUBLIC)
            em.persist(project)
            val issue = issues.save(Issue(title = "구문 경계", project = project, number = 1))
            comments.save(IssueComment(issue = issue, contents = "로그인"))
            comments.save(IssueComment(issue = issue, contents = "오류"))
            em.flush()
            index.synchronize(tasks::batch)
            val lucene = IssueSearchService(issues, comments, accessControl, "lucene", index)
            val spec = IssueSpecification.filterIssues(project, State.OPEN, null, null, null, null, null, null, null)
            lucene.search(spec, "로그인 오류", null, PageRequest.of(0, 10)).totalElements shouldBe 1
            lucene.search(spec, "\"로그인 오류\"", null, PageRequest.of(0, 10)).totalElements shouldBe 0
        }
    }
}
