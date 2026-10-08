package com.github.yonaprojects.yona.domain.vcs

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

private class RepositoryGateTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
    override fun doCommit(status: DefaultTransactionStatus) = Unit
    override fun doRollback(status: DefaultTransactionStatus) = Unit
}

class RepositoryAccessSpec : DescribeSpec({
    describe("repository reset gate") {
        it("waits for readers, serializes reset, and does not block another project") {
            val executor = Executors.newFixedThreadPool(4)
            val entered = CountDownLatch(1)
            val close = CountDownLatch(1)
            val resetting = CountDownLatch(1)
            val finishReset = CountDownLatch(1)
            val secondReset = CountDownLatch(1)
            try {
                val reader = executor.submit {
                    RepositoryAccess.read(-101) {
                        entered.countDown()
                        close.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                }
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val reset = executor.submit {
                    RepositoryAccess.reset(-101) {
                        resetting.countDown()
                        finishReset.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                }
                resetting.await(100, TimeUnit.MILLISECONDS) shouldBe false
                executor.submit<Boolean> { RepositoryAccess.read(-102) { true } }.get(5, TimeUnit.SECONDS) shouldBe true
                close.countDown()
                resetting.await(5, TimeUnit.SECONDS) shouldBe true
                val duplicate = executor.submit { RepositoryAccess.reset(-101) { secondReset.countDown() } }
                secondReset.await(100, TimeUnit.MILLISECONDS) shouldBe false
                finishReset.countDown()
                reader.get(5, TimeUnit.SECONDS)
                reset.get(5, TimeUnit.SECONDS)
                duplicate.get(5, TimeUnit.SECONDS)
            } finally {
                close.countDown()
                finishReset.countDown()
                executor.shutdownNow()
            }
        }

        it("quiesces sessions from other projects while isolating an archive") {
            val executor = Executors.newFixedThreadPool(3)
            val entered = CountDownLatch(1)
            val finishReader = CountDownLatch(1)
            val archiving = CountDownLatch(1)
            val finishArchive = CountDownLatch(1)
            val newReaderEntered = CountDownLatch(1)
            try {
                val reader = executor.submit {
                    RepositoryAccess.read(-401) {
                        entered.countDown()
                        finishReader.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                }
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val archive = executor.submit {
                    RepositoryAccess.archive(-402) {
                        archiving.countDown()
                        finishArchive.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                }
                archiving.await(100, TimeUnit.MILLISECONDS) shouldBe false
                finishReader.countDown()
                archiving.await(5, TimeUnit.SECONDS) shouldBe true
                val next = executor.submit {
                    RepositoryAccess.read(-403) { newReaderEntered.countDown() }
                }
                newReaderEntered.await(100, TimeUnit.MILLISECONDS) shouldBe false
                finishArchive.countDown()
                listOf(reader, archive, next).forEach { it.get(5, TimeUnit.SECONDS) }
                newReaderEntered.count shouldBe 0L
            } finally {
                finishReader.countDown()
                finishArchive.countDown()
                executor.shutdownNow()
            }
        }

        it("retains every nested acquisition until local transaction completion, on commit and rollback") {
            for (rollback in listOf(false, true)) {
                val executor = Executors.newFixedThreadPool(2)
                val firstReset = CountDownLatch(1)
                val secondReset = CountDownLatch(1)
                val futures = mutableListOf<java.util.concurrent.Future<*>>()
                var completion: Int? = null
                var heldUntilCompletion = false
                try {
                    TransactionTemplate(RepositoryGateTransactionManager()).executeWithoutResult { status ->
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCompletion(result: Int) {
                                completion = result
                                heldUntilCompletion = firstReset.count == 1L && secondReset.count == 1L
                            }
                        })
                        RepositoryAccess.read(-303) {
                            RepositoryAccess.readBoth(-303, -304) {
                                RepositoryAccess.read(-303) { Unit }
                            }
                        }
                        futures += executor.submit { RepositoryAccess.reset(-303) { firstReset.countDown() } }
                        futures += executor.submit { RepositoryAccess.reset(-304) { secondReset.countDown() } }
                        firstReset.await(100, TimeUnit.MILLISECONDS) shouldBe false
                        secondReset.await(100, TimeUnit.MILLISECONDS) shouldBe false
                        if (rollback) status.setRollbackOnly()
                    }
                    futures.forEach { it.get(5, TimeUnit.SECONDS) }
                    completion shouldBe if (rollback) TransactionSynchronization.STATUS_ROLLED_BACK else TransactionSynchronization.STATUS_COMMITTED
                    heldUntilCompletion shouldBe true
                    RepositoryAccess.reset(-303) { true } shouldBe true
                    RepositoryAccess.reset(-304) { true } shouldBe true
                } finally {
                    executor.shutdownNow()
                }
            }
        }

        it("acquires dual project IDs ascending even when the caller passes descending IDs") {
            val executor = Executors.newFixedThreadPool(3)
            val lowerHeld = CountDownLatch(1)
            val releaseLower = CountDownLatch(1)
            val reading = CountDownLatch(1)
            val readerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            try {
                val lowerReset = executor.submit {
                    RepositoryAccess.reset(-302) {
                        lowerHeld.countDown()
                        releaseLower.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                }
                lowerHeld.await(5, TimeUnit.SECONDS) shouldBe true
                val reader = executor.submit {
                    readerThread.set(Thread.currentThread())
                    reading.countDown()
                    RepositoryAccess.readBoth(-301, -302) { Unit }
                }
                reading.await(5, TimeUnit.SECONDS) shouldBe true
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (readerThread.get().state != Thread.State.WAITING && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                readerThread.get().state shouldBe Thread.State.WAITING
                // The reader must wait at the lower ID, leaving the higher ID available to reset.
                executor.submit<Boolean> { RepositoryAccess.reset(-301) { true } }.get(5, TimeUnit.SECONDS) shouldBe true
                releaseLower.countDown()
                lowerReset.get(5, TimeUnit.SECONDS)
                reader.get(5, TimeUnit.SECONDS)
            } finally {
                releaseLower.countDown()
                executor.shutdownNow()
            }
        }

        it("completes opposite-direction dual reads while resets contend") {
            val executor = Executors.newFixedThreadPool(3)
            val start = CountDownLatch(1)
            try {
                val forward = executor.submit {
                    start.await(5, TimeUnit.SECONDS) shouldBe true
                    repeat(20) { RepositoryAccess.readBoth(-310, -309) { Unit } }
                }
                val reverse = executor.submit {
                    start.await(5, TimeUnit.SECONDS) shouldBe true
                    repeat(20) { RepositoryAccess.readBoth(-309, -310) { Unit } }
                }
                val resets = executor.submit {
                    start.await(5, TimeUnit.SECONDS) shouldBe true
                    repeat(20) {
                        RepositoryAccess.reset(-310) { Unit }
                        RepositoryAccess.reset(-309) { Unit }
                    }
                }
                start.countDown()
                listOf(forward, reverse, resets).forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }

        it("deduplicates nullable IDs and supports non-local returns without leaking acquisitions") {
            fun returnFromRead(): Int {
                RepositoryAccess.read(-305) { return 7 }
            }
            fun returnFromBoth(): Int {
                RepositoryAccess.readBoth(-306, -305) { return 9 }
            }
            returnFromRead() shouldBe 7
            returnFromBoth() shouldBe 9
            RepositoryAccess.reset(-305) { true } shouldBe true
            RepositoryAccess.reset(-306) { true } shouldBe true
            TransactionTemplate(RepositoryGateTransactionManager()).executeWithoutResult {
                RepositoryAccess.readBoth(null, null) { true } shouldBe true
                RepositoryAccess.readBoth(-305, -305) { Unit }
                RepositoryAccess.readBoth(null, -306) { Unit }
                RepositoryAccess.readBoth(-307, null) { Unit }
            }
            RepositoryAccess.reset(-305) { true } shouldBe true
            RepositoryAccess.reset(-306) { true } shouldBe true
            RepositoryAccess.reset(-307) { true } shouldBe true
        }

        it("rejects read-to-write upgrades, including reads retained by a transaction") {
            RepositoryAccess.read(-308) {
                shouldThrow<IllegalStateException> { RepositoryAccess.reset(-308) { Unit } }
            }
            TransactionTemplate(RepositoryGateTransactionManager()).executeWithoutResult {
                RepositoryAccess.read(-308) { Unit }
                shouldThrow<IllegalStateException> { RepositoryAccess.reset(-308) { Unit } }
            }
            RepositoryAccess.reset(-308) {
                RepositoryAccess.read(-308) { RepositoryAccess.reset(-308) { true } } shouldBe true
            }
        }

        it("retains nested lifecycle writers and their reads until commit or rollback completes") {
            for (rollback in listOf(false, true)) {
                val fixture = Files.createTempDirectory("repository-writer-gate-")
                val source = Files.createDirectory(fixture.resolve("source"))
                val archive = fixture.resolve("archive")
                val marker = source.resolve("marker")
                val executor = Executors.newFixedThreadPool(2)
                val moved = CountDownLatch(1)
                val readerEntered = CountDownLatch(1)
                val futures = mutableListOf<java.util.concurrent.Future<*>>()
                var heldUntilCompletion = false
                try {
                    TransactionTemplate(RepositoryGateTransactionManager()).executeWithoutResult { status ->
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCompletion(result: Int) {
                                heldUntilCompletion = moved.count == 1L && readerEntered.count == 1L
                            }
                        })
                        RepositoryAccess.exclusive(-320) {
                            RepositoryAccess.exclusive(-320) {
                                Files.writeString(marker, "lifecycle-write")
                                RepositoryAccess.read(-320) { Files.readString(marker) shouldBe "lifecycle-write" }
                            }
                        }
                        futures += executor.submit {
                            RepositoryAccess.reset(-320) {
                                Files.move(source, archive)
                                moved.countDown()
                            }
                        }
                        futures += executor.submit { RepositoryAccess.read(-320) { readerEntered.countDown() } }
                        moved.await(100, TimeUnit.MILLISECONDS) shouldBe false
                        readerEntered.await(100, TimeUnit.MILLISECONDS) shouldBe false
                        Files.exists(source) shouldBe true
                        Files.exists(archive) shouldBe false
                        if (rollback) status.setRollbackOnly()
                    }
                    futures.forEach { it.get(5, TimeUnit.SECONDS) }
                    heldUntilCompletion shouldBe true
                    Files.readString(archive.resolve("marker")) shouldBe "lifecycle-write"
                    RepositoryAccess.exclusive(-320) { true } shouldBe true
                } finally {
                    executor.shutdownNow()
                    fixture.toFile().deleteRecursively()
                }
            }
        }

        it("cleans up non-local and exceptional writer exits, and rejects retained read upgrades") {
            fun returnFromWriter(): Int {
                RepositoryAccess.exclusive(-321) { return 11 }
            }
            returnFromWriter() shouldBe 11
            shouldThrow<IllegalArgumentException> {
                RepositoryAccess.exclusive(-321) { throw IllegalArgumentException("writer failure") }
            }
            RepositoryAccess.reset(-321) { true } shouldBe true
            RepositoryAccess.read(-321) {
                shouldThrow<IllegalStateException> { RepositoryAccess.exclusive(-321) { Unit } }
            }
            TransactionTemplate(RepositoryGateTransactionManager()).executeWithoutResult {
                RepositoryAccess.read(-321) { Unit }
                shouldThrow<IllegalStateException> { RepositoryAccess.exclusive(-321) { Unit } }
            }
            RepositoryAccess.exclusive(-321) { true } shouldBe true
        }

        it("permits absent leaves but rejects traversal, sibling prefixes, and symlink ancestors") {
            val temporary = Files.createTempDirectory("repository-access-")
            try {
                val root = Files.createDirectory(temporary.resolve("serving"))
                val archive = Files.createDirectory(temporary.resolve("archive"))
                val sibling = Files.createDirectory(temporary.resolve("serving-other"))
                Files.createSymbolicLink(root.resolve("owner"), archive)
                RepositoryAccess.isWithinRoot(root.resolve("new/project").toFile(), root.toFile()) shouldBe true
                RepositoryAccess.isWithinRoot(root.resolve("../archive").toFile(), root.toFile()) shouldBe false
                RepositoryAccess.isWithinRoot(sibling.toFile(), root.toFile()) shouldBe false
                RepositoryAccess.isWithinRoot(root.resolve("owner/absent").toFile(), root.toFile()) shouldBe false
                Files.delete(root.resolve("owner"))
            } finally {
                temporary.toFile().deleteRecursively()
            }
        }
    }
})
