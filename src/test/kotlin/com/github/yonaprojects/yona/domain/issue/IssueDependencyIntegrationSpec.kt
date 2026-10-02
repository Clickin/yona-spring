package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.apitoken.*
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.notification.NotificationEvent
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import jakarta.servlet.Filter
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Deliberately no test transaction: concurrency must exercise independently committed mutations.
class IssueDependencyIntegrationSpec @Autowired constructor(
    private val dependencies: IssueDependencyService,
    private val edges: IssueDependencyRepository,
    private val issues: IssueRepository,
    private val issueService: IssueService,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val tokens: ApiTokenRepository,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    private val context: WebApplicationContext
) : AbstractIntegrationTest() {
    private val transaction = TransactionTemplate(transactionManager)
    private lateinit var owner: User
    private lateinit var outsider: User
    private lateinit var project: Project
    private lateinit var otherProject: Project
    private lateinit var mvc: MockMvc
    private val tokenIds = mutableListOf<Long>()
    private var number = 0L

    private fun issue(title: String = "issue", inProject: Project = project, draft: Boolean = false): Issue =
        issues.save(Issue(title = title, body = "body", project = inProject, number = ++number,
            authorId = owner.id, authorLoginId = owner.loginId, authorName = owner.name,
            isDraft = draft, state = if (draft) State.DRAFT else State.OPEN))

    private fun api(issue: Issue) = "/api/v1/projects/${project.owner}/${project.name}/issues/${issue.number}/dependencies"
    private fun page(issue: Issue) = "/${project.owner}/${project.name}/issue/${issue.number}"
    private fun auth() = UsernamePasswordAuthenticationToken(owner.loginId, "unused", emptyList())

    init {
        beforeSpec {
            val unique = UUID.randomUUID().toString().take(8)
            owner = users.save(User(loginId = "dep-owner-$unique", name = "Owner", email = "dep-$unique@example.com"))
            outsider = users.save(User(loginId = "dep-other-$unique", name = "Other", email = "other-$unique@example.com"))
            project = projects.save(Project(name = "dependencies-$unique", owner = owner.loginId, projectScope = ProjectScope.PUBLIC))
            otherProject = projects.save(Project(name = "private-$unique", owner = owner.loginId, projectScope = ProjectScope.PRIVATE))
            mvc = MockMvcBuilders.webAppContextSetup(context).build()
        }
        afterSpec {
            transaction.executeWithoutResult {
                tokenIds.forEach { tokens.deleteById(it) }
                for (p in listOf(project, otherProject)) {
                    issues.findByProject(p).forEach { issueService.deleteIssueCascade(it) }
                    projects.deleteById(p.id!!)
                }
                entityManager.createQuery("delete from RecentIssue r where r.userId in :ids")
                    .setParameter("ids", listOf(owner.id, outsider.id)).executeUpdate()
                entityManager.createQuery("select e from NotificationEvent e where e.senderId in :ids", NotificationEvent::class.java)
                    .setParameter("ids", listOf(owner.id, outsider.id)).resultList.forEach(entityManager::remove)
                users.deleteById(owner.id!!)
                users.deleteById(outsider.id!!)
            }
        }

        describe("Issue dependencies") {
            it("adds from the detail form, renders both directions, follows close/reopen and removes from either detail") {
                val a = issue("Predecessor")
                val b = issue("Successor")
                mvc.perform(post("${page(b)}/dependencies").principal(auth())
                    .param("targetNumber", a.number.toString()).param("direction", "BLOCKED_BY"))
                    .andExpect(status().is3xxRedirection)
                dependencies.get(project.id!!, b.number!!, owner).blocked shouldBe true
                val predecessorPage = Jsoup.parse(mvc.perform(get(page(a))).andReturn().response.contentAsString)
                val successorPage = Jsoup.parse(mvc.perform(get(page(b))).andReturn().response.contentAsString)
                predecessorPage.select("#issue-dependencies a").map { it.text() } shouldBe listOf("#${b.number} Successor")
                successorPage.select("#issue-dependencies a").map { it.text() } shouldBe listOf("#${a.number} Predecessor")
                issueService.changeState(a.id!!, State.CLOSED, owner.loginId!!)
                dependencies.get(project.id!!, b.number!!, owner).blocked shouldBe false
                dependencies.get(project.id!!, b.number!!, owner).blockedBy.single().state shouldBe State.CLOSED
                issueService.changeState(a.id!!, State.RESOLVED, owner.loginId!!)
                dependencies.get(project.id!!, b.number!!, owner).blocked shouldBe false
                issueService.changeState(a.id!!, State.OPEN, owner.loginId!!)
                dependencies.get(project.id!!, b.number!!, owner).blocked shouldBe true
                mvc.perform(post("${page(b)}/dependencies/delete").principal(auth())
                    .param("targetNumber", a.number.toString()).param("direction", "BLOCKED_BY"))
                    .andExpect(status().is3xxRedirection)
                dependencies.get(project.id!!, a.number!!, owner).blocking shouldBe emptyList()
                dependencies.get(project.id!!, b.number!!, owner).blockedBy shouldBe emptyList()
            }

            it("rejects self links, duplicate links, transitive cycles, and cross-project targets") {
                val a = issue()
                val b = issue()
                val c = issue()
                val foreign = issue("Secret foreign issue", otherProject)
                mvc.perform(post("${api(a)}/${a.number}").principal(auth())).andExpect(status().isBadRequest)
                mvc.perform(post("${api(a)}/${b.number}").principal(auth())).andExpect(status().isCreated)
                mvc.perform(post("${api(a)}/${b.number}").principal(auth())).andExpect(status().isConflict)
                dependencies.add(project.id!!, b.number!!, c.number!!, owner)
                issueService.changeState(a.id!!, State.CLOSED, owner.loginId!!)
                mvc.perform(post("${api(c)}/${a.number}").principal(auth())).andExpect(status().isConflict)
                mvc.perform(post("${api(a)}/${foreign.number}").principal(auth())).andExpect(status().isNotFound)
                mvc.perform(delete("${api(a)}/${b.number}").principal(auth())).andExpect(status().isNoContent)
                dependencies.get(project.id!!, b.number!!, owner).blockedBy shouldBe emptyList()
            }

            it("requires write access to both issues and exposes neither drafts nor private neighbors") {
                val a = issue("Visible issue")
                val draft = issue("Hidden draft title", draft = true)
                dependencies.add(project.id!!, draft.number!!, a.number!!, owner)
                dependencies.get(project.id!!, a.number!!, outsider).blockedBy shouldBe emptyList()
                dependencies.get(project.id!!, a.number!!, outsider).blocked shouldBe false
                mvc.perform(get(api(a))).andExpect(jsonPath("$.blockedBy").isEmpty)
                val body = Jsoup.parse(mvc.perform(get(page(a))).andReturn().response.contentAsString)
                body.select("#issue-dependencies").text().contains("Hidden draft title") shouldBe false
                mvc.perform(post("${api(a)}/${draft.number}").principal(auth())).andExpect(status().isConflict)
                mvc.perform(delete("${api(draft)}/${a.number}")
                    .principal(UsernamePasswordAuthenticationToken(outsider.loginId, "unused")))
                    .andExpect(status().isNotFound)

                val privateA = issue("Private predecessor", otherProject)
                val privateB = issue("Shared successor", otherProject)
                transaction.executeWithoutResult {
                    val b = issues.findById(privateB.id!!).orElseThrow()
                    b.sharers.add(IssueSharer(loginId = outsider.loginId!!, user = outsider, issue = b))
                }
                dependencies.add(otherProject.id!!, privateA.number!!, privateB.number!!, owner)
                dependencies.get(otherProject.id!!, privateB.number!!, outsider).blockedBy shouldBe emptyList()
                shouldThrow<IssueDependencyException> {
                    dependencies.get(otherProject.id!!, privateA.number!!, outsider)
                }.code shouldBe "notfound"
                val editableOnlyByOther = issue("Other author")
                editableOnlyByOther.authorId = outsider.id
                editableOnlyByOther.authorLoginId = outsider.loginId
                issues.save(editableOnlyByOther)
                mvc.perform(post("${api(a)}/${editableOnlyByOther.number}").principal(auth()))
                    .andExpect(status().isForbidden)
                mvc.perform(delete("${api(draft)}/${a.number}"))
                    .andExpect(status().isNotFound)
            }

            it("serializes concurrent graph edits so opposite paths cannot create a cycle") {
                val a = issue()
                val b = issue()
                val c = issue()
                dependencies.add(project.id!!, a.number!!, b.number!!, owner)
                val barrier = CyclicBarrier(2)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val results = executor.invokeAll(listOf(b to c, c to a).map { (from, to) ->
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runCatching { dependencies.add(project.id!!, from.number!!, to.number!!, owner) }
                        }
                    }, 30, TimeUnit.SECONDS).map { it.get() }
                    results.count { it.isSuccess } shouldBe 1
                    (results.single { it.isFailure }.exceptionOrNull() as IssueDependencyException).code shouldBe "cycle"
                    edges.findForIssue(c.id!!).size shouldBe 1
                } finally {
                    executor.shutdownNow()
                }
            }

            it("removes both incoming and outgoing edges on deletion and project moves") {
                val a = issue()
                val b = issue()
                val c = issue()
                dependencies.add(project.id!!, a.number!!, b.number!!, owner)
                dependencies.add(project.id!!, b.number!!, c.number!!, owner)
                issueService.deleteIssueCascade(b)
                dependencies.get(project.id!!, a.number!!, owner).blocking shouldBe emptyList()
                dependencies.get(project.id!!, c.number!!, owner).blockedBy shouldBe emptyList()
                dependencies.add(project.id!!, a.number!!, c.number!!, owner)
                issueService.moveIssue(c.id!!, otherProject.id!!, owner)
                dependencies.get(project.id!!, a.number!!, owner).blocking shouldBe emptyList()
                edges.findForIssue(c.id!!).size shouldBe 0
            }

            it("enforces API token issue-write scope and browser CSRF protection") {
                val a = issue()
                val b = issue()
                val secured = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java)).build()
                fun token(permission: ApiTokenPermission): String {
                    val raw = "dependency-${UUID.randomUUID()}"
                    val token = ApiToken(owner = owner, tokenHash = hashApiToken(raw), allRepositories = true,
                        expiresAt = java.time.Instant.now().plusSeconds(3600))
                    token.scopes.add(ApiTokenScope(apiToken = token, scopeGroup = ApiTokenScopeGroup.ISSUES, permission = permission))
                    tokenIds.add(tokens.save(token).id!!)
                    return raw
                }
                val readOnly = token(ApiTokenPermission.READ)
                val write = token(ApiTokenPermission.WRITE)
                val session = MockHttpSession()
                val securityContext = SecurityContextHolder.createEmptyContext().apply { authentication = auth() }
                session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext)
                val cookie = secured.perform(get(page(a)).session(session)).andExpect(status().isOk)
                    .andReturn().response.getCookie("XSRF-TOKEN") ?: error("Detail form did not issue a CSRF cookie")
                secured.perform(post("${api(a)}/${b.number}").session(session)).andExpect(status().isForbidden)
                secured.perform(post("${api(a)}/${b.number}").session(session).header("Yona-Token", "forged"))
                    .andExpect(status().isForbidden)
                secured.perform(post("${api(a)}/${b.number}").session(session).header("Authorization", "token forged"))
                    .andExpect(status().isForbidden)
                secured.perform(post("${api(a)}/${b.number}").session(session).header("Authorization", "Bearer forged"))
                    .andExpect(status().isUnauthorized)
                secured.perform(post("${api(a)}/${b.number}").session(session).header("Yona-Token", write))
                    .andExpect(status().isForbidden)
                dependencies.get(project.id!!, a.number!!, owner).blocking shouldBe emptyList()
                secured.perform(post("${api(a)}/${b.number}").session(session).cookie(cookie)
                    .header("X-XSRF-TOKEN", cookie.value)).andExpect(status().isCreated)
                secured.perform(delete("${api(a)}/${b.number}").session(session)).andExpect(status().isForbidden)
                secured.perform(delete("${api(a)}/${b.number}").session(session).cookie(cookie)
                    .header("X-XSRF-TOKEN", cookie.value)).andExpect(status().isNoContent)
                secured.perform(post("${api(a)}/${b.number}").header("Yona-Token", readOnly)).andExpect(status().isForbidden)
                secured.perform(post("${api(a)}/${b.number}").header("Yona-Token", write)).andExpect(status().isCreated)
                secured.perform(delete("${api(a)}/${b.number}").header("Yona-Token", readOnly)).andExpect(status().isForbidden)
                secured.perform(get(api(b)).header("Yona-Token", readOnly))
                    .andExpect(status().isOk).andExpect(jsonPath("$.blockedBy[0].number").value(a.number))
                secured.perform(post("${page(a)}/dependencies/delete").principal(auth())
                    .param("targetNumber", b.number.toString()).param("direction", "BLOCKS"))
                    .andExpect(status().isForbidden)
                dependencies.get(project.id!!, b.number!!, owner).blocked shouldBe true
                secured.perform(delete("${api(a)}/${b.number}").header("Yona-Token", write)).andExpect(status().isNoContent)
            }
        }
    }
}
