package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.Pre2faAuthenticationToken
import com.github.yonaprojects.yona.config.SpaCsrfTokenRequestHandler
import com.github.yonaprojects.yona.config.YONA_REMEMBER_ME_KEY
import com.github.yonaprojects.yona.domain.user.UserState
import jakarta.persistence.EntityManager
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.util.function.Supplier

internal const val QUEUE_API = "/api/admin/queue/v1"
internal const val QUEUE_EVENTS = "$QUEUE_API/events"
internal const val QUEUE_PAGE = "/site/admin/queue"
internal const val QUEUE_AUTH_STARTED_ATTRIBUTE = "yona.queue.admin.auth-started"
internal const val QUEUE_ACTOR_ATTRIBUTE = "yona.queue.admin.actor"

internal data class QueueApiError(val code: String, val message: String)

@Component
internal class QueueAdminAccess(private val entityManager: EntityManager) {
    fun actor(request: HttpServletRequest): Long {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
        }
        if (authentication is Pre2faAuthenticationToken) {
            throw QueueHttpFailure(403, "FORBIDDEN", "Complete authentication before accessing the queue")
        }
        // Scalar query bypasses cached principal authorities and managed User instances.
        val query = entityManager.createQuery(
            "select u.id from User u where u.loginId = :login and u.state = :state", Long::class.javaObjectType,
        ).setParameter("login", authentication.name).setParameter("state", UserState.SITE_ADMIN)
            .setMaxResults(1)
        if (request.servletPath == QUEUE_EVENTS) query.setHint("jakarta.persistence.query.timeout", 1_000)
        return query.resultList.firstOrNull()
            ?: throw QueueHttpFailure(403, "FORBIDDEN", "Current site administrator access is required")
    }
}

internal class QueueHttpFailure(val status: Int, val code: String, override val message: String) : RuntimeException(message)

// Unlike the main chain, deliberately excludes Pre2faGate, ApiToken, formLogin, oauth2,
// saml2 and httpBasic. Only session/remember-me authentication plus live admin checks apply.
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class QueueAdminSecurity(private val access: QueueAdminAccess, private val users: UserDetailsService) {
    private val json = JsonMapper.builder().build()
    private val requestCache = HttpSessionRequestCache()

    @Bean
    @Order(0)
    fun queueAdminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfHandler = SpaCsrfTokenRequestHandler()
        // Keep this filter local to this chain, not a globally registered servlet filter.
        val authorization = object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                try {
                    val events = request.servletPath == QUEUE_EVENTS
                    val started = if (events) System.nanoTime() else 0L
                    if (events) request.setAttribute(QUEUE_AUTH_STARTED_ATTRIBUTE, started)
                    val actor = access.actor(request)
                    if (events && System.nanoTime() - started > 1_000_000_000L) {
                        throw QueueHttpFailure(500, "INTERNAL_ERROR", "Queue authorization is temporarily unavailable")
                    }
                    request.setAttribute(QUEUE_ACTOR_ATTRIBUTE, actor)
                } catch (failure: QueueHttpFailure) {
                    error(request, response, failure.status, failure.code, failure.message)
                    return
                } catch (_: Exception) {
                    error(request, response, 500, "INTERNAL_ERROR", "Queue authorization is temporarily unavailable")
                    return
                }
                chain.doFilter(request, response)
            }
        }
        http.securityMatcher(QUEUE_API, "$QUEUE_API/**", QUEUE_PAGE, "$QUEUE_PAGE/**")
            .requestCache { it.requestCache(requestCache) }
            .rememberMe { it.key(YONA_REMEMBER_ME_KEY).rememberMeParameter("rememberMe").userDetailsService(users) }
            .headers { it.frameOptions { frames -> frames.sameOrigin() } }
            .securityContext { it.securityContextRepository(HttpSessionSecurityContextRepository()) }
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(object : CsrfTokenRequestHandler {
                        override fun handle(request: HttpServletRequest, response: HttpServletResponse, csrfToken: Supplier<CsrfToken>) {
                            csrfHandler.handle(request, response, csrfToken)
                            csrfToken.get() // REST snapshots have no Thymeleaf form to materialize the cookie.
                        }

                        // Kotlin delegation does not forward this Java default method.
                        override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? =
                            csrfHandler.resolveCsrfTokenValue(request, csrfToken)
                    })
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    error(request, response, 401, "UNAUTHENTICATED", "A signed-in session is required")
                }.accessDeniedHandler { request, response, _ ->
                    error(request, response, 403, "FORBIDDEN", "Access denied or invalid CSRF token")
                }
            }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .addFilterAfter(authorization, RememberMeAuthenticationFilter::class.java)
        return http.build()
    }

    private fun error(request: HttpServletRequest, response: HttpServletResponse, status: Int, code: String, message: String) {
        if (request.servletPath == QUEUE_PAGE || request.servletPath.startsWith("$QUEUE_PAGE/")) {
            response.setHeader("Cache-Control", "no-store")
            if (status == 401) {
                requestCache.saveRequest(request, response)
                response.sendRedirect("${request.contextPath}/users/loginform")
            } else {
                response.sendError(status, message)
            }
            return
        }
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        json.writeValue(response.outputStream, QueueApiError(code, message))
    }
}
