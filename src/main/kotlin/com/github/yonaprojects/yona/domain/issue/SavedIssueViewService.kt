package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class SavedIssueViewService(
    private val repository: SavedIssueViewRepository,
    private val accessControl: AccessControl
) {
    enum class Visibility { PERSONAL, PROJECT }
    data class View(val id: Long, val name: String, val visibility: Visibility,
                    val parameters: Map<String, List<String>>, val url: String, val editable: Boolean)

    fun requireReader(project: Project, user: User?) {
        if (user == null || user.id == null || user.state == UserState.DELETED || user.state == UserState.LOCKED) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        if (user.isGuest || !accessControl.isAllowedToReadProject(user, project)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    fun canManage(project: Project, user: User): Boolean = accessControl.isAllowed(user, project, Operation.UPDATE)

    fun list(project: Project, user: User): List<View> {
        requireReader(project, user)
        return repository.findVisible(project.id!!, user.id!!).map { response(it, user) }
    }

    fun get(project: Project, user: User, id: Long): View = response(find(project, user, id), user)

    @Transactional
    fun create(project: Project, user: User, name: String, visibility: Visibility,
               parameters: Map<String, List<String>>): View {
        requireReader(project, user)
        if (visibility == Visibility.PROJECT && !canManage(project, user)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        return response(repository.save(SavedIssueView(project = project,
            owner = if (visibility == Visibility.PERSONAL) user else null,
            name = validName(name), queryParameters = IssueViewQuery.encode(parameters))), user)
    }

    @Transactional
    fun rename(project: Project, user: User, id: Long, name: String): View {
        val view = find(project, user, id)
        requireEditor(view, user)
        view.name = validName(name)
        return response(repository.save(view), user)
    }

    @Transactional
    fun delete(project: Project, user: User, id: Long) {
        val view = find(project, user, id)
        requireEditor(view, user)
        repository.delete(view)
    }

    private fun find(project: Project, user: User, id: Long): SavedIssueView {
        requireReader(project, user)
        val view = repository.findByIdAndProjectId(id, project.id!!)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (view.owner != null && view.owner!!.id != user.id) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return view
    }

    private fun requireEditor(view: SavedIssueView, user: User) {
        if (view.owner == null && !canManage(view.project, user)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    private fun response(view: SavedIssueView, user: User): View = View(
        view.id!!, view.name, if (view.owner == null) Visibility.PROJECT else Visibility.PERSONAL,
        IssueViewQuery.decode(view.queryParameters),
        listPath(view.project) + if (view.queryParameters.isEmpty()) "" else "?${view.queryParameters}",
        view.owner != null || canManage(view.project, user)
    )

    private fun validName(name: String): String = name.trim().also {
        if (it.isEmpty() || it.length > 100 || it.any(Char::isISOControl)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "View name must be 1–100 characters")
        }
    }

    fun listPath(project: Project): String = UriComponentsBuilder.newInstance()
        .pathSegment(project.owner!!, project.name, "issues").build().encode().toUriString()
}

// Only list conditions are stored. Pagination, detail selection, exports and redirect targets are not views.
object IssueViewQuery {
    private val keys = setOf("state", "filter", "titleHead", "literalFilter", "authorId", "assigneeId", "milestoneId", "commenterId",
        "labelIds", "dueDate", "orderBy", "orderDir", "itemsPerPage")

    fun encode(parameters: Map<String, List<String>>): String {
        if (!keys.containsAll(parameters.keys)) invalid()
        val normalized = linkedMapOf<String, List<String>>()
        parameters.forEach { (key, values) ->
            if (values.isEmpty() || (key != "labelIds" && values.size != 1)) invalid()
            val nonEmpty = values.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) return@forEach
            val parsed = when (key) {
                "labelIds" -> nonEmpty.flatMap { it.split(',') }
                "state", "orderDir" -> nonEmpty.map { it.lowercase() }
                else -> nonEmpty
            }
            parsed.forEach { value ->
                val valid = when (key) {
                    "state" -> State.entries.any { it.state() == value }
                    "filter", "titleHead" -> value.length <= 1000 && value.none(Char::isISOControl)
                    "literalFilter" -> value == "true" || value == "false"
                    "orderBy" -> value in setOf("createdDate", "updatedDate", "dueDate", "numOfComments", "relevance")
                    "orderDir" -> value in setOf("asc", "desc")
                    "itemsPerPage" -> value.toIntOrNull()?.let { it in 1..45 } == true
                    "dueDate" -> runCatching { LocalDate.parse(value) }.isSuccess
                    "assigneeId", "milestoneId" -> value.toLongOrNull()?.let { it >= -1 } == true
                    else -> value.toLongOrNull()?.let { it >= 0 } == true
                }
                if (!valid) invalid()
            }
            normalized[key] = parsed
        }
        return normalized.entries.flatMap { (key, values) ->
            values.map { "$key=${URLEncoder.encode(it, UTF_8)}" }
        }.joinToString("&").also { if (it.length > 4096) invalid() }
    }

    fun decode(query: String): Map<String, List<String>> = if (query.isEmpty()) emptyMap() else
        query.split('&').groupBy({ it.substringBefore('=') }, { URLDecoder.decode(it.substringAfter('='), UTF_8) })

    private fun invalid(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported issue list parameters")
}
