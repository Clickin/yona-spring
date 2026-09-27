package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class QueueAdminCommandsAcceptanceTest {
    @Test
    fun priorityCommandsExposeStateAndAbandonRequiresReasonAndAcknowledgement() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val factory = (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!
            val manager = SharedEntityManagerCreator.createSharedEntityManager(factory)
            val registry = TaskRegistry(listOf(
                TaskDefinition("queue.api.failed", 1, {}, handler = { _, _ -> throw PermanentTaskFailure("Failed") }),
                TaskDefinition("queue.api.recovery", 1, {}, handler = { _, _ -> throw RecoveryRequiredTaskFailure("Inspect effects") }),
            ))
            val clock = fixture.contextQueueClock()
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            QueueWorkerRuntime(store, registry, clock, meters, workers = 1,
                dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4).use { runtime ->
                val control = QueueControl(manager, fixture.transactionManager, clock, registry, store, runtime, fixture.queue)
                val queries = QueueAdminQueries(factory)
                val mvc = MockMvcBuilders.standaloneSetup(QueueAdminController(queries, control, fixture.dataDirectory.toString()))
                    .setHandlerExceptionResolvers(QueueAdminErrors()).build()
                val actor = fixture.contextUserService().createUser(User(loginId = "queue-api-${UUID.randomUUID()}",
                    name = "Queue admin", email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN)).id!!
                val job = fixture.queue.enqueue("queue.api.pending", 1, "{}".toByteArray(), Instant.now().plusSeconds(3600), null, "api").jobId
                fun command(action: String, body: String, jobId: Long = job) = mvc.perform(post("$QUEUE_API/jobs/$jobId/$action")
                    .servletPath("$QUEUE_API/jobs/$jobId/$action").requestAttr(QUEUE_ACTOR_ATTRIBUTE, actor)
                    .contentType("application/json").content(body))
                val commandId = UUID.randomUUID().toString()
                command("prioritize", """{"commandId":"$commandId"}""")
                    .andExpect(status().isOk).andExpect(jsonPath("$.job.prioritized").value(true))
                command("prioritize", """{"commandId":"$commandId"}""")
                    .andExpect(status().isOk).andExpect(jsonPath("$.changed").value(false))
                mvc.perform(get("$QUEUE_API/jobs/$job").servletPath("$QUEUE_API/jobs/$job"))
                    .andExpect(status().isOk).andExpect(jsonPath("$.prioritized").value(true))
                mvc.perform(get("$QUEUE_API/jobs").servletPath("$QUEUE_API/jobs").param("type", "queue.api.pending"))
                    .andExpect(status().isOk).andExpect(jsonPath("$.items[0].prioritized").value(true))
                command("deprioritize", """{"commandId":"${UUID.randomUUID()}"}""")
                    .andExpect(status().isOk).andExpect(jsonPath("$.job.prioritized").value(false))
                command("prioritize", """{"commandId":"${UUID.randomUUID()}","reason":"unexpected"}""")
                    .andExpect(status().isBadRequest)
                command("abandon", """{"commandId":"${UUID.randomUUID()}"}""").andExpect(status().isBadRequest)
                command("abandon", """{"commandId":"${UUID.randomUUID()}","reason":"No longer needed"}""")
                    .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
                command("cancel", """{"commandId":"${UUID.randomUUID()}"}""").andExpect(status().isOk)
                command("prioritize", """{"commandId":"${UUID.randomUUID()}"}""")
                    .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
                runtime.start()
                for ((type, expected) in listOf("failed" to "FAILED", "recovery" to "RECOVERY_REQUIRED", "unsupported" to "BLOCKED_UNSUPPORTED")) {
                    val terminal = fixture.queue.enqueue("queue.api.$type", 1, "{}".toByteArray(), Instant.EPOCH, null, "api").jobId
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                    while (queries.detail(terminal, null, 50)["status"] != expected && System.nanoTime() < deadline) Thread.sleep(20)
                    org.junit.jupiter.api.Assertions.assertEquals(expected, queries.detail(terminal, null, 50)["status"])
                    if (type == "recovery") {
                        command("abandon", """{"commandId":"${UUID.randomUUID()}","reason":"Inspected"}""", terminal)
                            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("RECOVERY_ACK_REQUIRED"))
                    }
                    command("abandon", """{"commandId":"${UUID.randomUUID()}","reason":"${"x".repeat(301)}"}""", terminal)
                        .andExpect(status().isBadRequest)
                    val abandonId = UUID.randomUUID().toString()
                    val body = """{"commandId":"$abandonId","reason":"Inspected; no longer needed","recoveryAcknowledged":true}"""
                    command("abandon", body, terminal).andExpect(status().isOk)
                        .andExpect(jsonPath("$.job.status").value("CANCELLED")).andExpect(jsonPath("$.job.prioritized").value(false))
                    command("abandon", body, terminal).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(false))
                }
            }
        }
    }
}
