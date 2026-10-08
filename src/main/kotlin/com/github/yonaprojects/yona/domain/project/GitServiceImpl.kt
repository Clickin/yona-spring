package com.github.yonaprojects.yona.domain.project

import com.github.yonaprojects.yona.domain.vcs.RepositoryAccess
import com.github.yonaprojects.yona.domain.vcs.RepositoryArchive
import com.github.yonaprojects.yona.domain.vcs.RepositoryUnavailableException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class GitServiceImpl(
    @Value("\${yona.git.base-dir:/tmp/yona/git}")
    private val baseDir: String,
    private val repositoryArchive: RepositoryArchive
) : GitService {

    override fun createRepository(owner: String, name: String): File = RepositoryAccess.session {
        val repoDir = getRepositoryPath(owner, name)
        if (repoDir.exists()) return@session repoDir
        RepositoryBuilder().setGitDir(repoDir).setBare().build().use { it.create(true) }
        repoDir
    }

    override fun getRepositoryPath(owner: String, name: String): File {
        val directory = File(baseDir, "$owner/$name.git")
        if (!RepositoryAccess.isWithinRoot(directory, File(baseDir)) || repositoryArchive.contains(directory)) {
            throw RepositoryUnavailableException()
        }
        return directory
    }

    override fun deleteRepository(owner: String, name: String): Boolean = RepositoryAccess.session {
        val repoDir = getRepositoryPath(owner, name)
        if (repoDir.exists()) repoDir.deleteRecursively() else false
    }

    override fun cloneRepository(gitUrl: String, owner: String, name: String, authId: String?, authPw: String?): File = RepositoryAccess.session {
        val source = URIish(gitUrl)
        if ((source.scheme == null && source.host == null) || source.scheme.equals("file", ignoreCase = true)) {
            if (repositoryArchive.contains(File(source.path))) throw RepositoryUnavailableException()
        }
        val repoDir = getRepositoryPath(owner, name)
        if (repoDir.exists()) {
            repoDir.deleteRecursively()
        }
        val cloneCommand = Git.cloneRepository()
            .setURI(gitUrl)
            .setDirectory(repoDir)
            .setCloneAllBranches(true)
            .setBare(true)

        if (!authId.isNullOrEmpty() || !authPw.isNullOrEmpty()) {
            cloneCommand.setCredentialsProvider(
                UsernamePasswordCredentialsProvider(authId ?: "", authPw ?: "")
            )
        }

        cloneCommand.call().close()
        repoDir
    }
}
