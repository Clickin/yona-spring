package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.SchemaTestDatabase
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class QueuePrioritySchemaAcceptanceTest {
    @Test
    fun addingPriorityToAnExistingQueuePreservesJobsAndBackfillsNormalPriority() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val job = fixture.queue.enqueue("queue.schema.priority", 1, "{}".toByteArray(), Instant.EPOCH, null, "schema")
            val before = fixture.queue.find(job.jobId)
            val jdbc = JdbcTemplate(fixture.dataSource)
            val product = fixture.dataSource.connection.use { it.metaData.databaseProductName }
            jdbc.execute(if (product == "MySQL" || product == "MariaDB" || product == "CUBRID" || product.contains("Microsoft SQL Server")) {
                "DROP INDEX queue_job_ready ON queue_job"
            } else "DROP INDEX queue_job_ready")
            // Re-create the populated PR01 schema from before the priority column existed.
            if (product.contains("Microsoft SQL Server")) {
                val constraint = jdbc.queryForObject(
                    "SELECT dc.name FROM sys.default_constraints dc JOIN sys.columns c ON c.object_id = dc.parent_object_id " +
                        "AND c.column_id = dc.parent_column_id WHERE dc.parent_object_id = OBJECT_ID('queue_job') AND c.name = 'priority'",
                    String::class.java,
                )!!
                jdbc.execute("ALTER TABLE queue_job DROP CONSTRAINT [${constraint.replace("]", "]]")}]")
            }
            jdbc.execute("ALTER TABLE queue_job DROP COLUMN priority")
            val upgrade = SchemaTestDatabase.factory(fixture.dataSource, "com.github.yonaprojects.yona.queue")
            try {
                upgrade.afterPropertiesSet()
            } finally {
                upgrade.destroy()
            }
            assertEquals(before, fixture.queue.find(job.jobId))
            assertEquals(0, jdbc.queryForObject("SELECT priority FROM queue_job WHERE id = ?", Int::class.java, job.jobId))
            explainReadyScan(fixture)
        }
    }

    /** Emitted in the six-DB acceptance matrix for inspection; optimizers may choose a table scan for tiny fixtures. */
    private fun explainReadyScan(fixture: JdbcQueueAcceptanceFixture) {
        val clock = QueueClock(fixture.dataSource)
        val now = clock.epochMillisSql(false)
        val scan = "SELECT id, priority FROM queue_job WHERE " +
            "((status = 'QUEUED' AND scheduled_at_epoch_ms <= $now) " +
            "OR (status = 'RETRY_WAIT' AND next_attempt_at_epoch_ms <= $now)) ORDER BY priority DESC, id ASC"
        fixture.dataSource.connection.use { connection ->
            val product = connection.metaData.databaseProductName
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                when {
                    product == "CUBRID" -> {
                        statement.execute("SET TRACE ON")
                        try {
                            statement.executeQuery(scan.replaceFirst("SELECT", "SELECT /*+ RECOMPILE */")).use { rows ->
                                while (rows.next()) rows.getLong(1)
                            }
                            statement.executeQuery("SHOW TRACE").use { rows ->
                                while (rows.next()) println("queue_job_ready EXPLAIN [$product]: ${rows.getString(1)}")
                            }
                        } finally { statement.execute("SET TRACE OFF") }
                    }
                    product.contains("Microsoft SQL Server") -> {
                        statement.execute("SET SHOWPLAN_XML ON")
                        try {
                            statement.executeQuery(scan).use { rows ->
                                while (rows.next()) println("queue_job_ready EXPLAIN [$product]: ${rows.getString(1)}")
                            }
                        } finally { statement.execute("SET SHOWPLAN_XML OFF") }
                    }
                    else -> statement.executeQuery("EXPLAIN $scan").use { rows ->
                        while (rows.next()) println("queue_job_ready EXPLAIN [$product]: " +
                            (1..rows.metaData.columnCount).joinToString(" | ") { rows.getString(it).orEmpty() })
                    }
                }
            }
        }
    }
}
