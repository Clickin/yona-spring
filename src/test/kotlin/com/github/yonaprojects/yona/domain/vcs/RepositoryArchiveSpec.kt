package com.github.yonaprojects.yona.domain.vcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockServletContext
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

class RepositoryArchiveSpec : DescribeSpec({
    describe("private atomic repository archives") {
        it("archives the real source, reserves and creates a real target, then restores on rollback") {
            ArchiveFixture().use { f ->
                val source = f.git("original")
                val target = f.svn("replacement")
                source.create()
                f.mark(source.getDirectory().toPath())
                val pending = f.archive().begin(41, "GIT", source.getDirectory().toPath(), target.getDirectory().toPath())

                Files.exists(source.getDirectory().toPath()) shouldBe false
                f.marker(pending.archiveDirectory) shouldBe "original-history"
                pending.archiveDirectory.fileName.toString() shouldBe "repository"
                pending.archiveDirectory.parent.fileName.toString().startsWith("41-git-") shouldBe true
                FileRepositoryBuilder().setGitDir(pending.archiveDirectory.toFile()).build().use { it.isBare shouldBe true }
                pending.createTarget(target)
                Files.exists(target.getDirectory().toPath().resolve("format")) shouldBe true
                pending.failedTargetDirectory shouldBe null

                pending.rollback()
                f.marker(source.getDirectory().toPath()) shouldBe "original-history"
                Files.exists(target.getDirectory().toPath(), NOFOLLOW_LINKS) shouldBe false
                Files.exists(pending.archiveDirectory, NOFOLLOW_LINKS) shouldBe false
                val quarantined = pending.failedTargetDirectory!!
                Files.exists(quarantined.resolve("format")) shouldBe true
                quarantined.parent.fileName.toString().startsWith("41-failed-svn-") shouldBe true
                Files.getPosixFilePermissions(quarantined.parent) shouldBe PosixFilePermissions.fromString("rwx------")
                pending.rollback() // Completed compensation is harmless when called again.
            }
        }

        it("isolates existing fork hard links before archiving and preserves that snapshot on rollback") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val fork = Files.createDirectories(f.root.resolve("hg/other/fork"))
                val alias = Files.createLink(fork.resolve("marker"), source.resolve("marker"))
                val pending = RepositoryAccess.archive(41) { f.begin(source) }
                Files.isSameFile(alias, pending.archiveDirectory.resolve("marker")) shouldBe false
                Files.writeString(alias, "fork changed")
                f.marker(pending.archiveDirectory) shouldBe "original-history"
                pending.rollback()
                f.marker(source) shouldBe "original-history"
                Files.readString(alias) shouldBe "fork changed"
            }
        }

        it("is disabled unless the operator explicitly acknowledges the single serving process prerequisite") {
            ArchiveFixture().use { f ->
                val source = f.source()
                f.environment.setProperty("yona.repository-archive.single-serving-process", "false")
                shouldThrow<IllegalStateException> { f.begin(source) }.message shouldContain "single-serving-process=true"
                f.marker(source) shouldBe "original-history"
                Files.exists(f.root.resolve("archive")) shouldBe false
            }
        }

        it("rejects missing source without creating a target or archive") {
            ArchiveFixture().use { f ->
                shouldThrow<NoSuchFileException> { f.begin(f.root.resolve("git/owner/missing.git")) }
                Files.exists(f.target) shouldBe false
                Files.exists(f.root.resolve("archive")) shouldBe false
            }
        }

        it("never overwrites an existing target or its sentinel") {
            ArchiveFixture().use { f ->
                val source = f.source()
                Files.createDirectories(f.target)
                Files.writeString(f.target.resolve("sentinel"), "someone-else")
                shouldThrow<FileAlreadyExistsException> { f.begin(source) }
                Files.readString(f.target.resolve("sentinel")) shouldBe "someone-else"
                f.marker(source) shouldBe "original-history"
            }
        }

        it("treats unsupported legacy VCS as Git and permits the exact source path as target") {
            ArchiveFixture().use { f ->
                val repository = f.git("same")
                repository.create()
                val source = repository.getDirectory().toPath()
                f.mark(source)
                val pending = f.archive().begin(42, "legacy-other", source, source)
                pending.archiveDirectory.parent.fileName.toString().startsWith("42-git-") shouldBe true
                pending.createTarget(repository)
                Files.exists(source.resolve("marker")) shouldBe false
                pending.rollback()
                f.marker(source) shouldBe "original-history"
            }
        }

        it("uses the selected per-VCS root and normalized aliases, never a user-controlled VCS path") {
            for ((vcs, normalized) in listOf(null to "git", "SUBVERSION" to "svn", "SVN" to "svn", "MERCURIAL" to "hg", "HG" to "hg")) {
                ArchiveFixture().use { f ->
                    val privateRoot = f.root.resolve("private-$normalized")
                    f.environment.setProperty("yona.repository-archive.$normalized-dir", privateRoot.toString())
                    val source = Files.createDirectories(f.root.resolve("$normalized/owner/original"))
                    f.mark(source)
                    val pending = f.archive().begin(43, vcs, source, f.target)
                    pending.archiveDirectory.parent.parent shouldBe privateRoot
                    pending.archiveDirectory.parent.fileName.toString().startsWith("43-$normalized-") shouldBe true
                    pending.rollback()
                    f.marker(source) shouldBe "original-history"
                }
            }
        }

        it("quarantines only its reserved partial target after a real create failure, without following nested symlinks") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val realRepository = f.git("replacement")
                val outside = Files.createDirectories(f.root.resolve("unrelated"))
                Files.writeString(outside.resolve("sentinel"), "keep-me")
                val failing = object : PlayRepository by realRepository {
                    override fun create() {
                        realRepository.create()
                        Files.createSymbolicLink(realRepository.getDirectory().toPath().resolve("external"), outside)
                        throw IOException("after real partial create")
                    }
                }
                val pending = f.archive().begin(44, "git", source, realRepository.getDirectory().toPath())
                shouldThrow<IOException> { pending.createTarget(failing) }
                Files.exists(realRepository.getDirectory().toPath().resolve("HEAD")) shouldBe true
                pending.rollback()
                Files.exists(realRepository.getDirectory().toPath(), NOFOLLOW_LINKS) shouldBe false
                Files.readString(outside.resolve("sentinel")) shouldBe "keep-me"
                f.marker(source) shouldBe "original-history"
                val quarantined = pending.failedTargetDirectory!!
                Files.exists(quarantined.resolve("HEAD")) shouldBe true
                Files.isSymbolicLink(quarantined.resolve("external")) shouldBe true
                Files.readSymbolicLink(quarantined.resolve("external")) shouldBe outside
            }
        }

        it("restores after a failure before target reservation and leaves a newly colliding target untouched") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val target = f.git("replacement")
                val pending = f.archive().begin(45, "git", source, target.getDirectory().toPath())
                Files.createDirectories(target.getDirectory().toPath())
                Files.writeString(target.getDirectory().toPath().resolve("sentinel"), "racing-data")
                shouldThrow<FileAlreadyExistsException> { pending.createTarget(target) }
                pending.rollback()
                Files.readString(target.getDirectory().toPath().resolve("sentinel")) shouldBe "racing-data"
                f.marker(source) shouldBe "original-history"
            }
        }

        it("preserves the archive and substituted unowned target when cleanup cannot establish ownership") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val repository = f.git("replacement")
                val target = repository.getDirectory().toPath()
                val pending = f.archive().begin(46, "git", source, target)
                pending.createTarget(repository)
                Files.move(target, f.root.resolve("owned-target"), ATOMIC_MOVE)
                Files.createDirectory(target)
                Files.writeString(target.resolve("sentinel"), "unowned")

                shouldThrow<IllegalStateException> { pending.rollback() }.message shouldContain "operator recovery"
                f.marker(pending.archiveDirectory) shouldBe "original-history"
                Files.readString(target.resolve("sentinel")) shouldBe "unowned"
                Files.exists(source) shouldBe false
            }
        }

        it("preserves an unowned symlink substituted for the target and never traverses it") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val repository = f.git("replacement")
                val target = repository.getDirectory().toPath()
                val pending = f.archive().begin(47, "git", source, target)
                pending.createTarget(repository)
                val moved = f.root.resolve("owned-target")
                Files.move(target, moved, ATOMIC_MOVE)
                Files.createSymbolicLink(target, moved)
                shouldThrow<IllegalStateException> { pending.rollback() }
                Files.isSymbolicLink(target) shouldBe true
                Files.exists(moved.resolve("HEAD")) shouldBe true
                f.marker(pending.archiveDirectory) shouldBe "original-history"
            }
        }

        it("retains the archive when restoration collides, including an empty directory") {
            for (withSentinel in listOf(false, true)) {
                ArchiveFixture().use { f ->
                    val source = f.source()
                    val pending = f.begin(source)
                    Files.createDirectory(source)
                    if (withSentinel) Files.writeString(source.resolve("sentinel"), "new-data")
                    shouldThrow<FileAlreadyExistsException> { pending.rollback() }
                    f.marker(pending.archiveDirectory) shouldBe "original-history"
                    if (withSentinel) Files.readString(source.resolve("sentinel")) shouldBe "new-data"
                }
            }
        }

        it("retains the original archive and active target when quarantine movement is denied (requires non-root POSIX user)").config(
            enabled = System.getProperty("user.name") != "root"
        ) {
            ArchiveFixture().use { f ->
                val source = f.source()
                val repository = f.git("replacement")
                val pending = f.archive().begin(48, "git", source, repository.getDirectory().toPath())
                pending.createTarget(repository)
                val parent = repository.getDirectory().toPath().parent
                val permissions = Files.getPosixFilePermissions(parent)
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-x------"))
                try {
                    shouldThrow<IOException> { pending.rollback() }
                    f.marker(pending.archiveDirectory) shouldBe "original-history"
                    Files.exists(source) shouldBe false
                    Files.exists(repository.getDirectory().toPath().resolve("HEAD")) shouldBe true
                    pending.failedTargetDirectory shouldBe null
                } finally {
                    Files.setPosixFilePermissions(parent, permissions)
                }
                pending.rollback()
                f.marker(source) shouldBe "original-history"
                Files.exists(pending.failedTargetDirectory!!.resolve("HEAD")) shouldBe true
            }
        }

        it("moves an unreadable partial subtree intact instead of traversing or deleting it") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val repository = f.git("replacement")
                val pending = f.archive().begin(48, "git", source, repository.getDirectory().toPath())
                pending.createTarget(repository)
                val locked = Files.createDirectory(repository.getDirectory().toPath().resolve("locked"))
                Files.writeString(locked.resolve("sentinel"), "partial")
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
                try {
                    pending.rollback()
                    f.marker(source) shouldBe "original-history"
                    Files.exists(repository.getDirectory().toPath()) shouldBe false
                } finally {
                    val retained = pending.failedTargetDirectory?.resolve("locked") ?: locked
                    Files.setPosixFilePermissions(retained, PosixFilePermissions.fromString("rwx------"))
                }
                Files.readString(pending.failedTargetDirectory!!.resolve("locked/sentinel")) shouldBe "partial"
            }
        }

        it("retains both archives when restoring the original source collides after target quarantine") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val repository = f.svn("replacement")
                val pending = f.archive().begin(48, "git", source, repository.getDirectory().toPath())
                pending.createTarget(repository)
                Files.createDirectory(source)
                Files.writeString(source.resolve("sentinel"), "unowned")
                shouldThrow<FileAlreadyExistsException> { pending.rollback() }
                f.marker(pending.archiveDirectory) shouldBe "original-history"
                Files.readString(source.resolve("sentinel")) shouldBe "unowned"
                Files.exists(pending.failedTargetDirectory!!.resolve("format")) shouldBe true
                Files.exists(repository.getDirectory().toPath()) shouldBe false
            }
        }

        it("uses the target VCS private root for quarantine and validates its permissions before moving source") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val quarantineRoot = f.root.resolve("svn-private")
                f.environment.setProperty("yona.repository-archive.svn-dir", quarantineRoot.toString())
                val repository = f.svn("replacement")
                val pending = f.archive().begin(48, "git", source, repository.getDirectory().toPath())
                pending.createTarget(repository)
                pending.rollback()
                pending.failedTargetDirectory!!.parent.parent shouldBe quarantineRoot
                f.marker(source) shouldBe "original-history"
            }
            ArchiveFixture().use { f ->
                val source = f.source()
                val quarantineRoot = Files.createDirectory(f.root.resolve("svn-private"))
                Files.setPosixFilePermissions(quarantineRoot, PosixFilePermissions.fromString("rwxr-xr-x"))
                f.environment.setProperty("yona.repository-archive.svn-dir", quarantineRoot.toString())
                shouldThrow<IllegalStateException> { f.begin(source) }
                f.marker(source) shouldBe "original-history"
                Files.exists(f.target) shouldBe false
            }
        }

        it("rejects overlapping repository, LFS, upload, static and servlet roots, including absent descendants") {
            for (publicRoot in listOf("git", "svn", "hg", "lfs", "upload", "static", "servlet")) {
                ArchiveFixture().use { f ->
                    val source = f.source()
                    f.environment.setProperty("spring.web.resources.static-locations[0]", f.root.resolve("static").toUri().toString())
                    f.environment.setProperty("yona.repository-archive.base-dir", f.root.resolve("$publicRoot/missing/private").toString())
                    shouldThrow<IllegalStateException> { f.begin(source) }.message shouldContain "resource-serving root"
                    f.marker(source) shouldBe "original-history"
                    Files.exists(f.root.resolve("$publicRoot/missing")) shouldBe false
                }
            }
            ArchiveFixture().use { f ->
                val source = f.source()
                f.environment.setProperty("yona.repository-archive.base-dir", f.root.toString())
                shouldThrow<IllegalStateException> { f.begin(source) }
                f.marker(source) shouldBe "original-history"
            }
        }

        it("rejects archive roots reached through a serving-root symlink and serving roots aliasing the archive") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val alias = f.root.resolve("alias")
                Files.createSymbolicLink(alias, f.root.resolve("git"))
                f.environment.setProperty("yona.repository-archive.base-dir", alias.resolve("missing/archive").toString())
                shouldThrow<IllegalStateException> { f.begin(source) }
                f.marker(source) shouldBe "original-history"
                Files.exists(f.root.resolve("git/missing")) shouldBe false
            }
            ArchiveFixture().use { f ->
                val source = f.source()
                val privateRoot = Files.createDirectory(f.root.resolve("archive"))
                Files.createSymbolicLink(f.root.resolve("upload-alias"), privateRoot)
                f.environment.setProperty("yona.upload.base-dir", f.root.resolve("upload-alias").toString())
                shouldThrow<IllegalStateException> { f.begin(source) }
                f.marker(source) shouldBe "original-history"
            }
        }

        it("rejects dangling symlink roots, source symlinks and target symlinks without changing their referents") {
            ArchiveFixture().use { f ->
                val source = f.source()
                Files.createSymbolicLink(f.root.resolve("archive"), f.root.resolve("missing-private"))
                shouldThrow<NoSuchFileException> { f.begin(source) }
                Files.isSymbolicLink(f.root.resolve("archive")) shouldBe true
                f.marker(source) shouldBe "original-history"
            }
            ArchiveFixture().use { f ->
                val source = f.source()
                val alias = source.resolveSibling("alias")
                Files.createSymbolicLink(alias, source)
                shouldThrow<IllegalStateException> { f.begin(alias) }
                Files.createDirectories(f.target.parent)
                Files.createSymbolicLink(f.target, source)
                shouldThrow<IllegalStateException> { f.begin(source) }
                f.marker(source) shouldBe "original-history"
                Files.isSymbolicLink(f.target) shouldBe true
            }
        }

        it("includes classpath static even when configured locations override defaults and static does not exist yet") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val classpath = Files.createDirectory(f.root.resolve("classpath"))
                URLClassLoader(arrayOf(classpath.toUri().toURL()), javaClass.classLoader).use { loader ->
                    f.environment.setProperty("spring.web.resources.static-locations", f.root.resolve("other-static").toUri().toString())
                    f.environment.setProperty("yona.repository-archive.base-dir", classpath.resolve("static/archive").toString())
                    shouldThrow<IllegalStateException> {
                        f.archive(DefaultResourceLoader(loader)).begin(49, "GIT", source, f.target)
                    }
                    Files.exists(classpath.resolve("static")) shouldBe false
                    f.marker(source) shouldBe "original-history"
                }
            }
        }

        it("creates private roots and operations with 0700 without changing serving directory permissions") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val before = Files.getPosixFilePermissions(source.parent)
                val pending = f.begin(source)
                Files.getPosixFilePermissions(pending.archiveDirectory.parent) shouldBe PosixFilePermissions.fromString("rwx------")
                Files.getPosixFilePermissions(pending.archiveDirectory.parent.parent) shouldBe PosixFilePermissions.fromString("rwx------")
                Files.getPosixFilePermissions(source.parent) shouldBe before
                pending.rollback()
            }
        }

        it("refuses permissive and non-writable existing archive roots instead of chmodding them") {
            for (mode in listOf("rwxr-xr-x", "r-x------")) {
                ArchiveFixture().use { f ->
                    val source = f.source()
                    val archive = Files.createDirectory(f.root.resolve("archive"))
                    val permissions = PosixFilePermissions.fromString(mode)
                    Files.setPosixFilePermissions(archive, permissions)
                    try {
                        shouldThrow<IllegalStateException> { f.begin(source) }.message shouldContain "0700"
                        Files.getPosixFilePermissions(archive) shouldBe permissions
                        f.marker(source) shouldBe "original-history"
                    } finally {
                        Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rwx------"))
                    }
                }
            }
        }

        it("leaves the source intact when its parent disallows atomic movement (requires non-root POSIX user)").config(
            enabled = System.getProperty("user.name") != "root"
        ) {
            ArchiveFixture().use { f ->
                val source = f.source()
                val permissions = Files.getPosixFilePermissions(source.parent)
                Files.setPosixFilePermissions(source.parent, PosixFilePermissions.fromString("r-x------"))
                try {
                    shouldThrow<IOException> { f.begin(source) }
                    f.marker(source) shouldBe "original-history"
                    Files.exists(f.target) shouldBe false
                    Files.newDirectoryStream(f.root.resolve("archive")).use { it.iterator().hasNext() shouldBe false }
                } finally {
                    Files.setPosixFilePermissions(source.parent, permissions)
                }
            }
        }

        it("preserves distinct archives for successive resets of the same project") {
            ArchiveFixture().use { f ->
                val source = f.source()
                val first = f.begin(source)
                Files.createDirectory(source)
                Files.writeString(source.resolve("marker"), "second-history")
                val second = f.begin(source)
                first.archiveDirectory shouldNotBe second.archiveDirectory
                f.marker(first.archiveDirectory) shouldBe "original-history"
                f.marker(second.archiveDirectory) shouldBe "second-history"
            }
        }

        // Supply a writable POSIX directory on a DIFFERENT mounted filesystem. Missing fixture is skipped, not a passing atomic-move test.
        val crossFilesystem = System.getenv("YONA_ARCHIVE_CROSS_FILESYSTEM_DIR")?.takeIf(String::isNotBlank)
        it("rejects atomic cross-filesystem movement without losing source (requires YONA_ARCHIVE_CROSS_FILESYSTEM_DIR)").config(
            enabled = crossFilesystem != null
        ) {
            ArchiveFixture().use { f ->
                val source = f.source()
                val external = Files.createTempDirectory(Path.of(crossFilesystem!!), "yona-archive-cross-device-").toRealPath()
                try {
                    Files.getFileStore(source) shouldNotBe Files.getFileStore(external)
                    f.environment.setProperty("yona.repository-archive.base-dir", external.toString())
                    shouldThrow<AtomicMoveNotSupportedException> { f.begin(source) }
                    f.marker(source) shouldBe "original-history"
                    Files.exists(f.target) shouldBe false
                    Files.newDirectoryStream(external).use { it.iterator().hasNext() shouldBe false }
                } finally {
                    deleteFixture(external)
                }
            }
        }

        it("rejects a target quarantine root on another filesystem before archiving source (requires YONA_ARCHIVE_CROSS_FILESYSTEM_DIR)").config(
            enabled = crossFilesystem != null
        ) {
            ArchiveFixture().use { f ->
                val source = f.source()
                val external = Files.createTempDirectory(Path.of(crossFilesystem!!), "yona-quarantine-cross-device-").toRealPath()
                try {
                    Files.getFileStore(source) shouldNotBe Files.getFileStore(external)
                    f.environment.setProperty("yona.repository-archive.svn-dir", external.toString())
                    shouldThrow<AtomicMoveNotSupportedException> { f.begin(source) }
                    f.marker(source) shouldBe "original-history"
                    Files.exists(f.target) shouldBe false
                    Files.newDirectoryStream(f.root.resolve("archive")).use { it.iterator().hasNext() shouldBe false }
                    Files.newDirectoryStream(external).use { it.iterator().hasNext() shouldBe false }
                } finally {
                    deleteFixture(external)
                }
            }
        }

        it("restores across separate VCS mounts using a private quarantine on the target filesystem (requires YONA_ARCHIVE_CROSS_FILESYSTEM_DIR)").config(
            enabled = crossFilesystem != null
        ) {
            ArchiveFixture().use { f ->
                val source = f.source()
                val external = Files.createTempDirectory(Path.of(crossFilesystem!!), "yona-target-mount-").toRealPath()
                try {
                    Files.getFileStore(source) shouldNotBe Files.getFileStore(external)
                    val svnRoot = external.resolve("svn")
                    val quarantineRoot = external.resolve("private")
                    f.environment.setProperty("yona.svn.base-dir", svnRoot.toString())
                    f.environment.setProperty("yona.repository-archive.svn-dir", quarantineRoot.toString())
                    val repository = SvnRepository("owner", "replacement", svnRoot.toString(), { _ -> null })
                    val pending = f.archive().begin(50, "git", source, repository.getDirectory().toPath())
                    pending.createTarget(repository)
                    pending.rollback()
                    f.marker(source) shouldBe "original-history"
                    Files.exists(repository.getDirectory().toPath()) shouldBe false
                    pending.failedTargetDirectory!!.parent.parent shouldBe quarantineRoot
                    Files.exists(pending.failedTargetDirectory!!.resolve("format")) shouldBe true
                } finally {
                    deleteFixture(external)
                }
            }
        }
    }
})

private class ArchiveFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("yona-repository-archive-").toRealPath()
    val environment = MockEnvironment()
        .withProperty("yona.repository-archive.single-serving-process", "true")
        .withProperty("yona.repository-archive.base-dir", root.resolve("archive").toString())
    val target: Path = root.resolve("svn/owner/replacement")

    init {
        for (vcs in listOf("git", "svn", "hg", "lfs", "upload")) {
            environment.setProperty("yona.$vcs.base-dir", root.resolve(vcs).toString())
        }
    }

    fun archive(loader: ResourceLoader = DefaultResourceLoader()): RepositoryArchive {
        val servletRoot = root.resolve("servlet")
        val context = object : MockServletContext() {
            override fun getRealPath(path: String): String = servletRoot.resolve(path.removePrefix("/")).toString()
            override fun getResource(path: String): URL = servletRoot.resolve(path.removePrefix("/")).toUri().toURL()
        }
        return RepositoryArchive(environment, loader, context)
    }

    fun git(name: String) = GitRepository("owner", name, root.resolve("git").toString(), { _, _ -> null })
    fun svn(name: String) = SvnRepository("owner", name, root.resolve("svn").toString(), { _ -> null })
    fun source(): Path = Files.createDirectories(root.resolve("git/owner/original.git")).also(::mark)
    fun mark(directory: Path) { Files.writeString(directory.resolve("marker"), "original-history") }
    fun marker(directory: Path): String = Files.readString(directory.resolve("marker"))
    fun begin(source: Path): PendingRepositoryReset = archive().begin(41, "GIT", source, target)
    override fun close() = deleteFixture(root)
}

private fun deleteFixture(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
            if (failure != null) throw failure
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}
