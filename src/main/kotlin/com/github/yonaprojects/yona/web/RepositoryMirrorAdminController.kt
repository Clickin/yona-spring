package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping(value = ["/site/repository-mirrors", "/sites/repository-mirrors"])
class RepositoryMirrorAdminController(
    private val service: RepositoryMirrorAdminService,
    private val users: UserRepository,
    private val settings: RepositoryMirrorProperties,
    private val sourcePolicy: MirrorSourcePolicy,
    private val authorService: RepositoryMirrorAuthorService,
    engines: List<MirrorEngine>
) {
    private val supportedVcs = engines.map { it.vcs }.sorted()

    @GetMapping
    fun index(authentication: Authentication?, request: HttpServletRequest,
        @RequestParam(defaultValue = "0") page: Int, model: Model): String {
        val login = checkAdmin(authentication, request)
        model.addAttribute("mirrors", service.list(login, page))
        model.addAttribute("mirrorEnabled", settings.enabled)
        model.addAttribute("credentialRefs", sourcePolicy.credentialRefs())
        model.addAttribute("supportedVcs", supportedVcs)
        model.addAttribute("message", "mirror.title")
        return "site/repository_mirror"
    }

    @PostMapping
    fun create(authentication: Authentication?, request: HttpServletRequest,
        @RequestParam name: String, @RequestParam sourceUrl: String,
        @RequestParam(required = false) credentialRef: String?,
        @RequestParam(defaultValue = "SUBVERSION") vcs: String, flash: RedirectAttributes): String {
        val login = checkAdmin(authentication, request)
        return mutate(flash) {
            val kind = MirrorVcs.parse(vcs)
            if (kind !in supportedVcs) throw MirrorFailure("UNSUPPORTED_VCS")
            service.create(login, name, sourceUrl, credentialRef, kind)
        }
    }

    @PostMapping("/{id}/pause")
    fun pause(authentication: Authentication?, request: HttpServletRequest, @PathVariable id: Long,
        flash: RedirectAttributes): String {
        val login = checkAdmin(authentication, request)
        return mutate(flash) { service.pause(login, id) }
    }

    @PostMapping("/{id}/retry")
    fun retry(authentication: Authentication?, request: HttpServletRequest, @PathVariable id: Long,
        flash: RedirectAttributes): String {
        val login = checkAdmin(authentication, request)
        return mutate(flash) { service.retry(login, id) }
    }

    @PostMapping("/{id}/credential")
    fun credential(authentication: Authentication?, request: HttpServletRequest, @PathVariable id: Long,
        @RequestParam(required = false) credentialRef: String?, flash: RedirectAttributes): String {
        val login = checkAdmin(authentication, request)
        return mutate(flash) { service.setCredential(login, id, credentialRef) }
    }

    @GetMapping("/{id}/authors")
    fun authors(authentication: Authentication?, request: HttpServletRequest, @PathVariable id: Long, model: Model): String {
        val login = checkAdmin(authentication, request)
        model.addAttribute("view", authorService.view(login, id))
        model.addAttribute("message", "mirror.authors")
        return "site/repository_mirror_authors"
    }

    @PostMapping("/{id}/authors")
    fun addAuthor(authentication: Authentication?, request: HttpServletRequest, @PathVariable id: Long,
        @RequestParam sourceAuthor: String, @RequestParam loginId: String, flash: RedirectAttributes): String {
        val login = checkAdmin(authentication, request)
        return mutate(flash, "redirect:/site/repository-mirrors/$id/authors", "mirror.authors.saved") {
            authorService.add(login, id, sourceAuthor, loginId)
        }
    }

    @PostMapping("/{id}/authors/{mappingId}/delete")
    fun removeAuthor(authentication: Authentication?, request: HttpServletRequest, @PathVariable id: Long,
        @PathVariable mappingId: Long, flash: RedirectAttributes): String {
        val login = checkAdmin(authentication, request)
        return mutate(flash, "redirect:/site/repository-mirrors/$id/authors", "mirror.authors.removed") {
            authorService.remove(login, id, mappingId)
        }
    }

    private fun checkAdmin(authentication: Authentication?, request: HttpServletRequest): String {
        // These are session-only administrative forms. PAT/OAuth scopes do not grant source-network
        // administration, and token headers must not turn Spring's CSRF exemption into a bypass.
        if (request.getHeader("Authorization") != null || request.getHeader("Yona-Token") != null ||
            authentication?.isAuthenticated != true ||
            users.findByLoginId(authentication.name).orElse(null)?.isSiteManager != true
        ) throw AccessDeniedException("Site administrator session required")
        return authentication.name
    }

    private fun mutate(
        flash: RedirectAttributes,
        redirect: String = "redirect:/site/repository-mirrors",
        notice: String = "mirror.actionQueued",
        action: () -> Any?
    ): String {
        try {
            action()
            flash.addFlashAttribute("mirrorNotice", notice)
        } catch (failure: MirrorFailure) {
            flash.addFlashAttribute("mirrorNotice", mirrorErrorMessage(failure.code))
        }
        return redirect
    }
}
