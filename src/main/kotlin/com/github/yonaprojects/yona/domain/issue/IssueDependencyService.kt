package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class IssueDependencyException(val code: String) : RuntimeException(code)

data class IssueDependencyItem(
    val number: Long,
    val title: String,
    val state: State,
    val active: Boolean,
    val canRemove: Boolean
)

data class IssueDependencies(
    val blockedBy: List<IssueDependencyItem>,
    val blocking: List<IssueDependencyItem>,
    val canEdit: Boolean
) {
    // Only visible relationships contribute: even a count could disclose a private issue.
    val blocked: Boolean get() = blockedBy.any { it.active }
}

@Service
@Transactional
class IssueDependencyService(
    private val dependencies: IssueDependencyRepository,
    private val issues: IssueRepository,
    private val projects: ProjectRepository,
    private val accessControl: AccessControl,
    private val entityManager: EntityManager
) {
    @Transactional(readOnly = true)
    fun get(projectId: Long, number: Long, user: User?): IssueDependencies =
        overview(findReadableIssue(projectId, number, user), user)

    @Transactional(readOnly = true)
    fun overview(issue: Issue, user: User?): IssueDependencies {
        if (!canRead(issue, user)) return IssueDependencies(emptyList(), emptyList(), false)
        val editable = canEdit(issue, user)
        val incoming = mutableListOf<IssueDependencyItem>()
        val outgoing = mutableListOf<IssueDependencyItem>()
        for (edge in dependencies.findForIssue(issue.id!!)) {
            val predecessor = edge.predecessor
            val successor = edge.successor
            val other = if (predecessor.id == issue.id) successor else predecessor
            // Defensive filter for stale/corrupt cross-project links, as well as private/draft issues.
            if (other.project.id != issue.project.id || !canRead(other, user)) continue
            val item = IssueDependencyItem(
                other.number!!, other.title, other.state,
                unresolved(predecessor) && unresolved(successor),
                editable && canEdit(other, user)
            )
            if (predecessor.id == issue.id) outgoing.add(item) else incoming.add(item)
        }
        return IssueDependencies(incoming.sortedBy { it.number }, outgoing.sortedBy { it.number }, editable)
    }

    fun add(projectId: Long, predecessorNumber: Long, successorNumber: Long, user: User?) {
        val (predecessor, successor) = writablePair(projectId, predecessorNumber, successorNumber, user)
        if (predecessor.id == successor.id) throw IssueDependencyException("self")
        val graph = dependencies.findGraphForUpdate(projectId)
        if (graph.any { it.predecessor.id == predecessor.id && it.successor.id == successor.id }) {
            throw IssueDependencyException("duplicate")
        }
        // ponytail: O(E) per project mutation, including closed issues so reopening cannot make a
        // cycle. A recursive database query is only needed if project graphs outgrow this scan.
        val successors = graph.groupBy({ it.predecessor.id!! }, { it.successor.id!! })
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(successor.id!!)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == predecessor.id) throw IssueDependencyException("cycle")
            if (visited.add(current)) successors[current]?.forEach { pending.add(it) }
        }
        dependencies.saveAndFlush(IssueDependency(predecessor = predecessor, successor = successor))
    }

    fun remove(projectId: Long, predecessorNumber: Long, successorNumber: Long, user: User?) {
        val (predecessor, successor) = writablePair(projectId, predecessorNumber, successorNumber, user)
        val edge = dependencies.findGraphForUpdate(projectId).firstOrNull {
            it.predecessor.id == predecessor.id && it.successor.id == successor.id
        } ?: throw IssueDependencyException("notfound")
        dependencies.delete(edge)
    }

    private fun writablePair(projectId: Long, predecessorNumber: Long, successorNumber: Long, user: User?): Pair<Issue, Issue> {
        val predecessor = findReadableIssue(projectId, predecessorNumber, user)
        val successor = findReadableIssue(projectId, successorNumber, user)
        if (!canEdit(predecessor, user) || !canEdit(successor, user)) throw IssueDependencyException("forbidden")
        projects.lockIssueDependencies(projectId)
        // A concurrent move/delete may have completed while we waited. Refresh with a current
        // read, then repeat authorization before touching the graph (also avoids stale OSIV entities).
        for (issue in listOf(predecessor, successor).distinctBy { it.id }) {
            if (issues.findById(issue.id!!).isEmpty) throw IssueDependencyException("notfound")
            try {
                entityManager.refresh(issue, LockModeType.PESSIMISTIC_WRITE)
            } catch (_: jakarta.persistence.EntityNotFoundException) {
                throw IssueDependencyException("notfound")
            }
            if (issue.project.id != projectId || !canRead(issue, user)) throw IssueDependencyException("notfound")
            if (!canEdit(issue, user)) throw IssueDependencyException("forbidden")
        }
        if (predecessor.number != predecessorNumber || successor.number != successorNumber) throw IssueDependencyException("notfound")
        return predecessor to successor
    }

    private fun findReadableIssue(projectId: Long, number: Long, user: User?): Issue {
        val project = projects.findById(projectId).orElseThrow { IssueDependencyException("notfound") }
        val issue = issues.findByProjectAndNumber(project, number) ?: throw IssueDependencyException("notfound")
        if (!canRead(issue, user)) throw IssueDependencyException("notfound")
        return issue
    }

    private fun unresolved(issue: Issue): Boolean = issue.state != State.CLOSED && issue.state != State.RESOLVED

    private fun canRead(issue: Issue, user: User?): Boolean =
        (!issue.isDraft && issue.state != State.DRAFT || user?.id != null && issue.authorId == user.id) &&
            accessControl.isAllowed(user, issue.project, issue, Operation.READ)

    private fun canEdit(issue: Issue, user: User?): Boolean =
        user != null && canRead(issue, user) && accessControl.isAllowed(user, issue.project, issue, Operation.UPDATE)
}
