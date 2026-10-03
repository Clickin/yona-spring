package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.user.User
import jakarta.persistence.*
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

@Entity
@Table(name = "saved_issue_view", indexes = [Index(columnList = "project_id,owner_id")])
class SavedIssueView(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var project: Project = Project(),
    // A null owner explicitly denotes a project-shared view, not an anonymous personal view.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    var owner: User? = null,
    @Column(nullable = false, length = 100)
    var name: String = "",
    @Column(name = "query_parameters", nullable = false, length = 4096)
    var queryParameters: String = ""
)
