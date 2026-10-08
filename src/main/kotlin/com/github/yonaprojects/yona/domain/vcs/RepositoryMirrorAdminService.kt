package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.*
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Instant

@Service
class RepositoryMirrorAdminService(
    private val settings: RepositoryMirrorProperties,
    private val sourcePolicy: SvnMirrorSourcePolicy,
    private val storage: RepositoryMirrorStorage,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val projectUsers: ProjectUserRepository,
    private val mirrors: RepositoryMirrorRepository,
    private val store: RepositoryMirrorStore,
    private val namespaceGuard: RepositoryNamespaceGuard,
    transactionManager: PlatformTransactionManager
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun list(loginId: String, page: Int): Page<RepositoryMirrorAdminStatus> = transaction.execute {
        admin(loginId)
        val now = store.databaseNow()
        mirrors.findAllByOrderByIdDesc(PageRequest.of(page.coerceAtLeast(0), 25)).map { RepositoryMirrorAdminStatus.from(it, now) }
    }!!

    fun create(loginId: String, name: String, sourceUrl: String, credentialRef: String?): Long {
        admin(loginId)
        settings.requireActivation()
        if (!RepositoryMirrorStorage.validSegment(name) || ProjectNameValidator.isRestricted(name)) throw MirrorFailure("PROJECT_NAME")
        val source = sourcePolicy.normalize(sourceUrl)
        val ref = credentialRef?.takeIf { it.isNotBlank() }
        sourcePolicy.validateCredentialRef(ref)
        try {
            return transaction.execute {
                val creator = admin(loginId)
                namespaceGuard.holdUntilTransactionCompletion(creator.loginId, name)
                existing(creator, name, source)?.let { return@execute it }
                if (projects.existsByOwnerIgnoreCaseAndNameIgnoreCaseAndIdNot(creator.loginId, name, -1)) {
                    throw MirrorFailure("PROJECT_EXISTS")
                }
                namespaceGuard.requireUnreserved(creator.loginId, name)
                val managerRole = roles.findById(RoleType.MANAGER.roleType).orElseThrow { MirrorFailure("MANAGER_ROLE_REQUIRED") }
                val project = projects.saveAndFlush(Project(
                    name = name, owner = creator.loginId, vcs = "SUBVERSION", createdDate = Instant.now(),
                    projectScope = ProjectScope.PRIVATE, repositoryMode = RepositoryMode.MIRROR,
                    isPullRequestEnabled = false
                ))
                val membership = projectUsers.save(ProjectUser(project = project, user = creator, role = managerRole))
                project.projectUsers.add(membership)
                storage.reserve(project, 1)
                mirrors.saveAndFlush(RepositoryMirror(project = project, sourceUrl = source, credentialRef = ref)).id!!
            }!!
        } catch (failure: Exception) {
            // DB commit and mkdir are not atomic. Re-query after the transaction has finished, never
            // delete/recreate a path on uncertainty. Only an exact persisted identity may be resumed.
            val committed = try {
                transaction.execute {
                    val creator = admin(loginId)
                    namespaceGuard.holdUntilTransactionCompletion(creator.loginId, name)
                    existing(creator, name, source)
                }
            } catch (_: Exception) { null }
            if (committed != null) return committed
            if (failure is MirrorFailure) throw failure
            if (failure is AccessDeniedException || failure is IllegalStateException) {
                throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
            }
            throw MirrorFailure("CREATE_UNCERTAIN", attention = true)
        }
    }

    fun pause(loginId: String, id: Long) {
        admin(loginId)
        requireEnabled()
        store.pause(id)
    }

    fun retry(loginId: String, id: Long) {
        admin(loginId)
        settings.requireActivation()
        val mirror = store.snapshot(id)
        sourcePolicy.normalize(mirror.sourceUrl)
        sourcePolicy.validateCredentialRef(mirror.credentialRef)
        store.retry(id)
    }

    fun setCredential(loginId: String, id: Long, credentialRef: String?) {
        admin(loginId)
        requireEnabled()
        val ref = credentialRef?.takeIf { it.isNotBlank() }
        sourcePolicy.validateCredentialRef(ref)
        store.setCredential(id, ref)
    }

    private fun requireEnabled() {
        if (!settings.enabled) throw MirrorFailure("FEATURE_DISABLED")
    }

    private fun admin(loginId: String): User {
        val user = users.findByLoginId(loginId).orElse(null)
        if (user?.isSiteManager != true) throw AccessDeniedException("Site administrator required")
        return user
    }

    private fun existing(creator: User, name: String, source: String): Long? {
        val project = projects.findByOwnerAndNameOrPreviousPlace(creator.loginId, name).orElse(null) ?: return null
        val mirror = mirrors.findByProjectId(project.id!!)
        if (project.owner != creator.loginId || project.name != name || project.repositoryMode != RepositoryMode.MIRROR ||
            mirror == null || mirror.sourceUrl != source
        ) throw MirrorFailure("PROJECT_EXISTS")
        storage.directory(mirror)
        return mirror.id!!
    }
}

data class RepositoryMirrorAdminStatus(
    val id: Long,
    val projectOwner: String,
    val projectName: String,
    val source: String,
    val credentialConfigured: Boolean,
    val status: String,
    val enabled: Boolean,
    val ready: Boolean,
    val initialImportTargetRevision: Long?,
    val lastVerifiedRevision: Long,
    val lastIndexedRevision: Long,
    val localYoungestRevision: Long,
    val sourceYoungestRevision: Long,
    val generation: Long,
    val lastSyncedAt: Instant?,
    val nextSyncAt: Instant?,
    val retryCount: Int,
    val hasError: Boolean,
    val errorMessage: String,
    val leaseActive: Boolean,
    val leaseUntil: Instant?
) {
    companion object {
        fun from(mirror: RepositoryMirror, now: Instant): RepositoryMirrorAdminStatus {
            val source = try {
                val uri = URI(mirror.sourceUrl)
                if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null) "[hidden]"
                else "https://${uri.host}:${if (uri.port == -1) 443 else uri.port}/[hidden]"
            } catch (_: Exception) { "[hidden]" }
            val target = mirror.initialImportTargetRevision
            return RepositoryMirrorAdminStatus(
                mirror.id!!, mirror.project.owner ?: "", mirror.project.name, source, mirror.credentialRef != null,
                mirror.status.name, mirror.enabled,
                mirror.status != RepositoryMirrorStatus.NEEDS_ATTENTION && target != null &&
                    mirror.lastVerifiedRevision >= target && mirror.lastIndexedRevision >= target,
                target, mirror.lastVerifiedRevision, mirror.lastIndexedRevision, mirror.localYoungestRevision, mirror.sourceYoungestRevision,
                mirror.generation, mirror.lastSyncedAt, mirror.nextSyncAt, mirror.retryCount,
                mirror.lastErrorCode != null || mirror.lastError != null,
                mirrorErrorMessage(mirror.lastErrorCode),
                mirror.leaseOwner != null && mirror.leaseUntil?.isAfter(now) == true, mirror.leaseUntil
            )
        }
    }
}

internal fun mirrorErrorMessage(code: String?): String = when (code) {
    "FEATURE_DISABLED", "ACTIVATION_REQUIRED" -> "mirror.error.activation"
    "SOURCE_POLICY" -> "mirror.error.sourcePolicy"
    "SOURCE_ROOT_REQUIRED", "SOURCE_REDIRECT" -> "mirror.error.sourceRoot"
    "CREDENTIAL_UNAVAILABLE" -> "mirror.error.credential"
    "DIRECTORY_OWNERSHIP", "CREATE_UNCERTAIN" -> "mirror.error.directory"
    "PROJECT_NAME", "PROJECT_EXISTS", "MANAGER_ROLE_REQUIRED" -> "mirror.error.project"
    "SOURCE_CONNECTION" -> "mirror.error.connection"
    else -> "mirror.actionFailed"
}
