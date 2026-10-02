package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.issue.IssueService
import com.github.yonaprojects.yona.domain.issue.IssueShareService
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
class IssueShareController(
    private val issueShareService: IssueShareService,
    private val projectRepository: ProjectRepository,
    private val issueRepository: IssueRepository,
    private val userRepository: UserRepository,
    private val issueService: IssueService,
    private val accessControl: AccessControl
) {

    @GetMapping("/-_-api/v1/owners/{owner}/projects/{projectName}/assignableUsers")
    fun findAssignableUsersOfProject(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @RequestParam(required = false, defaultValue = "") query: String,
        authentication: Authentication?
    ): ResponseEntity<List<Map<String, Any>>> {
        val currentUser = getLoginUser(authentication) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val list = issueShareService.findAssignableUsersOfProject(project, query, currentUser)
        return ResponseEntity.ok(list)
    }

    @GetMapping("/-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/assignableUsers")
    fun findAssignableUsers(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestParam(required = false, defaultValue = "") query: String,
        authentication: Authentication?
    ): ResponseEntity<List<Map<String, Any>>> {
        val currentUser = getLoginUser(authentication) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
            ?: return ResponseEntity.notFound().build()
        val issue = issueRepository.findByProjectAndNumber(project, number)
            ?: return ResponseEntity.notFound().build()

        val list = issueShareService.findAssignableUsers(issue, query, currentUser)
        return ResponseEntity.ok(list)
    }

    @PostMapping("/-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/assignees")
    fun updateAssignees(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestBody body: Map<String, Any>,
        authentication: Authentication?
    ): ResponseEntity<Map<String, Any>> {
        val currentUser = getLoginUser(authentication) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
            ?: return ResponseEntity.notFound().build()
        val issue = issueRepository.findByProjectAndNumber(project, number)
            ?: return ResponseEntity.notFound().build()

        if (!accessControl.isAllowed(currentUser, project, issue, Operation.UPDATE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val loginIds = body["assignees"] as? List<*> ?: return ResponseEntity.badRequest().build()
        val targets = loginIds.map { value ->
            val loginId = value as? String ?: return ResponseEntity.badRequest().build()
            userRepository.findByLoginId(loginId).orElse(null) ?: return ResponseEntity.badRequest().build()
        }
        val assignees = when (body["action"] ?: "replace") {
            "replace" -> targets
            "clear" -> emptyList()
            "toggle" -> {
                if (targets.size != 1) return ResponseEntity.badRequest().build()
                val target = targets.single()
                if (issue.hasAssignee(target.id)) issue.assignees.filterNot { it.id == target.id }
                else issue.assignees.toList() + target
            }
            else -> return ResponseEntity.badRequest().build()
        }
        val updatedIssue = issueService.changeAssignees(issue.id!!, assignees, currentUser.loginId!!)
        return ResponseEntity.ok(mapOf(
            "assignees" to updatedIssue.assignees.map {
                mapOf("id" to it.id, "loginId" to it.loginId, "name" to it.getDisplayName())
            },
            "issue" to "/api/projects/${project.id}/issues/${updatedIssue.id}"
        ))
    }

    @GetMapping("/-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/findSharer")
    fun findSharerByloginIds(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestParam query: String,
        authentication: Authentication?
    ): ResponseEntity<List<Map<String, Any>>> {
        val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
            ?: return ResponseEntity.notFound().build()
        val issue = issueRepository.findByProjectAndNumber(project, number)
            ?: return ResponseEntity.notFound().build()

        val list = issueShareService.findSharerByloginIds(issue, query)
        return ResponseEntity.ok(list)
    }

    @GetMapping("/-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/sharableUsers")
    fun findSharableUsers(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestParam(required = false, defaultValue = "") query: String,
        authentication: Authentication?
    ): ResponseEntity<List<Map<String, Any>>> {
        val list = issueShareService.findSharableUsers(query, null)
        return ResponseEntity.ok(list)
    }

    @PostMapping("/-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/share")
    fun updateSharer(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestBody body: Map<String, Any>,
        authentication: Authentication?
    ): ResponseEntity<Map<String, Any>> {
        val currentUser = getLoginUser(authentication) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)
            ?: return ResponseEntity.notFound().build()
        val issue = issueRepository.findByProjectAndNumber(project, number)
            ?: return ResponseEntity.notFound().build()

        val sharerNode = body["sharer"] as? Map<*, *> ?: return ResponseEntity.badRequest().build()
        val loginId = sharerNode["loginId"]?.toString() ?: return ResponseEntity.badRequest().build()
        val type = sharerNode["type"]?.toString() ?: "user"
        val action = body["action"]?.toString() ?: "add"

        val result = issueShareService.changeSharer(issue, loginId, type, action, currentUser)
        return ResponseEntity.ok(result)
    }

    private fun getLoginUser(authentication: Authentication?): User? {
        if (authentication == null) return null
        return userRepository.findByLoginId(authentication.name).orElse(null)
    }
}
