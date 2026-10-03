package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.config.oauth2server.JwkKeyPairProvider
import com.github.yonaprojects.yona.domain.apitoken.ApiToken
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenPermission
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenRepository
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenScope
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenScopeGroup
import com.github.yonaprojects.yona.domain.apitoken.hashApiToken
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import jakarta.servlet.Filter
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date

class ProjectArchiveCsrfIntegrationSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val users: UserRepository,
    private val projects: ProjectRepository,
    private val issues: com.github.yonaprojects.yona.domain.issue.IssueRepository,
    private val attachments: com.github.yonaprojects.yona.domain.attachment.AttachmentRepository,
    private val tokens: ApiTokenRepository,
    private val keys: JwkKeyPairProvider
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)
    private lateinit var mvc: MockMvc
    private lateinit var owner: User
    private lateinit var project: Project
    private lateinit var token: ApiToken
    private val rawToken = "archive-csrf-administration-token"
    private val endpoint get() = "/api/v1/projects/${owner.loginId}/${project.name}/settings/archive"

    private fun session(): MockHttpSession = MockHttpSession().apply {
        val auth = UsernamePasswordAuthenticationToken(owner.loginId, "unused", listOf(SimpleGrantedAuthority("ROLE_USER")))
        setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(auth))
    }

    private fun bearer(): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder().subject(owner.loginId).issuer("http://localhost:8080")
            .audience("http://localhost:8080/api/v1").claim("scope", "administration:write")
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims).apply {
            sign(RSASSASigner(keys.keyPair.private as RSAPrivateKey))
        }.serialize()
    }

    init {
        beforeSpec {
            mvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters<DefaultMockMvcBuilder>(wac.getBean("springSecurityFilterChain", Filter::class.java)).build()
            owner = users.save(User(loginId = "archive-csrf-admin", name = "Archive admin", email = "archive-csrf@example.com", state = UserState.SITE_ADMIN))
            project = projects.save(Project(owner = owner.loginId, name = "archive-csrf-repo"))
            token = ApiToken(owner = owner, tokenHash = hashApiToken(rawToken), allRepositories = true, expiresAt = Instant.now().plusSeconds(3600))
            token.scopes.add(ApiTokenScope(apiToken = token, scopeGroup = ApiTokenScopeGroup.ADMINISTRATION, permission = ApiTokenPermission.WRITE))
            token = tokens.save(token)
        }
        beforeTest {
            project = projects.findById(project.id!!).orElseThrow().apply { archivedAt = null }
            projects.save(project)
        }
        afterSpec {
            tokens.delete(token)
            projects.deleteById(project.id!!)
            users.deleteById(owner.id!!)
        }

        it("global issue writes reject archived ownership without lazy-loading outside a session") {
            project.archivedAt = Instant.now()
            projects.save(project)
            val issue = issues.save(com.github.yonaprojects.yona.domain.issue.Issue(project = project, title = "Archived favorite"))
            try {
                val csrf = "archive-favorite-csrf"
                val response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/-_-api/v1/favoriteIssues/${issue.id}").session(session())
                    .cookie(Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)).andReturn().response
                response.status shouldBe 403
            } finally {
                issues.delete(issue)
            }
        }

        it("mapped logo deletion keeps archived attachments for encoded paths and servlet contexts") {
            project.archivedAt = Instant.now()
            projects.save(project)
            val attachment = attachments.save(com.github.yonaprojects.yona.domain.attachment.Attachment(
                name = "archive-logo.txt", hash = "archive-encoded-logo", ownerLoginId = owner.loginId,
                containerType = com.github.yonaprojects.yona.domain.enumeration.ResourceType.PROJECT,
                containerId = project.id.toString()
            ))
            try {
                for (context in listOf("", "/yona", "/hg", "/svn", "/files")) {
                    for (path in listOf("/files/", "/%66iles/")) {
                        val csrf = "archive-logo-csrf"
                        val result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post(java.net.URI.create("$context$path${attachment.id}")).contextPath(context)
                            .session(session()).cookie(Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)
                            .param("_method", "delete")).andReturn()
                        (result.handler as org.springframework.web.method.HandlerMethod).beanType shouldBe
                            com.github.yonaprojects.yona.web.AttachmentController::class.java
                        result.response.status shouldBe 403
                        attachments.existsById(attachment.id!!) shouldBe true
                    }
                }
                project.archivedAt = null
                projects.save(project)
                val csrf = "archive-logo-restored-csrf"
                val response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post(java.net.URI.create("/yona/%66iles/${attachment.id}")).contextPath("/yona")
                    .session(session()).cookie(Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)
                    .param("_method", "delete")).andReturn().response
                response.status shouldBe 200
                attachments.existsById(attachment.id!!) shouldBe false
            } finally {
                attachments.deleteById(attachment.id!!)
            }
        }

        it("encoded Mercurial and SVN writes reach the archive filter rather than the repository") {
            project.archivedAt = Instant.now()
            for (context in listOf("", "/yona")) {
                for ((vcs, path, method) in listOf(
                    Triple("HG", "/%68g/${owner.loginId}/${project.name}?cmd=pushkey", "GET"),
                    Triple("HG", "/%68g/${owner.loginId}/${project.name}?cmd=unbundle", "POST"),
                    Triple("SVN", "/%73vn/${owner.loginId}/${project.name}", "PUT")
                )) {
                    project.vcs = vcs
                    projects.save(project)
                    val csrf = "archive-protocol-csrf"
                    val response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(method), java.net.URI.create("$context$path"))
                        .contextPath(context).session(session())
                        .cookie(Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)).andReturn().response
                    response.status shouldBe 403
                    response.errorMessage shouldBe "Archived project is read-only"
                }
            }
        }

        it("session mutation without CSRF rejects before changing the project") {
            val response = mvc.perform(patch(endpoint).session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andReturn().response
            response.status shouldBe 403
            projects.findById(project.id!!).orElseThrow().isArchived shouldBe false
        }

        it("forged token headers do not authorize a session write without CSRF") {
            for ((header, value) in listOf("Yona-Token" to "forged", "Authorization" to "token forged", "Authorization" to "Bearer forged")) {
                val response = mvc.perform(patch(endpoint).session(session()).header(header, value)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andReturn().response
                response.status shouldBe if (value.startsWith("Bearer ")) 401 else 403
                projects.findById(project.id!!).orElseThrow().isArchived shouldBe false
            }
        }

        it("encoded archive paths cannot bypass session CSRF") {
            val encoded = java.net.URI.create(endpoint.replace("/archive", "/%61rchive"))
            val response = mvc.perform(patch(encoded).session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andReturn().response
            response.status shouldBe 403
            projects.findById(project.id!!).orElseThrow().isArchived shouldBe false
        }

        it("session mutation with the cookie/header CSRF pair succeeds") {
            val csrf = "archive-session-csrf-token"
            val response = mvc.perform(patch(endpoint).session(session()).cookie(Cookie("XSRF-TOKEN", csrf))
                .header("X-XSRF-TOKEN", csrf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\":true}")).andReturn().response
            response.status shouldBe 200
            projects.findById(project.id!!).orElseThrow().isArchived shouldBe true
        }

        it("stateless scoped PAT can archive without a browser CSRF token") {
            val response = mvc.perform(patch(endpoint).header("Yona-Token", rawToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andReturn().response
            response.status shouldBe 200
            projects.findById(project.id!!).orElseThrow().isArchived shouldBe true
        }

        it("stateless signed OAuth Bearer can archive without a browser CSRF token") {
            val response = mvc.perform(patch(endpoint).header("Authorization", "Bearer ${bearer()}")
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andReturn().response
            response.status shouldBe 200
            projects.findById(project.id!!).orElseThrow().isArchived shouldBe true
        }
    }
}
