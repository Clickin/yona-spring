package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.util.UUID

class IssueDependencySchemaTest {
    @Test
    fun additiveSchemaUpdatePreservesExistingIssuesAndParentRelations() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:dependency-upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE", "sa", "")
        val jdbc = JdbcTemplate(dataSource)
        fun factory() = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
            setJpaVendorAdapter(HibernateJpaVendorAdapter())
            setPackagesToScan("com.github.yonaprojects.yona.domain")
            setJpaPropertyMap(mapOf(
                "hibernate.hbm2ddl.auto" to "update",
                "hibernate.hbm2ddl.halt_on_error" to true,
                "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"
            ))
            afterPropertiesSet()
        }
        try {
            val first = factory()
            val ids = try {
                first.`object`!!.createEntityManager().use { em ->
                    em.transaction.begin()
                    val project = Project(name = "existing", owner = "existing")
                    em.persist(project)
                    val parent = Issue(title = "Existing parent", project = project, number = 1)
                    em.persist(parent)
                    val child = Issue(title = "Existing child", project = project, number = 2, parent = parent)
                    em.persist(child)
                    em.transaction.commit()
                    parent.id!! to child.id!!
                }
            } finally {
                first.destroy()
            }
            // The old schema has no dependency table. Its existing issue data is left untouched.
            jdbc.execute("drop table issue_dependency")
            val upgraded = factory()
            try {
                assertEquals("Existing child", jdbc.queryForObject("select title from issue where id = ?", String::class.java, ids.second))
                assertEquals(ids.first, jdbc.queryForObject("select parent_id from issue where id = ?", Long::class.java, ids.second))
                jdbc.update("insert into issue_dependency (predecessor_id, successor_id) values (?, ?)", ids.first, ids.second)
                assertThrows(DataIntegrityViolationException::class.java) {
                    jdbc.update("insert into issue_dependency (predecessor_id, successor_id) values (?, ?)", ids.first, ids.second)
                }
                assertThrows(DataIntegrityViolationException::class.java) {
                    jdbc.update("insert into issue_dependency (predecessor_id, successor_id) values (?, ?)", ids.first, ids.first)
                }
                assertThrows(DataIntegrityViolationException::class.java) {
                    jdbc.update("insert into issue_dependency (predecessor_id, successor_id) values (?, ?)", ids.first, -1)
                }
                assertEquals(1L, jdbc.queryForObject("select count(*) from issue_dependency", Long::class.java))
            } finally {
                upgraded.destroy()
            }
        } finally {
            jdbc.execute("shutdown")
        }
    }
}
