package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.SchemaTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.sql.Types
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueClockAcceptanceTest {
    @Test
    fun sharedSchedulerBlockedForTenSecondsDoesNotExpireAdmissionTrust() {
        SchemaTestDatabase.dataSource().use { source ->
            AnnotationConfigApplicationContext().use { context ->
                context.registerBean(QueueClock::class.java, java.util.function.Supplier { QueueClock(source) })
                context.register(BlockedScheduler::class.java)
                context.refresh()
                val blocker = context.getBean(SchedulerBlocker::class.java)
                assertTrue(blocker.entered.await(5, TimeUnit.SECONDS))
                val clock = context.getBean(QueueClock::class.java)
                clock.verifySynchronization()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    Thread.sleep(250)
                    clock.checkSynchronized()
                }
            }
        }
    }

    @Test
    fun databaseClockAndScanExpressionsPreserveTheBorrowedSessionTimezone() {
        SchemaTestDatabase.dataSource().use { source ->
            source.connection.use { connection ->
                val borrowed = SingleConnectionDataSource(connection, true)
                val jdbc = JdbcTemplate(borrowed)
                val product = connection.metaData.databaseProductName
                val zoneSql = when (product) {
                    "PostgreSQL" -> { jdbc.execute("SET TIME ZONE 'Asia/Seoul'"); "SHOW TIME ZONE" }
                    "H2" -> {
                        jdbc.execute("SET TIME ZONE 'Asia/Seoul'")
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT CURRENT_TIMESTAMP, EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = SESSION_ID()").use { rows ->
                                assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, rows.metaData.getColumnType(1))
                                assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, rows.metaData.getColumnType(2))
                            }
                        }
                        "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'TIME ZONE'"
                    }
                    "CUBRID" -> {
                        jdbc.execute("SET SYSTEM PARAMETERS 'timezone=Asia/Seoul'")
                        "SELECT SESSIONTIMEZONE()"
                    }
                    "MariaDB", "MySQL" -> { jdbc.execute("SET time_zone = '+09:00'"); "SELECT @@session.time_zone" }
                    else -> null
                }
                val zone = zoneSql?.let { jdbc.queryForObject(it, String::class.java) }
                val clock = QueueClock(borrowed)
                for (precise in listOf(false, true)) {
                    val hostBefore = System.currentTimeMillis()
                    val sqlBefore = jdbc.queryForObject("SELECT ${clock.epochMillisSql(precise)}", Long::class.java)!!
                    val now = if (precise) clock.leaseNow() else clock.now()
                    val sqlAfter = jdbc.queryForObject("SELECT ${clock.epochMillisSql(precise)}", Long::class.java)!!
                    val tolerance = if (product == "CUBRID" && !precise) 1000 else 1
                    assertTrue(now in (sqlBefore - tolerance)..(sqlAfter + tolerance), "$product precise=$precise SQL=$sqlBefore..$sqlAfter clock=$now")
                    assertTrue(now in (hostBefore - 1000)..(System.currentTimeMillis() + 1000))
                    zoneSql?.let { assertEquals(zone, jdbc.queryForObject(it, String::class.java)) }
                }
            }
        }
    }

    @Test
    fun configuredTrustExpiresWithoutRefreshAndInvalidSkewIsRejected() {
        SchemaTestDatabase.dataSource().use { source ->
            assertThrows(IllegalArgumentException::class.java) { QueueClock(source, skewMillis = 49) }
            assertThrows(IllegalArgumentException::class.java) { QueueClock(source, skewMillis = 5001) }
            val clock = QueueClock(source, trustMillis = 50)
            clock.verifySynchronization()
            clock.checkSynchronized()
            Thread.sleep(75)
            assertEquals("CLOCK_UNTRUSTED", assertThrows(QueueAdmissionException::class.java) {
                clock.checkSynchronized()
            }.code)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class BlockedScheduler {
        @Bean fun schedulerBlocker() = SchedulerBlocker()
    }

    class SchedulerBlocker {
        val entered = CountDownLatch(1)
        @Scheduled(fixedDelay = 60_000)
        fun block() {
            entered.countDown()
            Thread.sleep(10_000)
        }
    }
}
