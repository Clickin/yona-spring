package com.github.yonaprojects.yona.domain.vcs

import jakarta.persistence.*
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Indexed position of one source ref for mirrors without linear revisions (Git). A commit is new when it
 * is reachable from a fetched ref but from none of the indexed positions. Written only inside the fenced
 * indexer transaction, together with the references it implies.
 */
@Entity
@Table(
    name = "repository_mirror_ref",
    uniqueConstraints = [UniqueConstraint(name = "uk_mirror_ref_name", columnNames = ["mirror_id", "ref_name"])]
)
class RepositoryMirrorRef(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mirror_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var mirror: RepositoryMirror = RepositoryMirror(),
    @Column(name = "ref_name", nullable = false, length = 255)
    var refName: String = "",
    @Column(name = "commit_id", nullable = false, length = 64)
    var commitId: String = ""
)

interface RepositoryMirrorRefRepository : JpaRepository<RepositoryMirrorRef, Long> {
    fun findAllByMirrorId(mirrorId: Long): List<RepositoryMirrorRef>
}
