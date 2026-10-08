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

@Component
class RepositoryMirrorStorage(
    @Value("\${yona.svn.base-dir:/tmp/yona/svn}") private val baseDir: String,
    @Value("\${yona.git.base-dir:/tmp/yona/git}") private val gitBaseDir: String,
    @Value("\${yona.hg.base-dir:/tmp/yona/hg}") private val hgBaseDir: String,
    private val namespaceGuard: RepositoryNamespaceGuard
) {
    fun reserve(project: Project, generation: Long): File = safe {
        val path = path(project)
        // Check every VCS root while the admin transaction holds the name lock.
        if (!Files.notExists(path, NOFOLLOW_LINKS) ||
            !Files.notExists(Path.of(gitBaseDir).resolve(project.owner!!).resolve("${project.name}.git"), NOFOLLOW_LINKS) ||
            !Files.notExists(Path.of(hgBaseDir).resolve(project.owner!!).resolve(project.name), NOFOLLOW_LINKS)
        ) {
            throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        // Publish ownership first: interruption or rollback must never leave an adoptable orphan.
        Files.writeString(marker(project, path), owner(project, generation, path), CREATE_NEW, WRITE)
        Files.createDirectory(path)
        path.toFile()
    }

    fun directory(mirror: RepositoryMirror): File = safe {
        val path = path(mirror.project)
        val marker = marker(mirror.project, path)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            !Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) > 4096 ||
            Files.readString(marker) != owner(mirror.project, mirror.generation, path)
        ) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        // SVN metadata may not redirect the writer outside this reserved directory either.
        for (name in listOf("db", "conf", "hooks", "locks", "format")) {
            if (Files.isSymbolicLink(path.resolve(name))) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        path.toFile()
    }

    private fun path(project: Project): Path {
        val owner = project.owner ?: throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        if (!validSegment(owner) || !validSegment(project.name)) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        val configured = Path.of(baseDir).toAbsolutePath().normalize()
        Files.createDirectories(configured)
        val base = configured.toRealPath()
        val parent = base.resolve(owner)
        if (!Files.exists(parent, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(parent) } catch (_: java.nio.file.FileAlreadyExistsException) { /* Another project reserved this owner. */ }
        }
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || parent.toRealPath() != parent) {
            throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        val target = parent.resolve(project.name)
        if (!target.startsWith(base) || Files.isSymbolicLink(target)) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        return target
    }

    private fun marker(project: Project, path: Path): Path {
        val owners = path.parent.parent.resolve(".mirror-owners")
        if (!Files.exists(owners, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(owners) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        }
        if (!Files.isDirectory(owners, NOFOLLOW_LINKS) || owners.toRealPath() != owners) {
            throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        }
        return owners.resolve(namespaceGuard.markerPath(project.owner!!, project.name).fileName)
    }

    private fun owner(project: Project, generation: Long, path: Path): String {
        val id = project.id ?: throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        if (id <= 0 || generation <= 0) throw MirrorFailure("DIRECTORY_OWNERSHIP", attention = true)
        return "yona-svn-mirror:$id:$generation\n$path\n"
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
