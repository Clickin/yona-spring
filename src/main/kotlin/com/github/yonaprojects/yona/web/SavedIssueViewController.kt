package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.issue.IssueViewQuery
import com.github.yonaprojects.yona.domain.issue.SavedIssueViewService
import com.github.yonaprojects.yona.domain.issue.SavedIssueViewService.Visibility
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@Controller
class SavedIssueViewController(
    private val service: SavedIssueViewService,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository
) {
    private fun context(owner: String, projectName: String, authentication: Authentication?): Pair<Project, User> {
        val user = authentication?.let { userRepository.findByLoginId(it.name).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        service.requireReader(project, user)
        return project to user
    }

    @GetMapping("/{owner}/{projectName}/issues/saved-views")
    fun page(@PathVariable owner: String, @PathVariable projectName: String,
             @RequestParam parameters: MultiValueMap<String, String>, authentication: Authentication?, model: Model): String {
        val (project, user) = context(owner, projectName, authentication)
        model.addAttribute("project", project)
        model.addAttribute("views", service.list(project, user))
        model.addAttribute("viewParameters", IssueViewQuery.decode(IssueViewQuery.encode(parameters)))
        model.addAttribute("canManageViews", service.canManage(project, user))
        model.addAttribute("listPath", service.listPath(project))
        return "issue/saved_views"
    }

    @PostMapping("/{owner}/{projectName}/issues/saved-views")
    fun saveForm(@PathVariable owner: String, @PathVariable projectName: String,
                 @RequestParam name: String, @RequestParam visibility: Visibility,
                 @RequestParam parameters: MultiValueMap<String, String>, authentication: Authentication?): String {
        val (project, user) = context(owner, projectName, authentication)
        service.create(project, user, name, visibility, parameters.filterKeys { it !in setOf("name", "visibility", "_csrf") })
        return "redirect:${service.listPath(project)}/saved-views"
    }

    @PostMapping("/{owner}/{projectName}/issues/saved-views/{id}/rename")
    fun renameForm(@PathVariable owner: String, @PathVariable projectName: String, @PathVariable id: Long,
                   @RequestParam name: String, authentication: Authentication?): String {
        val (project, user) = context(owner, projectName, authentication)
        service.rename(project, user, id, name)
        return "redirect:${service.listPath(project)}/saved-views"
    }

    @PostMapping("/{owner}/{projectName}/issues/saved-views/{id}/delete")
    fun deleteForm(@PathVariable owner: String, @PathVariable projectName: String, @PathVariable id: Long,
                   authentication: Authentication?): String {
        val (project, user) = context(owner, projectName, authentication)
        service.delete(project, user, id)
        return "redirect:${service.listPath(project)}/saved-views"
    }

    @GetMapping("/{owner}/{projectName}/issues/saved-views/{id}/open",
        "/api/v1/projects/{owner}/{projectName}/issues/saved-views/{id}/open")
    fun open(@PathVariable owner: String, @PathVariable projectName: String, @PathVariable id: Long,
             authentication: Authentication?): String {
        val (project, user) = context(owner, projectName, authentication)
        return "redirect:${service.get(project, user, id).url}"
    }

    @GetMapping("/api/v1/projects/{owner}/{projectName}/issues/saved-views")
    @ResponseBody
    fun list(@PathVariable owner: String, @PathVariable projectName: String, authentication: Authentication?): List<SavedIssueViewService.View> {
        val (project, user) = context(owner, projectName, authentication)
        return service.list(project, user)
    }

    @GetMapping("/api/v1/projects/{owner}/{projectName}/issues/saved-views/{id}")
    @ResponseBody
    fun get(@PathVariable owner: String, @PathVariable projectName: String, @PathVariable id: Long,
            authentication: Authentication?): SavedIssueViewService.View {
        val (project, user) = context(owner, projectName, authentication)
        return service.get(project, user, id)
    }

    data class CreateRequest(val name: String, val visibility: Visibility, val parameters: Map<String, List<String>> = emptyMap())
    data class RenameRequest(val name: String)

    @PostMapping("/api/v1/projects/{owner}/{projectName}/issues/saved-views")
    @ResponseBody
    fun create(@PathVariable owner: String, @PathVariable projectName: String,
               @RequestBody request: CreateRequest, authentication: Authentication?): ResponseEntity<SavedIssueViewService.View> {
        val (project, user) = context(owner, projectName, authentication)
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(project, user, request.name, request.visibility, request.parameters))
    }

    @PatchMapping("/api/v1/projects/{owner}/{projectName}/issues/saved-views/{id}")
    @ResponseBody
    fun rename(@PathVariable owner: String, @PathVariable projectName: String, @PathVariable id: Long,
               @RequestBody request: RenameRequest, authentication: Authentication?): SavedIssueViewService.View {
        val (project, user) = context(owner, projectName, authentication)
        return service.rename(project, user, id, request.name)
    }

    @DeleteMapping("/api/v1/projects/{owner}/{projectName}/issues/saved-views/{id}")
    @ResponseBody
    fun delete(@PathVariable owner: String, @PathVariable projectName: String, @PathVariable id: Long,
               authentication: Authentication?): ResponseEntity<Void> {
        val (project, user) = context(owner, projectName, authentication)
        service.delete(project, user, id)
        return ResponseEntity.noContent().build()
    }
}
