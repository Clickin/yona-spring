package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.security.access.AccessDeniedException
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import java.nio.file.Files

class RepositoryMirrorStorageSpec : DescribeSpec({
    describe("new mirror destination ownership") {
        it("reserves an empty SVN-compatible directory and verifies external immutable ownership") {
            val base = Files.createTempDirectory("mirror-storage-").toRealPath()
            try {
                val guard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
                val storage = RepositoryMirrorStorage(base.toString(), base.resolve("_git").toString(), base.resolve("_hg").toString(), guard)
                val project = Project(id = 71L, owner = "admin", name = "mirror")
                val mirror = RepositoryMirror(project = project, generation = 1)
                val directory = storage.reserve(project, 1)
                directory.list()!!.toList() shouldBe emptyList()
                Files.readString(guard.markerPath("admin", "mirror")) shouldBe "yona-svn-mirror:71:1\n${directory.toPath()}\n"
                SVNRepositoryFactory.createLocalRepository(directory, true, false)
                storage.directory(mirror) shouldBe directory
                shouldThrow<MirrorFailure> { storage.reserve(project, 1) }.attention shouldBe true
                directory.resolve("format").exists() shouldBe true
                mirror.generation = 2
                shouldThrow<MirrorFailure> { storage.directory(mirror) }.attention shouldBe true
                mirror.generation = 1
                project.name = "elsewhere"
                shouldThrow<MirrorFailure> { storage.directory(mirror) }
            } finally { base.toFile().deleteRecursively() }
        }

        it("refuses existing destinations without changing their bytes") {
            val base = Files.createTempDirectory("mirror-existing-").toRealPath()
            try {
                val target = Files.createDirectories(base.resolve("admin/existing"))
                Files.writeString(target.resolve("precious"), "keep")
                val guard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
                val storage = RepositoryMirrorStorage(base.toString(), base.resolve("_git").toString(), base.resolve("_hg").toString(), guard)
                shouldThrow<MirrorFailure> { storage.reserve(Project(id = 72L, owner = "admin", name = "existing"), 1) }
                Files.readString(target.resolve("precious")) shouldBe "keep"
                Files.exists(guard.markerPath("admin", "existing")) shouldBe false
            } finally { base.toFile().deleteRecursively() }
        }

        it("refuses precloned Git and Mercurial destinations before publishing a marker") {
            val base = Files.createTempDirectory("mirror-cross-vcs-").toRealPath()
            try {
                val git = base.resolve("_git")
                val hg = base.resolve("_hg")
                val guard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
                val storage = RepositoryMirrorStorage(base.toString(), git.toString(), hg.toString(), guard)
                for ((name, target) in listOf("imported-git" to git.resolve("admin/imported-git.git"), "imported-hg" to hg.resolve("admin/imported-hg"))) {
                    Files.createDirectories(target)
                    Files.writeString(target.resolve("precious"), "import awaiting project save")
                    shouldThrow<MirrorFailure> { storage.reserve(Project(id = 75L, owner = "admin", name = name), 1) }
                    Files.readString(target.resolve("precious")) shouldBe "import awaiting project save"
                    Files.exists(guard.markerPath("admin", name)) shouldBe false
                    Files.exists(base.resolve("admin/$name")) shouldBe false
                    guard.requireUnreserved("admin", name)
                }
            } finally { base.toFile().deleteRecursively() }
        }

        it("retains a stale name reservation even when its old target and project identity are gone") {
            val base = Files.createTempDirectory("mirror-stale-").toRealPath()
            try {
                val guard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
                val storage = RepositoryMirrorStorage(base.toString(), base.resolve("_git").toString(), base.resolve("_hg").toString(), guard)
                val original = Project(id = 76L, owner = "admin", name = "orphan")
                val directory = storage.reserve(original, 1).toPath()
                val marker = guard.markerPath("admin", "orphan")
                val ownership = Files.readString(marker)
                Files.delete(directory)
                val replacement = Project(id = 77L, owner = "admin", name = "orphan")
                shouldThrow<MirrorFailure> { storage.reserve(replacement, 1) }.attention shouldBe true
                Files.exists(directory) shouldBe false
                Files.readString(marker) shouldBe ownership
                shouldThrow<AccessDeniedException> { guard.requireUnreserved("admin", "orphan") }
                shouldThrow<AccessDeniedException> { guard.requireUnreserved("ADMIN", "ORPHAN") }
                shouldThrow<MirrorFailure> { storage.directory(RepositoryMirror(project = original)) }
                Files.writeString(marker, "interrupted marker")
                shouldThrow<MirrorFailure> { storage.reserve(replacement, 1) }
                Files.readString(marker) shouldBe "interrupted marker"
                Files.exists(directory) shouldBe false
                shouldThrow<AccessDeniedException> { guard.requireUnreserved("admin", "orphan") }
            } finally { base.toFile().deleteRecursively() }
        }

        it("rejects traversal and symlink owners targets markers and SVN metadata") {
            val base = Files.createTempDirectory("mirror-links-").toRealPath()
            val elsewhere = Files.createTempDirectory("mirror-outside-").toRealPath()
            try {
                val guard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
                val storage = RepositoryMirrorStorage(base.toString(), base.resolve("_git").toString(), base.resolve("_hg").toString(), guard)
                shouldThrow<MirrorFailure> { storage.reserve(Project(id = 73L, owner = "..", name = "bad"), 1) }
                Files.createSymbolicLink(base.resolve("linked"), elsewhere)
                shouldThrow<MirrorFailure> { storage.reserve(Project(id = 73L, owner = "linked", name = "bad"), 1) }
                val project = Project(id = 74L, owner = "admin", name = "valid")
                val directory = storage.reserve(project, 1).toPath()
                Files.createSymbolicLink(directory.resolve("db"), elsewhere)
                shouldThrow<MirrorFailure> { storage.directory(RepositoryMirror(project = project)) }
                Files.delete(directory.resolve("db"))
                val marker = guard.markerPath("admin", "valid")
                Files.copy(marker, elsewhere.resolve("owner"))
                Files.delete(marker)
                Files.createSymbolicLink(marker, elsewhere.resolve("owner"))
                shouldThrow<MirrorFailure> { storage.directory(RepositoryMirror(project = project)) }
                Files.delete(marker)
                Files.delete(directory)
                Files.createSymbolicLink(directory, elsewhere)
                shouldThrow<MirrorFailure> { storage.directory(RepositoryMirror(project = project)) }
            } finally {
                base.toFile().deleteRecursively()
                elsewhere.toFile().deleteRecursively()
            }
        }
    }
})
