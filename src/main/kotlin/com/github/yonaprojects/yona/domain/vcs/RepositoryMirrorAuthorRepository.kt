package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.user.User
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RepositoryMirrorAuthorRepository : JpaRepository<RepositoryMirrorAuthor, Long> {
    @EntityGraph(attributePaths = ["user"])
    fun findAllByMirrorIdOrderBySourceAuthorAsc(mirrorId: Long): List<RepositoryMirrorAuthor>

    @EntityGraph(attributePaths = ["user"])
    fun findByIdAndMirrorId(id: Long, mirrorId: Long): RepositoryMirrorAuthor?

    // Callers compare sourceAuthor exactly: some collations match case-insensitively.
    @EntityGraph(attributePaths = ["user"])
    @Query("select a from RepositoryMirrorAuthor a where a.mirror.id = :mirrorId and a.sourceAuthor = :sourceAuthor")
    fun findCandidates(@Param("mirrorId") mirrorId: Long, @Param("sourceAuthor") sourceAuthor: String): List<RepositoryMirrorAuthor>

    @EntityGraph(attributePaths = ["user"])
    @Query("select a from RepositoryMirrorAuthor a where a.mirror.project.id = :projectId and a.sourceAuthor = :sourceAuthor")
    fun findCandidatesByProjectId(@Param("projectId") projectId: Long, @Param("sourceAuthor") sourceAuthor: String): List<RepositoryMirrorAuthor>
}

fun RepositoryMirrorAuthorRepository.findUser(mirrorId: Long, sourceAuthor: String): User? =
    findCandidates(mirrorId, sourceAuthor).firstOrNull { it.sourceAuthor == sourceAuthor }?.user

fun RepositoryMirrorAuthorRepository.findUserByProjectId(projectId: Long, sourceAuthor: String): User? =
    findCandidatesByProjectId(projectId, sourceAuthor).firstOrNull { it.sourceAuthor == sourceAuthor }?.user
