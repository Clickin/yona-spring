package com.github.yonaprojects.yona.domain.webhook

import com.github.yonaprojects.yona.domain.enumeration.EventType
import com.github.yonaprojects.yona.domain.enumeration.WebhookType
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MirrorPushWebhookSpec : DescribeSpec({
    val repository = mockk<WebhookRepository>()
    val service = WebhookServiceImpl(
        repository, mockk(relaxed = true), "https://yona.example.test",
        mockk(relaxed = true), mockk(relaxed = true), SimpleMeterRegistry()
    )
    val project = Project(id = 42, owner = "owner", name = "mirror-project", projectScope = ProjectScope.PRIVATE)
    val commit = PushedVcsCommit("12345678901", "Fix #17", "upstream-author", null, Instant.parse("2005-01-02T03:04:05Z"))
    val pushed = PushedVcsCommits(listOf(commit), emptyList())

    describe("mirror NEW_COMMIT webhooks") {
        it("keeps the source author and commit ID without inventing a sender or pusher") {
            val webhook = Webhook(project = project, payloadUrl = "https://receiver.example.test", webhookType = WebhookType.JSON)
            val root = ObjectMapper().readTree(service.buildMirrorPushPayload(webhook, pushed))
            root.path("ref").size() shouldBe 0
            root.path("sender").isNull shouldBe true
            root.path("pusher").isNull shouldBe true
            val head = root.path("head_commit")
            head.path("id").asText() shouldBe commit.id
            head.path("message").asText() shouldBe commit.message
            head.path("url").asText() shouldBe "https://yona.example.test/owner/mirror-project/commit/12345678901"
            head.path("author").path("name").asText() shouldBe "upstream-author"
            head.path("committer") shouldBe head.path("author")
            root.path("repository").path("id").asLong() shouldBe 42L
            root.path("repository").path("private").asBoolean() shouldBe true
        }

        it("describes the batch and its ref in text formats and keeps the existing gitPush switch") {
            for (type in WebhookType.entries) {
                val webhook = Webhook(project = project, payloadUrl = "https://receiver.example.test", webhookType = type, gitPush = false)
                service.shouldDeliverToWebhook(webhook, EventType.NEW_COMMIT) shouldBe (type == WebhookType.JSON)
                if (type != WebhookType.JSON) {
                    ObjectMapper().readTree(service.buildMirrorPushPayload(webhook, pushed)).path("text").asText() shouldBe
                        "[mirror-project] 1개의 커밋이 미러에 반영되었습니다"
                    ObjectMapper().readTree(service.buildMirrorPushPayload(webhook, pushed.copy(refNames = listOf("refs/heads/main"))))
                        .path("text").asText() shouldBe "[mirror-project] 1개의 커밋이 미러에 반영되었습니다 (refs/heads/main)"
                }
            }
        }

        it("uses the existing sender transport for JSON even when gitPush is off") {
            val received = AtomicReference<String>()
            val latch = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/hook") { exchange ->
                exchange.use {
                    received.set(it.requestBody.readBytes().toString(Charsets.UTF_8))
                    it.sendResponseHeaders(204, -1)
                }
                latch.countDown()
            }
            server.start()
            try {
                val webhook = Webhook(project = project, payloadUrl = "http://127.0.0.1:${server.address.port}/hook", webhookType = WebhookType.JSON, gitPush = false)
                every { repository.findByProjectId(42) } returns listOf(webhook)
                service.sendMirrorPushWebhook(project, pushed)
                latch.await(5, TimeUnit.SECONDS) shouldBe true
                ObjectMapper().readTree(received.get()).path("head_commit").path("id").asText() shouldBe commit.id
            } finally {
                server.stop(0)
            }
        }
    }
})
