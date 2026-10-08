package com.github.yonaprojects.yona.domain.board

import com.github.yonaprojects.yona.domain.project.Project
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class UnsupportedReadmeException(val project: Project) : ResponseStatusException(
    HttpStatus.BAD_REQUEST,
    "Online README editing is only supported for Git repositories. Use your repository client to update README."
)

// Match RepositoryService's SVN/Hg aliases and default-to-Git resolution.
fun supportsReadmeEditing(project: Project): Boolean = when (project.vcs?.uppercase()) {
    "SUBVERSION", "SVN", "MERCURIAL", "HG" -> false
    else -> true
}

fun requireReadmeSupport(project: Project, readme: Boolean) {
    if (readme && !supportsReadmeEditing(project)) {
        throw UnsupportedReadmeException(project)
    }
}
