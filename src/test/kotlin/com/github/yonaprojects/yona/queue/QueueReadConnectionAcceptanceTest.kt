package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.UUID

class QueueReadConnectionAcceptanceTest {
    @Test
    fun eventSnapshotRechecksBatchedAuthorityOutsideTheOpenRequestContext() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val factory = (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!
            val users = fixture.contextUserService()
            val suffix = UUID.randomUUID().toString()
            val admin = users.createUser(User(
                loginId = "event-admin-$suffix", name = "Event admin",
                email = "event-admin-$suffix@example.invalid", state = UserState.SITE_ADMIN,
            ))
            val member = users.createUser(User(
                loginId = "event-member-$suffix", name = "Event member",
                email = "event-member-$suffix@example.invalid", state = UserState.ACTIVE,
            ))
            val adminId = checkNotNull(admin.id)
            val memberId = checkNotNull(member.id)
            val queries = QueueAdminQueries(factory)
            val before = queries.events(setOf(adminId, memberId))
            fixture.registry.register(TaskDefinition("queue.acceptance.event-read-$suffix", 1, {}))
            fixture.queue.enqueue("queue.acceptance.event-read-$suffix", 1, "{}".toByteArray(),
                Instant.EPOCH, null, "event-read")
            val pool = fixture.dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean
            fun minimumActive() = (1..20).minOf { Thread.sleep(10); pool.activeConnections }
            fun changeState(id: Long, state: UserState) {
                factory.createEntityManager().use { manager ->
                    manager.transaction.begin()
                    manager.find(User::class.java, id).state = state
                    manager.transaction.commit()
                }
            }
            factory.createEntityManager().use { requestContext ->
                val cachedAdmin = requestContext.find(User::class.java, adminId)
                val baseline = minimumActive()
                TransactionSynchronizationManager.bindResource(factory, EntityManagerHolder(requestContext))
                try {
                    assertEquals(mapOf(adminId to admin.loginId), queries.events(setOf(adminId, memberId)).siteAdmins)
                    changeState(adminId, UserState.ACTIVE)
                    changeState(memberId, UserState.SITE_ADMIN)
                    assertEquals(UserState.SITE_ADMIN, cachedAdmin.state, "The request still holds a stale User")
                    assertEquals(mapOf(memberId to member.loginId), queries.events(setOf(adminId, memberId)).siteAdmins)
                    changeState(memberId, UserState.DELETED)
                    assertEquals(emptyMap<Long, String>(), queries.events(setOf(adminId, memberId)).siteAdmins)
                    assertEquals(before.generation + 1, queries.events(setOf(adminId, memberId)).generation)
                    assertEquals(baseline, minimumActive(), "No event snapshot retains a stream-owned connection")
                } finally {
                    TransactionSynchronizationManager.unbindResource(factory)
                }
            }
        }
    }

    @Test
    fun resultMetadataReleasesItsConnectionWhileTheRequestPersistenceContextRemainsOpen() {
        val meters = SimpleMeterRegistry()
        try {
            JdbcQueueAcceptanceFixture().use { fixture ->
                val factory = (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!
                val manager = SharedEntityManagerCreator.createSharedEntityManager(factory)
                val clock = fixture.contextQueueClock()
                val definition = TaskDefinition("queue.acceptance.read-connection", 1, {}, handler = { context, _ ->
                    context.writeArtifact("connection.txt") { it.write("closed read connection".toByteArray()) }
                })
                fixture.registry.register(definition)
                val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
                    fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
                val id: Long
                QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = 1, pollMillis = 20,
                    shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString()).use { runtime ->
                    runtime.start()
                    id = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "read-connection").jobId
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (fixture.queue.find(id)?.status != QueueStatus.SUCCEEDED && System.nanoTime() < deadline) Thread.sleep(20)
                    assertEquals(QueueStatus.SUCCEEDED, fixture.queue.find(id)?.status)
                }
                val pool = fixture.dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean
                fun minimumActive() = (1..20).minOf { Thread.sleep(10); pool.activeConnections }
                val baseline = minimumActive()
                val requestContext = factory.createEntityManager()
                TransactionSynchronizationManager.bindResource(factory, EntityManagerHolder(requestContext))
                try {
                    val artifact = QueueAdminQueries(factory).result(id)
                    assertEquals("connection.txt", artifact.fileName)
                    assertEquals("closed read connection", Files.readString(fixture.dataDirectory.resolve(artifact.storagePath)))
                    assertEquals(baseline, minimumActive(), "Returning metadata must release its connection before a slow consumer starts")
                } finally {
                    TransactionSynchronizationManager.unbindResource(factory)
                    requestContext.close()
                }
            }
        } finally {
            meters.close()
        }
    }
}
