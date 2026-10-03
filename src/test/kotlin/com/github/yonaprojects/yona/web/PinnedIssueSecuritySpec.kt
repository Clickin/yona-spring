package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.config.oauth2server.JwkKeyPairProvider
import com.github.yonaprojects.yona.domain.apitoken.ApiToken
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenPermission
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenRepository
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenScope
import com.github.yonaprojects.yona.domain.apitoken.ApiTokenScopeGroup
import com.github.yonaprojects.yona.domain.apitoken.hashApiToken
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date

@Transactional
class PinnedIssueSecuritySpec @Autowired constructor(
    private val context: WebApplicationContext,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val memberships: ProjectUserRepository,
    private val roles: RoleRepository,
    private val issues: IssueRepository,
    private val tokens: ApiTokenRepository,
    private val keys: JwkKeyPairProvider
) : AbstractIntegrationTest() {
    private val mvc by lazy {
        MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    init {
        describe("Pin mutation authentication boundaries") {
            it("requires CSRF for ambient sessions even with spoofed headers, while accepting scoped stateless tokens") {
                val manager = users.save(User(loginId = "pin-csrf-manager", name = "Manager", email = "pin-csrf@example.test"))
                val project = projects.save(Project(owner = manager.loginId!!, name = "pin-csrf", projectScope = ProjectScope.PUBLIC))
                val role = roles.findById(RoleType.MANAGER.roleType).orElseGet {
                    roles.save(Role(id = RoleType.MANAGER.roleType, name = "manager"))
                }
                manager.projectUsers.add(memberships.save(ProjectUser(user = manager, project = project, role = role)))
                val issue = issues.save(Issue(project = project, number = 1, title = "CSRF protected pin"))
                val api = "/api/v1/projects/${project.owner}/${project.name}/issues/1/pin"
                val session = MockHttpSession()
                session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextImpl(UsernamePasswordAuthenticationToken(manager.loginId, "password",
                        AuthorityUtils.createAuthorityList("ROLE_ACTIVE"))))
                fun request(pinned: Boolean = true) = put(api).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":$pinned}")

                mvc.perform(request().session(session)).andExpect(status().isForbidden)
                mvc.perform(put(URI.create(api.removeSuffix("/pin") + "/%70in")).session(session)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}")).andExpect(status().isForbidden)
                mvc.perform(request().session(session).header("Yona-Token", "spoofed")).andExpect(status().isForbidden)
                mvc.perform(request().session(session).header("Authorization", "token spoofed")).andExpect(status().isForbidden)
                mvc.perform(request().session(session).header("Authorization", "Bearer spoofed")).andExpect(status().is4xxClientError)
                mvc.perform(post("/${project.owner}/${project.name}/issue/1/pin").session(session)
                    .header("Yona-Token", "spoofed").param("pinned", "true")).andExpect(status().isForbidden)
                mvc.perform(post(URI.create("/${project.owner}/${project.name}/issue/1/%70in")).session(session)
                    .header("Yona-Token", "spoofed").param("pinned", "true")).andExpect(status().isForbidden)
                issues.findById(issue.id!!).orElseThrow().pinnedAt shouldBe null
                mvc.perform(request().session(session).with(csrf())).andExpect(status().isOk)
                issues.findById(issue.id!!).orElseThrow().pinnedAt shouldNotBe null

                val raw = "pin-scoped-write-token"
                val token = ApiToken(owner = users.findById(manager.id!!).orElseThrow(), tokenHash = hashApiToken(raw),
                    allRepositories = true, expiresAt = Instant.now().plusSeconds(3600))
                token.scopes.add(ApiTokenScope(apiToken = token, scopeGroup = ApiTokenScopeGroup.ISSUES, permission = ApiTokenPermission.WRITE))
                tokens.save(token)
                mvc.perform(request(false).header("Yona-Token", raw)).andExpect(status().isOk)
                issues.findById(issue.id!!).orElseThrow().pinnedAt shouldBe null

                val now = Instant.now()
                val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), JWTClaimsSet.Builder()
                    .subject(manager.loginId).issuer("http://localhost:8080").audience("http://localhost:8080/api/v1")
                    .claim("scope", "issues:write").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build())
                jwt.sign(RSASSASigner(keys.keyPair.private as RSAPrivateKey))
                mvc.perform(request().header("Authorization", "Bearer ${jwt.serialize()}")).andExpect(status().isOk)
                issues.findById(issue.id!!).orElseThrow().pinnedAt shouldNotBe null
            }
        }
    }
}
