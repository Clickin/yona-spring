package com.github.yonaprojects.yona.domain.issue

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SavedIssueViewRepository : JpaRepository<SavedIssueView, Long> {
    @Query("select v from SavedIssueView v where v.project.id = :projectId and (v.owner is null or v.owner.id = :userId) order by v.name, v.id")
    fun findVisible(projectId: Long, userId: Long): List<SavedIssueView>
    fun findByIdAndProjectId(id: Long, projectId: Long): SavedIssueView?
    fun deleteByOwnerId(ownerId: Long)
}
