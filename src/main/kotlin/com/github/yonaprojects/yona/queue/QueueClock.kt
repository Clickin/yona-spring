package com.github.yonaprojects.yona.queue

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock

@Component
open class QueueClock(
    dataSource: DataSource,
    @Value("\${yona.queue.clock-skew-millis:250}") private val skewMillis: Long = 250,
    @Value("\${yona.queue.clock-trust-millis:5000}") private val trustMillis: Long = 5000,
) : SmartLifecycle {
    private val jdbc = JdbcTemplate(dataSource)
    private val synchronizationLock = ReentrantLock()
    private val log = LoggerFactory.getLogger(QueueClock::class.java)
    private val trustNanos: Long
    private val databaseProduct by lazy(LazyThreadSafetyMode.PUBLICATION) {
        jdbc.execute(ConnectionCallback { it.metaData.databaseProductName })
    }
    @Volatile private var lastTrustedAtNanos = Long.MIN_VALUE
    @Volatile private var scheduler: ScheduledExecutorService? = null

    init {
        require(skewMillis in 50..5000 && trustMillis > 0)
        trustNanos = Math.multiplyExact(trustMillis, 1_000_000)
    }

    open fun now(): Long = jdbc.execute(ConnectionCallback { connection -> databaseTime(connection, precise = false) })

    /** Lease authority needs a fresh DB reading after lock waits, not transaction-start time. */
    open fun leaseNow(): Long = jdbc.execute(ConnectionCallback { connection -> databaseTime(connection, precise = true) })

    /** Runs outside business transactions; transaction-start clocks must not measure their age as skew. */
    fun verifySynchronization() = synchronizationLock.withLock {
        val previouslyTrusted = lastTrustedAtNanos != Long.MIN_VALUE
        var measuredSkew: Long? = null
        try {
            val before = System.currentTimeMillis()
            val databaseNow = jdbc.execute(ConnectionCallback { connection -> databaseTime(connection, precise = true) })
            val after = System.currentTimeMillis()
            measuredSkew = databaseNow - (before + (after - before) / 2)
            if (databaseNow < before - skewMillis || databaseNow > after + skewMillis) {
                throw QueueAdmissionException("CLOCK_UNTRUSTED")
            }
            lastTrustedAtNanos = System.nanoTime()
            if (!previouslyTrusted) log.info("Queue admission clock trust restored; measured skew={}ms", measuredSkew)
        } catch (failure: Exception) {
            lastTrustedAtNanos = Long.MIN_VALUE
            if (previouslyTrusted) log.warn("Queue admission disabled: database clock untrusted; measured skew={}ms", measuredSkew)
            throw failure
        }
    }

    fun refreshSynchronization() {
        try {
            verifySynchronization()
        } catch (_: Exception) {
            // Trust was revoked and the transition logged by verifySynchronization.
        }
    }

    override fun start(): Unit = synchronizationLock.withLock {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "yona-queue-clock").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::refreshSynchronization, 0, 1, TimeUnit.SECONDS) }
    }

    override fun stop() {
        val stopping = synchronizationLock.withLock { scheduler.also { scheduler = null } }
        stopping?.shutdownNow()
    }

    override fun isRunning(): Boolean = scheduler != null

    override fun getPhase(): Int = Int.MAX_VALUE - 200

    internal fun isTrusted(): Boolean {
        val checkedAt = lastTrustedAtNanos
        return checkedAt != Long.MIN_VALUE && System.nanoTime() - checkedAt <= trustNanos
    }

    fun checkSynchronized() {
        if (!isTrusted()) throw QueueAdmissionException("CLOCK_UNTRUSTED")
    }

    /** Scalar SQL for scans: no extra clock round trip and no borrowed-session timezone changes. */
    internal open fun epochMillisSql(precise: Boolean): String = when (val product = databaseProduct) {
        "PostgreSQL" -> {
            val time = if (precise) "clock_timestamp()" else "CURRENT_TIMESTAMP"
            // PostgreSQL's bigint cast rounds; floor preserves the not-before boundary.
            "FLOOR(EXTRACT(EPOCH FROM $time) * 1000)::bigint"
        }
        "H2" -> {
            val time = if (precise) {
                "(SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = SESSION_ID())"
            } else "CURRENT_TIMESTAMP"
            // H2 DATEDIFF compares local fields even for zoned timestamps; normalize the operand first.
            "DATEDIFF('MILLISECOND', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00', ($time AT TIME ZONE 'UTC'))"
        }
        "MariaDB", "MySQL" -> "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)"
        "CUBRID" -> {
            val time = if (precise) "CURRENT_DATETIME" else "CAST(CURRENT_TIMESTAMP AS DATETIME)"
            "(SELECT NEW_TIME($time, SESSIONTIMEZONE(), 'UTC') - DATETIME '1970-01-01 00:00:00')"
        }
        else -> if (product.contains("Microsoft SQL Server")) {
            "DATEDIFF_BIG(millisecond, '1970-01-01', SYSUTCDATETIME())"
        } else throw QueueAdmissionException("UNSUPPORTED_DATABASE")
    }

    private fun databaseTime(connection: Connection, precise: Boolean): Long {
        val product = connection.metaData.databaseProductName
        connection.createStatement().use { statement ->
            statement.queryTimeout = 5
            var restoreTimezone: String? = null
            try {
                val sql = when {
                    product == "PostgreSQL" -> if (precise) "SELECT clock_timestamp()" else "SELECT CURRENT_TIMESTAMP"
                    product == "H2" -> if (precise) {
                        "SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = SESSION_ID()"
                    } else "SELECT CURRENT_TIMESTAMP"
                    product == "MariaDB" || product == "MySQL" -> "SELECT UTC_TIMESTAMP(3)"
                    product.contains("Microsoft SQL Server") -> "SELECT SYSUTCDATETIME()"
                    product == "CUBRID" -> {
                        val zone = statement.executeQuery("SELECT SESSIONTIMEZONE()").use { rows ->
                            check(rows.next())
                            rows.getString(1)
                        }
                        val rules = ZoneId.of(zone).rules
                        if (!rules.isFixedOffset || rules.getOffset(java.time.Instant.EPOCH) != ZoneOffset.UTC) {
                            statement.execute("SET SYSTEM PARAMETERS 'timezone=UTC'")
                            restoreTimezone = zone
                        }
                        // getTimestamp ignores Calendar here; arithmetic avoids JVM-zone/DST reinterpretation.
                        val time = if (precise) "CURRENT_DATETIME" else "CAST(CURRENT_TIMESTAMP AS DATETIME)"
                        "SELECT $time - DATETIME '1970-01-01 00:00:00'"
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
            } finally {
                restoreTimezone?.let { statement.execute("SET SYSTEM PARAMETERS 'timezone=${it.replace("'", "''")}'") }
            }
        }
    }
}
