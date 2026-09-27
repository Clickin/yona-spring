package com.github.yonaprojects.yona.queue

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

class QueueReadConnectionAcceptanceTest {
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
