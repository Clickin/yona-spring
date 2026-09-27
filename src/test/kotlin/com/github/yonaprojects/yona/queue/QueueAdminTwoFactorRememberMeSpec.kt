package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.twofactor.TotpSecretEncryptor
import com.github.yonaprojects.yona.domain.twofactor.TwoFactorService
import com.github.yonaprojects.yona.domain.twofactor.TwoFactorTotpCredential
import com.github.yonaprojects.yona.domain.twofactor.TwoFactorTotpCredentialRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import io.kotest.extensions.spring.SpringExtension
import jakarta.servlet.Filter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

// A site administrator who knows only the password must not reach queue administration
// by abandoning the 2FA step and replaying whatever cookie the password step returned.
class QueueAdminTwoFactorRememberMeSpec @Autowired constructor(
    private val context: WebApplicationContext,
    private val users: UserRepository,
    private val totpCredentials: TwoFactorTotpCredentialRepository,
    private val totpSecretEncryptor: TotpSecretEncryptor,
    private val twoFactorService: TwoFactorService,
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)
    private lateinit var mvc: MockMvc

    init {
        beforeSpec {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java)).build()
        }
        afterTest { SecurityContextHolder.clearContext() }

        describe("Queue administration behind 2FA") {
            it("rejects cookies from an unfinished 2FA login") {
                val login = "queue-2fa-${UUID.randomUUID()}"
                val salt = "queue-2fa-salt"
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(salt.toByteArray())
                var hash = digest.digest("password1234".toByteArray())
                repeat(1023) { hash = digest.digest(hash) }
                val admin = users.save(User(loginId = login, name = login, email = "$login@example.invalid",
                    state = UserState.SITE_ADMIN, password = Base64.getEncoder().encodeToString(hash), passwordSalt = salt))
                totpCredentials.save(TwoFactorTotpCredential(user = admin, label = login,
                    encryptedSecret = totpSecretEncryptor.encrypt("JBSWY3DPEHPK3PXP"), enabled = true, activatedAt = Instant.now()))
                twoFactorService.refreshSummaryFlag(admin)

                val passwordStep = mvc.perform(post("/users/login").param("loginIdOrEmail", login)
                    .param("password", "password1234").param("rememberMe", "true")
                    .session(MockHttpSession()).with(csrf()))
                    .andExpect(redirectedUrl("/users/login/2fa")).andReturn()
                SecurityContextHolder.clearContext()

                // Drop the session; replay only the cookies returned by the password step.
                val cookies = passwordStep.response.cookies.filter { it.maxAge != 0 && !it.value.isNullOrEmpty() }
                val request = get("$QUEUE_API/jobs").servletPath("$QUEUE_API/jobs")
                if (cookies.isNotEmpty()) request.cookie(*cookies.toTypedArray())
                mvc.perform(request).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            }
        }
    }
}
