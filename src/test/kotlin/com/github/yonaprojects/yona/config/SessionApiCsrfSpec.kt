package com.github.yonaprojects.yona.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter

class SessionApiCsrfSpec : StringSpec({
    "session mutations require CSRF across API routes even with forged token headers" {
        val repository = CookieCsrfTokenRepository.withHttpOnlyFalse()
        val filter = CsrfFilter(repository).apply {
            setRequireCsrfProtectionMatcher(sessionApiMutationMatcher)
            setRequestHandler(SpaCsrfTokenRequestHandler())
        }
        val session = MockHttpSession().apply {
            setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("user", "", emptyList())))
        }
        for (path in listOf("issues/1/dependencies", "issues/1/pin", "issues/saved-views", "settings/archive", "issues")) {
            for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                val request = MockHttpServletRequest(method, "/yona/api/v1/projects/owner/project/$path").apply {
                    contextPath = "/yona"
                    setSession(session)
                    addHeader("Authorization", "Bearer forged")
                    addHeader("Yona-Token", "forged")
                }
                val response = MockHttpServletResponse()
                var invoked = false
                filter.doFilter(request, response) { _, _ -> invoked = true }
                response.status shouldBe 403
                invoked shouldBe false
            }
        }
        for (withSession in listOf(false, true)) {
            val request = MockHttpServletRequest(if (withSession) "POST" else "DELETE", "/api/v1/projects/owner/project/issues")
            if (withSession) {
                request.setSession(session)
                val token = repository.generateToken(request)
                val cookieResponse = MockHttpServletResponse()
                repository.saveToken(token, request, cookieResponse)
                request.setCookies(*cookieResponse.cookies)
                request.addHeader(token.headerName, token.token)
            }
            var invoked = false
            filter.doFilter(request, MockHttpServletResponse()) { _, _ -> invoked = true }
            invoked shouldBe true
        }
        val read = MockHttpServletRequest("GET", "/api/v1/projects/owner/project/issues").apply { setSession(session) }
        var invoked = false
        filter.doFilter(read, MockHttpServletResponse()) { _, _ -> invoked = true }
        invoked shouldBe true
    }
})
