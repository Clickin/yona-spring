package com.github.yonaprojects.yona.domain.vcs

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

/**
 * Git adapter for [MirrorCommitIndexer]: one batch of verified commits together with the ref positions
 * that batch completes. The position rows change in the same fenced transaction as the references, so a
 * crash leaves either both or neither.
 */
@Component
class GitMirrorIndexer(
    private val indexer: MirrorCommitIndexer,
    private val entityManager: EntityManager
) {
    /**
     * [positions] maps a ref name to the commit whose ancestors are all indexed after this batch; [deletions]
     * are refs that no longer exist at the source. A batch without commits only moves positions.
     */
    fun index(
        id: Long,
        owner: String,
        fence: Long,
        commits: List<MirrorCommit>,
        refNames: List<String>,
        positions: Map<String, String> = emptyMap(),
        deletions: Collection<String> = emptyList()
    ) {
        indexer.index(id, owner, fence, commits, refNames) { mirror ->
            for (names in positions.keys.chunked(NAMES_PER_QUERY)) {
                val rows = entityManager.createQuery("select r from RepositoryMirrorRef r where r.mirror.id = :id and r.refName in :names", RepositoryMirrorRef::class.java)
                    .setParameter("id", id).setParameter("names", names).resultList.associateBy { it.refName }
                for (name in names) {
                    val row = rows[name]
                    if (row != null) row.commitId = positions.getValue(name)
                    else entityManager.persist(RepositoryMirrorRef(mirror = mirror, refName = name, commitId = positions.getValue(name)))
                }
            }
            for (names in deletions.chunked(NAMES_PER_QUERY)) {
                entityManager.createQuery("delete from RepositoryMirrorRef r where r.mirror.id = :id and r.refName in :names")
                    .setParameter("id", id).setParameter("names", names).executeUpdate()
            }
        }
    }

    private companion object {
        // Stays well below the 2100 parameter limit of SQL Server.
        const val NAMES_PER_QUERY = 500
    }
}
