package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.GitMaintenanceService
import com.github.yonaprojects.yona.queue.QueueAdmissionException
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class ProjectGitMaintenanceController(
    private val projects: ProjectRepository,
    private val members: ProjectUserRepository,
    private val users: UserRepository,
    private val maintenance: GitMaintenanceService,
) {
    @GetMapping("/{owner}/{projectName}/setting/git-maintenance")
    fun show(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        authentication: Authentication?,
        model: Model,
    ): String {
        val project = managedProject(owner, projectName, authentication)
        model.addAttribute("project", project)
        model.addAttribute("gcJob", maintenance.status(project))
        return "project/setting_git_maintenance"
    }

    @PostMapping("/{owner}/{projectName}/setting/git-maintenance")
    fun collect(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        authentication: Authentication?,
        redirect: RedirectAttributes,
    ): String {
        val project = managedProject(owner, projectName, authentication)
        val user = users.findByLoginId(authentication!!.name).orElseThrow()
        try {
            redirect.addFlashAttribute("gcQueued", maintenance.enqueue(project, "user:${user.id}"))
        } catch (e: QueueAdmissionException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.code, e)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Git repository is unavailable", e)
        }
        redirect.addAttribute("owner", project.owner)
        redirect.addAttribute("projectName", project.name)
        return "redirect:/{owner}/{projectName}/setting/git-maintenance"
    }

    private fun managedProject(owner: String, name: String, authentication: Authentication?): Project {
        val user = authentication?.let { users.findByLoginId(it.name).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val project = projects.findByOwnerAndNameOrPreviousPlace(owner, name).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val manager = members.findByProjectIdAndUserId(project.id!!, user.id!!)
            .map { it.role.id == RoleType.MANAGER.roleType }.orElse(false)
        if (!manager) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        if (!(project.vcs ?: "GIT").equals("GIT", ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Git maintenance requires a Git repository")
        }
        return project
    }
}
