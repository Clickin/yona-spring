package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component

enum class RepositoryKind { CODE, WIKI }

@Component
class RepositoryWriteGuard(private val projectRepository: ProjectRepository) {
    // Deliberately independent of the mirror feature switch and the current user's privileges.
    fun isWritable(project: Project, kind: RepositoryKind = RepositoryKind.CODE): Boolean {
        if (kind == RepositoryKind.WIKI) return true
        val mode = project.id?.let(projectRepository::findRepositoryModeById) ?: run {
            if (project.id != null) return false
            project.repositoryMode
        }
        return mode == RepositoryMode.HOSTED
    }

    fun requireWritable(project: Project, kind: RepositoryKind = RepositoryKind.CODE) {
        if (!isWritable(project, kind)) throw AccessDeniedException("Mirror code repositories are read-only")
    }

    fun requireWritable(owner: String, name: String, kind: RepositoryKind = RepositoryKind.CODE) {
        if (kind == RepositoryKind.WIKI) return
        if (projectRepository.findRepositoryModeByOwnerAndName(owner, name) != RepositoryMode.HOSTED) {
            throw AccessDeniedException("Mirror code repositories are read-only")
        }
    }

    fun requireDestinationWritable(owner: String, name: String, kind: RepositoryKind = RepositoryKind.CODE) {
        if (kind != RepositoryKind.WIKI && projectRepository.findRepositoryModeByOwnerAndName(owner, name) == RepositoryMode.MIRROR) {
            throw AccessDeniedException("Mirror code repositories are read-only")
        }
    }
}
