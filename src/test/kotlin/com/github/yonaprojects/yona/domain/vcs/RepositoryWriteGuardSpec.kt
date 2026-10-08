package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.security.access.AccessDeniedException
import tools.jackson.databind.ObjectMapper

class RepositoryWriteGuardSpec : DescribeSpec({
    val projects = mockk<ProjectRepository>()
    val guard = RepositoryWriteGuard(projects)
    val project = Project(id = 42L, owner = "owner", name = "mirror", repositoryMode = RepositoryMode.HOSTED)
    val user = User(id = 1L, loginId = "owner", name = "Owner", email = "owner@example.test", state = UserState.SITE_ADMIN)

    beforeTest {
        every { projects.findRepositoryModeById(42L) } returns RepositoryMode.MIRROR
        every { projects.findRepositoryModeByOwnerAndName("owner", "mirror") } returns RepositoryMode.MIRROR
    }

    it("uses the fresh scalar mode rather than a stale Project and has no feature-switch or admin bypass") {
        project.repositoryMode shouldBe RepositoryMode.HOSTED
        guard.isWritable(project) shouldBe false
        shouldThrow<AccessDeniedException> { guard.requireWritable(project) }
        every { projects.findRepositoryModeById(42L) } returns RepositoryMode.HOSTED
        guard.isWritable(project) shouldBe true
        guard.requireWritable(project)
        every { projects.findRepositoryModeById(42L) } returns null
        shouldThrow<AccessDeniedException> { guard.requireWritable(project) }
    }

    it("keeps normal unsaved hosted creation and rejects an unsaved mirror in normal writers") {
        guard.requireWritable(Project())
        shouldThrow<AccessDeniedException> { guard.requireWritable(Project(repositoryMode = RepositoryMode.MIRROR)) }
    }

    it("does not extend the existing Project JSON representation") {
        ObjectMapper().readTree(ObjectMapper().writeValueAsString(project)).has("repositoryMode") shouldBe false
    }

    it("denies every direct Git Hg and SVN mutation before creating or changing files") {
        val root = tempdir()
        val repositories = listOf<PlayRepository>(
            GitRepository(guard, "owner", "mirror", root.path, { _, _ -> null }),
            HgRepository(guard, "owner", "mirror", root.path, { _, _ -> null }),
            SvnRepository(guard, "owner", "mirror", root.path) { null }
        )
        repositories.forEach { repository ->
            val mutations: List<() -> Unit> = listOf(
                { repository.create() }, { repository.delete() },
                { repository.renameTo("renamed"); Unit },
                { repository.move("owner", "mirror", "other", "renamed"); Unit },
                { repository.setDefaultBranch("main") },
                { repository.createBranch("branch", "HEAD") }, { repository.deleteBranch("branch") },
                { repository.createTag("tag", "HEAD", null, null, null) }, { repository.deleteTag("tag") }
            )
            mutations.forEach { mutate -> shouldThrow<AccessDeniedException> { mutate() } }
            repository.getDirectory().exists() shouldBe false
        }
        shouldThrow<AccessDeniedException> {
            repositories[1].commitTextFile("default", null, "README.md", "text", "message", "Owner", "owner@example.test")
        }
        shouldThrow<AccessDeniedException> { BareCommit(project, user, root.path, guard) }
        root.listFiles()!!.isEmpty() shouldBe true
    }

    it("rechecks BareCommit mode at the actual mutation after construction") {
        val root = tempdir()
        every { projects.findRepositoryModeByOwnerAndName("owner", "mirror") } returns RepositoryMode.HOSTED
        every { projects.findRepositoryModeById(42L) } returns RepositoryMode.HOSTED
        GitRepository(guard, "owner", "mirror", root.path, { _, _ -> null }).create()
        val bare = BareCommit(project, user, root.path, guard)
        every { projects.findRepositoryModeById(42L) } returns RepositoryMode.MIRROR
        shouldThrow<AccessDeniedException> { bare.commitTextFile("README.md", "text", "message") }
        shouldThrow<AccessDeniedException> { bare.commitTextFile("main", "nested/file", "text", "message") }
        shouldThrow<AccessDeniedException> { bare.commitPage("main", null, "Home.md", "text", "message") }
        shouldThrow<AccessDeniedException> { bare.deletePage("main", "Home.md", "message") }
    }

    it("keeps a mirror's separate wiki writable with real guards") {
        val root = tempdir()
        val wiki = GitRepository(guard, "owner", "mirror.wiki", root.path, { _, _ -> null }, repositoryKind = RepositoryKind.WIKI)
        wiki.create()
        val bare = BareCommit(project, user, root.path, guard, repoNameOverride = "mirror.wiki")
        bare.setRefName("refs/heads/main")
        bare.commitPage("main", null, "Home.md", "wiki text", "create wiki") shouldNotBe null
        wiki.getRawFile("main", "Home.md").toString(Charsets.UTF_8) shouldBe "wiki text"
        shouldThrow<IllegalArgumentException> {
            BareCommit(project, user, root.path, guard, repoNameOverride = "other")
        }
        shouldThrow<IllegalArgumentException> {
            GitRepository(guard, "owner", "mirror", root.path, { _, _ -> null }, repositoryKind = RepositoryKind.WIKI)
        }
    }

    it("guards standalone clone create and delete utilities without an authorization caller") {
        val root = tempdir()
        val service = com.github.yonaprojects.yona.domain.project.GitServiceImpl(root.path, guard, mockk<RepositoryNamespaceGuard>(relaxed = true))
        val directory = service.getRepositoryPath("owner", "mirror")
        directory.mkdirs()
        val sentinel = java.io.File(directory, "preserve").apply { writeText("keep") }
        shouldThrow<AccessDeniedException> { service.createRepository("owner", "mirror") }
        shouldThrow<AccessDeniedException> { service.deleteRepository("owner", "mirror") }
        shouldThrow<AccessDeniedException> { service.cloneRepository("file:///not-contacted", "owner", "mirror", null, null) }
        sentinel.readText() shouldBe "keep"
    }

    it("does not allow a hosted writer to move into a persisted mirror destination") {
        val root = tempdir()
        every { projects.findRepositoryModeByOwnerAndName("owner", "hosted") } returns RepositoryMode.HOSTED
        val repository = SvnRepository(guard, "owner", "hosted", root.path) { null }
        shouldThrow<AccessDeniedException> { repository.move("owner", "hosted", "owner", "mirror") }
        root.listFiles()!!.isEmpty() shouldBe true
    }
})
