package com.github.yonaprojects.yona.domain.project

import com.github.yonaprojects.yona.domain.vcs.RepositoryWriteGuard
import com.github.yonaprojects.yona.domain.vcs.RepositoryNamespaceGuard
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

@Service
class GitServiceImpl(
    @Value("\${yona.git.base-dir:/tmp/yona/git}")
    private val baseDir: String,
    private val writeGuard: RepositoryWriteGuard,
    private val namespaceGuard: RepositoryNamespaceGuard
) : GitService {

    override fun createRepository(owner: String, name: String): File =
        namespaceGuard.acquire(owner, name).use {
            namespaceGuard.requireUnreserved(owner, name)
            writeGuard.requireDestinationWritable(owner, name)
            val repoDir = getRepositoryPath(owner, name)
            if (!repoDir.exists()) {
                RepositoryBuilder()
                    .setGitDir(repoDir)
                    .setBare()
                    .build().use { repository -> repository.create(true) }
            }
            repoDir
        }

    override fun getRepositoryPath(owner: String, name: String): File {
        return File(baseDir, "$owner/$name.git")
    }

    override fun deleteRepository(owner: String, name: String): Boolean {
        writeGuard.requireDestinationWritable(owner, name)
        val repoDir = getRepositoryPath(owner, name)
        return if (repoDir.exists()) {
            repoDir.deleteRecursively()
        } else {
            false
        }
    }

    override fun cloneRepository(gitUrl: String, owner: String, name: String, authId: String?, authPw: String?): File =
        namespaceGuard.acquire(owner, name).use {
            namespaceGuard.requireUnreserved(owner, name)
            writeGuard.requireDestinationWritable(owner, name)
            val repoDir = getRepositoryPath(owner, name)
            require(!Files.exists(repoDir.toPath(), NOFOLLOW_LINKS)) { "Destination repository already exists." }
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
