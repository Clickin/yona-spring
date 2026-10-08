package com.github.yonaprojects.yona.domain.vcs

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface RepositoryMirrorRepository : JpaRepository<RepositoryMirror, Long> {
    @EntityGraph(attributePaths = ["project"])
    fun findByProjectId(projectId: Long): RepositoryMirror?

    @EntityGraph(attributePaths = ["project"])
    fun findAllByOrderByIdDesc(pageable: Pageable): Page<RepositoryMirror>
}
