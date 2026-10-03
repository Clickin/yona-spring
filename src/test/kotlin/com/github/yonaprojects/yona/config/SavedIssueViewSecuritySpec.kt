package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.apitoken.*
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.net.URI

@Transactional
class SavedIssueViewSecuritySpec @Autowired constructor(
    private val context: WebApplicationContext,
    private val users: UserRepository,
    private val projects: ProjectRepository,
    private val tokens: ApiTokenRepository
) : AbstractIntegrationTest() {
    init {
        it("requires CSRF for session writes despite spoofed headers but accepts scoped stateless tokens") {
            val owner = users.save(User(loginId = "saved-csrf-owner", name = "Owner", email = "saved-csrf@example.test"))
            val project = projects.save(Project(owner = owner.loginId, name = "saved-csrf", projectScope = ProjectScope.PUBLIC))
            val mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
            val path = "/api/v1/projects/${owner.loginId}/${project.name}/issues/saved-views"
            val create = """{"name":"Session","visibility":"PERSONAL"}"""
            listOf(post(path), patch("$path/1"), delete("$path/1")).forEach { request ->
                mvc.perform(request.with(user(owner.loginId)).contentType(MediaType.APPLICATION_JSON).content(create))
                    .andExpect(status().isForbidden)
            }
            mvc.perform(post(URI.create(path.replace("saved-views", "%73aved-views")))
                .with(user(owner.loginId)).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isForbidden)
            mvc.perform(post(path).with(user(owner.loginId)).header("Yona-Token", "spoof")
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isForbidden)
            mvc.perform(post(path).with(user(owner.loginId)).header("Authorization", "token spoof")
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isForbidden)
            mvc.perform(post(path).with(user(owner.loginId)).header("Authorization", "Bearer spoof")
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isUnauthorized)
            mvc.perform(post(path).with(user(owner.loginId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isCreated)

            listOf(ApiTokenPermission.READ, ApiTokenPermission.WRITE).forEach { permission ->
                val raw = "saved-view-smoke-$permission"
                val token = ApiToken(owner = owner, tokenHash = hashApiToken(raw), allRepositories = true,
                    expiresAt = Instant.now().plusSeconds(3600))
                token.scopes.add(ApiTokenScope(apiToken = token, scopeGroup = ApiTokenScopeGroup.ISSUES, permission = permission))
                tokens.saveAndFlush(token)
                mvc.perform(get(path).header("Yona-Token", raw)).andExpect(status().isOk)
                mvc.perform(post(path).header("Yona-Token", raw).contentType(MediaType.APPLICATION_JSON).content(create))
                    .andExpect(if (permission == ApiTokenPermission.WRITE) status().isCreated else status().isForbidden)
            }
        }
    }
}
