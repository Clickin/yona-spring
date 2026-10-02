package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Controller
class ProjectArchiveController(
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val accessControl: AccessControl
) {
    data class ArchiveRequest(val archived: Boolean)

    @PatchMapping("/api/v1/projects/{owner}/{projectName}/settings/archive")
    @ResponseBody
    @Transactional
    fun archiveApi(@PathVariable owner: String, @PathVariable projectName: String,
                   @RequestBody request: ArchiveRequest, authentication: Authentication?): Map<String, Any?> =
        update(owner, projectName, request.archived, authentication)

    @PostMapping("/{owner}/{projectName}/settings/archive")
    @Transactional
    fun archiveForm(@PathVariable owner: String, @PathVariable projectName: String,
                    @RequestParam archived: Boolean, authentication: Authentication?): String {
        update(owner, projectName, archived, authentication)
        return "redirect:/$owner/$projectName/setting"
    }

    private fun update(owner: String, name: String, archived: Boolean, authentication: Authentication?): Map<String, Any?> {
        val user = authentication?.let { users.findByLoginId(it.name).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val project = projects.findByOwnerAndName(owner, name).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!accessControl.canManageArchive(user, project)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        project.archivedAt = if (archived) project.archivedAt ?: Instant.now() else null
        projects.save(project)
        return mapOf("archived" to project.isArchived, "archivedAt" to project.archivedAt)
    }
}
