package com.github.yonaprojects.yona.domain.issue

import jakarta.persistence.*
import org.hibernate.Session
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.*
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection

@Entity
@Table(name = "issue_search_pending")
class IssueSearchPending(
    @Id @Column(name = "issue_id") var issueId: Long = 0,
    @Column(nullable = false) var generation: Long = 0
)

@Entity
@Table(name = "issue_search_window")
class IssueSearchWindow(
    @Id var id: Int = 1,
    @Column(nullable = false) var generation: Long = 0,
    @Column(name = "rebuild_generation", nullable = false) var rebuildGeneration: Long = 0,
    @Column(name = "first_change") var firstChange: Long? = null,
    @Column(name = "last_change") var lastChange: Long? = null
)

data class SearchChangeBatch(val versions: Map<Long, Long>, val rebuildGeneration: Long, val startedAt: Long)
data class SearchChangeWindow(val first: Long?, val last: Long?) {
    fun due(now: Long, windowMillis: Long): Boolean =
        first != null && now - first >= windowMillis
}

/** Durable dirty-ID set and ONE global fixed batch window, committed with the source mutation. */
@Component
@ConditionalOnProperty(name = ["yona.search.backend"], havingValue = "lucene")
class IssueSearchChanges(private val em: EntityManager, private val emf: EntityManagerFactory,
    transactions: PlatformTransactionManager
) : SmartInitializingSingleton, PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {
    private val transaction = TransactionTemplate(transactions)

    override fun afterSingletonsInstantiated() {
        transaction.executeWithoutResult {
            if (em.find(IssueSearchWindow::class.java, 1) == null) em.persist(IssueSearchWindow())
        }
        val registry = emf.unwrap(SessionFactoryImplementor::class.java).serviceRegistry
            .getService(EventListenerRegistry::class.java)!!
        registry.appendListeners(EventType.POST_INSERT, this)
        registry.appendListeners(EventType.POST_UPDATE, this)
        registry.appendListeners(EventType.POST_DELETE, this)
    }

    override fun onPostInsert(event: PostInsertEvent) = record(event.entity, event.session)
    override fun onPostUpdate(event: PostUpdateEvent) = record(event.entity, event.session)
    override fun onPostDelete(event: PostDeleteEvent) = record(event.entity, event.session)

    private fun record(entity: Any, session: org.hibernate.engine.spi.SharedSessionContractImplementor) {
        val id = when (entity) {
            is Issue -> entity.id
            is IssueComment -> entity.issue.id
            else -> null
        } ?: return
        // Use the flush's JDBC connection. Calling repository.save here would recursively flush JPA.
        session.doWork { connection: Connection -> mark(connection, id, System.currentTimeMillis()) }
    }

    private fun mark(connection: Connection, issueId: Long?, now: Long) {
        // This short global row serializes marks/upserts and acknowledgements on all six DBs.
        connection.prepareStatement("update issue_search_window set generation = generation + 1, first_change = coalesce(first_change, ?), last_change = ? where id = 1").use {
            it.setLong(1, now); it.setLong(2, now)
            check(it.executeUpdate() == 1) { "Search batch window is missing" }
        }
        val generation = connection.prepareStatement("select generation from issue_search_window where id = 1").use {
            it.executeQuery().use { result -> check(result.next()); result.getLong(1) }
        }
        if (issueId == null) {
            connection.prepareStatement("update issue_search_window set rebuild_generation = ? where id = 1").use {
                it.setLong(1, generation); it.executeUpdate()
            }
        } else {
            val updated = connection.prepareStatement("update issue_search_pending set generation = ? where issue_id = ?").use {
                it.setLong(1, generation); it.setLong(2, issueId); it.executeUpdate()
            }
            if (updated == 0) connection.prepareStatement("insert into issue_search_pending (issue_id, generation) values (?, ?)").use {
                it.setLong(1, issueId); it.setLong(2, generation); it.executeUpdate()
            }
        }
    }

    fun requestRebuild() = jdbc { mark(it, null, System.currentTimeMillis()) }

    fun window(): SearchChangeWindow = jdbc { connection ->
        connection.prepareStatement("select first_change, last_change from issue_search_window where id = 1").use {
            it.executeQuery().use { rows ->
                check(rows.next())
                val first = rows.getLong(1).let { value -> if (rows.wasNull()) null else value }
                val last = rows.getLong(2).let { value -> if (rows.wasNull()) null else value }
                SearchChangeWindow(first, last)
            }
        }
    }

    fun snapshot(): SearchChangeBatch = jdbc { connection ->
        lock(connection)
        val versions = linkedMapOf<Long, Long>()
        connection.prepareStatement("select issue_id, generation from issue_search_pending order by issue_id").use {
            it.executeQuery().use { rows -> while (rows.next()) versions[rows.getLong(1)] = rows.getLong(2) }
        }
        val rebuild = connection.prepareStatement("select rebuild_generation from issue_search_window where id = 1").use {
            it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
        SearchChangeBatch(versions, rebuild, System.currentTimeMillis())
    }

    fun acknowledge(batch: SearchChangeBatch) = jdbc { connection ->
        lock(connection)
        connection.prepareStatement("delete from issue_search_pending where issue_id = ? and generation = ?").use {
            batch.versions.forEach { (id, version) -> it.setLong(1, id); it.setLong(2, version); it.addBatch() }
            it.executeBatch()
        }
        connection.prepareStatement("update issue_search_window set rebuild_generation = 0 where id = 1 and rebuild_generation = ?").use {
            it.setLong(1, batch.rebuildGeneration); it.executeUpdate()
        }
        val remains = connection.prepareStatement("select count(*) from issue_search_pending").use {
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) > 0 }
        }
        val rebuild = connection.prepareStatement("select rebuild_generation from issue_search_window where id = 1").use {
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) > 0 }
        }
        if (!remains && !rebuild) connection.prepareStatement("update issue_search_window set first_change = null, last_change = null where id = 1").use { it.executeUpdate() }
        else connection.prepareStatement("update issue_search_window set first_change = ? where id = 1").use {
            // Surviving versions arrived after this snapshot; start the next global window at the batch boundary.
            it.setLong(1, batch.startedAt); it.executeUpdate()
        }
    }

    private fun lock(connection: Connection) {
        connection.prepareStatement("update issue_search_window set generation = generation where id = 1").use {
            check(it.executeUpdate() == 1)
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T = transaction.execute {
        em.unwrap(Session::class.java).doReturningWork { connection: Connection -> block(connection) }
    }!!
}
