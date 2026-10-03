package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.attachment.AttachmentRepository
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import org.springframework.web.servlet.ModelAndView
import com.github.yonaprojects.yona.web.AttachmentController
import com.github.yonaprojects.yona.web.HgController
import com.github.yonaprojects.yona.web.SvnController
import com.github.yonaprojects.yona.web.IssueController
import com.github.yonaprojects.yona.web.MarkdownController
import com.github.yonaprojects.yona.web.ProjectArchiveController
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/** Covers web, legacy API, numeric API and REST routes without duplicating controller checks. */
@Component
class ProjectArchiveInterceptor(
    private val projects: ProjectRepository,
    private val issues: IssueRepository,
    private val attachments: AttachmentRepository
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler is HandlerMethod && ProjectArchiveController::class.java.isAssignableFrom(handler.beanType)) return true
        // VCS protocols classify reads by command, not HTTP verb; their authorization filters guard writes.
        if (handler is HandlerMethod &&
            (HgController::class.java.isAssignableFrom(handler.beanType) ||
                SvnController::class.java.isAssignableFrom(handler.beanType))) return true
        if (request.method in setOf("GET", "HEAD", "OPTIONS")) return true
        // These POST handlers only compute a response; archived reads remain available.
        if (request.method == "POST" && handler is HandlerMethod) {
            val readOnly = when (handler.method.declaringClass) {
                MarkdownController::class.java -> handler.method.name == "render"
                IssueController::class.java -> handler.method.name == "detectChange" || handler.method.name == "commentNotiReceivers"
                else -> false
            }
            if (readOnly) return true
        }
        @Suppress("UNCHECKED_CAST")
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
            ?: emptyMap()
        val project = variables["projectId"]?.toLongOrNull()?.let { projects.findById(it).orElse(null) }
            ?: variables["issueId"]?.toLongOrNull()?.let { issues.findById(it).orElse(null)?.project?.id }
                ?.let { projects.findById(it).orElse(null) }
            ?: (if (handler is HandlerMethod && AttachmentController::class.java.isAssignableFrom(handler.beanType)) {
                variables["id"]?.toLongOrNull()?.let { attachments.findById(it).orElse(null) }
                    ?.takeIf { it.containerType == ResourceType.PROJECT }
                    ?.containerId?.toLongOrNull()?.let { projects.findById(it).orElse(null) }
            } else null)
            ?: run {
                val owner = variables["owner"] ?: variables["ownerName"] ?: return@run null
                val name = variables["projectName"] ?: variables["project"] ?: return@run null
                projects.findByOwnerAndNameOrPreviousPlace(owner, name).orElse(null)
            }
        if (project?.isArchived == true) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Archived project is read-only; unarchive before making changes")
            return false
        }
        return true
    }

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        val model = modelAndView?.model ?: return
        val project = model["project"] as? Project ?: return
        if (!project.isArchived) return
        for (permission in listOf("isAllowedUpdate", "isProjectManager", "isAllowedToNotice", "canReadmefy",
            "canUpdate", "canDelete", "canManage", "isAcceptable", "canDeleteBranch", "canRestoreBranch",
            "canApproveOrRequestChanges", "canWriteWiki")) {
            if (permission in model) model[permission] = false
        }
        model["disabledAcceptReason"] = "Archived project is read-only"
    }
}
