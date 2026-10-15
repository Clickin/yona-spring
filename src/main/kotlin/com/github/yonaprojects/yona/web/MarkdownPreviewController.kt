package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.support.MarkdownService
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

@Controller
class MarkdownPreviewController(
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val accessControl: AccessControl,
    private val markdownService: MarkdownService
) {
    @PostMapping(
        value = ["/markdown/preview", "/markdown/{owner}/{projectName}/preview"],
        consumes = ["application/x-www-form-urlencoded"], produces = ["text/html;charset=UTF-8"]
    )
    fun preview(
        @PathVariable(required = false) owner: String?,
        @PathVariable(required = false) projectName: String?,
        @RequestParam body: String,
        @RequestParam(defaultValue = "true") breaks: Boolean,
        @RequestParam frameId: String,
        authentication: Authentication?,
        model: Model
    ): String {
        if (!frameId.matches(Regex("markdown-preview-[A-Za-z0-9_-]{1,100}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        val project = if (owner != null && projectName != null) {
            projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        } else null
        if (project != null) {
            val user = authentication?.let { userRepository.findByLoginId(it.name).orElse(null) }
            if (!accessControl.isAllowedToReadProject(user, project)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }
        }
        model.addAttribute("frameId", frameId)
        model.addAttribute("renderedMarkdown", markdownService.render(body, breaks, project))
        return "common/markdownPreview"
    }
}
