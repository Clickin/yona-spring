package com.github.yonaprojects.yona.config

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/** Runs before Hibernate update can add a non-null column to an existing populated table. */
@Component
class RepositoryModeMigration(private val dataSource: DataSource) : HibernatePropertiesCustomizer {
    override fun customize(hibernateProperties: MutableMap<String, Any>) {
        dataSource.connection.use { migrate(it) }
    }

    fun migrate(connection: Connection, targetTable: String = "project"): Boolean {
        val metadata = connection.metaData
        val product = metadata.databaseProductName
        // CUBRID's JDBC driver wraps unsupported getSchema() in a plain SQLException.
        // Its metadata calls accept a null schema; do not suppress unrelated SQL failures.
        val schema = if (product == "CUBRID") null
            else try { connection.schema } catch (_: java.sql.SQLFeatureNotSupportedException) { null }
        val tables = metadata.getTables(connection.catalog, schema, "%", arrayOf("TABLE", "BASE TABLE")).use { rows ->
            buildList {
                while (rows.next()) {
                    if (rows.getString("TABLE_NAME").equals(targetTable, ignoreCase = true)) {
                        add(Triple(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME")))
                    }
                }
            }
        }
        // A fresh schema gets the same default/not-null definition from the entity mapping.
        if (tables.isEmpty()) return false
        check(tables.size == 1) { "Ambiguous project table for repository mode migration" }
        val (catalog, tableSchema, tableName) = tables.single()
        check(product in setOf("H2", "PostgreSQL", "MySQL", "MariaDB", "Microsoft SQL Server", "CUBRID")) {
            "Unsupported database for repository mode migration"
        }
        val quote = metadata.identifierQuoteString.trim()
        fun quoted(name: String) = if (quote.isEmpty()) name else quote + name.replace(quote, quote + quote) + quote
        val table = listOfNotNull(tableSchema?.takeIf(String::isNotBlank), tableName).joinToString(".", transform = ::quoted)
        fun column(): Triple<String, Boolean, String?>? = metadata.getColumns(catalog, tableSchema, tableName, "%").use { rows ->
            var result: Triple<String, Boolean, String?>? = null
            while (rows.next()) {
                if (rows.getString("COLUMN_NAME").equals("repository_mode", ignoreCase = true)) {
                    result = Triple(rows.getString("COLUMN_NAME"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rows.getString("COLUMN_DEF"))
                }
            }
            result
        }
        fun execute(sql: String) = connection.createStatement().use { it.execute(sql); Unit }
        val mode = column() ?: run {
            execute("ALTER TABLE $table ADD repository_mode VARCHAR(16)")
            checkNotNull(column()) { "Repository mode column was not created" }
        }
        val name = quoted(mode.first)
        execute("UPDATE $table SET $name = 'HOSTED' WHERE $name IS NULL")
        fun hostedDefault(value: String?): Boolean = value?.substringBefore("::")
            ?.replace("(", "")?.replace(")", "")?.trim()?.removePrefix("N")?.removeSurrounding("'") == "HOSTED"
        if (mode.second || !hostedDefault(mode.third)) {
            when (product) {
                "MySQL", "MariaDB", "CUBRID" -> execute("ALTER TABLE $table MODIFY COLUMN $name VARCHAR(16) DEFAULT 'HOSTED' NOT NULL")
                "Microsoft SQL Server" -> {
                    if (!hostedDefault(mode.third)) {
                        val constraint = connection.prepareStatement(
                            "SELECT dc.name FROM sys.default_constraints dc JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id WHERE dc.parent_object_id = OBJECT_ID(?) AND c.name = ?"
                        ).use { statement ->
                            statement.setString(1, table)
                            statement.setString(2, mode.first)
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                        }
                        if (constraint != null) execute("ALTER TABLE $table DROP CONSTRAINT ${quoted(constraint)}")
                        execute("ALTER TABLE $table ADD DEFAULT 'HOSTED' FOR $name")
                    }
                    if (mode.second) execute("ALTER TABLE $table ALTER COLUMN $name VARCHAR(16) NOT NULL")
                }
                else -> {
                    if (!hostedDefault(mode.third)) execute("ALTER TABLE $table ALTER COLUMN $name SET DEFAULT 'HOSTED'")
                    if (mode.second) execute("ALTER TABLE $table ALTER COLUMN $name SET NOT NULL")
                }
            }
        }
        val migrated = checkNotNull(column())
        check(!migrated.second && hostedDefault(migrated.third)) { "Repository mode default/not-null migration incomplete" }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table WHERE $name IS NULL").use { rows ->
                check(rows.next() && rows.getLong(1) == 0L) { "Repository mode backfill incomplete" }
            }
        }
        return true
    }
}
