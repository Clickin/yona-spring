package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.SchedulerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.function.Supplier
import javax.sql.DataSource

class QueueClockHealthTest {
    @Test
    fun previouslyTrustedDatabaseFailureDisablesAdmissionAndRecoveryReenablesIt() {
        val unavailable = AtomicBoolean(false)
        val source = object : DelegatingDataSource(DriverManagerDataSource("jdbc:h2:mem:clock-${UUID.randomUUID()}")) {
            override fun getConnection(): Connection {
                if (unavailable.get()) throw SQLException("Injected database outage")
                return super.getConnection()
            }
        }
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(DataSource::class.java, Supplier { source })
            context.register(SchedulerConfig::class.java, QueueClock::class.java)
            context.refresh()
            val clock = context.getBean(QueueClock::class.java)
            clock.verifySynchronization()
            clock.checkSynchronized()
            unavailable.set(true)
            awaitHealth(clock, trusted = false)
            unavailable.set(false)
            awaitHealth(clock, trusted = true)
        }
    }

    private fun awaitHealth(clock: QueueClock, trusted: Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val actual = try {
                clock.checkSynchronized()
                true
            } catch (error: QueueAdmissionException) {
                assertEquals("CLOCK_UNTRUSTED", error.code)
                false
            }
            if (actual == trusted) return
            LockSupport.parkNanos(20_000_000)
        }
        assertTrue(false, "Database clock health did not become trusted=$trusted")
    }
}
