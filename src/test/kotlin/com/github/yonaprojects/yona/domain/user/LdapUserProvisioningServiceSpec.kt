package com.github.yonaprojects.yona.domain.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.LockedException
import java.time.Instant
import java.util.Optional

class LdapUserProvisioningServiceSpec : DescribeSpec({
    val userRepository = mockk<UserRepository>()
    val passwordEncodingService = PasswordEncodingService()
    val service = LdapUserProvisioningService(userRepository, passwordEncodingService)

    beforeTest {
        clearMocks(userRepository)
    }

    fun stubExistingUser(user: User) {
        every { userRepository.findByEmail(user.email) } returns Optional.of(user)
        every { userRepository.findById(user.id!!) } returns Optional.of(user)
        every { userRepository.replacePasswordIfUnchanged(user.id!!, user.password, user.passwordSalt, any()) } answers {
            user.password = arg(3)
            user.passwordSalt = null
            1
        }
        every { userRepository.syncLdapProfile(user.id!!, any(), any(), any()) } answers {
            user.name = arg(1)
            arg<String?>(2)?.let { user.englishName = it }
            user.isGuest = arg(3)
            1
        }
    }

    describe("LdapUserProvisioningService.reconcile") {
        it("이메일로 로컬 유저를 찾지 못하면 LDAP 정보로 신규 유저를 생성하고 비밀번호는 Argon2로 저장해야 한다") {
            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                department = "개발팀", isGuestUser = false
            )
            every { userRepository.findByEmail("gildong@example.com") } returns Optional.empty()
            val savedSlot = slot<User>()
            every { userRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.reconcile(ldapUser, "myPassword123!")

            result.loginId shouldBe "gildong"
            result.email shouldBe "gildong@example.com"
            result.name shouldBe "홍길동 [개발팀]"
            result.state shouldBe UserState.ACTIVE
            result.passwordSalt shouldBe null
            passwordEncodingService.matches("myPassword123!", result.password, null) shouldBe true
        }

        it("이메일로 기존 유저를 찾으면 변경된 LDAP 비밀번호를 Argon2로 저장하고 이름/게스트 여부를 동기화해야 한다") {
            val oldSalt = "old-salt"
            val existingUser = User(
                id = 5L, loginId = "gildong", name = "옛이름", email = "gildong@example.com",
                password = PasswordEncodingService.legacyHash("oldPassword", oldSalt), passwordSalt = oldSalt, isGuest = false
            )
            stubExistingUser(existingUser)

            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                department = "개발팀", isGuestUser = true
            )

            val result = service.reconcile(ldapUser, "newPassword456!")

            result.id shouldBe 5L
            result.name shouldBe "홍길동 [개발팀]"
            result.isGuest shouldBe true
            result.passwordSalt shouldBe null
            passwordEncodingService.matches("newPassword456!", result.password, null) shouldBe true
        }

        it("기존 유저와 동일한 LDAP 비밀번호도 레거시 해시라면 Argon2로 업그레이드해야 한다") {
            val salt = "same-salt"
            val legacyHashed = PasswordEncodingService.legacyHash("samePassword", salt)
            val existingUser = User(
                id = 5L, loginId = "gildong", name = "옛이름", email = "gildong@example.com",
                password = legacyHashed, passwordSalt = salt, isGuest = false
            )
            stubExistingUser(existingUser)

            val ldapUser = LdapUser(displayName = "홍길동", email = "gildong@example.com", loginId = "gildong")

            val result = service.reconcile(ldapUser, "samePassword")

            result.password shouldNotBe legacyHashed
            result.passwordSalt shouldBe null
            passwordEncodingService.matches("samePassword", result.password, null) shouldBe true
        }

        it("기존 Argon2 비밀번호가 LDAP 비밀번호와 같으면 저장된 해시를 유지해야 한다") {
            val encoded = passwordEncodingService.encode("samePassword")
            val existingUser = User(
                id = 5L, loginId = "gildong", email = "gildong@example.com",
                password = encoded, passwordSalt = null
            )
            stubExistingUser(existingUser)

            val result = service.reconcile(
                LdapUser(displayName = "홍길동", email = existingUser.email, loginId = existingUser.loginId),
                "samePassword"
            )

            result.password shouldBe encoded
            result.passwordSalt shouldBe null
        }

        it("LOCKED/DELETED 계정은 LDAP 비밀번호가 일치하거나 변경되어도 자격증명과 프로필을 변경하지 않아야 한다") {
            for (state in listOf(UserState.LOCKED, UserState.DELETED)) {
                for (rawPassword in listOf("legacy-password", "changed-password")) {
                    val salt = "synthetic-salt"
                    val legacyHash = PasswordEncodingService.legacyHash("legacy-password", salt)
                    val existingUser = User(
                        id = 5L, loginId = "rejected", name = "Original name", email = "rejected@example.com",
                        password = legacyHash, passwordSalt = salt, state = state
                    )
                    stubExistingUser(existingUser)
                    val ldapUser = LdapUser(displayName = "Changed name", email = existingUser.email, loginId = existingUser.loginId)

                    if (state == UserState.LOCKED) {
                        shouldThrow<LockedException> { service.reconcile(ldapUser, rawPassword) }
                    } else {
                        shouldThrow<DisabledException> { service.reconcile(ldapUser, rawPassword) }
                    }
                    existingUser.password shouldBe legacyHash
                    existingUser.passwordSalt shouldBe salt
                    existingUser.name shouldBe "Original name"
                }
            }
        }

        it("자동 잠금이 유효한 계정은 LDAP 인증 성공 후에도 비밀번호를 변경하지 않아야 한다") {
            val salt = "synthetic-salt"
            val legacyHash = PasswordEncodingService.legacyHash("legacy-password", salt)
            val existingUser = User(
                id = 5L, loginId = "temporarily-locked", email = "temporarily-locked@example.com",
                password = legacyHash, passwordSalt = salt, lockedUntil = Instant.now().plusSeconds(900)
            )
            stubExistingUser(existingUser)
            val ldapUser = LdapUser(displayName = "Changed name", email = existingUser.email, loginId = existingUser.loginId)

            shouldThrow<LockedException> { service.reconcile(ldapUser, "changed-password") }

            existingUser.password shouldBe legacyHash
            existingUser.passwordSalt shouldBe salt
        }

        it("englishName이 비어있지 않으면 기존 유저의 englishName을 갱신해야 한다") {
            val existingUser = User(
                id = 5L, loginId = "gildong", name = "옛이름", email = "gildong@example.com",
                password = "x", passwordSalt = "y", isGuest = false
            )
            stubExistingUser(existingUser)

            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                englishName = "Gildong Hong"
            )

            val result = service.reconcile(ldapUser, "pw")

            result.englishName shouldBe "Gildong Hong"
        }

        it("신규 유저 생성 시 englishName이 존재하면 설정해야 한다") {
            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                department = "개발팀", isGuestUser = false, englishName = "Gildong Hong"
            )
            every { userRepository.findByEmail("gildong@example.com") } returns Optional.empty()
            val savedSlot = slot<User>()
            every { userRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.reconcile(ldapUser, "myPassword123!")

            result.englishName shouldBe "Gildong Hong"
        }

        // isNullOrBlank()는 null-체크와 isBlank-체크 두 서브 분기로 구성된다. 기존 테스트는
        // englishName이 없음(null 기본값)과 명확한 값("Gildong Hong")만 다뤄서, "non-null이지만
        // 공백뿐"인 케이스(isBlank()==true 서브 분기)가 신규 생성/기존 동기화 양쪽 호출부 모두 비어 있었다.
        it("신규 유저 생성 시 englishName이 공백뿐이면 설정하지 않아야 한다") {
            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                department = "개발팀", isGuestUser = false, englishName = "   "
            )
            every { userRepository.findByEmail("gildong@example.com") } returns Optional.empty()
            val savedSlot = slot<User>()
            every { userRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.reconcile(ldapUser, "myPassword123!")

            result.englishName shouldBe null
        }

        it("기존 유저 동기화 시 englishName이 공백뿐이면 갱신하지 않아야 한다") {
            val existingUser = User(
                id = 5L, loginId = "gildong", name = "옛이름", email = "gildong@example.com",
                password = "x", passwordSalt = "y", isGuest = false, englishName = "Old English Name"
            )
            stubExistingUser(existingUser)

            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                englishName = "   "
            )

            val result = service.reconcile(ldapUser, "pw")

            result.englishName shouldBe "Old English Name"
        }

        it("null salt로 저장된 빈 salt 레거시 비밀번호도 LDAP 로그인 후 Argon2로 업그레이드해야 한다") {
            val existingUser = User(
                id = 5L, loginId = "gildong", name = "옛이름", email = "gildong@example.com",
                password = PasswordEncodingService.legacyHash("newPassword456!", ""), passwordSalt = null, isGuest = false
            )
            stubExistingUser(existingUser)

            val ldapUser = LdapUser(
                displayName = "홍길동", email = "gildong@example.com", loginId = "gildong",
                department = "개발팀", isGuestUser = true
            )

            val result = service.reconcile(ldapUser, "newPassword456!")

            result.passwordSalt shouldBe null
            passwordEncodingService.matches("newPassword456!", result.password, null) shouldBe true
        }
    }
})
