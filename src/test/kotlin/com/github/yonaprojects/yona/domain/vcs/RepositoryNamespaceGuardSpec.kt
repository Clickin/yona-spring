package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.AbstractIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepositoryNamespaceGuardSpec @Autowired constructor(transactionManager: PlatformTransactionManager) : AbstractIntegrationTest() {
    init {
        describe("repository name reservations across VCS roots") {
            it("holds the stable name lock through commit and reuses it within the same transaction") {
                val root = Files.createTempDirectory("repository-name-lock").toRealPath()
                val guard = RepositoryNamespaceGuard(root.resolve("data").toString(), root.resolve("svn").toString())
                val executor = Executors.newSingleThreadExecutor()
                try {
                    TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }.executeWithoutResult {
                        guard.holdUntilTransactionCompletion("Owner", "Project")
                        guard.holdUntilTransactionCompletion("owner", "project")
                        executor.submit<Boolean> {
                            shouldThrow<IllegalStateException> { guard.acquire("OWNER", "PROJECT") }
                            true
                        }.get(5, TimeUnit.SECONDS) shouldBe true
                    }
                    guard.acquire("owner", "project").use { }
                    Files.list(root.resolve("data/repository-names")).use { it.count() } shouldBe 1L
                } finally { executor.shutdownNow(); root.toFile().deleteRecursively() }
            }
            it("releases on rollback but never makes an orphan mirror reservation available to hosted creation") {
                val root = Files.createTempDirectory("repository-name-orphan").toRealPath()
                val guard = RepositoryNamespaceGuard(root.resolve("data").toString(), root.resolve("svn").toString())
                try {
                    TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }.executeWithoutResult { transaction ->
                        guard.holdUntilTransactionCompletion("owner", "project")
                        val marker = guard.markerPath("owner", "project")
                        Files.createDirectories(marker.parent)
                        Files.writeString(marker, "orphaned reservation; operator must inspect")
                        transaction.setRollbackOnly()
                    }
                    guard.acquire("owner", "project").use {
                        shouldThrow<AccessDeniedException> { guard.requireUnreserved("owner", "project") }
                    }
                    Files.exists(guard.markerPath("owner", "project")) shouldBe true
                    guard.requireUnreserved("other", "project")
                } finally { root.toFile().deleteRecursively() }
            }
            it("rejects unsafe lock inode replacement and allocation without a transaction") {
                val root = Files.createTempDirectory("repository-name-unsafe").toRealPath()
                val elsewhere = Files.createTempDirectory("repository-name-other").toRealPath()
                val guard = RepositoryNamespaceGuard(root.toString(), root.resolve("svn").toString())
                try {
                    shouldThrow<IllegalStateException> { guard.holdUntilTransactionCompletion("owner", "project") }
                    Files.createSymbolicLink(root.resolve("repository-names"), elsewhere)
                    shouldThrow<IllegalStateException> { guard.acquire("owner", "project") }
                } finally { root.toFile().deleteRecursively(); elsewhere.toFile().deleteRecursively() }
            }
        }
    }
}
