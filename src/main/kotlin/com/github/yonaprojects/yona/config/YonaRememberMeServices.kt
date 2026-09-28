package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.domain.twofactor.TwoFactorService
import com.github.yonaprojects.yona.domain.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices

// Rotate all old cookies: their signatures cannot distinguish completed logins from pre-2FA logins.
const val YONA_REMEMBER_ME_KEY = "yonaRememberMe2faKey"

class YonaRememberMeServices(
    userDetailsService: UserDetailsService,
    private val userRepository: UserRepository,
    private val twoFactorService: TwoFactorService,
) : TokenBasedRememberMeServices(YONA_REMEMBER_ME_KEY, userDetailsService) {
    init {
        setParameter("rememberMe")
    }

    override fun onLoginSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        // The authentication filter calls this BEFORE the success handler installs the 2FA gate.
        val user = userRepository.findByLoginId(authentication.name).orElse(null)
        if (user != null && twoFactorService.isTwoFactorEnabled(user)) return
        super.onLoginSuccess(request, response, authentication)
    }

    fun deferLogin(request: HttpServletRequest, response: HttpServletResponse) {
        request.session.setAttribute(PENDING_REMEMBER_ME, rememberMeRequested(request, parameter))
        loginFail(request, response)
    }

    fun completeTwoFactorLogin(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val session = request.getSession(false) ?: return
        val requested = session.getAttribute(PENDING_REMEMBER_ME) == true
        session.removeAttribute(PENDING_REMEMBER_ME)
        if (requested) {
            // The second-factor request has no rememberMe parameter; use the saved choice, not its input.
            super.onLoginSuccess(request, response, authentication)
        }
    }

    private companion object {
        const val PENDING_REMEMBER_ME = "YONA_PENDING_REMEMBER_ME"
    }
}
