package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.QUEUE_TABLE_NAMES
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * durable queue 테이블은 export/import에서 제외된다 — H2/PostgreSQL/MariaDB 스펙이 공유하는 시나리오.
 * 세 시나리오는 하나의 DB 상태를 이어 쓰므로 순서에 의존한다(작업 enqueue → 거부 확인 → 종료 후 import).
 */
fun DescribeSpec.queueBackupScenarios(
    dataBackupService: DataBackupService,
    queue: Queue,
    userRepository: UserRepository,
    jdbc: JdbcTemplate,
    objectMapper: ObjectMapper,
) {
    val payload = byteArrayOf(0, 1, 2, -1, -128, 127, 65, 66)

    // 큐 상태 스냅샷 — payload는 바이트 리스트로 비교해 byte-for-byte 동일성을 본다.
    fun queueSnapshot(): List<List<Any?>> {
        val jobs = jdbc.queryForList("SELECT id, status, payload, payload_hash FROM queue_job ORDER BY id")
            .map { listOf(it["id"], it["status"], (it["payload"] as ByteArray).toList(), it["payload_hash"]) }
        val meta = jdbc.queryForList("SELECT counter_name, counter_value FROM queue_meta ORDER BY counter_name")
            .map { listOf(it["counter_name"], it["counter_value"]) }
        val keys = jdbc.queryForList("SELECT key_digest FROM queue_idempotency_key ORDER BY key_digest")
            .map { listOf(it["key_digest"]) }
        return jobs + meta + keys
    }

    describe("DataBackupService 큐 테이블 제외") {
        it("export에는 큐 테이블이 없어야 한다(대소문자 무관)") {
            userRepository.save(User(loginId = "queue-scn-user", name = "큐시나리오", email = "queue-scn@example.com"))
            queue.enqueue("scratch.unknown", 1, payload, Instant.now().plusSeconds(3600), "k1", "scratch")

            val archive = DataBackupArchiveTestSupport.exportSiteToBytes(dataBackupService)
            val tables = DataBackupArchiveTestSupport.readTables(objectMapper, archive).keys
            tables.none { it in QUEUE_TABLE_NAMES } shouldBe true
            tables.none { it.startsWith("queue_") } shouldBe true
        }

        it("미완료 작업이 있으면 import를 거부하고 아무것도 삭제하지 않아야 한다") {
            val backup = DataBackupArchiveTestSupport.exportSiteToBytes(dataBackupService)
            val usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java)!!
            (usersBefore > 0) shouldBe true
            val queueBefore = queueSnapshot()

            shouldThrow<UnfinishedQueueJobsException> {
                DataBackupArchiveTestSupport.importSiteBytes(dataBackupService, backup)
            }

            jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java) shouldBe usersBefore
            queueSnapshot() shouldBe queueBefore
        }

        it("큐 테이블이 주입된 백업을 변경 전에 거부하며 큐 데이터를 그대로 유지한다") {
            jdbc.update("UPDATE queue_job SET status = 'SUCCEEDED'")
            val queueBefore = queueSnapshot()

            // 외부 백업에 큐 운영 상태가 주입되면 무시하지 않고 명시적으로 거부한다.
            val exported = DataBackupArchiveTestSupport.readTables(
                objectMapper, DataBackupArchiveTestSupport.exportSiteToBytes(dataBackupService)
            )
            val injected = LinkedHashMap<String, List<Map<String, Any?>>>(exported)
            val fakeJob = mapOf("id" to 999, "task_type" to "bogus", "payload" to "AAECf4B/QUI=", "status" to "QUEUED")
            injected["queue_job"] = listOf(fakeJob)
            injected["queue_meta"] = listOf(mapOf("counter_name" to "next-id", "counter_value" to 123456))
            val usersInDump = exported["n4user"]?.size ?: exported.entries
                .first { it.key.equals("n4user", ignoreCase = true) }.value.size

            val dump = DataBackupArchiveTestSupport.buildSiteArchive(objectMapper, injected, sequences = mapOf("queue_job" to 5000))
            shouldThrow<BadBackupArchiveException> { DataBackupArchiveTestSupport.importSiteBytes(dataBackupService, dump) }

            queueSnapshot() shouldBe queueBefore
            jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java) shouldBe usersInDump
        }
    }
}
