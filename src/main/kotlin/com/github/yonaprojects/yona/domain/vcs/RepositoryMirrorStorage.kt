package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/**
 * Reserves and validates the local repository directory of a mirror. The directory is the one the
 * hosted protocols already serve for the project's VCS (`<svn>/owner/name`, `<git>/owner/name.git`,
 * `<hg>/owner/name`); its ownership marker lives outside every VCS root.
 */
@Component
class RepositoryMirrorStorage(
    @Value("\${yona.svn.base-dir:/tmp/yona/svn}") private val svnBaseDir: String,
    @Value("\${yona.git.base-dir:/tmp/yona/git}") private val gitBaseDir: String,
    @Value("\${yona.hg.base-dir:/tmp/yona/hg}") private val hgBaseDir: String,
    private val namespaceGuard: RepositoryNamespaceGuard
) {
    fun reserve(project: Project, generation: Long): File = safe {
        val owner = project.owner ?: throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        // Check every VCS root while the admin transaction holds the name lock.
        if (MirrorVcs.entries.any { !Files.notExists(candidate(it, owner, project.name), NOFOLLOW_LINKS) }) {
            throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        val path = path(project)
        // Publish ownership first: interruption or rollback must never leave an adoptable orphan.
        Files.writeString(marker(project), owner(project, generation, path), CREATE_NEW, WRITE)
        Files.createDirectory(path)
        path.toFile()
    }

    fun directory(mirror: RepositoryMirror): File = safe {
        val path = path(mirror.project)
        val marker = marker(mirror.project)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            !Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) > 4096 ||
            Files.readString(marker) != owner(mirror.project, mirror.generation, path)
        ) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        // Repository metadata may not redirect the writer outside this reserved directory either.
        val metadata = if (MirrorVcs.of(mirror.project) == MirrorVcs.MERCURIAL) path.resolve(".hg") else path
        for (root in listOf(path, metadata).distinct()) {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) continue
            Files.list(root).use { children ->
                if (children.anyMatch { Files.isSymbolicLink(it) }) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
            }
        }
        path.toFile()
    }

    private fun candidate(vcs: MirrorVcs, owner: String, name: String): Path = when (vcs) {
        MirrorVcs.SUBVERSION -> Path.of(svnBaseDir).resolve(owner).resolve(name)
        MirrorVcs.GIT -> Path.of(gitBaseDir).resolve(owner).resolve("$name.git")
        MirrorVcs.MERCURIAL -> Path.of(hgBaseDir).resolve(owner).resolve(name)
    }

    private fun path(project: Project): Path {
        val owner = project.owner ?: throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        if (!validSegment(owner) || !validSegment(project.name)) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        val vcs = MirrorVcs.of(project)
        val configured = when (vcs) {
            MirrorVcs.SUBVERSION -> svnBaseDir
            MirrorVcs.GIT -> gitBaseDir
            MirrorVcs.MERCURIAL -> hgBaseDir
        }.let { Path.of(it).toAbsolutePath().normalize() }
        Files.createDirectories(configured)
        val base = configured.toRealPath()
        val parent = base.resolve(owner)
        if (!Files.exists(parent, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(parent) } catch (_: java.nio.file.FileAlreadyExistsException) { /* Another project reserved this owner. */ }
        }
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || parent.toRealPath() != parent) {
            throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        val target = parent.resolve(if (vcs == MirrorVcs.GIT) "${project.name}.git" else project.name)
        if (!target.startsWith(base) || Files.isSymbolicLink(target)) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        return target
    }

    private fun marker(project: Project): Path {
        val marker = namespaceGuard.markerPath(project.owner!!, project.name)
        val owners = marker.parent
        if (!Files.exists(owners, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(owners) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        }
        if (!Files.isDirectory(owners, NOFOLLOW_LINKS) || owners.toRealPath() != owners) {
            throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        return marker
    }

    private fun owner(project: Project, generation: Long, path: Path): String {
        val id = project.id ?: throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        if (id <= 0 || generation <= 0) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        return "yona-mirror:$id:$generation\n$path\n"
    }

    private fun <T> safe(action: () -> T): T = try { action() } catch (e: MirrorFailure) {
        throw e
    } catch (_: Exception) {
        // Never delete an orphan on a failed/ambiguous DB commit; an operator must inspect it.
        throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
    }

    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
        fun validSegment(value: String): Boolean = SEGMENT.matches(value) && !value.endsWith(".")
    }
}
