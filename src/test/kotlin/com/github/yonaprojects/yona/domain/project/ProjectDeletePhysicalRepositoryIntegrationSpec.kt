package com.github.yonaprojects.yona.domain.project

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryNamespaceGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import java.io.File
import java.nio.file.Files
import java.util.UUID

// TASK-0421(P3-02 11라운드, 버그9) — 실서버(H2 프로파일) + 실제 yona-cli 바이너리로 `project delete`
// → 같은 owner/name으로 재생성/재-fork를 반복 검증하던 중 발견. ProjectServiceImpl.deleteProject()가
// DB의 Project 행은 지우지만 {yona.git.base-dir}/{owner}/{project}.git 물리 bare 저장소 디렉터리는
// 파일시스템에 그대로 남겨, 이후 같은 owner/name으로 새 프로젝트를 만들면(createProject의
// repositoryService.getRepository(project).create() -> GitRepository.create()가 이미 존재하는
// 디렉터리와 충돌) FileAlreadyExistsException이 그대로 500으로 튄다(실측 확인). changeVCS()가 이미
// 쓰고 있는 "getRepository(project).delete() 후 재생성" 패턴을 deleteProject()에도 적용해 고쳤다 —
// 이 스펙은 mockk가 아닌 실제 RepositoryService/GitRepository + 실제 파일시스템으로 그 수정을
// 고정한다.
class ProjectDeletePhysicalRepositoryIntegrationSpec @Autowired constructor(
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val projectService: ProjectService,
    private val gitService: GitService,
    private val namespaceGuard: RepositoryNamespaceGuard,
    private val projectTransferRepository: ProjectTransferRepository,
    @Value("\${yona.svn.base-dir:/tmp/yona/svn}") private val svnBaseDir: String
) : AbstractIntegrationTest() {

    override fun extensions() = listOf(SpringExtension)

    private val ownerName = "del-phys-owner"
    private val projName = "del-phys-repo"
    // ProjectServiceImplSpec.kt의 물리 저장소 테스트들과 동일하게 @Value 기본값(설정 오버라이드가
    // 없는 test 프로파일)을 그대로 가정한다.
    private val gitDir = File("/tmp/yona/git/$ownerName/$projName.git")

    init {
        beforeTest {
            projectRepository.findByOwnerAndName(ownerName, projName).ifPresent { projectRepository.delete(it) }
            userRepository.findByLoginId(ownerName).ifPresent { userRepository.delete(it) }
            gitDir.deleteRecursively()
        }

        describe("ProjectServiceImpl.deleteProject — 물리 git 저장소 정리") {
            it("프로젝트를 삭제하면 물리 bare 저장소 디렉터리도 함께 삭제되고, 같은 owner/name으로 재생성해도 500이 나지 않아야 한다") {
                val creator = userRepository.save(User(loginId = ownerName, name = "물리삭제소유자", email = "$ownerName@example.com"))

                val created = projectService.createProject(
                    Project(owner = ownerName, name = projName, vcs = "GIT"),
                    creator
                )
                gitDir.exists() shouldBe true

                projectService.deleteProject(created.id!!)
                gitDir.exists() shouldBe false

                // RED였을 때는 FileAlreadyExistsException(500)이 여기서 발생했다.
                val recreated = projectService.createProject(
                    Project(owner = ownerName, name = projName, vcs = "GIT"),
                    creator
                )
                recreated.owner shouldBe ownerName
                recreated.name shouldBe projName
                gitDir.exists() shouldBe true

                projectService.deleteProject(recreated.id!!)
            }
        }

        describe("hosted namespace reservation") {
            it("keeps a pending transfer unchanged when a mirror takes its destination before acceptance") {
                val suffix = UUID.randomUUID().toString().take(8)
                val sender = userRepository.save(User(loginId = "pending-source-$suffix", name = "sender", email = "sender-$suffix@example.com"))
                val acceptor = userRepository.save(User(loginId = "pending-target-$suffix", name = "acceptor", email = "acceptor-$suffix@example.com"))
                val source = projectService.createProject(Project(owner = sender.loginId, name = "repo", vcs = "GIT"), sender)
                val transfer = projectService.requestNewTransfer(source.id!!, sender.id!!, acceptor.loginId)
                val mirror = projectRepository.save(
                    Project(owner = acceptor.loginId, name = transfer.newProjectName, vcs = "SUBVERSION", repositoryMode = RepositoryMode.MIRROR)
                )
                val sourceDir = gitService.getRepositoryPath(sender.loginId, source.name)
                val sourceFile = File(sourceDir, "sentinel").apply { writeText("hosted source") }
                val targetDir = File(svnBaseDir, "${acceptor.loginId}/${transfer.newProjectName}").apply { mkdirs() }
                val targetFile = File(targetDir, "sentinel").apply { writeText("mirror target") }
                try {
                    shouldThrow<IllegalArgumentException> {
                        projectService.acceptTransfer(transfer.id!!, transfer.confirmKey, acceptor.id!!)
                    }

                    val unchanged = projectRepository.findById(source.id!!).get()
                    unchanged.owner shouldBe sender.loginId
                    unchanged.name shouldBe "repo"
                    unchanged.previousName shouldBe null
                    unchanged.previousOwnerLoginId shouldBe null
                    unchanged.previousNameChangedTime shouldBe null
                    projectTransferRepository.findById(transfer.id!!).get().accepted shouldBe false
                    projectRepository.findAll().count { it.owner == acceptor.loginId && it.name == transfer.newProjectName } shouldBe 1
                    sourceFile.readText() shouldBe "hosted source"
                    targetFile.readText() shouldBe "mirror target"
                    gitService.getRepositoryPath(acceptor.loginId, transfer.newProjectName).exists() shouldBe false
                } finally {
                    projectService.deleteProject(source.id!!)
                    projectRepository.delete(mirror)
                    targetDir.deleteRecursively()
                    userRepository.delete(sender)
                    userRepository.delete(acceptor)
                }
            }

            it("refuses an orphan mirror marker without creating a hosted row or directory") {
                val owner = "orphan-${UUID.randomUUID()}"
                val name = "reserved"
                val creator = userRepository.save(User(loginId = owner, name = "orphan", email = "$owner@example.com"))
                val marker = namespaceGuard.markerPath(owner, name)
                Files.createDirectories(marker.parent)
                Files.writeString(marker, "orphan reservation")
                try {
                    shouldThrow<AccessDeniedException> {
                        projectService.createProject(Project(owner = owner, name = name, vcs = "GIT"), creator)
                    }
                    projectRepository.findByOwnerAndName(owner, name).isPresent shouldBe false
                    gitService.getRepositoryPath(owner, name).exists() shouldBe false
                    Files.readString(marker) shouldBe "orphan reservation"
                } finally {
                    Files.deleteIfExists(marker)
                    projectRepository.findByOwnerAndName(owner, name).ifPresent { projectService.deleteProject(it.id!!) }
                    userRepository.delete(creator)
                }
            }

            it("reuses the repository published by a legitimate import clone") {
                val owner = "import-${UUID.randomUUID()}"
                val creator = userRepository.save(User(loginId = owner, name = "import", email = "$owner@example.com"))
                try {
                    val remote = gitService.createRepository(owner, "remote")
                    val cloned = gitService.cloneRepository(remote.path, owner, "imported", null, null)
                    val sentinel = File(cloned, "sentinel").apply { writeText("imported data") }

                    val created = projectService.createProject(Project(owner = owner, name = "imported", vcs = "GIT"), creator)

                    projectRepository.findByOwnerAndName(owner, "imported").get().id shouldBe created.id
                    sentinel.readText() shouldBe "imported data"
                    File(cloned, "HEAD").exists() shouldBe true
                } finally {
                    projectRepository.findByOwnerAndName(owner, "imported").ifPresent { projectService.deleteProject(it.id!!) }
                    gitService.deleteRepository(owner, "imported")
                    gitService.deleteRepository(owner, "remote")
                    userRepository.delete(creator)
                }
            }
        }
    }
}
