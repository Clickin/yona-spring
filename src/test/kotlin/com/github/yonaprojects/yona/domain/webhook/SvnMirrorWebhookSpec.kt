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

class SvnMirrorWebhookSpec : DescribeSpec({
    val repository = mockk<WebhookRepository>()
    val service = WebhookServiceImpl(
        repository, mockk(relaxed = true), "https://yona.example.test",
        mockk(relaxed = true), mockk(relaxed = true), SimpleMeterRegistry()
    )
    val project = Project(id = 42, owner = "owner", name = "svn-project", projectScope = ProjectScope.PRIVATE)
    val commit = SvnMirrorCommit("12345678901", "upstream-author", Instant.parse("2005-01-02T03:04:05.123456Z"), "Fix #17")

    describe("SVN mirror NEW_COMMIT webhooks") {
        it("preserves numeric revision, source author and precise timestamp without inventing sender or email") {
            val webhook = Webhook(project = project, payloadUrl = "https://receiver.example.test", webhookType = WebhookType.JSON)
            val root = ObjectMapper().readTree(service.buildSvnMirrorPayload(webhook, commit))
            root.path("ref").size() shouldBe 0
            root.path("sender").isNull shouldBe true
            root.path("pusher").isNull shouldBe true
            root.path("commits").size() shouldBe 1
            val head = root.path("head_commit")
            head.path("id").asText() shouldBe commit.revision
            head.path("message").asText() shouldBe commit.message
            head.path("timestamp").asText() shouldBe "2005-01-02T03:04:05.123456Z"
            head.path("url").asText() shouldBe "https://yona.example.test/owner/svn-project/commit/12345678901"
            head.path("author").path("name").asText() shouldBe "upstream-author"
            head.path("author").path("email").isNull shouldBe true
            head.path("author").has("id") shouldBe false
            head.path("committer") shouldBe head.path("author")
            root.path("repository").path("id").asLong() shouldBe 42L
            root.path("repository").path("private").asBoolean() shouldBe true
        }

        it("keeps absent author absent rather than claiming a local User") {
            val webhook = Webhook(project = project, payloadUrl = "https://receiver.example.test", webhookType = WebhookType.JSON)
            val root = ObjectMapper().readTree(service.buildSvnMirrorPayload(webhook, commit.copy(author = null)))
            root.path("head_commit").path("author").path("name").isNull shouldBe true
            root.path("sender").isNull shouldBe true
        }

        it("supports all configured text formats and keeps the existing gitPush delivery switch") {
            for (type in WebhookType.entries) {
                val webhook = Webhook(project = project, payloadUrl = "https://receiver.example.test", webhookType = type, gitPush = false)
                service.shouldDeliverToWebhook(webhook, EventType.NEW_COMMIT) shouldBe (type == WebhookType.JSON)
                if (type != WebhookType.JSON) {
                    val root = ObjectMapper().readTree(service.buildSvnMirrorPayload(webhook, commit.copy(author = null)))
                    root.path("text").asText() shouldBe "[svn-project] SVN r12345678901 (unknown author)"
                }
                webhook.gitPush = true
                service.shouldDeliverToWebhook(webhook, EventType.NEW_COMMIT) shouldBe true
            }
        }

        it("uses the existing sender transport for SVN JSON even when gitPush is off") {
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
                val webhook = Webhook(
                    project = project,
                    payloadUrl = "http://127.0.0.1:${server.address.port}/hook",
                    webhookType = WebhookType.JSON,
                    gitPush = false
                )
                every { repository.findByProjectId(42) } returns listOf(webhook)
                service.sendSvnMirrorWebhook(project, commit)
                latch.await(5, TimeUnit.SECONDS) shouldBe true
                ObjectMapper().readTree(received.get()).path("head_commit").path("id").asText() shouldBe commit.revision
            } finally {
                server.stop(0)
            }
        }
    }
})
