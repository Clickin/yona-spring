package com.github.yonaprojects.yona.domain.issue

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

// Pull requests retain singular Assignee records. Project deletion also removes orphaned PR assignments.
@Repository
interface AssigneeRepository : JpaRepository<Assignee, Long> {
    fun findByProjectId(projectId: Long): List<Assignee>
}
