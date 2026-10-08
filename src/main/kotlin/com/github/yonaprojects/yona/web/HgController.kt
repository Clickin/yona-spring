package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.branchprotection.ProtectedBranchRepository
import com.github.yonaprojects.yona.domain.gpgkey.GpgSignatureVerifier
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.pullrequest.PullRequestRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.HgBranchProtectionPrePushkeyHook
import com.github.yonaprojects.yona.domain.vcs.HgYonaPostPushkeyHook
import com.github.yonaprojects.yona.domain.vcs.PushedBranchRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryAccess
import io.github.search5.hg4j.api.HgHook
import io.github.search5.hg4j.lib.HgRepository
import io.github.search5.hg4j.transport.HgHttpWireServer
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
@RestController
class HgController(
    @Value("\${yona.hg.base-dir:/tmp/yona/hg}")
    private val baseDir: String,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val protectedBranchRepository: ProtectedBranchRepository,
    private val projectUserRepository: ProjectUserRepository,
    private val gpgSignatureVerifier: GpgSignatureVerifier,
    private val pullRequestRepository: PullRequestRepository,
    private val pushedBranchRepository: PushedBranchRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(HgController::class.java)
    // A cached wire server retains a live repository across reset. Open and close per request instead.

    @RequestMapping(value = ["/hg/{owner}/{project}", "/hg/{owner}/{project}/**"])
    fun service(
        @PathVariable owner: String,
        @PathVariable project: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val resolvedProject = resolveProject(owner, project)
        if (resolvedProject?.id == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        RepositoryAccess.read(resolvedProject.id!!) {
            val repoDir = File(File(baseDir, owner), project)
            if (!RepositoryAccess.isWithinRoot(repoDir, File(baseDir)) || !repoDir.isDirectory) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND)
                return@read
            }
            try {
                HgRepository(repoDir).use { repository ->
                    val wireServer = HgHttpWireServer(repository).apply {
                        registerPrePushkeyHook(
                            HgHook { context ->
                                val currentProject = resolveProject(owner, project) ?: return@HgHook true
                                HgBranchProtectionPrePushkeyHook(
                                    currentProject, resolveCurrentUser(), protectedBranchRepository,
                                    projectUserRepository, gpgSignatureVerifier
                                ).run(context)
                            }
                        )
                        registerPostPushkeyHook(
                            HgHook { context ->
                                val currentProject = resolveProject(owner, project) ?: return@HgHook true
                                val pusher = resolveCurrentUser() ?: return@HgHook true
                                HgYonaPostPushkeyHook(
                                    currentProject, pusher, projectRepository, pullRequestRepository,
                                    pushedBranchRepository, eventPublisher, meterRegistry
                                ).run(context)
                            }
                        )
                    }
                    wireServer.service(HgServletRequestWrapper(request, owner, project), response)
                }
            } catch (e: Exception) {
                logger.error("Failed to process a Mercurial request: {}", request.requestURI, e)
                if (!response.isCommitted) response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            } finally {
                response.flushBuffer()
            }
        }
    }

    // GitServletConfig.resolveProject()와 동일한 폴백(findByOwnerAndNameOrPreviousPlace) — 프로젝트가
    // 개명된 뒤에도 기존 clone/push URL이 계속 동작해야 한다.
    private fun resolveProject(owner: String, projectName: String) =
        projectRepository.findByOwnerAndNameOrPreviousPlace(owner, projectName).orElse(null)

    private fun resolveCurrentUser(): User? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (!authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            return null
        }
        return userRepository.findByLoginId(authentication.name).orElse(null)
    }
}
