package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.enumeration.EventType
import com.github.yonaprojects.yona.domain.issue.IssueEvent
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import jakarta.persistence.EntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.SVNRevisionProperty
import org.tmatesoft.svn.core.SVNURL
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory

/**
 * Site-admin management of source author mappings. Changes take the mirror row lock (the same row
 * every fenced indexer transaction updates first), so a reference is either indexed before the
 * change and corrected here, or indexed after it with the new mapping.
 */
@Service
class RepositoryMirrorAuthorService(
    private val authors: RepositoryMirrorAuthorRepository,
    private val users: UserRepository,
    private val storage: RepositoryMirrorStorage,
    private val store: RepositoryMirrorStore,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun view(loginId: String, mirrorId: Long): RepositoryMirrorAuthorView {
        admin(loginId)
        val mirror = store.snapshot(mirrorId)
        val mappings = authors.findAllByMirrorIdOrderBySourceAuthorAsc(mirrorId)
            .map { RepositoryMirrorAuthorView.Mapping(it.id!!, it.sourceAuthor, it.user.loginId) }
        val mapped = mappings.map { it.sourceAuthor }.toSet()
        return RepositoryMirrorAuthorView(
            mirror.id!!, mirror.project.owner ?: "", mirror.project.name, mappings,
            sourceAuthors(mirror).filterNot { it in mapped }
        )
    }

    /** Returns how many existing references were attributed to the account. */
    fun add(loginId: String, mirrorId: Long, sourceAuthor: String, yonaLoginId: String): Int {
        admin(loginId)
        if (sourceAuthor.isBlank() || sourceAuthor.length > 255 || sourceAuthor.any { it.isISOControl() }) {
            throw MirrorFailure("AUTHOR_INVALID")
        }
        val user = users.findByLoginId(yonaLoginId.trim()).orElse(null)
            ?.takeIf { it.state != UserState.DELETED && !it.isGuest }
            ?: throw MirrorFailure("AUTHOR_USER")
        return try {
            transaction.execute {
                val mirror = lockMirror(mirrorId)
                if (authors.findUser(mirrorId, sourceAuthor) != null) throw MirrorFailure("AUTHOR_MAPPING_EXISTS")
                authors.saveAndFlush(RepositoryMirrorAuthor(mirror = mirror, sourceAuthor = sourceAuthor, user = user))
                // Only unattributed references: a reference already credited to an account is kept.
                reattribute(mirror, sourceAuthor, references(mirror, null), user)
            }!!
        } catch (_: DataIntegrityViolationException) {
            // A case-insensitive collation can treat a differently cased author as the same key.
            throw MirrorFailure("AUTHOR_MAPPING_EXISTS")
        }
    }

    /** Returns how many references lost their attribution. */
    fun remove(loginId: String, mirrorId: Long, mappingId: Long): Int {
        admin(loginId)
        return transaction.execute {
            val mirror = lockMirror(mirrorId)
            val mapping = authors.findByIdAndMirrorId(mappingId, mirrorId) ?: throw MirrorFailure("AUTHOR_MAPPING_NOT_FOUND")
            val cleared = reattribute(mirror, mapping.sourceAuthor, references(mirror, mapping.user.loginId), null)
            authors.delete(mapping)
            cleared
        }!!
    }

    private fun lockMirror(id: Long): RepositoryMirror {
        val count = entityManager.createQuery("update RepositoryMirror m set m.version = m.version + 1 where m.id = :id")
            .setParameter("id", id).executeUpdate()
        if (count != 1) throw MirrorFailure("MIRROR_NOT_FOUND")
        return entityManager.find(RepositoryMirror::class.java, id).also { entityManager.refresh(it) }
    }

    private fun references(mirror: RepositoryMirror, senderLoginId: String?): List<IssueEvent> {
        val sender = if (senderLoginId == null) "e.senderLoginId is null" else "e.senderLoginId = :sender"
        val query = entityManager.createQuery(
            "select e from IssueEvent e where e.issue.project.id = :projectId and e.eventType = :type and $sender",
            IssueEvent::class.java
        ).setParameter("projectId", mirror.project.id).setParameter("type", EventType.ISSUE_REFERRED_FROM_COMMIT)
        if (senderLoginId != null) query.setParameter("sender", senderLoginId)
        return query.resultList
    }

    // The reference keeps only the revision number, so the author is read back from the verified local copy.
    private fun reattribute(mirror: RepositoryMirror, sourceAuthor: String, events: List<IssueEvent>, user: User?): Int {
        if (events.isEmpty()) return 0
        return withLocalRepository(mirror) { repository ->
            val authorByRevision = HashMap<Long, String?>()
            var changed = 0
            for (event in events) {
                val revision = event.newValue?.toLongOrNull()?.takeIf { it in 1..mirror.lastVerifiedRevision } ?: continue
                val author = authorByRevision.getOrPut(revision) {
                    repository.getRevisionPropertyValue(revision, SVNRevisionProperty.AUTHOR)?.string
                }
                if (author != sourceAuthor) continue
                event.senderLoginId = user?.loginId
                event.senderEmail = user?.email
                changed++
            }
            changed
        } ?: throw MirrorFailure("REPOSITORY_DAMAGED", attention = true)
    }

    private fun sourceAuthors(mirror: RepositoryMirror): List<String> {
        if (mirror.lastVerifiedRevision < 1) return emptyList()
        val found = sortedSetOf<String>()
        withLocalRepository(mirror) { repository ->
            repository.log(arrayOf(""), 1, mirror.lastVerifiedRevision, false, false, 0, false, arrayOf(SVNRevisionProperty.AUTHOR)) { entry ->
                if (found.size < MAX_LISTED_AUTHORS) entry.author?.let(found::add)
            }
        }
        return found.toList()
    }

    private fun <T> withLocalRepository(mirror: RepositoryMirror, action: (SVNRepository) -> T): T? {
        val repository = try {
            FSRepositoryFactory.setup()
            SVNRepositoryFactory.create(SVNURL.fromFile(storage.directory(mirror)))
        } catch (_: SVNException) {
            return null
        } catch (_: MirrorFailure) {
            return null
        }
        return try { action(repository) } catch (_: SVNException) { null } finally { repository.closeSession() }
    }

    private fun admin(loginId: String) {
        if (users.findByLoginId(loginId).orElse(null)?.isSiteManager != true) throw AccessDeniedException("Site administrator required")
    }

    companion object {
        private const val MAX_LISTED_AUTHORS = 1000
    }
}

data class RepositoryMirrorAuthorView(
    val mirrorId: Long,
    val projectOwner: String,
    val projectName: String,
    val mappings: List<Mapping>,
    val unmappedAuthors: List<String>
) {
    data class Mapping(val id: Long, val sourceAuthor: String, val loginId: String)
}
