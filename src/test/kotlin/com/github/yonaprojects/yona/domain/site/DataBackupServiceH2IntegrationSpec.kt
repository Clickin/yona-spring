package com.github.yonaprojects.yona.domain.site

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import javax.sql.DataSource
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository

/**
 * H2(신규 지원 DB) 방언 복원 경로 검증 — DataBackupServicePostgresIntegrationSpec(P1-33/34)과
 * 동일한 시나리오를 H2 기준으로 재현한다. H2는 identity 컬럼을 내부 시퀀스로 관리하는데(2.x),
 * MariaDB의 AUTO_INCREMENT처럼 명시적 INSERT 값을 보고 스스로 다음 채번을 전진시키는지,
 * 아니면 PostgreSQL의 SERIAL처럼 전혀 별개로 관리돼 복원 후 첫 신규 insert가 PK 충돌을
 * 일으키는지 실측으로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataBackupServiceH2IntegrationSpec @Autowired constructor(
    private val dataBackupService: DataBackupService,
    private val userRepository: UserRepository,
    private val issueRepository: IssueRepository,
    private val projectRepository: ProjectRepository,
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
    private val queue: Queue
) : DescribeSpec() {

    override fun extensions() = listOf(SpringExtension)

    companion object {
        private val appData = java.nio.file.Files.createTempDirectory("yona-backup-app-data").toFile()
        private val queueData = java.io.File(appData, "queue")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:h2:mem:yona-backup-it-${System.nanoTime()};DB_CLOSE_DELAY=-1" }
            registry.add("spring.datasource.username") { "sa" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.datasource.driver-class-name") { "org.h2.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.H2Dialect" }
            registry.add("yona.data") { appData.absolutePath }
            registry.add("yona.queue.data-dir") { queueData.absolutePath }
            for (kind in listOf("git", "svn", "lfs", "upload")) {
                registry.add("yona.$kind.base-dir") { java.io.File(appData, kind).absolutePath }
            }
        }
    }

    private val jdbc: JdbcTemplate by lazy { JdbcTemplate(dataSource) }

    init {
        queueBackupScenarios(dataBackupService, queue, userRepository, jdbc, objectMapper)

        describe("DataBackupService H2 복원 시퀀스 재설정") {
            it("복원된 PK 이후에 저장되는 신규 행이 시퀀스 충돌 없이 저장돼야 한다") {
                // H2는 unquoted DDL 식별자를 대문자로 접어 저장한다(listTables()가 실제
                // exportSite()에서 사용하는 테이블명도 N4USER) — MariaDB/PostgreSQL과 달리
                // JDBC DatabaseMetaData 조회(hasIdColumn())는 SQL 파서처럼 대소문자를 자동으로
                // 접어주지 않아 정확한 케이스가 필요하다.
                val backupBytes = DataBackupArchiveTestSupport.buildSiteArchive(
                    objectMapper,
                    tables = mapOf(
                        "n4user" to listOf(
                            mapOf(
                                "id" to 1,
                                "name" to "복원된유저",
                                "login_id" to "restored-user-1",
                                "email" to "restored1@example.com",
                                "remember_me" to false,
                                "is_guest" to false,
                                "state" to "ACTIVE",
                                "failed_login_attempts" to 0
                            )
                        )
                    ),
                    // yona export 시점의 "다음 값" 캡처 대응 — id=1을 이미 점유하고 있으므로 다음 값은 2.
                    sequences = mapOf("n4user" to 2),
                )

                dataBackupService.importSite(ByteArrayInputStream(backupBytes))

                val restoredCount = jdbc.queryForObject("SELECT COUNT(*) FROM n4user WHERE id = 1", Int::class.java)
                restoredCount shouldBe 1

                val newUser = userRepository.save(
                    User(loginId = "new-after-restore", name = "신규유저", email = "new@example.com")
                )

                newUser.id shouldNotBe null
                newUser.id shouldNotBe 1L
            }

            it("backs up and restores every issue assignee without duplicating issue rows") {
                val first = userRepository.save(User(loginId = "backup-first", name = "First", email = "first@backup.test"))
                val second = userRepository.save(User(loginId = "backup-second", name = "Second", email = "second@backup.test"))
                val project = projectRepository.save(Project(name = "backup-assignments", owner = "backup-owner"))
                val issue = issueRepository.saveAndFlush(
                    Issue(project = project, number = 1L, title = "Both assignees", assignees = mutableSetOf(first, second))
                )
                val backup = DataBackupArchiveTestSupport.exportSiteToBytes(dataBackupService)

                jdbc.update("DELETE FROM issue_assignee WHERE issue_id = ?", issue.id)
                DataBackupArchiveTestSupport.importSiteBytes(dataBackupService, backup)

                jdbc.queryForList("SELECT user_id FROM issue_assignee WHERE issue_id = ?", Long::class.java, issue.id).toSet() shouldBe
                    setOf(first.id, second.id)
                jdbc.queryForObject("SELECT COUNT(*) FROM issue WHERE id = ?", Int::class.java, issue.id) shouldBe 1
            }
        }
        describe("사이트 파일 백업") {
            it("application data를 복원하면서 대상 서버의 queue 디렉터리와 활성 DB 파일을 보존한다") {
                val appMarker = java.io.File(appData, "oauth2/backup-marker.txt").apply {
                    parentFile?.mkdirs()
                    writeText("backup-value")
                }
                val queueMarker = java.io.File(queueData, "preserve-marker.txt").apply {
                    parentFile?.mkdirs()
                    writeText("before-export")
                }
                val h2Marker = java.io.File(appData, "h2/yona.mv.db").apply {
                    parentFile?.mkdirs()
                    writeText("active-database-placeholder")
                }
                val archive = DataBackupArchiveTestSupport.exportSiteToBytes(dataBackupService)
                val entries = DataBackupArchiveTestSupport.readEntryNames(archive)
                entries.none { it.startsWith("files/data/queue/") } shouldBe true
                entries.none { it.startsWith("files/data/h2/") } shouldBe true

                appMarker.writeText("changed-after-export")
                queueMarker.writeText("target-queue-state")
                h2Marker.writeText("target-db-state")
                DataBackupArchiveTestSupport.importSiteBytes(dataBackupService, archive)

                appMarker.readText() shouldBe "backup-value"
                queueMarker.readText() shouldBe "target-queue-state"
                h2Marker.readText() shouldBe "target-db-state"
            }
        }

    }
}
