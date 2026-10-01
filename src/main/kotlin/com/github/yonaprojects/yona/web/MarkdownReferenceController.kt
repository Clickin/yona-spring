package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.organization.OrganizationRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

@RestController
class MarkdownReferenceController(
    private val projectRepository: ProjectRepository,
    private val issueRepository: IssueRepository,
    private val userRepository: UserRepository,
    private val organizationRepository: OrganizationRepository,
    private val repositoryService: RepositoryService,
    private val accessControl: AccessControl,
    private val projectUserRepository: ProjectUserRepository,
    private val messageSource: MessageSource
) {
    data class Reference(val type: String, val value: String)
    data class Request(val items: List<Reference>)
    data class Metadata(
        val key: String, val type: String, val href: String, val label: String,
        val state: String? = null, val stateLabel: String? = null,
        val kind: String? = null, val popover: String? = null
    )
    data class Response(val items: List<Metadata>)

    @PostMapping("/api/{owner}/{project}/markdown/references/resolve")
    fun resolve(
        @PathVariable owner: String,
        @PathVariable project: String,
        @RequestBody request: Request,
        authentication: Authentication?
    ): ResponseEntity<*> {
        if (request.items.size > 100 || request.items.any {
                it.value.isEmpty() || it.value.length > 200 || it.type !in TYPES
            }) return ResponseEntity.badRequest().build<Void>()
        val context = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, project).orElse(null)
            ?: return ResponseEntity.notFound().build<Void>()
        val user = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?.let { userRepository.findByLoginId(it.name).orElse(null) }
        if (!accessControl.isAllowed(user, context, Operation.READ)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        // Cache project lookups only within this permission-checked request, never across users.
        val projects = mutableMapOf<String, Project?>("${context.owner}/${context.name}" to context)
        fun target(path: String): Project? {
            if (path.isEmpty()) return context
            // Legacy fork shorthand: a bare owner names that owner's project with the current project's name.
            val fullPath = if ('/' in path) path else "$path/${context.name}"
            if (!PROJECT.matches(fullPath) || fullPath.split('/').any { it == "." || it == ".." }) return null
            val found = projects.getOrPut(fullPath) {
                projectRepository.findByOwnerAndName(fullPath.substringBefore('/'), fullPath.substringAfter('/')).orElse(null)
            } ?: return null
            return found.takeIf { accessControl.isAllowed(user, it, Operation.READ) }
        }
        val items = request.items.distinct().mapNotNull { reference ->
            val value = reference.value
            val key = "${reference.type}:$value"
            when (reference.type) {
                "issue" -> {
                    val match = ISSUE.matchEntire(value) ?: return@mapNotNull null
                    val destination = target(match.groupValues[1]) ?: return@mapNotNull null
                    val number = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val issue = issueRepository.findByProjectAndNumber(destination, number) ?: return@mapNotNull null
                    if (!accessControl.isAllowed(user, destination, issue, Operation.READ)) return@mapNotNull null
                    val state = issue.state.state()
                    Metadata(key, "issue", "${projectUrl(destination)}/issue/${issue.number}",
                        "${match.groupValues[1]}#${issue.number}.${issue.title}", state,
                        messageSource.getMessage("issue.state.$state", null, state, LocaleContextHolder.getLocale()))
                }
                "project" -> {
                    val destination = target(value) ?: return@mapNotNull null
                    Metadata(key, "project", projectUrl(destination), "@${destination.owner}/${destination.name}")
                }
                "user" -> resolveUser(value, key, user)
                "commit" -> {
                    val match = COMMIT.matchEntire(value) ?: return@mapNotNull null
                    val destination = target(match.groupValues[1]) ?: return@mapNotNull null
                    if (!destination.isCodeEnabled || (destination.vcs ?: "GIT").uppercase() != "GIT") return@mapNotNull null
                    if (destination.isCodeAccessibleMemberOnly == true &&
                        (user?.id == null || (!projectUserRepository.existsByProjectIdAndUserId(destination.id!!, user.id!!) &&
                            !accessControl.isAllowedIfGroupMember(destination, user)))) return@mapNotNull null
                    val commit = try {
                        repositoryService.getRepository(destination).getCommit(match.groupValues[2])
                    } catch (_: Exception) {
                        null // Missing/ambiguous revisions and unavailable repositories remain literal text.
                    } ?: return@mapNotNull null
                    val prefix = match.groupValues[1].let { if (it.isEmpty()) "" else "$it@" }
                    Metadata(key, "commit", "${projectUrl(destination)}/commit/${segment(commit.getId())}", "$prefix${commit.getShortId()}")
                }
                else -> null
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Response(items))
    }

    private fun resolveUser(value: String, key: String, viewer: User?): Metadata? {
        if (!USER.matches(value)) return null
        val login = value.removePrefix("@")
        val organization = organizationRepository.findByName(login).orElse(null)
        if (organization != null) {
            if (!accessControl.isAllowed(viewer, organization, Operation.READ)) return null
            return Metadata(key, "user", "/org/${segment(organization.name)}", "@${organization.name}", kind = "org")
        }
        // User profiles are global readable resources; anonymous/deleted identities are not references.
        val user = userRepository.findByLoginId(login).orElse(null) ?: return null
        if (user.id == null || user.loginId == "anonymous") return null
        return Metadata(key, "user", "/user/${segment(user.loginId)}", "@${user.getPureNameOnly(LocaleContextHolder.getLocale().language)}",
            kind = "user", popover = "${user.name} ${user.loginId}")
    }

    private fun projectUrl(project: Project) = "/${segment(project.owner.orEmpty())}/${segment(project.name)}"
    private fun segment(value: String) = UriUtils.encodePathSegment(value, StandardCharsets.UTF_8)

    companion object {
        private val TYPES = setOf("issue", "user", "project", "commit")
        private const val NAME = "[a-zA-Z0-9_.가-힣-]+"
        private val PROJECT = Regex("$NAME/$NAME")
        private val ISSUE = Regex("(?:($NAME(?:/$NAME)?))?#([0-9]+)")
        private val COMMIT = Regex("(?:($NAME(?:/$NAME)?)@)?([a-f0-9]{7,40})")
        private val USER = Regex("@$NAME")
    }
}
