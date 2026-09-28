package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

@Controller
@RequestMapping(QUEUE_PAGE)
internal class QueueAdminViewController(
    private val queries: QueueAdminQueries,
    private val control: QueueControl,
    private val users: UserRepository,
) {
    @GetMapping
    fun page(request: HttpServletRequest, response: HttpServletResponse, model: Model): String {
        parameters(request, QUERY_KEYS)
        return render(state(request), request, response, model)
    }

    @PostMapping("/jobs/{jobId}/{action}", consumes = ["application/x-www-form-urlencoded"])
    fun command(@PathVariable jobId: String, @PathVariable action: String,
                request: HttpServletRequest, response: HttpServletResponse, model: Model): String? {
        if (request.contentLengthLong > 8192) throw QueueHttpFailure(413, "REQUEST_TOO_LARGE", "Queue form exceeds its size limit")
        parameters(request, QUERY_KEYS + setOf("_csrf", "commandId", "reason", "recoveryAcknowledged"))
        val id = id(jobId)
        val state = state(request).copy(selected = jobId)
        val commandId = request.getParameter("commandId") ?: invalid()
        val reason = request.getParameter("reason")?.takeIf { it.isNotEmpty() }
        val acknowledged = when (request.getParameter("recoveryAcknowledged")) {
            null -> false
            "true" -> true
            else -> invalid()
        }
        val actor = request.getAttribute(QUEUE_ACTOR_ATTRIBUTE) as? Long
            ?: throw QueueHttpFailure(403, "FORBIDDEN", "Current site administrator access is required")
        try {
            when (action) {
                "cancel" -> {
                    if (acknowledged) invalid()
                    control.cancel(id, commandId, actor, reason)
                }
                "retry" -> control.retry(id, commandId, actor, acknowledged, reason)
                else -> throw QueueHttpFailure(404, "NOT_FOUND", "Unknown queue command")
            }
        } catch (failure: QueueControlException) {
            response.status = when (failure.code) {
                "FORBIDDEN" -> 403
                "NOT_FOUND" -> 404
                "INVALID_REQUEST", "INVALID_REASON", "INVALID_COMMAND_ID" -> 400
                else -> 409
            }
            if (response.status == 403 || response.status == 404) {
                throw QueueHttpFailure(response.status, failure.code, "Queue command is unavailable")
            }
            model.addAttribute("queueError", failure.code)
            model.addAttribute("commandId", commandId)
            model.addAttribute("reason", reason.orEmpty())
            model.addAttribute("recoveryAcknowledged", acknowledged)
            return render(state, request, response, model)
        }
        response.status = 303
        response.setHeader("Location", request.contextPath + state.url())
        response.setHeader("Cache-Control", "no-store")
        return null
    }

    private fun render(state: ViewState, request: HttpServletRequest, response: HttpServletResponse, model: Model): String {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Vary", "Turbo-Frame")
        model.addAttribute("message", "title.siteSetting")
        if (request.getHeader("Turbo-Frame") != "queue-content") {
            val actor = request.getAttribute(QUEUE_ACTOR_ATTRIBUTE) as Long
            model.addAttribute("currentUser", users.findById(actor).orElse(null))
        }
        model.addAttribute("state", state)
        model.addAttribute("statuses", QueueStatus.entries.map { it.name })
        val page = queries.list(state.status, state.type, state.resource, state.cursor, 50)
        model.addAttribute("page", page)
        model.addAttribute("pageUrl", state.url())
        model.addAttribute("firstUrl", state.copy(cursor = null).url())
        model.addAttribute("nextUrl", (page["nextCursor"] as? String)?.let { state.copy(cursor = it).url() })
        @Suppress("UNCHECKED_CAST")
        val items = page["items"] as List<Map<String, Any?>>
        model.addAttribute("jobUrls", items.associate { row ->
            val id = row["id"] as String
            id to state.copy(selected = id, attemptCursor = null).url()
        })
        model.addAttribute("closeUrl", state.copy(selected = null, attemptCursor = null).url())
        val detail = state.selected?.let { queries.detail(id(it), state.attemptCursor, 50) }
        model.addAttribute("detail", detail)
        model.addAttribute("nextAttemptUrl", (detail?.get("nextAttemptCursor") as? String)?.let {
            state.copy(attemptCursor = it).url()
        })
        model.addAttribute("firstAttemptUrl", state.copy(attemptCursor = null).url())
        if (!model.containsAttribute("commandId")) model.addAttribute("commandId", UUID.randomUUID().toString())
        if (!model.containsAttribute("reason")) model.addAttribute("reason", "")
        if (!model.containsAttribute("recoveryAcknowledged")) model.addAttribute("recoveryAcknowledged", false)
        return view(request)
    }

    @ExceptionHandler(QueueHttpFailure::class)
    fun failure(failure: QueueHttpFailure, request: HttpServletRequest, response: HttpServletResponse, model: Model): String {
        response.status = failure.status
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Vary", "Turbo-Frame")
        model.asMap().remove("page")
        model.asMap().remove("state")
        model.asMap().remove("detail")
        model.addAttribute("message", "title.siteSetting")
        model.addAttribute("queueError", failure.code)
        return view(request)
    }

    private fun view(request: HttpServletRequest) =
        if (request.getHeader("Turbo-Frame") == "queue-content") "site/queueAdmin :: content" else "site/queueAdmin"

    private fun state(request: HttpServletRequest): ViewState {
        fun parameter(name: String, max: Int): String? = request.getParameter(name)?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > max) invalid()
        }
        val statuses = request.getParameterValues("status")?.filter { it.isNotEmpty() }.orEmpty()
        if (statuses.size > QueueStatus.entries.size || statuses.any { value -> QueueStatus.entries.none { it.name == value } }) invalid()
        val selected = parameter("selected", 19)?.also { id(it) }
        val attemptCursor = parameter("attemptCursor", 512)
        if (selected == null && attemptCursor != null) invalid()
        return ViewState(statuses.distinct(), parameter("type", 160), parameter("resource", 300),
            parameter("cursor", 512), selected, attemptCursor)
    }

    private fun parameters(request: HttpServletRequest, allowed: Set<String>) {
        if (request.parameterMap.any { (key, values) -> key !in allowed || (key != "status" && values.size != 1) }) invalid()
    }

    private fun id(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 && it.toString() == value } ?: invalid()
    private fun invalid(): Nothing = throw QueueHttpFailure(400, "INVALID_REQUEST", "Invalid queue request")

    internal data class ViewState(val status: List<String>, val type: String?, val resource: String?,
                                  val cursor: String?, val selected: String?, val attemptCursor: String?) {
        fun url(): String = UriComponentsBuilder.fromPath(QUEUE_PAGE).apply {
            status.forEach { queryParam("status", it) }
            type?.let { queryParam("type", it) }
            resource?.let { queryParam("resource", it) }
            cursor?.let { queryParam("cursor", it) }
            selected?.let { queryParam("selected", it) }
            attemptCursor?.let { queryParam("attemptCursor", it) }
        }.build().encode().toUriString()
    }

    companion object {
        private val QUERY_KEYS = setOf("status", "type", "resource", "cursor", "selected", "attemptCursor")
    }
}
