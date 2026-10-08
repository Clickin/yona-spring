package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.*
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import java.io.File
import java.time.Instant
import java.util.Optional

class RepositoryMirrorAdminServiceSpec : DescribeSpec({
    describe("ambiguous commit and presentation") {
        it("requeries a committed identity after a lost commit reply without reserving or saving twice") {
            val settings = RepositoryMirrorProperties().apply {
                enabled = true; egressRestricted = true; nodeId = "writer"; writerNodeId = "writer"
                proxyHost = "127.0.0.1"; proxyPort = 19444; allowedOrigins = listOf("https://example.org")
            }
            val projects = mockk<ProjectRepository>()
            val users = mockk<UserRepository>()
            val roles = mockk<RoleRepository>()
            val members = mockk<ProjectUserRepository>()
            val mirrors = mockk<RepositoryMirrorRepository>()
            val storage = mockk<RepositoryMirrorStorage>()
            val namespaceGuard = mockk<RepositoryNamespaceGuard>(relaxed = true)
            val transactions = mockk<PlatformTransactionManager>(relaxed = true)
            val admin = User(id = 1L, loginId = "admin", name = "Admin", state = UserState.SITE_ADMIN)
            var savedProject: Project? = null
            var savedMirror: RepositoryMirror? = null
            every { users.findByLoginId("admin") } returns Optional.of(admin)
            every { projects.findByOwnerAndNameOrPreviousPlace("admin", "mirror") } answers { Optional.ofNullable(savedProject) }
            every { projects.existsByOwnerIgnoreCaseAndNameIgnoreCaseAndIdNot("admin", "mirror", -1) } returns false
            every { roles.findById(1L) } returns Optional.of(Role(id = 1L, name = "MANAGER"))
            every { projects.saveAndFlush(any()) } answers { firstArg<Project>().also { it.id = 11L; savedProject = it } }
            every { members.save(any()) } answers { firstArg<ProjectUser>() }
            every { mirrors.saveAndFlush(any()) } answers { firstArg<RepositoryMirror>().also { it.id = 21L; savedMirror = it } }
            every { mirrors.findByProjectId(11L) } answers { savedMirror }
            every { storage.reserve(any(), 1) } returns File("reserved-target")
            every { storage.directory(any()) } returns File("reserved-target")
            every { transactions.getTransaction(any()) } answers { SimpleTransactionStatus() }
            var firstCommit = true
            every { transactions.commit(any()) } answers {
                if (firstCommit) {
                    firstCommit = false
                    throw TransactionSystemException("secret database endpoint must never reach the UI")
                }
            }
            val service = RepositoryMirrorAdminService(settings, SvnMirrorSourcePolicy(settings), storage, projects, users,
                roles, members, mirrors, mockk(), namespaceGuard, transactions)
            service.create("admin", "mirror", "https://example.org/svn", null) shouldBe 21L
            verify(exactly = 1) { storage.reserve(any(), 1) }
            verify(exactly = 1) { projects.saveAndFlush(any()) }
            verify(exactly = 1) { mirrors.saveAndFlush(any()) }
            verify(exactly = 1) { storage.directory(any()) }
            verify(exactly = 2) { namespaceGuard.holdUntilTransactionCompletion("admin", "mirror") }
            verify(exactly = 1) { namespaceGuard.requireUnreserved("admin", "mirror") }
            verifyOrder {
                namespaceGuard.holdUntilTransactionCompletion("admin", "mirror")
                projects.findByOwnerAndNameOrPreviousPlace("admin", "mirror")
                namespaceGuard.requireUnreserved("admin", "mirror")
                storage.reserve(any(), 1)
            }
        }

        it("returns only fixed failures for a busy or orphan-reserved namespace before project writes") {
            val settings = RepositoryMirrorProperties().apply {
                enabled = true; egressRestricted = true; nodeId = "writer"; writerNodeId = "writer"
                proxyHost = "127.0.0.1"; proxyPort = 19444; allowedOrigins = listOf("https://example.org")
            }
            val projects = mockk<ProjectRepository>()
            val users = mockk<UserRepository>()
            val namespaceGuard = mockk<RepositoryNamespaceGuard>(relaxed = true)
            val transactions = mockk<PlatformTransactionManager>(relaxed = true)
            val admin = User(id = 1L, loginId = "admin", name = "Admin", state = UserState.SITE_ADMIN)
            every { users.findByLoginId("admin") } returns Optional.of(admin)
            every { transactions.getTransaction(any()) } answers { SimpleTransactionStatus() }
            every { namespaceGuard.holdUntilTransactionCompletion("admin", "mirror") } throws IllegalStateException("private lock path")
            val service = RepositoryMirrorAdminService(settings, SvnMirrorSourcePolicy(settings), mockk(), projects, users,
                mockk(), mockk(), mockk(), mockk(), namespaceGuard, transactions)
            shouldThrow<MirrorFailure> { service.create("admin", "mirror", "https://example.org/svn", null) }.code shouldBe "DIRECTORY_OWNERSHIP"
            verify(exactly = 0) { projects.findByOwnerAndNameOrPreviousPlace(any(), any()) }
            every { namespaceGuard.holdUntilTransactionCompletion("admin", "mirror") } returns Unit
            every { projects.findByOwnerAndNameOrPreviousPlace("admin", "mirror") } returns Optional.empty()
            every { projects.existsByOwnerIgnoreCaseAndNameIgnoreCaseAndIdNot("admin", "mirror", -1) } returns false
            every { namespaceGuard.requireUnreserved("admin", "mirror") } throws AccessDeniedException("private reservation path")
            shouldThrow<MirrorFailure> { service.create("admin", "mirror", "https://example.org/svn", null) }.code shouldBe "DIRECTORY_OWNERSHIP"
            verify(exactly = 0) { projects.saveAndFlush(any()) }
        }

        it("masks credentials paths and raw errors while describing readiness and the database lease") {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val row = RepositoryMirror(id = 1L, project = Project(owner = "admin", name = "mirror"),
                sourceUrl = "https://example.org/secret-source?secret-token=hidden", credentialRef = "secret-ref",
                lastError = "password=hidden", lastErrorCode = "SECRET_ERROR", initialImportTargetRevision = 3,
                lastVerifiedRevision = 3, lastIndexedRevision = 2, localYoungestRevision = 4,
                leaseOwner = "internal-node-identity", leaseUntil = now.plusSeconds(60))
            val dto = RepositoryMirrorAdminStatus.from(row, now)
            dto.ready shouldBe false
            dto.leaseActive shouldBe true
            dto.source shouldBe "https://example.org:443/[hidden]"
            dto.toString() shouldNotContain "secret-source"
            dto.toString() shouldNotContain "secret-ref"
            dto.toString() shouldNotContain "password"
            dto.toString() shouldNotContain "SECRET_ERROR"
            dto.toString() shouldNotContain "internal-node-identity"
            row.lastIndexedRevision = 3
            RepositoryMirrorAdminStatus.from(row, now.plusSeconds(60)).ready shouldBe true
            RepositoryMirrorAdminStatus.from(row, now.plusSeconds(60)).leaseActive shouldBe false
            row.status = RepositoryMirrorStatus.NEEDS_ATTENTION
            RepositoryMirrorAdminStatus.from(row, now).ready shouldBe false
        }
    }
})
