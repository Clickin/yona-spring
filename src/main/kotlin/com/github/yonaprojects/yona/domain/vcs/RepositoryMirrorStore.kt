package com.github.yonaprojects.yona.domain.vcs

import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class RepositoryMirrorStore(
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val runnable = listOf(RepositoryMirrorStatus.INITIAL_IMPORT, RepositoryMirrorStatus.INCREMENTAL)

    fun databaseNow(): Instant = jdbc.execute(org.springframework.jdbc.core.ConnectionCallback { connection ->
        val sql = when (connection.metaData.databaseProductName) {
            "PostgreSQL" -> "select floor(extract(epoch from clock_timestamp()) * 1000)::bigint"
            "Microsoft SQL Server" -> "select datediff_big(millisecond, '1970-01-01', sysutcdatetime())"
            "MySQL", "MariaDB" -> "select cast(unix_timestamp(now(3)) * 1000 as signed)"
            "CUBRID" -> "select new_time(current_datetime, sessiontimezone(), 'UTC') - datetime '1970-01-01 00:00:00'"
            "H2" -> "select datediff('MILLISECOND', timestamp with time zone '1970-01-01 00:00:00+00:00', (executing_statement_start at time zone 'UTC')) from information_schema.sessions where session_id = session_id()"
            else -> throw IllegalStateException("Unsupported mirror database")
        }
        connection.createStatement().use { statement ->
            statement.queryTimeout = 5
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                Instant.ofEpochMilli(rows.getLong(1))
            }
        }
    })!!

    fun snapshot(id: Long): RepositoryMirror = transaction.execute {
        entityManager.createQuery("select m from RepositoryMirror m join fetch m.project where m.id = :id", RepositoryMirror::class.java)
            .setParameter("id", id).resultList.firstOrNull()?.also { entityManager.detach(it) }
            ?: throw MirrorFailure("MIRROR_NOT_FOUND")
    }!!

    fun due(limit: Int): List<Long> = transaction.execute {
        entityManager.createQuery("select m.id from RepositoryMirror m where m.enabled = true and m.status in :statuses and (m.nextSyncAt is null or m.nextSyncAt <= :now) and (m.leaseUntil is null or m.leaseUntil <= :now) order by m.id", Long::class.javaObjectType)
            .setParameter("statuses", runnable).setParameter("now", databaseNow()).setMaxResults(limit).resultList
    }!!

    fun claim(id: Long, owner: String, leaseSeconds: Long): Long? = transaction.execute {
        val before = snapshot(id)
        val now = databaseNow()
        val count = entityManager.createQuery("update RepositoryMirror m set m.leaseOwner = :owner, m.leaseUntil = :until, m.fence = m.fence + 1, m.version = m.version + 1 where m.id = :id and m.version = :version and m.enabled = true and m.status in :statuses and (m.nextSyncAt is null or m.nextSyncAt <= :now) and (m.leaseUntil is null or m.leaseUntil <= :now)")
            .setParameter("owner", owner).setParameter("until", now.plusSeconds(leaseSeconds))
            .setParameter("id", id).setParameter("version", before.version).setParameter("statuses", runnable).setParameter("now", now)
            .executeUpdate()
        if (count == 1) before.fence + 1 else null
    }

    fun heartbeat(id: Long, owner: String, fence: Long, leaseSeconds: Long): Boolean = transaction.execute {
        val now = databaseNow()
        entityManager.createQuery("update RepositoryMirror m set m.leaseUntil = :until, m.version = m.version + 1 where m.id = :id and m.leaseOwner = :owner and m.fence = :fence and m.leaseUntil > :now and m.enabled = true and m.status in :statuses")
            .setParameter("until", now.plusSeconds(leaseSeconds)).setParameter("id", id).setParameter("owner", owner)
            .setParameter("fence", fence).setParameter("now", now).setParameter("statuses", runnable).executeUpdate() == 1
    }!!

    fun <T> fenced(id: Long, owner: String, fence: Long, action: (RepositoryMirror) -> T): T {
        var result: T? = null
        transaction.executeWithoutResult {
            // Conditional UPDATE supplies the row lock on all six DBs, without dialect-specific FOR UPDATE.
            val count = entityManager.createQuery("update RepositoryMirror m set m.version = m.version + 1 where m.id = :id and m.leaseOwner = :owner and m.fence = :fence and m.leaseUntil > :now and m.enabled = true and m.status in :statuses")
                .setParameter("id", id).setParameter("owner", owner).setParameter("fence", fence)
                .setParameter("now", databaseNow()).setParameter("statuses", runnable).executeUpdate()
            if (count != 1) throw MirrorLeaseLost()
            val mirror = entityManager.find(RepositoryMirror::class.java, id)
            entityManager.refresh(mirror)
            mirror.checkCursors()
            val initialTarget = mirror.initialImportTargetRevision
            val sourceUuid = mirror.sourceRepositoryUuid
            val localUuid = mirror.localRepositoryUuid
            val generation = mirror.generation
            val indexed = mirror.lastIndexedRevision
            val verified = mirror.lastVerifiedRevision
            result = action(mirror)
            mirror.checkCursors()
            if ((initialTarget != null && mirror.initialImportTargetRevision != initialTarget) ||
                (sourceUuid != null && mirror.sourceRepositoryUuid != sourceUuid) ||
                (localUuid != null && mirror.localRepositoryUuid != localUuid) ||
                mirror.generation != generation || mirror.lastIndexedRevision < indexed || mirror.lastVerifiedRevision < verified) {
                throw MirrorFailure("IMMUTABLE_CHECKPOINT_CHANGED", attention = true)
            }
            entityManager.flush()
            if (mirror.leaseUntil?.isAfter(databaseNow()) != true) throw MirrorLeaseLost()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun observe(id: Long, owner: String, fence: Long, sourceUuid: String, sourceHead: Long, localUuid: String, localHead: Long): RepositoryMirror = fenced(id, owner, fence) { mirror ->
        if (mirror.sourceRepositoryUuid != null && mirror.sourceRepositoryUuid != sourceUuid) throw MirrorFailure("SOURCE_IDENTITY_CHANGED", attention = true)
        if (mirror.localRepositoryUuid != null && mirror.localRepositoryUuid != localUuid) throw MirrorFailure("TARGET_IDENTITY_CHANGED", attention = true)
        if (sourceHead < mirror.sourceYoungestRevision || sourceHead < (mirror.initialImportTargetRevision ?: 0) || localHead < mirror.lastVerifiedRevision || localHead > sourceHead) {
            throw MirrorFailure("REVISION_HISTORY_CHANGED", attention = true)
        }
        mirror.sourceRepositoryUuid = sourceUuid
        mirror.localRepositoryUuid = localUuid
        if (mirror.initialImportTargetRevision == null) mirror.initialImportTargetRevision = sourceHead
        mirror.sourceYoungestRevision = sourceHead
        mirror.localYoungestRevision = localHead
        mirror
    }

    fun verified(id: Long, owner: String, fence: Long, revision: Long, localHead: Long) = fenced(id, owner, fence) { mirror ->
        if (revision != mirror.lastVerifiedRevision + 1 || revision > localHead) throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
        mirror.localYoungestRevision = localHead
        mirror.lastVerifiedRevision = revision
    }

    fun complete(id: Long, owner: String, fence: Long, syncSeconds: Long) = fenced(id, owner, fence) { mirror ->
        if (mirror.lastIndexedRevision != mirror.lastVerifiedRevision) throw MirrorFailure("INDEX_INCOMPLETE", attention = true)
        if (mirror.lastIndexedRevision >= (mirror.initialImportTargetRevision ?: Long.MAX_VALUE)) mirror.status = RepositoryMirrorStatus.INCREMENTAL
        val now = databaseNow()
        mirror.lastSyncedAt = now
        mirror.nextSyncAt = now.plusSeconds(syncSeconds)
        mirror.retryCount = 0
        mirror.lastErrorCode = null
        mirror.lastError = null
        // Release separately, after this transaction's final lease validation.
    }

    fun release(id: Long, owner: String, fence: Long) = transaction.executeWithoutResult {
        entityManager.createQuery("update RepositoryMirror m set m.leaseOwner = null, m.leaseUntil = null, m.version = m.version + 1 where m.id = :id and m.leaseOwner = :owner and m.fence = :fence")
            .setParameter("id", id).setParameter("owner", owner).setParameter("fence", fence).executeUpdate()
    }

    fun fail(id: Long, owner: String, fence: Long, failure: MirrorFailure) = transaction.executeWithoutResult {
        val count = entityManager.createQuery("update RepositoryMirror m set m.version = m.version + 1 where m.id = :id and m.leaseOwner = :owner and m.fence = :fence and m.leaseUntil > :now and m.enabled = true and m.status in :statuses")
            .setParameter("id", id).setParameter("owner", owner).setParameter("fence", fence)
            .setParameter("now", databaseNow()).setParameter("statuses", runnable).executeUpdate()
        if (count != 1) throw MirrorLeaseLost()
        val mirror = entityManager.find(RepositoryMirror::class.java, id)
        entityManager.refresh(mirror)
        // Invalid checkpoints must be quarantined, not rejected by checkCursors again.
        mirror.retryCount += 1
        mirror.lastErrorCode = failure.code.take(64)
        mirror.lastError = when {
            failure.attention -> "Repository identity, checkpoints or exclusive access require administrator review."
            failure.transient -> "The source could not be reached. Check network access and retry settings."
            else -> "Synchronization stopped. Check the source policy, TLS and credential reference."
        }
        mirror.status = when {
            failure.attention -> RepositoryMirrorStatus.NEEDS_ATTENTION
            !failure.transient || mirror.retryCount >= 5 -> RepositoryMirrorStatus.FAILED
            else -> mirror.status
        }
        if (failure.transient && mirror.retryCount < 5) {
            mirror.nextSyncAt = databaseNow().plusSeconds(minOf(900L, 5L shl (mirror.retryCount - 1)))
        }
        entityManager.flush()
        if (mirror.leaseUntil?.isAfter(databaseNow()) != true) throw MirrorLeaseLost()
    }

    fun pause(id: Long) = transaction.executeWithoutResult {
        entityManager.createQuery("update RepositoryMirror m set m.enabled = false, m.version = m.version + 1 where m.id = :id")
            .setParameter("id", id).executeUpdate()
    }

    fun retry(id: Long) = changeIdle(id) { mirror ->
        mirror.checkCursors()
        mirror.enabled = true
        mirror.status = if (mirror.lastIndexedRevision >= (mirror.initialImportTargetRevision ?: Long.MAX_VALUE)) RepositoryMirrorStatus.INCREMENTAL else RepositoryMirrorStatus.INITIAL_IMPORT
        mirror.retryCount = 0
        mirror.nextSyncAt = databaseNow()
        mirror.lastError = null
        mirror.lastErrorCode = null
    }

    fun setCredential(id: Long, ref: String?) = changeIdle(id) { it.credentialRef = ref }

    private fun changeIdle(id: Long, action: (RepositoryMirror) -> Unit) = transaction.executeWithoutResult {
        val count = entityManager.createQuery("update RepositoryMirror m set m.version = m.version + 1 where m.id = :id and (m.leaseUntil is null or m.leaseUntil <= :now)")
            .setParameter("id", id).setParameter("now", databaseNow()).executeUpdate()
        if (count != 1) throw MirrorFailure("MIRROR_BUSY")
        val mirror = entityManager.find(RepositoryMirror::class.java, id)
        entityManager.refresh(mirror)
        action(mirror)
    }
}
