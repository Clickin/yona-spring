package com.github.yonaprojects.yona.queue

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.io.Serializable

const val QUEUE_PRIORITY_MIN: Short = -128
const val QUEUE_PRIORITY_DEFAULT: Short = 0
const val QUEUE_PRIORITY_MAX: Short = 127

@Entity
@Table(name = "queue_meta")
class QueueCounter(
    @Id @Column(name = "counter_name", length = 32) var name: String = "",
    @Column(name = "counter_value", nullable = false) var value: Long = 0,
)

@Entity
@Table(name = "queue_job", indexes = [
    Index(name = "queue_job_ready", columnList = "status,priority,id"),
    Index(name = "queue_job_type", columnList = "task_type,id"),
])
class QueueJob(
    @Id var id: Long = 0,
    @Column(name = "task_type", nullable = false, length = 120) var taskType: String = "",
    @Column(name = "payload_version", nullable = false) var payloadVersion: Int = 1,
    // CUBRID measures BIT VARYING length in bits. Admission still caps payloads at 1 MiB on every DB.
    @Column(nullable = false, length = 8_388_608) var payload: ByteArray = byteArrayOf(),
    @Column(name = "payload_hash", nullable = false, length = 64) var payloadHash: String = "",
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    var status: QueueStatus = QueueStatus.QUEUED,
    @Column(nullable = false, columnDefinition = "SMALLINT DEFAULT 0") var priority: Short = QUEUE_PRIORITY_DEFAULT,
    @Column(name = "created_at_epoch_ms", nullable = false) var createdAt: Long = 0,
    @Column(name = "updated_at_epoch_ms", nullable = false) var updatedAt: Long = 0,
    @Column(name = "scheduled_at_epoch_ms", nullable = false) var scheduledAt: Long = 0,
    @Column(name = "next_attempt_at_epoch_ms") var nextAttemptAt: Long? = null,
    @Column(name = "finished_at_epoch_ms") var finishedAt: Long? = null,
    @Column(name = "execution_generation", nullable = false) var executionGeneration: Long = 1,
    @Column(name = "generation_attempt_no", nullable = false) var generationAttemptNo: Int = 0,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Long = 0,
    @Column(name = "active_attempt_no") var activeAttemptNo: Long? = null,
    @Column(name = "next_fence", nullable = false) var nextFence: Long = 0,
    @Enumerated(EnumType.STRING) @Column(name = "failure_disposition", length = 24)
    var failureDisposition: FailureDisposition? = null,
    @Version @Column(name = "row_version", nullable = false) var rowVersion: Long = 0,
)

@Entity
@Table(name = "queue_idempotency_key")
class QueueIdempotencyKey(
    @Id @Column(name = "key_digest", length = 64) var digest: String = "",
    @Column(name = "task_type", nullable = false, length = 120) var taskType: String = "",
    @Column(name = "caller_scope", nullable = false, length = 200) var callerScope: String = "",
    @Column(name = "request_key", nullable = false, length = 200) var requestKey: String = "",
    @Column(name = "payload_version", nullable = false) var payloadVersion: Int = 1,
    @Column(name = "payload_hash", nullable = false, length = 64) var payloadHash: String = "",
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id", nullable = false, unique = true)
    var job: QueueJob? = null,
)

@Embeddable
data class QueueAttemptId(
    @Column(name = "job_id") var jobId: Long = 0,
    @Column(name = "attempt_no") var attemptNo: Long = 0,
) : Serializable

@Entity
@Table(name = "queue_attempt", indexes = [
    Index(name = "queue_attempt_lease", columnList = "outcome,lease_expires_at_epoch_ms,job_id"),
], uniqueConstraints = [UniqueConstraint(name = "queue_attempt_fence", columnNames = ["job_id", "fence"])])
class QueueAttempt(
    @EmbeddedId var id: QueueAttemptId = QueueAttemptId(),
    @MapsId("jobId") @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id", nullable = false)
    var job: QueueJob? = null,
    @Column(name = "execution_generation", nullable = false) var executionGeneration: Long = 1,
    @Column(name = "generation_attempt_no", nullable = false) var generationAttemptNo: Int = 1,
    @Column(nullable = false) var fence: Long = 0,
    @Column(name = "owner_instance", nullable = false, length = 128) var ownerInstance: String = "",
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    var outcome: AttemptOutcome = AttemptOutcome.RUNNING,
    @Column(name = "started_at_epoch_ms", nullable = false) var startedAt: Long = 0,
    @Column(name = "finished_at_epoch_ms") var finishedAt: Long? = null,
    @Column(name = "heartbeat_at_epoch_ms", nullable = false) var heartbeatAt: Long = 0,
    @Column(name = "lease_expires_at_epoch_ms", nullable = false) var leaseExpiresAt: Long = 0,
    @Column(name = "error_code", length = 80) var errorCode: String? = null,
    @Column(name = "error_summary", length = 4096) var errorSummary: String? = null,
)

@Embeddable
data class QueueJobResourceId(
    @Column(name = "job_id") var jobId: Long = 0,
    @Column(name = "resource_key", length = 300) var resourceKey: String = "",
) : Serializable

@Entity
@Table(name = "queue_job_resource", indexes = [Index(name = "queue_resource_jobs", columnList = "resource_key,job_id")])
class QueueJobResource(
    @EmbeddedId var id: QueueJobResourceId = QueueJobResourceId(),
    @MapsId("jobId") @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id", nullable = false)
    var job: QueueJob? = null,
)
