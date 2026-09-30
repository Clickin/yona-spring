package com.github.yonaprojects.yona.domain.user

import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.LockedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * yona의 UserApp.authenticateWithLdap() 성공 분기(LDAP 인증 자체가 아니라
 * "인증된 LDAP 사용자 정보를 로컬 User와 어떻게 맞출 것인가")에 대응.
 * LDAP 디렉터리 바인딩(LdapService)과 분리해 실제 LDAP 서버 없이 단위테스트 가능하다.
 */
@Service
class LdapUserProvisioningService(
    private val userRepository: UserRepository,
    private val passwordEncodingService: PasswordEncodingService
) {
    @Transactional
    fun reconcile(ldapUser: LdapUser, rawPassword: String): User {
        val existing = userRepository.findByEmail(ldapUser.email).orElse(null)
        return if (existing == null) {
            createNewUser(ldapUser, rawPassword)
        } else {
            syncExistingUser(existing, ldapUser, rawPassword)
        }
    }

    private fun createNewUser(ldapUser: LdapUser, rawPassword: String): User {
        val user = User(
            loginId = ldapUser.loginId,
            name = ldapUser.fullDisplayName,
            email = ldapUser.email,
            password = passwordEncodingService.encode(rawPassword),
            passwordSalt = null,
            isGuest = ldapUser.isGuestUser,
            state = UserState.ACTIVE
        )
        if (!ldapUser.englishName.isNullOrBlank()) {
            user.englishName = ldapUser.englishName
        }
        return userRepository.save(user)
    }

    private fun syncExistingUser(user: User, ldapUser: LdapUser, rawPassword: String): User {
        // Reject before syncing credentials: provider account checks happen after reconcile returns.
        if (user.state == UserState.LOCKED) {
            throw LockedException("계정이 잠겨 있습니다.")
        }
        if (user.state == UserState.DELETED) {
            throw DisabledException("탈퇴한 계정입니다.")
        }
        if (user.lockedUntil?.isAfter(Instant.now()) == true) {
            throw LockedException("로그인 실패 횟수가 많아 계정이 일시적으로 잠겼습니다. 잠시 후 다시 시도하거나 관리자에게 문의하세요.")
        }

        val userId = user.id!!
        if (!passwordEncodingService.matches(rawPassword, user.password, user.passwordSalt) ||
            passwordEncodingService.needsUpgrade(user.password)
        ) {
            userRepository.replacePasswordIfUnchanged(
                userId, user.password, user.passwordSalt, passwordEncodingService.encode(rawPassword)
            )
        }
        userRepository.syncLdapProfile(
            userId, ldapUser.fullDisplayName, ldapUser.englishName?.takeUnless { it.isBlank() }, ldapUser.isGuestUser
        )
        return userRepository.findById(userId).orElseThrow()
    }
}
