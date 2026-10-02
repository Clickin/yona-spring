package com.github.yonaprojects.yona.domain.issue

import jakarta.persistence.*
import org.hibernate.annotations.Check

// Separate from Issue.parent: predecessor blocks successor, not a subtask relationship.
@Entity
@Table(
    name = "issue_dependency",
    uniqueConstraints = [UniqueConstraint(name = "uk_issue_dependency", columnNames = ["predecessor_id", "successor_id"])],
    indexes = [Index(name = "ix_issue_dependency_successor", columnList = "successor_id")]
)
@Check(constraints = "predecessor_id <> successor_id")
class IssueDependency(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "predecessor_id", nullable = false)
    var predecessor: Issue,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "successor_id", nullable = false)
    var successor: Issue
)
