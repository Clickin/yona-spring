package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpSession
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QueueEventsIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun mvcStreamsResetInvalidateHeartbeatAndCloseOnRevocationAndShutdown(virtualThreads: Boolean) {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val factory = (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!
            val login = "sse-${UUID.randomUUID()}"
            val admin = fixture.contextUserService().createUser(User(
                loginId = login, name = "SSE admin", email = "$login@example.invalid", state = UserState.SITE_ADMIN,
            ))
            val actor = checkNotNull(admin.id)
            val hub = QueueEventHub(QueueAdminQueries(factory), virtualThreads)
            val mvc = MockMvcBuilders.standaloneSetup(QueueEventsController(hub))
                .setHandlerExceptionResolvers(QueueAdminErrors()).build()
            fun session() = MockHttpSession().apply {
                setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextImpl(UsernamePasswordAuthenticationToken(login, "", emptyList())))
            }
            fun open(session: MockHttpSession, reconnect: Boolean = false) = mvc.perform(
                get(QUEUE_EVENTS).servletPath(QUEUE_EVENTS).session(session)
                    .requestAttr(QUEUE_ACTOR_ATTRIBUTE, actor).apply {
                        if (reconnect) header("Last-Event-ID", "not-a-replay-cursor")
                    },
            ).andReturn()
            hub.start()
            try {
                val firstSession = session()
                val first = open(firstSession)
                assertTrue(first.request.isAsyncStarted)
                assertEquals(300_000L, checkNotNull(first.request.asyncContext).timeout)
                await(2) { first.response.contentAsString.contains("event:reset") }
                assertTrue(first.response.contentAsString.contains("\"reason\":\"connect\""))
                assertEquals("no-cache", first.response.getHeader("Cache-Control"))
                assertEquals("no", first.response.getHeader("X-Accel-Buffering"))
                assertFalse(first.response.containsHeader("Connection"))
                val secondSession = session()
                val second = open(secondSession, reconnect = true)
                await(2) { second.response.contentAsString.contains("\"reason\":\"reconnect\"") }
                val rejected = open(firstSession)
                assertEquals(429, rejected.response.status)
                assertEquals("application/json", rejected.response.contentType?.substringBefore(';'))
                assertEquals("1", rejected.response.getHeader("Retry-After"))
                assertTrue(rejected.response.contentAsString.contains("RATE_LIMITED"))
                assertFalse(rejected.request.isAsyncStarted)

                val type = "queue.acceptance.$login"
                fixture.registry.register(TaskDefinition(type, 1, {}))
                fixture.queue.enqueue(type, 1, "{}".toByteArray(), Instant.EPOCH, null, "sse")
                val generation = QueueAdminQueries(factory).events(setOf(actor)).generation.toString()
                await(2) { first.response.contentAsString.contains("\"generation\":\"$generation\"") }
                val mutations = Executors.newSingleThreadScheduledExecutor()
                try {
                    val updates = mutations.scheduleWithFixedDelay({
                        fixture.queue.enqueue(type, 1, "{}".toByteArray(), Instant.EPOCH, null, "sse-busy")
                    }, 0, 200, TimeUnit.MILLISECONDS)
                    await(15) { first.response.contentAsString.contains(":heartbeat") }
                    if (updates.isDone) updates.get() // A failed producer must not turn this into an idle-stream test.
                } finally {
                    mutations.shutdown()
                    assertTrue(mutations.awaitTermination(2, TimeUnit.SECONDS))
                }

                secondSession.invalidate()
                second.getAsyncResult(2_000)
                mvc.perform(asyncDispatch(second)).andReturn()
                factory.createEntityManager().use { manager ->
                    manager.transaction.begin()
                    manager.find(User::class.java, actor).state = UserState.ACTIVE
                    manager.transaction.commit()
                }
                first.getAsyncResult(2_000)
                mvc.perform(asyncDispatch(first)).andReturn()
                val closedBody = first.response.contentAsString
                fixture.queue.enqueue(type, 1, "{}".toByteArray(), Instant.EPOCH, null, "sse-after-revoke")
                Thread.sleep(1_100)
                assertEquals(closedBody, first.response.contentAsString)

                factory.createEntityManager().use { manager ->
                    manager.transaction.begin()
                    manager.find(User::class.java, actor).state = UserState.SITE_ADMIN
                    manager.transaction.commit()
                }
                val shutdownStream = open(session())
                await(2) { shutdownStream.response.contentAsString.contains("event:reset") }
                hub.stop()
                shutdownStream.getAsyncResult(2_000)
                mvc.perform(asyncDispatch(shutdownStream)).andReturn()
                assertEquals(503, open(session()).response.status)
            } finally {
                hub.stop()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun unavailableAuthorityConnectionsCloseStreamsWithoutWaitingForThePool(virtualThreads: Boolean) {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val factory = (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!
            val login = "sse-pool-${UUID.randomUUID()}"
            val actor = fixture.contextUserService().createUser(User(
                loginId = login, name = "SSE pool admin", email = "$login@example.invalid", state = UserState.SITE_ADMIN,
            )).id!!
            val hub = QueueEventHub(QueueAdminQueries(factory), virtualThreads)
            val mvc = MockMvcBuilders.standaloneSetup(QueueEventsController(hub))
                .setHandlerExceptionResolvers(QueueAdminErrors()).build()
            fun open() = mvc.perform(get(QUEUE_EVENTS).servletPath(QUEUE_EVENTS)
                .session(MockHttpSession().apply {
                    setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        SecurityContextImpl(UsernamePasswordAuthenticationToken(login, "", emptyList())))
                }).requestAttr(QUEUE_ACTOR_ATTRIBUTE, actor)).andReturn()
            hub.start()
            val held = mutableListOf<java.sql.Connection>()
            try {
                val stream = open()
                await(2) { stream.response.contentAsString.contains("event:reset") }
                val source = fixture.dataSource.unwrap(HikariDataSource::class.java)
                repeat(source.maximumPoolSize) { held += source.connection }
                // The pool's 10-second wait must not become the stream's authority lifetime.
                stream.getAsyncResult(2_500)
                mvc.perform(asyncDispatch(stream)).andReturn()
                assertEquals(503, open().response.status)
                held.forEach { it.close() }
                held.clear()
                var recovered: org.springframework.test.web.servlet.MvcResult? = null
                await(2) { recovered?.response?.status == 200 || open().also { recovered = it }.response.status == 200 }
                await(2) { recovered!!.response.contentAsString.contains("event:reset") }
            } finally {
                held.forEach { it.close() }
                hub.stop()
            }
        }
    }

    private fun await(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "SSE behavior did not arrive within $seconds seconds")
    }
}
