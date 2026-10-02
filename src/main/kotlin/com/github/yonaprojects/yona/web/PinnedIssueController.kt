package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant

@Controller
@Transactional
class PinnedIssueController(
    private val projectRepository: ProjectRepository,
    private val issueRepository: IssueRepository,
    private val userRepository: UserRepository,
    private val accessControl: AccessControl
) {
    data class PinRequest(val pinned: Boolean)

    @PutMapping("/api/v1/projects/{owner}/{projectName}/issues/{number}/pin")
    fun pinApi(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestBody request: PinRequest,
        authentication: Authentication?
    ): ResponseEntity<Map<String, Boolean>> = setPin(owner, projectName, number, request.pinned, authentication)

    @PostMapping("/{owner}/{projectName}/issue/{number}/pin")
    fun pinWeb(
        @PathVariable owner: String,
        @PathVariable projectName: String,
        @PathVariable number: Long,
        @RequestParam pinned: Boolean,
        authentication: Authentication?
    ): ResponseEntity<*> {
        val result = setPin(owner, projectName, number, pinned, authentication)
        if (!result.statusCode.is2xxSuccessful) return result
        val location = UriComponentsBuilder.fromPath("/{owner}/{projectName}/issue/{number}")
            .buildAndExpand(owner, projectName, number).encode().toUri()
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build<Void>()
    }

    private fun setPin(
        owner: String, projectName: String, number: Long, pinned: Boolean, authentication: Authentication?
    ): ResponseEntity<Map<String, Boolean>> {
        val user = authentication?.let { userRepository.findByLoginId(it.name).orElse(null) }
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val project = projectRepository.findByOwnerAndName(owner, projectName).orElse(null)
            ?: return ResponseEntity.notFound().build()
        if (!accessControl.isAllowed(user, project, Operation.READ)) return ResponseEntity.notFound().build()
        if (user.isGuest || !user.isManagerOf(project)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val issue = issueRepository.findByProjectAndNumber(project, number)
            ?: return ResponseEntity.notFound().build()
        if (!accessControl.isAllowed(user, project, issue, Operation.READ)) return ResponseEntity.notFound().build()
        if (issue.isDraft || issue.state == State.DRAFT) return ResponseEntity.badRequest().build()
        val changed = issueRepository.updatePin(issue.id!!, project.id!!, if (pinned) issue.pinnedAt ?: Instant.now() else null)
        if (changed == 0) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("pinned" to pinned))
    }
}
