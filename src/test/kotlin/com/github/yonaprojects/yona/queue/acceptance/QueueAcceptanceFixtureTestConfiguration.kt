package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.config.SchemaTestDatabase
import com.github.yonaprojects.yona.config.SchedulerConfig
import com.github.yonaprojects.yona.domain.mail.MailServiceImpl
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserService
import com.github.yonaprojects.yona.domain.user.UserServiceImpl
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.QueueClock
import com.github.yonaprojects.yona.queue.TaskDefinition
import com.github.yonaprojects.yona.queue.TaskRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.util.UUID
import javax.sql.DataSource

@TestConfiguration(proxyBeanMethods = false)
class QueueAcceptanceFixtureTestConfiguration {
    @Bean("realQueueAcceptanceFixture")
    fun realQueueAcceptanceFixture() = JdbcQueueAcceptanceFixture()
}

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = ["com.github.yonaprojects.yona.domain.user"])
@Import(Queue::class, QueueClock::class, TaskRegistry::class, UserServiceImpl::class, MailServiceImpl::class, SchedulerConfig::class)
class QueueAcceptancePersistenceConfiguration {
    @Bean fun dataSource() = SchemaTestDatabase.dataSource()
    @Bean fun entityManagerFactory(dataSource: DataSource) = SchemaTestDatabase.factory(
        dataSource, "com.github.yonaprojects.yona.domain", "com.github.yonaprojects.yona.queue",
    )
    @Bean fun transactionManager(entityManagerFactory: EntityManagerFactory) = JpaTransactionManager(entityManagerFactory)
    @Bean @Primary fun entityManager(entityManagerFactory: EntityManagerFactory): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
    @Bean fun mailSender() = JavaMailSenderImpl()
}

class JdbcQueueAcceptanceFixture : RealQueueAcceptanceFixture {
    private val context = AnnotationConfigApplicationContext(QueueAcceptancePersistenceConfiguration::class.java)
    override val dataSource: DataSource get() = context.getBean(DataSource::class.java)
    override val transactionManager: PlatformTransactionManager get() = context.getBean(PlatformTransactionManager::class.java)
    override val queue: Queue get() = context.getBean(Queue::class.java)
    private val jdbc get() = JdbcTemplate(dataSource)

    override fun createAcceptanceMarkerTable() {
        val exists = dataSource.connection.use { connection ->
            connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE")).use { rows ->
                var found = false
                while (rows.next()) if (rows.getString("TABLE_NAME").equals("queue_acceptance_marker", true)) found = true
                found
            }
        }
        if (!exists) jdbc.execute("CREATE TABLE queue_acceptance_marker (marker_id VARCHAR(36) PRIMARY KEY)")
    }

    override fun registerStoreAcceptanceTasks() {
        val registry = context.getBean(TaskRegistry::class.java)
        for (type in listOf("queue.acceptance.store.v1", "queue.acceptance.other.v1")) {
            for (version in 1..2) {
                if (registry.find(type, version) == null) {
                    registry.register(TaskDefinition(type, version, { node ->
                        require(node.properties().all { it.key in setOf("marker", "left", "right") })
                    }))
                }
            }
        }
        if (registry.find("queue.acceptance.resources.v1", 1) == null) {
            registry.register(TaskDefinition(
                "queue.acceptance.resources.v1", 1, { node -> require(node.isEmpty) },
                { listOf("repo:7", "repo:2", "repo:7") },
            ))
        }
    }

    override fun jobRow(jobId: Long): QueueJobRow? = jdbc.query(
        "SELECT id,status,payload_version,execution_generation,generation_attempt_no,attempt_count FROM queue_job WHERE id = ?",
        { row, _ -> QueueJobRow(row.getLong(1), row.getString(2), row.getInt(3), row.getLong(4), row.getInt(5), row.getLong(6)) },
        jobId,
    ).singleOrNull()

    override fun attemptRows(jobId: Long): List<QueueAttemptRow> = jdbc.query(
        "SELECT attempt_no,execution_generation,generation_attempt_no,outcome,fence FROM queue_attempt WHERE job_id = ? ORDER BY attempt_no",
        { row, _ -> QueueAttemptRow(row.getLong(1), row.getLong(2), row.getInt(3), row.getString(4), row.getLong(5)) }, jobId,
    )

    override fun idempotencyRowCount(jobId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM queue_idempotency_key WHERE job_id = ?", Int::class.java, jobId)!!

    override fun seedExistingYonaRowsThroughProductionServices() {
        context.getBean(UserService::class.java).createUser(User(
            loginId = "queue-upgrade-${UUID.randomUUID()}", name = "큐 업그레이드 원본", email = "fixture@example.invalid",
        ))
        // This database belongs only to this acceptance fixture. Reconstruct the pre-queue schema.
        for (table in listOf("queue_job_resource", "queue_idempotency_key", "queue_attempt", "queue_job", "queue_meta")) {
            jdbc.execute("DROP TABLE $table")
        }
    }

    override fun snapshotUnrelatedYonaRows(): Map<String, String> = jdbc.query(
        "SELECT id,login_id,name,email FROM n4user ORDER BY id",
        { row, _ -> row.getLong(1).toString() to listOf(row.getString(2), row.getString(3), row.getString(4)).joinToString("\u0000") },
    ).toMap()

    override fun applyQueueOnlyUpgradeToExistingYonaSchema() {
        val migration = SchemaTestDatabase.factory(dataSource, "com.github.yonaprojects.yona.queue")
        migration.afterPropertiesSet()
        migration.destroy()
        queue.afterPropertiesSet()
    }

    override fun reopenOnSamePersistentDatabase(): RealQueueAcceptanceFixture {
        close()
        return JdbcQueueAcceptanceFixture()
    }

    override fun close() = context.close()
}
