package com.github.yonaprojects.yona.domain.issue

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface IssueDependencyRepository : JpaRepository<IssueDependency, Long> {
    @Query("select d from IssueDependency d join fetch d.predecessor join fetch d.successor where d.predecessor.id = :issueId or d.successor.id = :issueId order by d.id")
    fun findForIssue(@Param("issueId") issueId: Long): List<IssueDependency>

    // A locking/current read also sees commits made while waiting for the project mutex on
    // databases whose default isolation is REPEATABLE_READ (not an earlier MVCC snapshot).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from IssueDependency d where d.predecessor.project.id = :projectId")
    fun findGraphForUpdate(@Param("projectId") projectId: Long): List<IssueDependency>

    @Modifying
    @Query("delete from IssueDependency d where d.predecessor.id = :issueId or d.successor.id = :issueId")
    fun deleteForIssue(@Param("issueId") issueId: Long): Int
}
