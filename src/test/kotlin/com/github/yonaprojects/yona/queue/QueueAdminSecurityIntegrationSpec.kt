package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import io.kotest.extensions.spring.SpringExtension
import jakarta.servlet.Filter
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class QueueAdminSecurityIntegrationSpec @Autowired constructor(
    private val context: WebApplicationContext,
    private val users: UserRepository,
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)
    private lateinit var mvc: MockMvc

    private fun rememberMe(state: UserState, loginSession: MockHttpSession? = null, redirect: String = "/"): Cookie {
        val login = "queue-security-${UUID.randomUUID()}"
        val salt = "queue-test-salt"
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        var hash = digest.digest("password1234".toByteArray())
        repeat(1023) { hash = digest.digest(hash) }
        users.save(User(loginId = login, name = login, email = "$login@example.invalid", state = state,
            password = Base64.getEncoder().encodeToString(hash), passwordSalt = salt))
        val loginRequest = post("/users/login").param("loginIdOrEmail", login)
            .param("password", "password1234").param("rememberMe", "true").with(csrf())
        if (loginSession != null) loginRequest.session(loginSession)
        val result = mvc.perform(loginRequest).andExpect(redirectedUrl(redirect)).andReturn()
        SecurityContextHolder.clearContext()
        return requireNotNull(result.response.getCookie("remember-me")).also {
            org.junit.jupiter.api.Assertions.assertTrue(it.maxAge > 0, "Login must issue a persistent remember-me cookie")
        }
    }

    init {
        beforeSpec {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java)).build()
        }
        afterTest { SecurityContextHolder.clearContext() }

        describe("Queue administrator authentication") {
            it("accepts the main login remember-me cookie without a session and rechecks current authority") {
                val cookie = rememberMe(UserState.SITE_ADMIN)
                mvc.perform(get(QUEUE_PAGE).servletPath(QUEUE_PAGE).cookie(cookie))
                    .andExpect(status().isOk).andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                mvc.perform(get("$QUEUE_API/jobs").servletPath("$QUEUE_API/jobs").cookie(cookie))
                    .andExpect(status().isOk)
            }
            it("rejects a remembered nonadministrator for pages and API commands") {
                val cookie = rememberMe(UserState.ACTIVE)
                mvc.perform(get(QUEUE_PAGE).servletPath(QUEUE_PAGE).cookie(cookie)).andExpect(status().isForbidden)
                mvc.perform(post("$QUEUE_API/jobs/1/abandon").servletPath("$QUEUE_API/jobs/1/abandon")
                    .cookie(cookie).with(csrf()).contentType("application/json")
                    .content("""{"commandId":"${UUID.randomUUID()}","reason":"No longer needed"}"""))
                    .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
            it("redirects pages to login and preserves the original request while APIs return JSON 401") {
                val result = mvc.perform(get(QUEUE_PAGE).servletPath(QUEUE_PAGE))
                    .andExpect(status().isFound).andExpect(redirectedUrl("/users/loginform")).andReturn()
                val saved = result.request.session.getAttribute("SPRING_SECURITY_SAVED_REQUEST") as org.springframework.security.web.savedrequest.SavedRequest
                org.junit.jupiter.api.Assertions.assertTrue(saved.redirectUrl.contains(QUEUE_PAGE))
                mvc.perform(get("$QUEUE_API/jobs").servletPath("$QUEUE_API/jobs"))
                    .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            }
            it("returns from login to the original queue filters using Spring saved requests") {
                val original = mvc.perform(get(QUEUE_PAGE).servletPath(QUEUE_PAGE).param("status", "FAILED"))
                    .andExpect(status().isFound).andReturn()
                val session = original.request.session as MockHttpSession
                val saved = session.getAttribute("SPRING_SECURITY_SAVED_REQUEST") as org.springframework.security.web.savedrequest.SavedRequest
                rememberMe(UserState.SITE_ADMIN, session, saved.redirectUrl)
                mvc.perform(get(saved.redirectUrl).servletPath(QUEUE_PAGE).session(session))
                    .andExpect(status().isOk)
            }
        }
    }
}
