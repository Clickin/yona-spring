package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.domain.attachment.Attachment
import com.github.yonaprojects.yona.domain.attachment.AttachmentRepository
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.instanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files
import javax.sql.DataSource

/**
 * 프로젝트 단위 export → 기존 사이트로 merge(본사 복귀 시나리오) — H2 실DB 검증.
 *
 * - export는 프로젝트 행에서 FK로 도달한 폐쇄곡선(사용자, 이슈, 첨부)과 첨부 파일을 담는다
 * - merge는 PK를 대상 사이트에서 새로 채번해 재매핑한다(yona-export의 "old id는 import 때
 *   사용안함" 관례). n4user는 login_id로 기존 사용자와 매칭해 재사용하고, attachment의
 *   container_id는 재매핑된 이슈 id를 따라간다.
 * - 같은 이름의 프로젝트가 이미 있으면 거부하지 않고 yona-export처럼 별도 이름으로 들어간다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataBackupProjectMergeH2Spec @Autowired constructor(
    private val dataBackupService: DataBackupService,
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val issueRepository: IssueRepository,
    private val attachmentRepository: AttachmentRepository,
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
) : DescribeSpec() {

    override fun extensions() = listOf(SpringExtension)

    companion object {
        private val appData = Files.createTempDirectory("yona-merge-data").toFile()
        private val uploadsDir = Files.createTempDirectory("yona-merge-uploads").toFile()
        private val gitDir = Files.createTempDirectory("yona-merge-git").toFile()
        private val lfsDir = Files.createTempDirectory("yona-merge-lfs").toFile()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:h2:mem:yona-merge-it-${System.nanoTime()};DB_CLOSE_DELAY=-1" }
            registry.add("spring.datasource.username") { "sa" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.datasource.driver-class-name") { "org.h2.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.H2Dialect" }
            registry.add("yona.upload.base-dir") { uploadsDir.absolutePath }
            registry.add("yona.git.base-dir") { gitDir.absolutePath }
            registry.add("yona.lfs.base-dir") { lfsDir.absolutePath }
            registry.add("yona.data") { appData.absolutePath }
            registry.add("yona.queue.data-dir") { java.io.File(appData, "queue").absolutePath }
            registry.add("yona.svn.base-dir") { java.io.File(appData, "svn").absolutePath }
        }
    }

    private val jdbc: JdbcTemplate by lazy { JdbcTemplate(dataSource) }

    init {
        val author = userRepository.save(
            User(
                loginId = "merge-author", name = "병합작성자", email = "merge-author@example.com",
                state = UserState.SITE_ADMIN, password = "secret-password-hash", passwordSalt = "secret-salt",
                token = "secret-legacy-token", rememberMe = true, failedLoginAttempts = 7,
                isTwoFactorEnabled = true,
            )
        )
        val project = projectRepository.save(
            Project(name = "merge-field-site", owner = "merge-author", projectScope = ProjectScope.PUBLIC)
        )
        val issue = issueRepository.save(
            Issue(title = "현장에서 발견한 결함", authorId = author.id, authorLoginId = author.loginId,
                authorName = author.name, project = project, number = 1)
        )
        val content = "attachment-bytes-${System.nanoTime()}".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        File(uploadsDir, hash).writeBytes(content)
        attachmentRepository.save(
            Attachment(name = "report.bin", hash = hash, containerType = ResourceType.ISSUE_POST,
                containerId = issue.id.toString(), mimeType = "application/octet-stream",
                size = content.size.toLong(), ownerLoginId = author.loginId)
        )
        // 프로젝트 git 저장소 흉내 — 아카이브에 files/git/merge-author/merge-field-site.git 이 담기고,
        // merge 목적지 매핑(원본 이름 vs 실효 이름)을 마커 파일로 검증한다.
        File(gitDir, "merge-author/merge-field-site.git").mkdirs()
        File(gitDir, "merge-author/merge-field-site.git/MARKER").writeText("field-repo")

        describe("프로젝트 export") {
            it("FK 폐쇄곡선(사용자·이슈·첨부)과 첨부 파일을 하나의 ZIP에 담는다") {
                val archive = DataBackupArchiveTestSupport.exportProjectToBytes(
                    dataBackupService, "merge-author", "merge-field-site"
                )
                val tables = DataBackupArchiveTestSupport.readTables(objectMapper, archive)

                tables.getValue("project").single().value("name") shouldBe "merge-field-site"
                tables.getValue("issue").single().value("title") shouldBe "현장에서 발견한 결함"
                tables.getValue("attachment").single().value("hash") shouldBe hash
                tables.getValue("n4user").map { it.value("login_id") }.contains("merge-author") shouldBe true
                val archivedUser = tables.getValue("n4user").single()
                archivedUser.value("state") shouldBe "ACTIVE"
                archivedUser.keys.none { it.lowercase() in setOf(
                    "password", "password_salt", "token", "is_two_factor_enabled", "locked_until", "totp_secret",
                ) } shouldBe true
                DataBackupArchiveTestSupport.readEntryNames(archive)
                    .contains("files/uploads/$hash") shouldBe true
            }

            it("공유 멤버가 있어도 다른 프로젝트와 사용자 보안 행은 아카이브에 새지 않는다") {
                // 같은 작성자가 소유한 두 번째 프로젝트 — project_user → n4user → project_user →
                // project B 경로와 사용자 보안 행(n4user 터미널 처리 전에는 통째로 따라 들어왔다)을 확인한다.
                val projectB = projectRepository.save(
                    Project(name = "merge-shared-b", owner = "merge-author", projectScope = ProjectScope.PUBLIC)
                )
                issueRepository.save(
                    Issue(title = "B의 이슈", authorId = author.id, authorLoginId = author.loginId,
                        project = projectB, number = 1)
                )
                // 프로젝트 생성 플로우가 만드는 멤버십/역할 행을 직접 구성한다(두 프로젝트가 멤버를 공유).
                // Role의 PK는 할당(assign) 방식이라 id를 직접 채번해 넣는다.
                jdbc.update(
                    "INSERT INTO role (id, name, active) VALUES ((SELECT COALESCE(MAX(id), 0) + 1 FROM role), 'member', TRUE)"
                )
                val roleId = (jdbc.queryForList("SELECT id FROM role WHERE name = 'member'").first().values.first() as Number).toLong()
                jdbc.update("INSERT INTO project_user (user_id, project_id, role_id) VALUES (?, ?, ?)", author.id, project.id, roleId)
                jdbc.update("INSERT INTO project_user (user_id, project_id, role_id) VALUES (?, ?, ?)", author.id, projectB.id, roleId)
                jdbc.update(
                    "INSERT INTO user_known_device (user_id, device_token_hash, first_seen_at, last_seen_at) " +
                        "VALUES (?, 'leak-probe-hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    author.id
                )

                val archive = DataBackupArchiveTestSupport.exportProjectToBytes(
                    dataBackupService, "merge-author", "merge-field-site"
                )
                val tables = DataBackupArchiveTestSupport.readTables(objectMapper, archive)

                tables["project"].orEmpty().map { it.value("name") } shouldBe listOf("merge-field-site")
                tables["project_user"].orEmpty().mapNotNull { it.value("project_id")?.toString() }.toSet() shouldBe
                    setOf(project.id.toString())
                tables.keys.none {
                    it in setOf(
                        "user_known_device", "user_setting", "ssh_key", "email", "api_token",
                        "user_totp_credential", "user_verification", "user_backup_code",
                    )
                } shouldBe true
                tables["n4user"].orEmpty().map { it.value("login_id") }.toSet() shouldBe setOf("merge-author")
            }

            it("존재하지 않는 프로젝트는 찾을 수 없다고 보고한다") {
                val thrown = runCatching {
                    DataBackupArchiveTestSupport.exportProjectToBytes(dataBackupService, "merge-author", "no-such-project")
                }
                thrown.exceptionOrNull() shouldBe instanceOf(NoSuchElementException::class)
            }
        }

        describe("프로젝트 merge (본사 복귀)") {
            it("삭제된 프로젝트 자리에 PK를 재채번해 복원하고 container_id와 사용자를 재매핑한다") {
                val archive = DataBackupArchiveTestSupport.exportProjectToBytes(
                    dataBackupService, "merge-author", "merge-field-site"
                )
                // 출장 프로젝트를 본사에 처음 올리는 상황을 흉내 — 데이터를 지운 뒤 병합한다.
                // 본사에 다른 프로젝트를 하나 남겨둔다: 새 PK가 기존 최대 id를 넘어서는지(재채번) 관찰하기 위함.
                val hqProject = projectRepository.save(
                    Project(name = "merge-hq-other", owner = "merge-author", projectScope = ProjectScope.PUBLIC)
                )
                val oldProjectId = project.id!!
                attachmentRepository.deleteAll()
                issueRepository.deleteAll()
                // JPA 영속성 컨텍스트 대신 JDBC로 정리한다 — 위에서 jdbc로 넣은 project_user 행까지
                // 포함해 지워야 하고, cascade/flush 개입 없이 import만 검증하면 된다.
                jdbc.update("DELETE FROM project_user WHERE project_id = ?", project.id)
                jdbc.update("DELETE FROM project WHERE id = ?", project.id)

                val mergedAs = DataBackupArchiveTestSupport.importProjectBytes(dataBackupService, archive)
                mergedAs shouldBe "merge-author/merge-field-site"

                val restored = jdbc.queryForList("SELECT id, owner FROM project WHERE name = 'merge-field-site'")
                restored.size shouldBe 1
                val newProjectId = (restored.single()["id"] as Number).toLong()
                (newProjectId != oldProjectId) shouldBe true
                (newProjectId > (hqProject.id ?: 0L)) shouldBe true

                val restoredIssue = jdbc.queryForList("SELECT id, number, project_id FROM issue WHERE project_id = ?", newProjectId)
                restoredIssue.size shouldBe 1
                // 이슈 테이블이 비어 있던 경우 새 id가 기존 id와 같을 수 있다(정상 재사용) —
                // 재매핑의 증거는 새 project_id 귀속과 attachment.container_id 추적이다.
                (restoredIssue.single()["number"] as Number).toLong() shouldBe 1L

                val restoredAttachment = jdbc.queryForList(
                    "SELECT container_id FROM attachment WHERE hash = ?", hash
                )
                val newIssueId = (restoredIssue.single()["id"] as Number).toLong()
                restoredAttachment.single()["container_id"] shouldBe newIssueId.toString()

                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM n4user WHERE login_id = 'merge-author'", Int::class.java
                ) shouldBe 1

                File(uploadsDir, hash).readBytes() shouldBe content
                // 아카이브 원본 이름 그대로의 저장소가 실효 이름 위치에 도착했는지(무충돌 merge) 확인한다.
                File(gitDir, "merge-author/merge-field-site.git/MARKER").readText() shouldBe "field-repo"
            }

            it("같은 이름의 프로젝트가 이미 있으면 별도 이름으로 병합한다(yona-export 관례)") {
                val archive = DataBackupArchiveTestSupport.exportProjectToBytes(
                    dataBackupService, "merge-author", "merge-field-site"
                )
                val before = jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE name LIKE 'merge-field-site%'", Int::class.java)!!

                // 충돌 merge가 기존 본사 저장소를 덮어쓰지 않는지 확인할 수 있도록 별개 마커를 심는다.
                File(gitDir, "merge-author/merge-field-site.git/MARKER").writeText("hq-repo-first")

                val mergedAs = DataBackupArchiveTestSupport.importProjectBytes(dataBackupService, archive)!!

                mergedAs shouldStartWith "merge-author/merge-field-site-imported-"
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project WHERE name LIKE 'merge-field-site%'", Int::class.java
                ) shouldBe before + 1
                // 기존 본사 저장소는 그대로고, 가져온 저장소는 실효(renamed) 이름 아래에 도착해야 한다.
                File(gitDir, "merge-author/merge-field-site.git/MARKER").readText() shouldBe "hq-repo-first"
                File(gitDir, "merge-author/${mergedAs.substringAfter('/')}.git/MARKER").readText() shouldBe "field-repo"
            }
            it("본사에 없는 작성자는 credential 없는 참조 계정으로 생성하고 author_id를 재매핑한다") {
                val fieldAuthor = userRepository.save(
                    User(
                        loginId = "field-only-author", name = "출장작성자", email = "field-only@example.com",
                        state = UserState.SITE_ADMIN, password = "field-password-hash", passwordSalt = "field-salt",
                        token = "field-token", isTwoFactorEnabled = true,
                    )
                )
                val currentProject = projectRepository.findByOwnerAndName("merge-author", "merge-field-site").get()
                issueRepository.save(
                    Issue(title = "비멤버 작성자 이슈", authorId = fieldAuthor.id,
                        authorLoginId = fieldAuthor.loginId, authorName = fieldAuthor.name,
                        project = currentProject, number = 42)
                )
                val archive = DataBackupArchiveTestSupport.exportProjectToBytes(
                    dataBackupService, "merge-author", "merge-field-site"
                )
                val exportedUser = DataBackupArchiveTestSupport.readTables(objectMapper, archive)
                    .getValue("n4user").single { it.value("login_id") == "field-only-author" }
                exportedUser.value("state") shouldBe "ACTIVE"
                exportedUser.keys.none { it.lowercase() in setOf("password", "password_salt", "token", "is_two_factor_enabled") } shouldBe true

                userRepository.delete(fieldAuthor)
                val importedAs = DataBackupArchiveTestSupport.importProjectBytes(dataBackupService, archive)!!
                val importedName = importedAs.substringAfter('/')
                val importedUser = jdbc.queryForList(
                    "SELECT id, state, password, token FROM n4user WHERE login_id = 'field-only-author'"
                ).single()
                importedUser["state"] shouldBe "ACTIVE"
                importedUser["password"] shouldBe null
                importedUser["token"] shouldBe null
                val importedProjectId = jdbc.queryForObject(
                    "SELECT id FROM project WHERE name = ?", Long::class.java, importedName
                )!!
                val importedUserId = (importedUser["id"] as Number).toLong()
                jdbc.queryForObject(
                    "SELECT author_id FROM issue WHERE project_id = ? AND title = '비멤버 작성자 이슈'",
                    Long::class.java, importedProjectId
                ) shouldBe importedUserId
            }
        }
    }
}
