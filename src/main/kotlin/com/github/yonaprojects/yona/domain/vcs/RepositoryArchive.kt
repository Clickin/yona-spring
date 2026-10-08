package com.github.yonaprojects.yona.domain.vcs

import jakarta.servlet.ServletContext
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Call only while holding RepositoryAccess.archive through transaction completion and compensation. */
@Service
class RepositoryArchive(
    private val environment: Environment,
    private val resourceLoader: ResourceLoader,
    private val servletContext: ServletContext
) {
    fun contains(path: File): Boolean {
        val candidate = realOrAbsent(path.toPath())
        val base = environment.getProperty("yona.repository-archive.base-dir", "/tmp/yona-archive")
        if (candidate.startsWith(realOrAbsent(Path.of(base)))) return true
        return listOf("git", "svn", "hg").any { vcs ->
            val root = environment.getProperty("yona.repository-archive.$vcs-dir")?.takeIf(String::isNotBlank)
            root != null && candidate.startsWith(realOrAbsent(Path.of(root)))
        }
    }

    fun begin(projectId: Long, oldVcs: String?, source: Path, target: Path): PendingRepositoryReset {
        check(environment.getProperty("yona.repository-archive.single-serving-process", Boolean::class.java, false)) {
            "Repository reset requires yona.repository-archive.single-serving-process=true: only this JVM may serve or modify repositories"
        }
        require(projectId > 0) { "Repository reset requires a persisted project" }
        val vcs = when (oldVcs?.uppercase()) {
            "SVN", "SUBVERSION" -> "svn"
            "HG", "MERCURIAL" -> "hg"
            else -> "git"
        }
        val repositories = listOf("git", "svn", "hg").associateWith {
            realOrAbsent(Path.of(environment.getProperty("yona.$it.base-dir", "/tmp/yona/$it")))
        }
        val servingRoots = repositories.values + listOf("lfs", "upload").map {
            val default = if (it == "upload") "/tmp/yona/uploads" else "/tmp/yona/lfs"
            realOrAbsent(Path.of(environment.getProperty("yona.$it.base-dir", default)))
        } + resourceRoots().map(::realOrAbsent)
        val base = environment.getProperty("yona.repository-archive.base-dir", "/tmp/yona-archive")
        val archiveRoots = listOf("git", "svn", "hg").associateWith {
            realOrAbsent(Path.of(environment.getProperty("yona.repository-archive.$it-dir")?.takeIf(String::isNotBlank) ?: base))
        }
        for (root in archiveRoots.values + listOf(realOrAbsent(Path.of(base)))) {
            check(servingRoots.none { root.startsWith(it) || it.startsWith(root) }) {
                "Repository archive locations must be outside and must not contain any repository, LFS, upload or resource-serving root"
            }
        }
        val sourceAttributes = directoryAttributes(source)
        val original = source.toRealPath()
        val destination = realOrAbsent(target)
        check(original != repositories.getValue(vcs) && original.startsWith(repositories.getValue(vcs))) {
            "Repository source must remain inside its serving root"
        }
        val nextVcs = when (oldVcs?.uppercase() ?: "GIT") {
            "GIT" -> "svn"
            "SVN", "SUBVERSION" -> "hg"
            else -> "git"
        }
        val targetRoots = repositories.filterValues { destination != it && destination.startsWith(it) }
        val targetVcs = if (nextVcs in targetRoots) nextVcs else targetRoots.maxByOrNull { it.value.nameCount }?.key
        check(targetVcs != null) { "Repository target must remain inside a repository serving root" }
        if (original != destination) {
            requireAbsent(target)
            check(!original.startsWith(destination) && !destination.startsWith(original)) {
                "Repository source and target must not overlap"
            }
        } else {
            directoryAttributes(target) // A symlink alias is not the original directory.
        }
        // Validate storage before changing any repository entry.
        val root = archiveRoots.getValue(vcs)
        val failedTargetRoot = archiveRoots.getValue(targetVcs)
        createPrivateDirectories(root)
        createPrivateDirectories(failedTargetRoot)
        requireSameStore(original, root)
        requireSameStore(existingDirectory(destination.parent), failedTargetRoot)
        check(Files.isWritable(failedTargetRoot) && !Files.getFileStore(failedTargetRoot).isReadOnly) {
            "Failed-target archive root must be writable before repository reset"
        }
        detachSharedFiles(original)
        val operation = Files.createTempDirectory(root, "$projectId-$vcs-", privatePermissions)
        checkPrivateDirectory(operation)
        check(Files.getOwner(root, NOFOLLOW_LINKS) == Files.getOwner(operation, NOFOLLOW_LINKS)) {
            "Repository archive root must be owned by the application's filesystem user"
        }
        check(Files.getOwner(failedTargetRoot, NOFOLLOW_LINKS) == Files.getOwner(operation, NOFOLLOW_LINKS)) {
            "Failed-target archive root must be owned by the application's filesystem user"
        }
        val archived = operation.resolve("repository")
        val pending = PendingRepositoryReset(
            original, destination, archived, sourceAttributes.fileKey()!!,
            failedTargetRoot, "$projectId-failed-$targetVcs-"
        )
        try {
            requireAbsent(archived)
            Files.move(original, archived, ATOMIC_MOVE)
        } catch (failure: Exception) {
            // ATOMIC_MOVE has no copy/delete fallback. Never remove an unexpectedly populated operation.
            try {
                Files.delete(operation)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return pending
    }

    private fun detachSharedFiles(source: Path) {
        check(Files.getFileStore(source).supportsFileAttributeView("unix")) {
            "Repository archive requires filesystem hard-link counts"
        }
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile &&
                    (Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() > 1L) {
                    val copy = Files.createTempFile(file.parent, ".archive-copy-", ".tmp")
                    try {
                        Files.copy(file, copy, REPLACE_EXISTING, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                        check(Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == attributes.fileKey()) {
                            "Repository entry changed while isolating archive data"
                        }
                        Files.move(copy, file, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(copy)
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun resourceRoots(): List<Path> {
        val locations = Binder.get(environment)
            .bind("spring.web.resources.static-locations", Bindable.listOf(String::class.java))
            .orElse(null) ?: listOf("classpath:/META-INF/resources/", "classpath:/resources/", "classpath:/static/", "classpath:/public/")
        val resolver = PathMatchingResourcePatternResolver(resourceLoader)
        val roots = mutableListOf<Path>()
        // /assets/** explicitly serves classpath:/static/, even when Boot's defaults are overridden.
        for (location in (locations + "classpath:/static/").distinct()) {
            if (location.startsWith("classpath:") || location.startsWith("classpath*:")) {
                val path = location.substringAfter(':')
                // Include absent resource suffixes: creating an archive must not turn one into a served directory.
                for (classpathRoot in resolver.getResources("classpath*:/")) {
                    if (classpathRoot.isFile) roots.add(classpathRoot.file.toPath().resolve(path.removePrefix("/")))
                }
                for (resource in resolver.getResources("classpath*:$path")) {
                    if (resource.isFile) roots.add(resource.file.toPath())
                    // A resource inside a jar is not a writable filesystem directory.
                    else check(resource.url.protocol in setOf("jar", "war", "zip", "vfszip")) {
                        "Repository reset cannot validate this classpath resource location"
                    }
                }
            } else if (location.startsWith("file:")) {
                roots.add(resourceLoader.getResource(location).file.toPath())
            } else if (location.startsWith('/')) {
                val real = servletContext.getRealPath(location)
                check(real != null) { "Repository reset requires a resolvable servlet resource location" }
                roots.add(Path.of(real))
            } else {
                error("Repository reset requires file, classpath or servlet-relative static resource locations")
            }
        }
        servletContext.getRealPath("/")?.let { roots.add(Path.of(it)) }
        servletContext.getResource("/")?.takeIf { it.protocol == "file" }?.let { roots.add(Path.of(it.toURI())) }
        return roots
    }
}

class PendingRepositoryReset internal constructor(
    private val source: Path,
    private val target: Path,
    val archiveDirectory: Path,
    private val sourceKey: Any,
    private val failedTargetRoot: Path,
    private val failedTargetPrefix: String
) {
    private val sourceParentKey = directoryAttributes(source.parent).fileKey()
    private val archiveParentKey = directoryAttributes(archiveDirectory.parent).fileKey()
    private val failedTargetRootKey = directoryAttributes(failedTargetRoot).fileKey()
    var failedTargetDirectory: Path? = null
        private set
    private var targetKey: Any? = null
    private var targetParentKey: Any? = null
    private var creationAttempted = false
    private var targetReserved = false
    private var restored = false

    fun createTarget(repository: PlayRepository) {
        check(!creationAttempted && !restored) { "Repository target creation was already attempted" }
        check(realOrAbsent(repository.getDirectory().toPath()) == target) { "Repository target changed" }
        creationAttempted = true
        Files.createDirectories(target.parent)
        check(target.parent.toRealPath() == target.parent) { "Repository target parent changed" }
        targetParentKey = directoryAttributes(target.parent).fileKey()
        Files.createDirectory(target)
        targetReserved = true
        targetKey = directoryAttributes(target).fileKey()
        repository.create()
        check(directoryAttributes(target).fileKey() == targetKey) { "Repository creation replaced its reserved directory" }
    }

    fun rollback() {
        if (restored) return
        // Any uncertainty stops compensation: keep the archive for operator recovery.
        check(archiveDirectory.parent.toRealPath() == archiveDirectory.parent &&
            directoryAttributes(archiveDirectory.parent).fileKey() == archiveParentKey) {
            "Repository archive parent changed; operator recovery required"
        }
        check(directoryAttributes(archiveDirectory).fileKey() == sourceKey) { "Repository archive changed; operator recovery required" }
        check(!targetReserved || targetKey != null) { "Reserved target ownership is unknown; operator recovery required" }
        if (targetKey != null) {
            check(target.parent.toRealPath() == target.parent && directoryAttributes(target.parent).fileKey() == targetParentKey) {
                "Repository target parent changed; operator recovery required"
            }
            check(directoryAttributes(target).fileKey() == targetKey) {
                "Repository target was replaced; operator recovery required"
            }
            check(failedTargetRoot.toRealPath() == failedTargetRoot &&
                directoryAttributes(failedTargetRoot).fileKey() == failedTargetRootKey) {
                "Failed-target archive root changed; operator recovery required"
            }
            checkPrivateDirectory(failedTargetRoot)
            val operation = Files.createTempDirectory(failedTargetRoot, failedTargetPrefix, privatePermissions)
            checkPrivateDirectory(operation)
            val quarantine = operation.resolve("repository")
            requireAbsent(quarantine)
            // Retain the whole partial target without traversing or deleting any of its entries.
            Files.move(target, quarantine, ATOMIC_MOVE)
            failedTargetDirectory = quarantine
            check(directoryAttributes(quarantine).fileKey() == targetKey) {
                "Quarantined target ownership changed; operator recovery required"
            }
            targetKey = null
            targetReserved = false
        }
        requireAbsent(source)
        check(source.parent.toRealPath() == source.parent && directoryAttributes(source.parent).fileKey() == sourceParentKey) {
            "Repository source parent changed; operator recovery required"
        }
        Files.move(archiveDirectory, source, ATOMIC_MOVE)
        restored = true
        // The now-empty private operation directory is harmless; never risk a successful restore to remove it.
    }
}

private val privateMode = PosixFilePermissions.fromString("rwx------")
private val privatePermissions = PosixFilePermissions.asFileAttribute(privateMode)

/** Resolve existing symlinks before normalizing missing suffixes; permission failures are not absence. */
private fun realOrAbsent(path: Path): Path {
    val absolute = path.toAbsolutePath()
    return try {
        Files.readAttributes(absolute, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        absolute.toRealPath()
    } catch (missing: NoSuchFileException) {
        // A dangling symlink is not an absent path that we may create over.
        if (Files.isSymbolicLink(absolute)) throw missing
        val parent = absolute.parent ?: throw missing
        realOrAbsent(parent).resolve(absolute.fileName).normalize()
    }
}

private fun requireAbsent(path: Path) {
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return
    }
    throw FileAlreadyExistsException(path.toString())
}

private fun directoryAttributes(path: Path): BasicFileAttributes {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() != null) {
        "Repository reset requires real directories with stable filesystem file keys"
    }
    return attributes
}

private fun createPrivateDirectories(path: Path) {
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        val parent = path.parent ?: error("Repository archive requires a private filesystem directory")
        if (Files.notExists(parent, NOFOLLOW_LINKS)) createPrivateDirectories(parent)
        check(Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            "Repository archive requires POSIX owner-only directories; ACL-only and unsupported stores are disabled"
        }
        try {
            Files.createDirectory(path, privatePermissions)
        } catch (_: FileAlreadyExistsException) {
            // Another project's reset may have created the configured private root.
        }
    }
    checkPrivateDirectory(path)
}

private fun checkPrivateDirectory(path: Path) {
    directoryAttributes(path)
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    check(posix != null && posix.readAttributes().permissions() == privateMode) {
        "Repository archive requires operator-owned POSIX 0700 directories; existing permissions are never changed"
    }
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
    check(acl == null || acl.acl.none { it.type() == AclEntryType.ALLOW && it.principal() != acl.owner && it.permissions().isNotEmpty() }) {
        "Repository archive ACL must not grant access to any principal other than its owner"
    }
}

private fun existingDirectory(path: Path): Path {
    return try {
        directoryAttributes(path)
        path
    } catch (_: NoSuchFileException) {
        existingDirectory(path.parent ?: throw NoSuchFileException(path.toString()))
    }
}

private fun requireSameStore(repository: Path, archiveRoot: Path) {
    if (Files.getFileStore(repository) != Files.getFileStore(archiveRoot)) {
        throw AtomicMoveNotSupportedException(repository.toString(), archiveRoot.toString(),
            "Repository and its private archive must share a filesystem")
    }
}

