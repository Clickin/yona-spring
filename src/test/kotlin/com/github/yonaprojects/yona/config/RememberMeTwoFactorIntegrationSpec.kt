package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.twofactor.TotpSecretEncryptor
import com.github.yonaprojects.yona.domain.twofactor.TwoFactorService
import com.github.yonaprojects.yona.domain.twofactor.TwoFactorTotpCredential
import com.github.yonaprojects.yona.domain.twofactor.TwoFactorTotpCredentialRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.time.SystemTimeProvider
import io.kotest.extensions.spring.SpringExtension
import jakarta.servlet.Filter
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

// The password step alone must never yield a persistent login for a 2FA account;
// remember-me is issued only after the second factor succeeds.
class RememberMeTwoFactorIntegrationSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val userRepository: UserRepository,
    private val totpCredentialRepository: TwoFactorTotpCredentialRepository,
    private val totpSecretEncryptor: TotpSecretEncryptor,
    private val twoFactorService: TwoFactorService,
) : AbstractIntegrationTest() {

    override fun extensions() = listOf(SpringExtension)

    private lateinit var mockMvc: MockMvc
    private val secret = "JBSWY3DPEHPK3PXP"

    private fun legacyHash(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        var hashed = digest.digest(password.toByteArray(Charsets.UTF_8))
        repeat(1023) { digest.reset(); hashed = digest.digest(hashed) }
        return Base64.getEncoder().encodeToString(hashed)
    }

    private fun twoFactorUser(): User {
        val salt = "salt-remember-2fa"
        val login = "remember2fa-${UUID.randomUUID()}"
        val user = userRepository.save(User(
            loginId = login, name = login, email = "$login@example.invalid",
            password = legacyHash("password1234", salt), passwordSalt = salt,
        ))
        totpCredentialRepository.save(TwoFactorTotpCredential(
            user = user, label = user.loginId, encryptedSecret = totpSecretEncryptor.encrypt(secret),
            enabled = true, activatedAt = Instant.now(),
        ))
        twoFactorService.refreshSummaryFlag(user)
        return user
    }

    private fun persistentRememberMe(response: MockHttpServletResponse): Cookie? =
        response.getCookie("remember-me")?.takeIf { it.maxAge != 0 && !it.value.isNullOrEmpty() }

    init {
        beforeSpec {
            mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters<DefaultMockMvcBuilder>(wac.getBean("springSecurityFilterChain", Filter::class.java))
                .build()
        }
        afterTest { SecurityContextHolder.clearContext() }

        describe("remember-me for a 2FA account") {
            it("is not issued by the password step") {
                val user = twoFactorUser()
                val response = mockMvc.perform(
                    post("/users/login").param("loginIdOrEmail", user.loginId).param("password", "password1234")
                        .param("rememberMe", "true").session(MockHttpSession()).with(csrf()),
                ).andExpect(redirectedUrl("/users/login/2fa")).andReturn().response
                assertTrue(persistentRememberMe(response) == null, "Password step issued a remember-me cookie before 2FA")
            }

            it("is issued after the second factor when it was requested at login") {
                val user = twoFactorUser()
                val session = MockHttpSession()
                mockMvc.perform(
                    post("/users/login").param("loginIdOrEmail", user.loginId).param("password", "password1234")
                        .param("rememberMe", "true").session(session).with(csrf()),
                ).andExpect(redirectedUrl("/users/login/2fa"))
                val code = DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(secret, SystemTimeProvider().time / 30)
                val response = mockMvc.perform(
                    post("/users/login/2fa/totp").param("code", code).session(session).with(csrf()),
                ).andExpect(redirectedUrl("/")).andReturn().response
                assertNotNull(persistentRememberMe(response), "Completing 2FA must issue the requested remember-me cookie")
            }
        }
    }
}
