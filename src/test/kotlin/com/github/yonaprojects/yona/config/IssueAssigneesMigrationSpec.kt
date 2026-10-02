package com.github.yonaprojects.yona.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class IssueAssigneesMigrationSpec : DescribeSpec({
    describe("legacy assignment migration") {
        it("preserves old issue assignments, leaves PRs alone and never restores removed assignments on restart") {
            DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()}").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE n4user (id BIGINT PRIMARY KEY)")
                    sql.execute("CREATE TABLE assignee (id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES n4user(id))")
                    sql.execute("CREATE TABLE issue (id BIGINT PRIMARY KEY, assignee_id BIGINT REFERENCES assignee(id))")
                    sql.execute("CREATE TABLE pull_request (id BIGINT PRIMARY KEY, assignee_id BIGINT REFERENCES assignee(id))")
                    sql.execute("INSERT INTO n4user VALUES (1), (2)")
                    sql.execute("INSERT INTO assignee VALUES (10, 1), (20, 2)")
                    sql.execute("INSERT INTO issue VALUES (100, 10), (200, NULL), (300, 20)")
                    sql.execute("INSERT INTO pull_request VALUES (1, 10)")
                }
                val migration = IssueAssigneesMigration(mockk())
                migration.migrate(connection) shouldBe 2
                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT issue_id, user_id FROM issue_assignee ORDER BY issue_id").use { rows ->
                        val assigned = buildList { while (rows.next()) add(rows.getLong(1) to rows.getLong(2)) }
                        assigned shouldBe listOf(100L to 1L, 300L to 2L)
                    }
                    shouldThrow<SQLException> { sql.execute("INSERT INTO issue_assignee VALUES (100, 1)") }
                    sql.execute("INSERT INTO issue_assignee VALUES (100, 2)")
                    sql.execute("DELETE FROM issue_assignee WHERE issue_id = 100 AND user_id = 1")
                    migration.migrate(connection) shouldBe 0
                    sql.executeQuery("SELECT user_id FROM issue_assignee WHERE issue_id = 100").use { rows ->
                        rows.next() shouldBe true
                        rows.getLong(1) shouldBe 2L
                        rows.next() shouldBe false
                    }
                    sql.executeQuery("SELECT assignee_id FROM pull_request WHERE id = 1").use { rows ->
                        rows.next() shouldBe true
                        rows.getLong(1) shouldBe 10L
                    }
                }
            }
        }
        it("allows a fresh database to be created by Hibernate") {
            DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()}").use { connection ->
                IssueAssigneesMigration(mockk()).migrate(connection) shouldBe 0
            }
        }
    }
})
