package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.Pre2faAuthenticationToken
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.SmartLifecycle
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val QUEUE_SSE_STREAM = "yona.queue.events.stream"
private const val SEND_TIMEOUT_NANOS = 2_000_000_000L
private const val HEARTBEAT_INTERVAL_NANOS = 12_000_000_000L

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class QueueEventsController(private val hub: QueueEventHub) {
    @GetMapping(QUEUE_EVENTS, produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(request: HttpServletRequest, response: HttpServletResponse): SseEmitter {
        val actor = request.getAttribute(QUEUE_ACTOR_ATTRIBUTE) as? Long
            ?: throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
        val session = request.getSession(false)
            ?: throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
        val stream = hub.open(actor, session, request.getHeader("Last-Event-ID") != null)
        request.setAttribute(QUEUE_SSE_STREAM, stream)
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("X-Accel-Buffering", "no")
        return stream.emitter
    }
}

/** One shared authority/generation poller; no poller or worker thread performs response IO. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class QueueEventHub(
    private val queries: QueueAdminQueries,
    @Value("\${spring.threads.virtual.enabled:false}") private val virtualThreads: Boolean = false,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val registryLock = ReentrantLock()
    private val streams = LinkedHashSet<QueueEventStream>()
    private lateinit var poller: ScheduledExecutorService
    private lateinit var senders: ExecutorService
    private lateinit var completions: ExecutorService
    private var generation: Long? = null
    @Volatile private var running = false
    @Volatile private var checkingSince = 0L
    internal val generationPollCount = AtomicLong()
    internal val principalAuthCheckCount = AtomicLong()
    internal val sessionValidityCheckCount = AtomicLong()

    override fun isRunning() = running
    // Stop before the web server's graceful shutdown phase (DEFAULT_PHASE - 1024).
    override fun getPhase() = Int.MAX_VALUE

    override fun start(): Unit = registryLock.withLock {
        if (running) return
        poller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("yona-queue-events-poll").factory(),
        )
        senders = if (virtualThreads) Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("yona-queue-events-send-", 0).factory(),
        ) else Executors.newFixedThreadPool(32, Thread.ofPlatform().daemon().name("yona-queue-events-send-", 0).factory())
        // complete() can wait for a blocked emitter write lock too. Never run it on the poller
        // or behind a full sender pool. Registrations retain their quota until completion.
        completions = Executors.newThreadPerTaskExecutor(
            if (virtualThreads) Thread.ofVirtual().name("yona-queue-events-complete-", 0).factory()
            else Thread.ofPlatform().daemon().name("yona-queue-events-complete-", 0).factory(),
        )
        generation = null
        running = true
        poller.scheduleAtFixedRate(::poll, 1, 1, TimeUnit.SECONDS)
        Unit
    }

    internal fun open(actor: Long, session: HttpSession, reconnect: Boolean): QueueEventStream {
        val login = sessionLogin(session)
            ?: throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
        return registryLock.withLock {
            if (!running) throw QueueHttpFailure(503, "UNAVAILABLE", "Queue events are stopping")
            val checking = checkingSince
            if (checking != 0L && System.nanoTime() - checking >= 1_000_000_000L) {
                throw QueueHttpFailure(503, "UNAVAILABLE", "Queue event authority is temporarily unavailable")
            }
            if (streams.size >= 32 || streams.count { it.actor == actor } >= 2 ||
                streams.count { it.sessionId == session.id } >= 2) {
                throw QueueHttpFailure(429, "RATE_LIMITED", "Too many queue event streams")
            }
            QueueEventStream(actor, login, session, SseEmitter(300_000L), senders, completions) { stream ->
                registryLock.withLock { streams.remove(stream) }
            }.also { stream ->
                streams.add(stream)
                stream.start(reconnect)
            }
        }
    }

    internal fun activeStreams() = registryLock.withLock { streams.count { it.active } }

    private fun poll() {
        val current = registryLock.withLock { streams.filter { it.active } }
        if (current.isEmpty() || !running) return
        val started = System.nanoTime()
        checkingSince = started
        // A pool borrow can outlive the SQL timeout. The shared JDK timer must still request closure.
        val refreshed = CompletableFuture<Void>()
        refreshed.orTimeout(1, TimeUnit.SECONDS).whenComplete { _, failure ->
            if (failure != null) registryLock.withLock { streams.toList() }.forEach { it.close() }
        }
        try {
            val sessions = current.distinctBy { it.sessionId }.associate { stream ->
                sessionValidityCheckCount.incrementAndGet()
                stream.sessionId to sessionLogin(stream.session)
            }
            val actors = current.mapTo(HashSet()) { it.actor }
            val snapshot = queries.events(actors)
            generationPollCount.incrementAndGet()
            principalAuthCheckCount.addAndGet(actors.size.toLong())
            if (System.nanoTime() - started > 1_000_000_000L) {
                current.forEach { it.close() }
                return
            }
            val changed = generation != snapshot.generation
            generation = snapshot.generation
            current.forEach { stream ->
                if (snapshot.siteAdmins[stream.actor] != stream.login || sessions[stream.sessionId] != stream.login) {
                    stream.close()
                } else {
                    stream.authorize(started)
                    if (changed) stream.changed(snapshot.generation) else stream.heartbeatIfDue()
                }
            }
        } catch (failure: Exception) {
            current.forEach { it.close() }
            logger.warn("Queue event authority refresh failed", failure)
        } finally {
            refreshed.complete(null)
            checkingSince = 0L
        }
    }

    override fun stop() {
        val closing = registryLock.withLock {
            if (!running) return
            running = false
            streams.toList()
        }
        poller.shutdownNow()
        closing.forEach { it.close() }
        senders.shutdownNow()
        completions.shutdown()
        // Healthy streams complete before web-server shutdown. A blocked container write is
        // deliberately not an application-level socket-close guarantee.
        try {
            completions.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private fun sessionLogin(session: HttpSession): String? = try {
    val maxIdle = session.maxInactiveInterval
    if (maxIdle > 0 && System.currentTimeMillis() - session.lastAccessedTime >= maxIdle.toLong() * 1_000) null
    else {
        val context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as? SecurityContext
        val authentication = context?.authentication
        authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken && it !is Pre2faAuthenticationToken }?.name
    }
} catch (_: IllegalStateException) {
    null
}

internal class QueueEventStream(
    val actor: Long,
    val login: String,
    val session: HttpSession,
    val emitter: SseEmitter,
    private val senders: ExecutorService,
    private val completions: ExecutorService,
    private val unregister: (QueueEventStream) -> Unit,
) {
    val sessionId: String = session.id
    private val gate = ReentrantLock()
    @Volatile var active = true
        private set
    private var pending: Frame? = null
    private var sending = false
    private var callbackFinished = false
    private var authorizedAt = System.nanoTime()
    private var lastHeartbeat = authorizedAt

    fun start(reconnect: Boolean) {
        emitter.onCompletion { finished() }
        emitter.onTimeout { close() }
        emitter.onError { finished() }
        offer(Frame("reset", "{\"reason\":\"${if (reconnect) "reconnect" else "connect"}\"}"))
    }

    fun authorize(started: Long) = gate.withLock { authorizedAt = started }
    fun changed(generation: Long) = offer(Frame("changed", "{\"generation\":\"$generation\"}"))
    fun heartbeatIfDue() {
        if (gate.withLock { System.nanoTime() - lastHeartbeat >= HEARTBEAT_INTERVAL_NANOS }) heartbeat()
    }
    fun heartbeat() = offer(Frame(null, "heartbeat"))

    private fun offer(frame: Frame) {
        val submit = gate.withLock {
            if (!active) return
            pending = when {
                pending == null -> frame
                pending?.name == "reset" || frame.name == null -> pending
                pending?.name == null -> frame
                else -> Frame("reset", "{\"reason\":\"buffer-overflow\"}")
            }
            if (sending) false else { sending = true; true }
        }
        if (submit) {
            try {
                senders.execute(::drain)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                endSend()
                close()
            }
        }
    }

    private fun drain() {
        while (true) {
            val frame = gate.withLock {
                if (!active || System.nanoTime() - authorizedAt >= SEND_TIMEOUT_NANOS) null
                else pending?.also { pending = null }
            }
            if (frame == null) {
                close()
                endSend()
                return
            }
            val heartbeat = frame.name == null ||
                gate.withLock { System.nanoTime() - lastHeartbeat >= HEARTBEAT_INTERVAL_NANOS }
            // The JDK timeout scheduler stays independent of a stalled authority query.
            val sent = CompletableFuture<Void>()
            sent.orTimeout(2, TimeUnit.SECONDS).whenComplete { _, failure -> if (failure != null) close() }
            try {
                // This send is already in flight if authorization is revoked concurrently.
                // No state lock is held across container IO, and no later send can start.
                if (!active) {
                    endSend()
                    return
                }
                val event = SseEmitter.event()
                // A due comment rides alongside invalidations so busy streams cannot starve it.
                if (heartbeat) event.comment("heartbeat")
                if (frame.name != null) event.name(frame.name).data(frame.data, MediaType.APPLICATION_JSON)
                emitter.send(event)
            } catch (_: Exception) {
                close()
                endSend()
                return
            } finally {
                sent.complete(null)
            }
            val again = gate.withLock {
                if (heartbeat) lastHeartbeat = System.nanoTime()
                if (active && pending != null) true else { sending = false; false }
            }
            if (!again) {
                if (gate.withLock { callbackFinished && !sending }) unregister(this)
                return
            }
        }
    }

    private fun endSend() {
        val release = gate.withLock { sending = false; callbackFinished }
        if (release) unregister(this)
    }

    fun close(): Unit = gate.withLock {
        if (!markClosed()) return
        completions.execute { emitter.complete() }
    }

    private fun markClosed(): Boolean = gate.withLock {
        if (!active) return false
        active = false
        pending = null
        true
    }

    private fun finished() {
        markClosed()
        val release = gate.withLock { callbackFinished = true; !sending }
        if (release) unregister(this)
    }

    private data class Frame(val name: String?, val data: String)
}
