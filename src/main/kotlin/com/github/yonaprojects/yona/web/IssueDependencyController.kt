package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.issue.IssueDependencies
import com.github.yonaprojects.yona.domain.issue.IssueDependencyException
import com.github.yonaprojects.yona.domain.issue.IssueDependencyService
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder

@Controller
class IssueDependencyController(
    private val dependencies: IssueDependencyService,
    private val projects: ProjectRepository,
    private val users: UserRepository
) {
    enum class Direction { BLOCKS, BLOCKED_BY }

    // The existing v1 security filters enforce ISSUES read/write and repository token scope.
    @GetMapping("/api/v1/projects/{owner}/{project}/issues/{number}/dependencies")
    @ResponseBody
    fun get(@PathVariable owner: String, @PathVariable project: String, @PathVariable number: Long,
            authentication: Authentication?): IssueDependencies =
        dependencies.get(projectId(owner, project), number, user(authentication))

    @PostMapping("/api/v1/projects/{owner}/{project}/issues/{number}/dependencies/{successorNumber}")
    @ResponseBody
    fun add(@PathVariable owner: String, @PathVariable project: String, @PathVariable number: Long,
            @PathVariable successorNumber: Long, authentication: Authentication?): ResponseEntity<Void> {
        dependencies.add(projectId(owner, project), number, successorNumber, user(authentication))
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @DeleteMapping("/api/v1/projects/{owner}/{project}/issues/{number}/dependencies/{successorNumber}")
    @ResponseBody
    fun remove(@PathVariable owner: String, @PathVariable project: String, @PathVariable number: Long,
               @PathVariable successorNumber: Long, authentication: Authentication?): ResponseEntity<Void> {
        dependencies.remove(projectId(owner, project), number, successorNumber, user(authentication))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{owner}/{project}/issue/{number}/dependencies")
    fun addForm(@PathVariable owner: String, @PathVariable project: String, @PathVariable number: Long,
                @RequestParam targetNumber: Long, @RequestParam direction: Direction,
                authentication: Authentication?, redirect: RedirectAttributes): String =
        changeForm(owner, project, number, targetNumber, direction, authentication, redirect, false)

    @PostMapping("/{owner}/{project}/issue/{number}/dependencies/delete")
    fun removeForm(@PathVariable owner: String, @PathVariable project: String, @PathVariable number: Long,
                   @RequestParam targetNumber: Long, @RequestParam direction: Direction,
                   authentication: Authentication?, redirect: RedirectAttributes): String =
        changeForm(owner, project, number, targetNumber, direction, authentication, redirect, true)

    private fun changeForm(owner: String, project: String, number: Long, targetNumber: Long,
                           direction: Direction, authentication: Authentication?, redirect: RedirectAttributes,
                           remove: Boolean): String {
        val predecessor = if (direction == Direction.BLOCKS) number else targetNumber
        val successor = if (direction == Direction.BLOCKS) targetNumber else number
        try {
            val projectId = projectId(owner, project)
            val user = user(authentication)
            if (remove) dependencies.remove(projectId, predecessor, successor, user)
            else dependencies.add(projectId, predecessor, successor, user)
        } catch (e: IssueDependencyException) {
            redirect.addFlashAttribute("dependencyError", "issue.dependencies.error.${e.code}")
        }
        val path = UriComponentsBuilder.fromPath("/{owner}/{project}/issue/{number}")
            .buildAndExpand(owner, project, number).encode().toUriString()
        return "redirect:$path#issue-dependencies"
    }

    @ExceptionHandler(IssueDependencyException::class)
    @ResponseBody
    fun rejected(exception: IssueDependencyException): ResponseEntity<Map<String, String>> {
        val status = when (exception.code) {
            "notfound" -> HttpStatus.NOT_FOUND
            "forbidden" -> HttpStatus.FORBIDDEN
            "duplicate", "cycle" -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
        return ResponseEntity.status(status).body(mapOf("error" to exception.code))
    }

    private fun projectId(owner: String, name: String): Long =
        projects.findByOwnerAndNameOrPreviousPlace(owner, name).orElseThrow { IssueDependencyException("notfound") }.id!!

    private fun user(authentication: Authentication?) =
        authentication?.let { users.findByLoginId(it.name).orElse(null) }
}
