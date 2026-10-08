package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "repository_mirror", indexes = [Index(name = "ix_mirror_due", columnList = "enabled,status,next_sync_at")])
class RepositoryMirror(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    var project: Project = Project(),
    @Column(nullable = false, length = 2048)
    var sourceUrl: String = "",
    var sourceRepositoryUuid: String? = null,
    var localRepositoryUuid: String? = null,
    var credentialRef: String? = null,
    @Column(nullable = false)
    var generation: Long = 1,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    var status: RepositoryMirrorStatus = RepositoryMirrorStatus.INITIAL_IMPORT,
    @Column(nullable = false)
    var enabled: Boolean = true,
    var initialImportTargetRevision: Long? = null,
    @Column(nullable = false)
    var lastVerifiedRevision: Long = -1,
    @Column(nullable = false)
    var lastIndexedRevision: Long = -1,
    @Column(nullable = false)
    var localYoungestRevision: Long = -1,
    @Column(nullable = false)
    var sourceYoungestRevision: Long = -1,
    var lastSyncedAt: Instant? = null,
    var nextSyncAt: Instant? = null,
    @Column(nullable = false)
    var retryCount: Int = 0,
    @Column(length = 64)
    var lastErrorCode: String? = null,
    @Column(length = 512)
    var lastError: String? = null,
    var leaseOwner: String? = null,
    var leaseUntil: Instant? = null,
    @Column(nullable = false)
    var fence: Long = 0,
    @Version
    var version: Long = 0
) {
    fun checkCursors() {
        if (generation < 1 || lastIndexedRevision < -1 || lastVerifiedRevision < -1 ||
            lastIndexedRevision > lastVerifiedRevision || lastVerifiedRevision > localYoungestRevision ||
            (initialImportTargetRevision != null && initialImportTargetRevision!! < 0) ||
            (lastVerifiedRevision >= 0 && (sourceRepositoryUuid.isNullOrBlank() || localRepositoryUuid.isNullOrBlank() || initialImportTargetRevision == null))) {
            throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
        }
    }
}

enum class RepositoryMirrorStatus { INITIAL_IMPORT, INCREMENTAL, FAILED, NEEDS_ATTENTION }

class MirrorLeaseLost : RuntimeException("Mirror execution no longer owns an active lease")

// Only fixed codes reach persisted errors; SVN exceptions may contain source credentials.
class MirrorFailure(val code: String, val attention: Boolean = false, val transient: Boolean = false) : RuntimeException(code)
