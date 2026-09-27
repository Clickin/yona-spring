package com.github.yonaprojects.yona.queue

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Tuple
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

internal data class QueueResultArtifact(val storagePath: String, val fileName: String, val sizeBytes: Long, val sha256: String)

@Service
internal class QueueAdminQueries(private val entityManagerFactory: EntityManagerFactory) {
    private val json = JsonMapper.builder().build()

    fun list(statuses: List<String>, type: String?, resource: String?, cursor: String?, limit: Int): Map<String, Any?> =
        read { entityManager ->
            requireLimit(limit)
            val states = statuses.distinct().sorted().map { value ->
                QueueStatus.entries.find { it.name == value } ?: invalid()
            }
            if (type != null && !TaskRegistry.TYPE.matches(type)) invalid()
            if (resource != null && (resource.length > 300 || !TaskRegistry.RESOURCE.matches(resource))) invalid()
            val filter = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (states.joinToString(",") { it.name } + "\n" + (type ?: "") + "\n" + (resource ?: "")).toByteArray(Charsets.UTF_8),
            ))
            val before = cursor?.let { decodeCursor(it, "jobs:$filter") }
            val predicates = mutableListOf<String>()
            val parameters = mutableMapOf<String, Any>()
            if (states.isNotEmpty()) { predicates += "j.status in :states"; parameters["states"] = states }
            if (type != null) { predicates += "j.taskType = :type"; parameters["type"] = type }
            if (resource != null) {
                predicates += "exists (select r.id.jobId from QueueJobResource r where r.id.jobId = j.id and r.id.resourceKey = :resource)"
                parameters["resource"] = resource
            }
            if (before != null) { predicates += "j.id < :before"; parameters["before"] = before }
            // Read generation first: a later concurrent commit must still invalidate this snapshot.
            val generation = entityManager.createQuery(
                "select c.value from QueueCounter c where c.name = 'change-generation'", Long::class.javaObjectType,
            ).singleResult
            val where = if (predicates.isEmpty()) "" else " where " + predicates.joinToString(" and ")
            val rows = query(entityManager, "select $JOB_COLUMNS from QueueJob j$where order by j.id desc", parameters, limit + 1)
            val page = rows.take(limit)
            linkedMapOf(
                "items" to summaries(entityManager, page),
                "nextCursor" to if (rows.size > limit) encodeCursor("jobs:$filter", page.last().number("id")) else null,
                "snapshotGeneration" to generation.toString(),
            )
        }

    fun detail(jobId: Long, cursor: String?, limit: Int): Map<String, Any?> = read { entityManager ->
        requireLimit(limit)
        val before = cursor?.let { decodeCursor(it, "attempts:$jobId") }
        val row = job(entityManager, jobId)
        val parameters = mutableMapOf<String, Any>("id" to jobId)
        val predicate = if (before == null) "" else " and a.id.attemptNo < :before".also { parameters["before"] = before }
        val attempts = query(entityManager,
            "select $ATTEMPT_COLUMNS from QueueAttempt a where a.id.jobId = :id$predicate order by a.id.attemptNo desc",
            parameters, limit + 1,
        )
        val page = attempts.take(limit)
        summaries(entityManager, listOf(row)).single().toMutableMap().apply {
            put("attempts", page.map(::attempt))
            put("nextAttemptCursor", if (attempts.size > limit) encodeCursor("attempts:$jobId", page.last().number("attemptNo")) else null)
            put("attemptHistoryCount", row.number("attemptCount").toString())
        }
    }

    fun summary(jobId: Long): Map<String, Any?> = read { entityManager ->
        summaries(entityManager, listOf(job(entityManager, jobId))).single()
    }

    fun result(jobId: Long): QueueResultArtifact = read { entityManager ->
        val row = job(entityManager, jobId)
        if (row.get("status") != QueueStatus.SUCCEEDED) throw QueueHttpFailure(404, "NOT_FOUND", "No result is available")
        val artifacts = artifactRows(entityManager, listOf(jobId))
        if (artifacts.isEmpty()) throw QueueHttpFailure(404, "NOT_FOUND", "No result is available")
        if (artifacts.size != 1) throw QueueHttpFailure(409, "RESULT_CONFLICT", "The result reference is inconsistent")
        val artifact = artifacts.single()
        QueueResultArtifact(
            artifact.get("storagePath", String::class.java), Path.of(artifact.get("relativePath", String::class.java)).fileName.toString(),
            artifact.number("sizeBytes"), artifact.get("sha256", String::class.java),
        )
    }

    private fun job(entityManager: EntityManager, id: Long): Tuple = query(entityManager,
        "select $JOB_COLUMNS from QueueJob j where j.id = :id", mapOf("id" to id), 1)
        .firstOrNull() ?: throw QueueHttpFailure(404, "NOT_FOUND", "Queue job not found")

    private fun summaries(entityManager: EntityManager, rows: List<Tuple>): List<Map<String, Any?>> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.number("id") }
        val resources = query(entityManager,
            "select r.id.jobId as jobId, r.id.resourceKey as resourceKey from QueueJobResource r where r.id.jobId in :ids order by r.id.resourceKey",
            mapOf("ids" to ids), ids.size * 16 + 1,
        ).groupBy { it.number("jobId") }
        val latest = query(entityManager,
            "select $ATTEMPT_COLUMNS from QueueAttempt a join a.job j where j.id in :ids and a.id.attemptNo = j.attemptCount and a.executionGeneration = j.executionGeneration",
            mapOf("ids" to ids), ids.size,
        ).associateBy { it.number("jobId") }
        val artifacts = artifactRows(entityManager, ids).groupBy { it.number("jobId") }
        return rows.map { row ->
            val id = row.number("id")
            val keys = resources[id].orEmpty().map { it.get("resourceKey", String::class.java) }
            check(keys.size <= 16) { "Queue resource projection exceeds its producer bound" }
            val resultRows = artifacts[id].orEmpty()
            check(resultRows.size <= 1) { "Queue result projection has multiple artifacts" }
            val last = latest[id]
            linkedMapOf<String, Any?>(
                "id" to id.toString(), "type" to row.get("taskType"), "payloadVersion" to row.get("payloadVersion"),
                "status" to (row.get("status") as QueueStatus).name,
                "failureDisposition" to (row.get("failureDisposition") as FailureDisposition?)?.name,
                "resources" to keys, "scheduledAt" to row.instant("scheduledAt"),
                "nextAttemptAt" to row.instant("nextAttemptAt"), "createdAt" to row.instant("createdAt"),
                "startedAt" to last?.instant("startedAt"), "updatedAt" to row.instant("updatedAt"),
                "finishedAt" to row.instant("finishedAt"), "attemptCount" to row.number("attemptCount").toString(),
                "executionGeneration" to row.number("executionGeneration").toString(),
                "ownerInstance" to last?.get("ownerInstance"), "progress" to progress(row),
                "errorCode" to row.get("errorCode"), "errorSummary" to row.get("errorSummary"),
                "result" to resultRows.singleOrNull()?.takeIf { row.get("status") == QueueStatus.SUCCEEDED }?.let {
                    linkedMapOf(
                        "fileName" to Path.of(it.get("relativePath", String::class.java)).fileName.toString(),
                        "mediaType" to "application/octet-stream", "sizeBytes" to it.number("sizeBytes").toString(),
                        "sha256" to it.get("sha256"), "downloadAvailable" to true,
                    )
                },
            )
        }
    }

    private fun artifactRows(entityManager: EntityManager, ids: List<Long>): List<Tuple> = query(entityManager,
        "select a.job.id as jobId, a.storagePath as storagePath, a.relativePath as relativePath, a.sizeBytes as sizeBytes, a.sha256 as sha256 from QueueArtifact a where a.job.id in :ids",
        mapOf("ids" to ids), ids.size + 1,
    )

    private fun attempt(row: Tuple): Map<String, Any?> = linkedMapOf(
        "attemptNo" to row.number("attemptNo").toString(), "executionGeneration" to row.number("executionGeneration").toString(),
        "generationAttemptNo" to row.get("generationAttemptNo"), "fence" to row.number("fence").toString(),
        "status" to (row.get("outcome") as AttemptOutcome).name, "startedAt" to row.instant("startedAt"),
        "finishedAt" to row.instant("finishedAt"), "ownerInstance" to row.get("ownerInstance"),
        "errorCode" to row.get("errorCode"), "errorSummary" to row.get("errorSummary"), "progress" to progress(row),
    )

    private fun progress(row: Tuple): Map<String, Any?>? {
        val stage = row.get("progressStage") as String?
        val encoded = row.get("progressJson") as String?
        if (stage == null && encoded == null) return null
        val counters = if (encoded == null) emptyMap() else json.readTree(encoded).properties()
            .associate { it.key to it.value.longValue().toString() }
        return linkedMapOf("stage" to stage, "counters" to counters)
    }

    private fun query(entityManager: EntityManager, hql: String, parameters: Map<String, Any>, limit: Int): List<Tuple> =
        entityManager.createQuery(hql, Tuple::class.java).also { query -> parameters.forEach { (key, value) -> query.setParameter(key, value) } }
            .setMaxResults(limit).resultList

    private fun <T> read(block: (EntityManager) -> T): T = entityManagerFactory.createEntityManager().use { manager ->
        // REQUIRES_NEW still reuses a nontransactional OSIV context; own and close this read context explicitly.
        val transaction = manager.transaction
        try {
            transaction.begin()
            val result = block(manager)
            transaction.commit()
            result
        } catch (failure: Throwable) {
            runCatching { if (transaction.isActive) transaction.rollback() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun Tuple.number(name: String): Long = (get(name) as Number).toLong()
    private fun Tuple.instant(name: String): String? = (get(name) as Number?)?.let { Instant.ofEpochMilli(it.toLong()).toString() }
    private fun requireLimit(limit: Int) { if (limit !in 1..100) invalid() }
    private fun invalid(): Nothing = throw QueueHttpFailure(400, "INVALID_REQUEST", "Invalid queue query")

    private fun encodeCursor(scope: String, before: Long): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$scope|$before".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String, scope: String): Long {
        if (cursor.length !in 1..512) invalid()
        val decoded = try { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) } catch (_: IllegalArgumentException) { invalid() }
        if (!decoded.startsWith("$scope|")) invalid()
        val value = decoded.substring(scope.length + 1)
        val before = value.toLongOrNull()?.takeIf { it > 0 && it.toString() == value } ?: invalid()
        if (encodeCursor(scope, before) != cursor) invalid()
        return before
    }

    companion object {
        private const val JOB_COLUMNS = "j.id as id, j.taskType as taskType, j.payloadVersion as payloadVersion, j.status as status, " +
            "j.failureDisposition as failureDisposition, j.scheduledAt as scheduledAt, j.nextAttemptAt as nextAttemptAt, " +
            "j.createdAt as createdAt, j.updatedAt as updatedAt, j.finishedAt as finishedAt, j.attemptCount as attemptCount, " +
            "j.executionGeneration as executionGeneration, j.progressStage as progressStage, j.progressJson as progressJson, " +
            "j.errorCode as errorCode, j.errorSummary as errorSummary"
        private const val ATTEMPT_COLUMNS = "a.id.jobId as jobId, a.id.attemptNo as attemptNo, a.executionGeneration as executionGeneration, " +
            "a.generationAttemptNo as generationAttemptNo, a.fence as fence, a.outcome as outcome, a.startedAt as startedAt, " +
            "a.finishedAt as finishedAt, a.ownerInstance as ownerInstance, a.errorCode as errorCode, a.errorSummary as errorSummary, " +
            "a.progressStage as progressStage, a.progressJson as progressJson"
    }
}
