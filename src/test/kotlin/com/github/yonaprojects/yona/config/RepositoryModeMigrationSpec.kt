package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import com.github.yonaprojects.yona.domain.vcs.RepositoryWriteGuard
import jakarta.persistence.EntityManager
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import javax.sql.DataSource

/** Uses the selected real DB, not a fresh Hibernate create-drop schema, for the upgrade itself. */
class RepositoryModeMigrationSpec @Autowired constructor(
    private val dataSource: DataSource,
    private val projects: ProjectRepository,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    init {
        describe("pre-Hibernate repository mode upgrade on every supported database") {
            it("uses a fresh scalar query even when Hibernate already holds a stale Project") {
                TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
                    try {
                        val project = projects.saveAndFlush(Project(owner = "mode-probe", name = "mode-probe"))
                        val guard = RepositoryWriteGuard(projects)
                        guard.isWritable(project) shouldBe true
                        entityManager.createNativeQuery("UPDATE project SET repository_mode = 'MIRROR' WHERE id = :id")
                            .setParameter("id", project.id!!).executeUpdate()
                        project.repositoryMode shouldBe RepositoryMode.HOSTED
                        projects.findById(project.id!!).get() shouldBe project
                        shouldThrow<AccessDeniedException> { guard.requireWritable(project) }
                        project.overview = "metadata edit must not revert the persisted mode"
                        projects.flush()
                        projects.findRepositoryModeById(project.id!!) shouldBe RepositoryMode.MIRROR
                    } finally {
                        transaction.setRollbackOnly()
                    }
                }
            }

            it("leaves an absent table to Hibernate") {
                dataSource.connection.use { connection ->
                    RepositoryModeMigration(dataSource).migrate(connection, "absent_repository_mode_probe") shouldBe false
                }
            }

            listOf(false, true).forEach { interrupted ->
                it("backfills an ${if (interrupted) "interrupted" else "old"} schema and survives restart") {
                    dataSource.connection.use { connection ->
                        val table = "repository_mode_upgrade_probe"
                        val migration = RepositoryModeMigration(dataSource)
                        connection.createStatement().use { statement ->
                            statement.execute("CREATE TABLE $table (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(100)${if (interrupted) ", repository_mode VARCHAR(16)" else ""})")
                        }
                        try {
                            connection.createStatement().use { statement ->
                                statement.execute("INSERT INTO $table (id, name) VALUES (1, 'preserve')")
                                if (interrupted) {
                                    statement.execute("INSERT INTO $table (id, name, repository_mode) VALUES (2, 'mirror', 'MIRROR')")
                                }
                            }
                            migration.migrate(connection, table) shouldBe true
                            migration.migrate(connection, table) shouldBe true
                            connection.createStatement().use { statement ->
                                statement.execute("INSERT INTO $table (id, name) VALUES (3, 'default')")
                                statement.executeQuery("SELECT name, repository_mode FROM $table WHERE id = 1").use { rows ->
                                    rows.next() shouldBe true
                                    rows.getString(1) shouldBe "preserve"
                                    rows.getString(2) shouldBe "HOSTED"
                                }
                                statement.executeQuery("SELECT repository_mode FROM $table WHERE id = 3").use { rows ->
                                    rows.next() shouldBe true
                                    rows.getString(1) shouldBe "HOSTED"
                                }
                                if (interrupted) {
                                    statement.executeQuery("SELECT repository_mode FROM $table WHERE id = 2").use { rows ->
                                        rows.next() shouldBe true
                                        rows.getString(1) shouldBe "MIRROR"
                                    }
                                }
                                statement.executeQuery("SELECT COUNT(*) FROM $table WHERE repository_mode IS NULL").use { rows ->
                                    rows.next() shouldBe true
                                    rows.getLong(1) shouldBe 0L
                                }
                                shouldThrow<SQLException> {
                                    statement.execute("INSERT INTO $table (id, name, repository_mode) VALUES (4, 'null', NULL)")
                                }
                            }
                        } finally {
                            connection.createStatement().use { it.execute("DROP TABLE $table") }
                        }
                    }
                }
            }
        }
    }
}
