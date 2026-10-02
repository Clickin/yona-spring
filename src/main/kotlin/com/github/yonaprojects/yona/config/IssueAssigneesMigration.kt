package com.github.yonaprojects.yona.config

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

/** Copy the old issue-only assignment before Hibernate updates the schema. PRs keep their FK. */
@Component
class IssueAssigneesMigration(private val dataSource: DataSource) : HibernatePropertiesCustomizer {
    override fun customize(hibernateProperties: MutableMap<String, Any>) {
        dataSource.connection.use { migrate(it) }
    }

    fun migrate(connection: Connection): Int {
        val metadata = connection.metaData
        val schema = connection.schema
        val issueTable = metadata.getTables(connection.catalog, schema, "%", arrayOf("TABLE")).use { rows ->
            var found: String? = null
            while (rows.next()) if (rows.getString("TABLE_NAME").equals("issue", ignoreCase = true)) {
                found = rows.getString("TABLE_NAME")
                break
            }
            found
        } ?: return 0
        val hasOldColumn = metadata.getColumns(connection.catalog, schema, issueTable, "%").use { rows ->
            var found = false
            while (rows.next()) if (rows.getString("COLUMN_NAME").equals("assignee_id", ignoreCase = true)) found = true
            found
        }
        if (!hasOldColumn) return 0
        val hasJoinTable = metadata.getTables(connection.catalog, schema, "%", arrayOf("TABLE")).use { rows ->
            var found = false
            while (rows.next()) if (rows.getString("TABLE_NAME").equals("issue_assignee", ignoreCase = true)) found = true
            found
        }
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val copied = connection.createStatement().use { sql ->
                if (!hasJoinTable) sql.execute("""
                    CREATE TABLE issue_assignee (
                        issue_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        PRIMARY KEY (issue_id, user_id),
                        CONSTRAINT fk_issue_assignee_issue FOREIGN KEY (issue_id) REFERENCES issue(id),
                        CONSTRAINT fk_issue_assignee_user FOREIGN KEY (user_id) REFERENCES n4user(id)
                    )
                """.trimIndent())
                sql.executeUpdate("""
                    INSERT INTO issue_assignee (issue_id, user_id)
                    SELECT i.id, a.user_id FROM issue i JOIN assignee a ON a.id = i.assignee_id
                    WHERE a.user_id IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM issue_assignee ia WHERE ia.issue_id = i.id AND ia.user_id = a.user_id
                    )
                """.trimIndent())
            }
            val foreignKeys = metadata.getImportedKeys(connection.catalog, schema, issueTable).use { rows ->
                buildSet {
                    while (rows.next()) if (rows.getString("FKCOLUMN_NAME").equals("assignee_id", ignoreCase = true)) {
                        add(rows.getString("FK_NAME"))
                    }
                }
            }
            val quote = metadata.identifierQuoteString.trim()
            connection.createStatement().use { sql ->
                val dropType = if (metadata.databaseProductName in setOf("MariaDB", "MySQL", "CUBRID")) "FOREIGN KEY" else "CONSTRAINT"
                foreignKeys.forEach { sql.execute("ALTER TABLE issue DROP $dropType $quote$it$quote") }
                sql.execute("ALTER TABLE issue DROP COLUMN assignee_id")
            }
            connection.commit()
            return copied
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }
}
