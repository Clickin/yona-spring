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
import java.util.UUID

@Entity
@Table(name = "issue_search_change", indexes = [Index(name = "ix_issue_search_change_time", columnList = "changed_at")])
class IssueSearchChange(
    @Id @Column(length = 36) var id: String = "",
    @Column(name = "issue_id") var issueId: Long? = null,
    @Column(name = "changed_at", nullable = false) var changedAt: Long = 0
)

data class SearchChangeBatch(val eventIds: List<String>, val issueIds: List<Long>, val rebuild: Boolean)
data class SearchChangeWindow(val first: Long?, val last: Long?) {
    fun due(now: Long, windowMillis: Long): Boolean =
        first != null && now - first >= windowMillis
}

/** Append-only events commit with the source mutation, without locking any shared bookkeeping row. */
@Component
@ConditionalOnProperty(name = ["yona.search.backend"], havingValue = "lucene")
class IssueSearchChanges(private val em: EntityManager, private val emf: EntityManagerFactory,
    transactions: PlatformTransactionManager
) : SmartInitializingSingleton, PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {
    private val transaction = TransactionTemplate(transactions)

    override fun afterSingletonsInstantiated() {
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
        session.doWork { connection: Connection -> mark(connection, id) }
    }

    private fun mark(connection: Connection, issueId: Long?) {
        connection.prepareStatement("insert into issue_search_change (id, issue_id, changed_at) values (?, ?, ?)").use {
            it.setString(1, UUID.randomUUID().toString())
            if (issueId == null) it.setNull(2, java.sql.Types.BIGINT) else it.setLong(2, issueId)
            it.setLong(3, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    fun requestRebuild() = jdbc { mark(it, null) }

    fun window(): SearchChangeWindow = jdbc { connection ->
        connection.prepareStatement("select min(changed_at), max(changed_at) from issue_search_change").use {
            it.executeQuery().use { rows ->
                check(rows.next())
                val first = rows.getLong(1).let { value -> if (rows.wasNull()) null else value }
                val last = rows.getLong(2).let { value -> if (rows.wasNull()) null else value }
                SearchChangeWindow(first, last)
            }
        }
    }

    fun snapshot(): SearchChangeBatch = jdbc { connection ->
        val events = mutableListOf<String>()
        val issues = linkedSetOf<Long>()
        var rebuild = false
        connection.prepareStatement("select id, issue_id from issue_search_change order by changed_at, id").use {
            it.maxRows = 1000
            it.executeQuery().use { rows ->
                while (rows.next()) {
                    events.add(rows.getString(1))
                    val issueId = rows.getLong(2)
                    if (rows.wasNull()) rebuild = true else issues.add(issueId)
                }
            }
        }
        SearchChangeBatch(events, issues.toList(), rebuild)
    }

    fun acknowledge(batch: SearchChangeBatch) = jdbc { connection ->
        // No timestamp/sequence high-watermark: a transaction that started earlier can commit later.
        // Delete only events actually visible to this pass, and only after the index was published.
        connection.prepareStatement("delete from issue_search_change where id = ?").use {
            batch.eventIds.forEach { id -> it.setString(1, id); it.addBatch() }
            it.executeBatch()
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T = transaction.execute {
        em.unwrap(Session::class.java).doReturningWork { connection: Connection -> block(connection) }
    }!!
}
