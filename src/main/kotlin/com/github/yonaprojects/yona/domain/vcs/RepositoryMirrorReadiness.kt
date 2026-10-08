package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class RepositoryMirrorReadiness(private val mirrors: RepositoryMirrorRepository) {
    fun isReady(project: Project): Boolean {
        if (project.repositoryMode != RepositoryMode.MIRROR) return true
        val mirror = project.id?.let(mirrors::findByProjectId)
            ?: return false
        val target = mirror.initialImportTargetRevision ?: return false
        return mirror.status != RepositoryMirrorStatus.NEEDS_ATTENTION &&
            mirror.lastVerifiedRevision >= target && mirror.lastIndexedRevision >= target
    }

    fun requireReady(project: Project) {
        if (!isReady(project)) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Repository mirror is preparing; initial verification and indexing are not complete.")
    }
}
