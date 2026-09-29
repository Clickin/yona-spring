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
@Suppress("UNCHECKED_CAST")
fun DescribeSpec.queueBackupScenarios(
    dataBackupService: DataBackupService,
    queue: Queue,
    userRepository: UserRepository,
    jdbc: JdbcTemplate,
    objectMapper: ObjectMapper
) {
    val payload = byteArrayOf(0, 1, 2, -1, -128, 127, 65, 66)

    fun tables(dump: ByteArray): Map<String, Any?> =
        (objectMapper.readValue(dump, Map::class.java) as Map<String, Any?>)["tables"] as Map<String, Any?>

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

            val keys = tables(dataBackupService.exportAll()).keys.map { it.lowercase() }
            keys.none { it in QUEUE_TABLE_NAMES } shouldBe true
            keys.none { it.startsWith("queue_") } shouldBe true
            val root = objectMapper.readValue(dataBackupService.exportAll(), Map::class.java) as Map<String, Any?>
            (root["sequences"] as Map<String, Any?>).keys.none { it.lowercase().startsWith("queue_") } shouldBe true
        }

        it("미완료 작업이 있으면 import를 거부하고 아무것도 삭제하지 않아야 한다") {
            val backup = dataBackupService.exportAll()
            val usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java)!!
            (usersBefore > 0) shouldBe true
            val queueBefore = queueSnapshot()

            shouldThrow<UnfinishedQueueJobsException> { dataBackupService.importAll(backup) }

            jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java) shouldBe usersBefore
            queueSnapshot() shouldBe queueBefore
        }

        it("작업이 종료되면 import가 성공하고 백업에 든 큐 테이블은 무시해 기존 큐 데이터를 그대로 둬야 한다") {
            jdbc.update("UPDATE queue_job SET status = 'SUCCEEDED'")
            val queueBefore = queueSnapshot()

            // 구버전 백업 흉내 — 대문자(H2)/소문자 키로 큐 테이블을 주입한다. 그대로 복원되면 손상될 값들.
            val root = objectMapper.readValue(dataBackupService.exportAll(), Map::class.java) as Map<String, Any?>
            val injected = LinkedHashMap(root["tables"] as Map<String, Any?>)
            val fakeJob = mapOf("id" to 999, "task_type" to "bogus", "payload" to "AAECf4B/QUI=", "status" to "QUEUED")
            injected["queue_job"] = listOf(fakeJob)
            injected["QUEUE_JOB"] = listOf(fakeJob)
            injected["queue_meta"] = listOf(mapOf("counter_name" to "next-id", "counter_value" to 123456))
            injected["QUEUE_META"] = listOf(mapOf("counter_name" to "next-id", "counter_value" to 123456))
            val sequences = LinkedHashMap((root["sequences"] as Map<String, Any?>))
            sequences["queue_job"] = 5000
            val dump = objectMapper.writeValueAsBytes(mapOf("tables" to injected, "sequences" to sequences))
            val usersInDump = ((root["tables"] as Map<String, List<*>>).entries
                .first { it.key.equals("n4user", ignoreCase = true) }.value).size

            dataBackupService.importAll(dump)

            queueSnapshot() shouldBe queueBefore
            jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java) shouldBe usersInDump
        }
    }
}
