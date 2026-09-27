package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator

class QueueAdmissionPoolRequirementTest {
    /** Admission borrows its count connection from Hikari; any other pool must fail at startup, not on every enqueue. */
    @Test
    fun nonHikariDataSourceFailsQueueStartupWithAClearMessage() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val hikari = fixture.dataSource.unwrap(HikariDataSource::class.java)
            val plain = DriverManagerDataSource(hikari.jdbcUrl, hikari.username, hikari.password)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val queue = Queue(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry, plain)
            val failure = assertThrows(Exception::class.java) { queue.afterPropertiesSet() }
            assertTrue(failure.message.orEmpty().contains("HikariCP"), "Startup failure must name the HikariCP requirement: ${failure.message}")
        }
    }
}
