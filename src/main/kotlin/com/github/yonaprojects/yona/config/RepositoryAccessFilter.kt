package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryAccess
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import com.github.yonaprojects.yona.domain.vcs.RepositoryUnavailableException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/** Drains synchronous protocol and code responses before reset, without replacing authorization. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class RepositoryAccessFilter(
    private val projectRepository: ProjectRepository,
    private val repositoryService: RepositoryService
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = try {
            UriUtils.decode(request.requestURI.removePrefix(request.contextPath), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST)
            return
        }
        val match = protocol.matchEntire(path) ?: vcsApi.matchEntire(path) ?: restCode.matchEntire(path)
            ?: browserCode.matchEntire(path)
        val projectId = if (match != null) {
            val owner = match.groupValues[1]
            val requestedName = match.groupValues[2]
            val isGit = path.startsWith("/git/") || path.startsWith("/git-lfs/")
            val name = if (isGit) requestedName.removeSuffix(".git") else requestedName
            val project = projectRepository.findByOwnerAndNameOrPreviousPlace(owner, name).orElse(null)
                ?: if (isGit && name.endsWith(".wiki")) {
                    projectRepository.findByOwnerAndNameOrPreviousPlace(owner, name.removeSuffix(".wiki")).orElse(null)
                } else null
            project?.id
        } else null
        if (projectId == null) {
            chain.doFilter(request, response)
            return
        }
        if (request.method != "GET" && request.method != "HEAD" && settings.matches(path)) {
            RepositoryAccess.exclusive(projectId) { serve(projectId, request, response, chain) }
        } else {
            RepositoryAccess.read(projectId) { serve(projectId, request, response, chain) }
        }
    }

    private fun serve(projectId: Long, request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            // Reload after waiting for reset; never keep a pre-reset VCS selection for a request.
            projectRepository.findById(projectId).orElse(null)?.let { repositoryService.getRepository(it) }
            chain.doFilter(request, response)
        } catch (_: RepositoryUnavailableException) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
        } finally {
            // MVC resource bodies and JGit/DAV responses are written synchronously inside this chain.
            response.flushBuffer()
        }
    }

    companion object {
        private val protocol = Regex("^/(?:git|git-lfs|svn|hg)/([^/]+)/([^/]+)(?:/.*)?$")
        private val vcsApi = Regex("^/api/vcs/([^/]+)/([^/]+)(?:/.*)?$")
        private val restCode = Regex("^/api/v1/projects/([^/]+)/([^/]+)/tags(?:/.*)?$")
        private val settings = Regex("^/[^/]+/[^/]+/setting/?$")
        // Include branch/tag mutations and the board's commitTextFile path. Never match changeVCS:
        // acquiring a read lock there would deadlock when the service enters reset's write lock.
        private val browserCode = Regex(
            "^/((?!stylesheets/|javascripts/|images/|bootstrap/|assets/|webjars/|api/|git/|git-lfs/|svn/|hg/)[^/]+)/([^/]+)(?:/(?:code|rawcode|image|files|commits|commit|compare|branches|tags|setting|postform|posts|issueform)(?:/.*)?|/post/new)?/?$"
        )
    }
}
