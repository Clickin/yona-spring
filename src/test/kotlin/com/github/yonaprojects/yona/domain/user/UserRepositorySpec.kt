package com.github.yonaprojects.yona.domain.user

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.config.YonaAuthenticationProvider
import com.github.yonaprojects.yona.domain.site.SiteService
import com.github.yonaprojects.yona.domain.attachment.Attachment
import com.github.yonaprojects.yona.domain.attachment.AttachmentRepository
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Instant

class UserRepositorySpec @Autowired constructor(
    private val userRepository: UserRepository,
    private val siteService: SiteService,
    private val attachmentRepository: AttachmentRepository
) : AbstractIntegrationTest() {

    init {
        describe("UserRepository") {
            beforeEach {
                userRepository.deleteAll()
                attachmentRepository.deleteAll()
            }

            it("사용자를 정상적으로 저장하고 조회할 수 있어야 한다") {
                // Given
                val user = User(
                    name = "홍길동",
                    loginId = "gildong",
                    email = "gildong@example.com",
                    createdDate = Instant.now()
                )

                // When
                val savedUser = userRepository.save(user)

                // Then
                savedUser.id shouldNotBe null
                
                val foundUser = userRepository.findByLoginId("gildong").orElse(null)
                foundUser shouldNotBe null
                foundUser.name shouldBe "홍길동"
                foundUser.email shouldBe "gildong@example.com"
            }

            it("다시 조회한 사용자의 아바타는 최신 사용자 아바타를 사용하고 교체 삭제를 반영한다") {
                val user = userRepository.save(User(loginId = "avatar-owner", name = "Synthetic avatar owner", email = "avatar@example.invalid"))
                val other = userRepository.save(User(loginId = "avatar-other", name = "Other", email = "other-avatar@example.invalid"))
                fun attachment(type: ResourceType, owner: User) = attachmentRepository.saveAndFlush(
                    Attachment(name = "synthetic-avatar.png", hash = "synthetic-avatar", containerType = type,
                        containerId = owner.id.toString(), mimeType = "image/png")
                )
                val first = attachment(ResourceType.USER_AVATAR, user)
                userRepository.findById(user.id!!).orElseThrow().avatarUrl shouldBe "/files/${first.id}"
                val latest = attachment(ResourceType.USER_AVATAR, user)
                attachment(ResourceType.NOT_A_RESOURCE, user)
                attachment(ResourceType.USER_AVATAR, other)
                userRepository.findByLoginId(user.loginId).orElseThrow().avatarUrl shouldBe "/files/${latest.id}"
                userRepository.searchUsers("avatar-owner", PageRequest.of(0, 10)).content.single().avatarUrl shouldBe "/files/${latest.id}"
                userRepository.findUsersForAdmin(UserState.ACTIVE, "avatar-owner", PageRequest.of(0, 10)).content.single().avatarUrl shouldBe "/files/${latest.id}"
                attachmentRepository.delete(latest)
                userRepository.findById(user.id!!).orElseThrow().avatarUrl shouldBe "/files/${first.id}"
                attachmentRepository.delete(first)
                userRepository.findById(user.id!!).orElseThrow().avatarUrl shouldBe "/assets/images/default-avatar-128.png"
            }

            it("레거시 로그인 검증 후 재해싱 도중 비밀번호가 리셋되면 새 자격증명이 유지되어야 한다") {
                val encoder = PasswordEncodingService()
                val rawPassword = "synthetic-legacy-password"
                val salt = "synthetic-legacy-salt"
                val loginId = "legacy-login-reset-race"
                val user = userRepository.save(
                    User(
                        loginId = loginId, name = "Synthetic user", email = "$loginId@example.com",
                        password = PasswordEncodingService.legacyHash(rawPassword, salt), passwordSalt = salt,
                        failedLoginAttempts = 3
                    )
                )
                val resetService = PasswordResetServiceImpl(userRepository, encoder)
                val resetToken = resetService.generateResetHash(loginId)
                resetService.addHashToResetTable(loginId, resetToken)
                val rehashEncoder = spyk(encoder)
                // Deterministic interleaving: reset after the login's credential snapshot was read.
                every { rehashEncoder.encode(rawPassword) } answers {
                    resetService.resetPassword(resetToken, "synthetic-reset-password") shouldBe true
                    encoder.encode(rawPassword)
                }
                val ldapService = mockk<LdapService>()
                every { ldapService.enabled } returns false
                val provider = YonaAuthenticationProvider(
                    UserDetailsServiceImpl(userRepository), ldapService,
                    LdapUserProvisioningService(userRepository, encoder), rehashEncoder, userRepository
                )

                provider.authenticate(UsernamePasswordAuthenticationToken(loginId, rawPassword)).isAuthenticated shouldBe true

                val persisted = userRepository.findById(user.id!!).orElseThrow()
                persisted.passwordSalt shouldBe null
                encoder.matches("synthetic-reset-password", persisted.password, persisted.passwordSalt) shouldBe true
                encoder.matches(rawPassword, persisted.password, persisted.passwordSalt) shouldBe false
                persisted.failedLoginAttempts shouldBe 0
            }

            it("비밀번호 업그레이드는 검증한 해시와 salt가 모두 그대로일 때만 적용되어야 한다") {
                val encoder = PasswordEncodingService()
                val salt = "synthetic-legacy-salt"
                val legacyHash = PasswordEncodingService.legacyHash("legacy-password", salt)
                val user = userRepository.save(
                    User(
                        loginId = "legacy-cas", name = "Synthetic legacy user", email = "legacy-cas@example.com",
                        password = legacyHash, passwordSalt = salt
                    )
                )
                val id = user.id!!
                val upgradeHash = encoder.encode("legacy-password")
                val resetHash = encoder.encode("reset-password")
                user.password = resetHash
                user.passwordSalt = null
                userRepository.saveAndFlush(user)

                userRepository.replacePasswordIfUnchanged(id, legacyHash, salt, upgradeHash) shouldBe 0
                var persisted = userRepository.findById(id).orElseThrow()
                persisted.password shouldBe resetHash
                persisted.passwordSalt shouldBe null
                encoder.matches("reset-password", persisted.password, persisted.passwordSalt) shouldBe true
                encoder.matches("legacy-password", persisted.password, persisted.passwordSalt) shouldBe false

                // A changed salt alone must also invalidate the credential snapshot.
                persisted.password = legacyHash
                persisted.passwordSalt = "changed-salt"
                userRepository.saveAndFlush(persisted)
                userRepository.replacePasswordIfUnchanged(id, legacyHash, salt, upgradeHash) shouldBe 0
                persisted = userRepository.findById(id).orElseThrow()
                persisted.password shouldBe legacyHash
                persisted.passwordSalt shouldBe "changed-salt"

                userRepository.replacePasswordIfUnchanged(id, legacyHash, "changed-salt", upgradeHash) shouldBe 1
                persisted = userRepository.findById(id).orElseThrow()
                persisted.password shouldBe upgradeHash
                persisted.passwordSalt shouldBe null
                encoder.matches("legacy-password", persisted.password, persisted.passwordSalt) shouldBe true

                userRepository.replacePasswordIfUnchanged(id, upgradeHash, null, resetHash) shouldBe 1
                persisted = userRepository.findById(id).orElseThrow()
                persisted.password shouldBe resetHash
                persisted.passwordSalt shouldBe null
            }

            it("실패 횟수와 LDAP 프로필 갱신은 새로 설정된 비밀번호와 salt를 덮어쓰지 않아야 한다") {
                val encoder = PasswordEncodingService()
                val user = userRepository.save(
                    User(
                        loginId = "credential-safe-updates", name = "Old name",
                        email = "credential-safe-updates@example.com",
                        password = PasswordEncodingService.legacyHash("old-password", "old-salt"),
                        passwordSalt = "old-salt", englishName = "Existing English Name"
                    )
                )
                val id = user.id!!
                val resetHash = encoder.encode("reset-password")
                user.password = resetHash
                user.passwordSalt = null
                userRepository.saveAndFlush(user)

                val lockDeadline = Instant.parse("2030-01-01T00:15:00Z")
                repeat(5) {
                    userRepository.recordLoginFailure(id, 5, lockDeadline)
                }
                userRepository.syncLdapProfile(id, "LDAP name", null, true)
                var persisted = userRepository.findById(id).orElseThrow()
                persisted.password shouldBe resetHash
                persisted.passwordSalt shouldBe null
                persisted.failedLoginAttempts shouldBe 5
                persisted.lockedUntil shouldBe lockDeadline
                persisted.name shouldBe "LDAP name"
                persisted.englishName shouldBe "Existing English Name"
                persisted.isGuest shouldBe true

                userRepository.resetLoginFailures(id)
                persisted = userRepository.findById(id).orElseThrow()
                persisted.password shouldBe resetHash
                persisted.passwordSalt shouldBe null
                persisted.failedLoginAttempts shouldBe 0
                persisted.lockedUntil shouldBe null
            }

            it("[Test-14-1] UserState가 SITE_ADMIN인 사용자도 searchUsers 검색 결과에 정상 노출되어야 한다") {
                val adminUser = User(
                    name = "임시관리자",
                    loginId = "tempadmin",
                    email = "tempadmin@yona.io",
                    state = UserState.SITE_ADMIN,
                    createdDate = Instant.now()
                )
                userRepository.save(adminUser)

                val result = userRepository.searchUsers("%임시%", PageRequest.of(0, 10))
                result.content.size shouldBe 1
                result.content[0].loginId shouldBe "tempadmin"
            }

            it("[Test-14-2] UserState가 SITE_ADMIN인 사용자가 아바타가 없는 경우 getNoAvatarUsers 목록에 정상 노출되어야 한다") {
                val adminUser = User(
                    name = "임시관리자",
                    loginId = "tempadmin",
                    email = "tempadmin@yona.io",
                    state = UserState.SITE_ADMIN,
                    createdDate = Instant.now()
                )
                userRepository.save(adminUser)

                val list = siteService.getNoAvatarUsers()
                val found = list.any { it["loginId"] == "tempadmin" }
                found shouldBe true
            }
        }
    }
}
