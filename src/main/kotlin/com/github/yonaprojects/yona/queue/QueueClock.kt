package com.github.yonaprojects.yona.queue

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone
import javax.sql.DataSource

@Component
class QueueClock(dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)
    @Volatile private var lastTrustedAtNanos = Long.MIN_VALUE

    fun now(): Long = jdbc.execute(ConnectionCallback { connection -> databaseTime(connection, precise = false) })

    /** Runs outside business transactions; transaction-start clocks must not measure their age as skew. */
    @Synchronized
    fun verifySynchronization() {
        try {
            val before = System.currentTimeMillis()
            val databaseNow = jdbc.execute(ConnectionCallback { connection -> databaseTime(connection, precise = true) })
            val after = System.currentTimeMillis()
            if (databaseNow < before - 250 || databaseNow > after + 250) {
                throw QueueAdmissionException("CLOCK_UNTRUSTED")
            }
            lastTrustedAtNanos = System.nanoTime()
        } catch (failure: Exception) {
            lastTrustedAtNanos = Long.MIN_VALUE
            throw failure
        }
    }

    @Scheduled(fixedDelay = 1000)
    fun refreshSynchronization() {
        val previouslyTrusted = lastTrustedAtNanos != Long.MIN_VALUE
        try {
            verifySynchronization()
        } catch (_: Exception) {
            if (previouslyTrusted) LoggerFactory.getLogger(QueueClock::class.java)
                .warn("Queue admission disabled: database clock health unavailable")
        }
    }

    fun checkSynchronized() {
        val checkedAt = lastTrustedAtNanos
        if (checkedAt == Long.MIN_VALUE || System.nanoTime() - checkedAt > 5_000_000_000L) {
            throw QueueAdmissionException("CLOCK_UNTRUSTED")
        }
    }

    private fun databaseTime(connection: Connection, precise: Boolean): Long {
        val product = connection.metaData.databaseProductName
        connection.createStatement().use { statement ->
            statement.queryTimeout = 5
            val sql = when {
                product == "PostgreSQL" -> {
                    statement.execute("SET TIME ZONE 'UTC'")
                    "SELECT CURRENT_TIMESTAMP"
                }
                product == "H2" -> {
                    statement.execute("SET TIME ZONE 'UTC'")
                    "SELECT CURRENT_TIMESTAMP"
                }
                product == "MariaDB" || product == "MySQL" -> "SELECT UTC_TIMESTAMP(3)"
                product.contains("Microsoft SQL Server") -> "SELECT SYSUTCDATETIME()"
                product == "CUBRID" -> {
                    statement.execute("SET SYSTEM PARAMETERS 'timezone=UTC'")
                    statement.executeQuery("SELECT SESSIONTIMEZONE()").use { zone ->
                        check(zone.next())
                        val rules = ZoneId.of(zone.getString(1)).rules
                        check(rules.isFixedOffset && rules.getOffset(java.time.Instant.EPOCH) == ZoneOffset.UTC)
                    }
                    // getTimestamp ignores Calendar here; native arithmetic avoids JVM-zone/DST reinterpretation.
                    // Health needs millisecond precision; scheduling retains the specified whole-second clock.
                    val currentTime = if (precise) "CURRENT_DATETIME" else "CAST(CURRENT_TIMESTAMP AS DATETIME)"
                    "SELECT $currentTime - DATETIME '1970-01-01 00:00:00'"
                }
                else -> throw QueueAdmissionException("UNSUPPORTED_DATABASE")
            }
            statement.executeQuery(sql).use { result ->
                check(result.next())
                return if (product == "CUBRID") {
                    result.getLong(1)
                } else if (product == "PostgreSQL" || product == "H2") {
                    result.getObject(1, OffsetDateTime::class.java).toInstant().toEpochMilli()
                } else {
                    result.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).time
                }
            }
        }
    }
}
