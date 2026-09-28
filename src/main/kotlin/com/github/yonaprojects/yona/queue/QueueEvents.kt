package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.Pre2faAuthenticationToken
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.apache.tomcat.util.net.SocketEvent
import org.apache.tomcat.util.net.SocketWrapperBase
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.Ordered
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

private const val AUTHORITY_LEASE_NANOS = 2_500_000_000L
private const val OUTPUT_ABORT_NANOS = 1_400_000_000L
internal val QUEUE_SSE_HEARTBEAT = ": heartbeat\n\n".toByteArray(Charsets.UTF_8)
private val CONNECT = "event: reset\ndata: {\"reason\":\"connect\"}\n\n".toByteArray(Charsets.UTF_8)
private val RECONNECT = "event: reset\ndata: {\"reason\":\"reconnect\"}\n\n".toByteArray(Charsets.UTF_8)
private val OVERFLOW = "event: reset\ndata: {\"reason\":\"buffer-overflow\"}\n\n".toByteArray(Charsets.UTF_8)

internal class QueueEventChange(generation: Long) {
    val bytes = "event: changed\ndata: {\"generation\":\"$generation\"}\n\n".toByteArray(Charsets.UTF_8)
}

/** One node-wide poller and watchdog; invalidations contain no job data or replay history. */
internal class QueueEvents(
    private val queries: QueueAdminQueries,
    private val applicationContext: ApplicationContext,
) : HttpServlet(), ApplicationListener<ContextClosedEvent>, Ordered, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()
    private val registryLock = Any()
    private val streams = LinkedHashSet<QueueEventStream>()
    private val poller = Executors.newSingleThreadScheduledExecutor { Thread(it, "yona-queue-events-poll").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "yona-queue-events-close").apply { isDaemon = true } }
    private var generation: Long? = null
    @Volatile internal var admissionStopped = false
        private set
    internal val generationPollCount = AtomicLong()
    internal val principalAuthCheckCount = AtomicLong()
    internal val sessionValidityCheckCount = AtomicLong()

    init {
        poller.scheduleAtFixedRate(::poll, 0, 1, TimeUnit.SECONDS)
        watchdog.scheduleAtFixedRate(::expire, 50, 50, TimeUnit.MILLISECONDS)
    }

    override fun service(request: HttpServletRequest, response: HttpServletResponse) {
        try {
            if (request.method != "GET") throw QueueHttpFailure(405, "METHOD_NOT_ALLOWED", "Use GET for queue events")
            val actor = request.getAttribute(QUEUE_ACTOR_ATTRIBUTE) as? Long
                ?: throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
            val session = request.getSession(false)
                ?: throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
            val login = sessionLogin(session)
                ?: throw QueueHttpFailure(401, "UNAUTHENTICATED", "A signed-in session is required")
            val started = request.getAttribute(QUEUE_AUTH_STARTED_ATTRIBUTE) as? Long
                ?: throw QueueHttpFailure(500, "INTERNAL_ERROR", "Queue authorization is temporarily unavailable")
            val socket = request.getAttribute(QUEUE_SSE_CONNECTION) as? SocketWrapperBase<*>
            if (socket == null || socket.isClosed || !request.isAsyncSupported) {
                throw QueueHttpFailure(503, "SSE_TRANSPORT_UNSUPPORTED", "SSE transport is unavailable on this deployment.")
            }
            if (System.nanoTime() - started >= AUTHORITY_LEASE_NANOS) {
                throw QueueHttpFailure(500, "INTERNAL_ERROR", "Queue authorization is temporarily unavailable")
            }
            val stream = synchronized(registryLock) {
                if (admissionStopped) throw QueueHttpFailure(503, "UNAVAILABLE", "Queue events are stopping")
                if (streams.size >= 32 || streams.count { it.actor == actor } >= 2 ||
                    streams.count { it.sessionId == session.id } >= 2) {
                    response.setHeader("Retry-After", "1")
                    throw QueueHttpFailure(429, "RATE_LIMITED", "Too many queue event streams")
                }
                response.contentType = QUEUE_SSE_CONTENT_TYPE
                response.setHeader("Cache-Control", "no-cache")
                response.setHeader("Connection", "close")
                response.setHeader("X-Accel-Buffering", "no")
                val async = request.startAsync(request, response)
                async.timeout = 0
                QueueEventStream(actor, login, session, socket, async, response.outputStream, started,
                    request.getHeader("Last-Event-ID") != null).also { it.start(); streams.add(it) }
            }
            request.setAttribute(QUEUE_SSE_STREAM, stream)
        } catch (failure: QueueHttpFailure) {
            response.status = failure.status
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            response.setHeader("Cache-Control", "no-store")
            json.writeValue(response.outputStream, QueueApiError(failure.code, failure.message))
        }
    }

    internal fun currentStreams(): List<QueueEventStream> = synchronized(registryLock) { streams.toList() }
    internal fun activeStreams(): Int = synchronized(registryLock) { streams.count { it.active } }

    private fun poll() {
        val current = currentStreams().filter { it.active }
        if (current.isEmpty() || admissionStopped) return
        val started = System.nanoTime()
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
                current.forEach { it.authorizationFailed() }
                return
            }
            val changed = generation != snapshot.generation
            generation = snapshot.generation
            val frame = if (changed) QueueEventChange(snapshot.generation) else null
            current.forEach { stream ->
                if (snapshot.siteAdmins[stream.actor] != stream.login || sessions[stream.sessionId] != stream.login) {
                    stream.authorizationFailed()
                } else {
                    stream.authorize(started)
                    if (frame != null) stream.changed(frame) else stream.heartbeatIfIdle()
                }
            }
        } catch (failure: Exception) {
            current.forEach { it.authorizationFailed() }
            logger.warn("Queue event authority refresh failed", failure)
        }
    }

    private fun expire() {
        currentStreams().forEach { stream ->
            try {
                stream.expire()
                if (!stream.active) stream.terminate()
                if (stream.socketClosed()) synchronized(registryLock) { streams.remove(stream) }
            } catch (failure: Exception) {
                logger.warn("Queue event connection termination failed", failure)
            }
        }
    }

    override fun getOrder() = 0
    override fun supportsAsyncExecution() = false
    override fun onApplicationEvent(event: ContextClosedEvent) {
        if (event.applicationContext === applicationContext) close()
    }

    override fun close() {
        val closing = synchronized(registryLock) {
            admissionStopped = true
            streams.toList()
        }
        poller.shutdownNow()
        watchdog.shutdownNow()
        // Termination joins a watchdog already inside ERROR processing before Tomcat can force TIMEOUT.
        closing.forEach { it.close(); it.terminate() }
        synchronized(registryLock) { streams.removeIf { it.socketClosed() } }
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

/** State and bounded writes share Tomcat's native connection lock; callbacks never dispatch aborts. */
internal class QueueEventStream(
    val actor: Long,
    val login: String,
    val session: HttpSession,
    private val socket: SocketWrapperBase<*>,
    private val async: AsyncContext,
    private val output: ServletOutputStream,
    authorizedAt: Long,
    reconnect: Boolean,
) : WriteListener, AsyncListener {
    val sessionId: String = session.id
    private val gate = socket.lock
    private val termination = Any()
    @Volatile private var closed = false
    private var authorizedAt = authorizedAt
    private var pending: ByteArray? = if (reconnect) RECONNECT else CONNECT
    private var outstandingSince = System.nanoTime()
    private var lastWrite = outstandingSince
    private var draining = false
    private var needsFlush = false
    private var inFlightBytes = 0
    private var readinessBlocked = false
    private var readinessChecks = 0L
    private var writes = 0L
    private var abortRequestedAt = 0L
    private var authorizationFailedAt = 0L
    private var writesAfterAuthorizationFailure = 0L
    val active: Boolean get() = !closed && !socket.isClosed

    fun start() {
        async.addListener(this)
        try {
            output.setWriteListener(this)
        } catch (_: Exception) {
            close()
        }
    }

    internal fun authorize(started: Long) = gate.withLock {
        if (!closed && System.nanoTime() - authorizedAt < AUTHORITY_LEASE_NANOS) authorizedAt = started
        else failAuthorization()
    }

    internal fun authorizationFailed() = gate.withLock { failAuthorization() }

    private fun failAuthorization() {
        if (authorizationFailedAt == 0L) authorizationFailedAt = System.nanoTime()
        markClosed()
    }

    internal fun changed(frame: QueueEventChange) = offer(frame.bytes)
    internal fun heartbeat() = offer(QUEUE_SSE_HEARTBEAT)
    internal fun heartbeatIfIdle() {
        gate.withLock { if (!closed && System.nanoTime() - lastWrite >= 12_000_000_000L) offer(QUEUE_SSE_HEARTBEAT) }
    }

    private fun offer(frame: ByteArray) = gate.withLock {
        if (closed) return
        if (outstandingSince == 0L) outstandingSince = System.nanoTime()
        pending = when {
            pending == null -> frame
            pending === CONNECT || pending === RECONNECT -> pending
            frame === QUEUE_SSE_HEARTBEAT -> pending
            else -> OVERFLOW
        }
        drain()
    }

    private fun ready(): Boolean = output.isReady.also {
        readinessChecks++
        readinessBlocked = !it && inFlightBytes > 0
    }

    private fun drain() = gate.withLock {
        if (closed || draining || socket.isClosed) return
        if (System.nanoTime() - authorizedAt >= AUTHORITY_LEASE_NANOS) {
            failAuthorization()
            return
        }
        draining = true
        try {
            if (needsFlush) {
                if (!ready()) return
                output.flush()
                needsFlush = false
            }
            val frame = pending
            if (frame != null) {
                if (!ready()) return
                pending = null
                inFlightBytes = frame.size
                needsFlush = true
                if (authorizationFailedAt != 0L) writesAfterAuthorizationFailure++
                output.write(frame)
                writes++
                lastWrite = System.nanoTime()
                if (closed || !ready()) return
                output.flush()
                needsFlush = false
            }
            if (!closed && !needsFlush && pending == null && ready()) {
                inFlightBytes = 0
                outstandingSince = 0
                readinessBlocked = false
            }
        } catch (_: Exception) {
            markClosed()
        } finally {
            draining = false
        }
    }

    internal fun expire() = gate.withLock {
        if (closed) return
        val now = System.nanoTime()
        if (now - authorizedAt >= AUTHORITY_LEASE_NANOS) failAuthorization()
        else if (outstandingSince != 0L && now - outstandingSince >= OUTPUT_ABORT_NANOS) markClosed()
    }

    internal fun close() = gate.withLock { markClosed() }

    private fun markClosed() {
        closed = true
        pending = null
        inFlightBytes = 0
        needsFlush = false
    }

    /** Only the external watchdog or synchronous context-close listener calls this, never native callbacks. */
    internal fun terminate() = synchronized(termination) {
        if (socket.isClosed) return
        gate.withLock {
            markClosed()
            if (abortRequestedAt != 0L) return
            abortRequestedAt = System.nanoTime()
        }
        socket.setError(IOException("Queue event connection closed"))
        socket.processSocket(SocketEvent.ERROR, false)
        check(socket.isClosed) { "Queue event ERROR processing did not close its connection" }
    }

    internal fun socketClosed() = socket.isClosed

    internal fun observation(): Map<String, Any> = gate.withLock {
        mapOf(
            "firstOutstandingMonotonicNanos" to outstandingSince,
            "isReadyFalseObserved" to readinessBlocked,
            "readinessCheckCount" to readinessChecks,
            "pendingOutputBytes" to ((pending?.size ?: 0) + inFlightBytes),
            "pendingFrames" to if (pending == null) 0 else 1,
            "transportProgressCount" to writes,
            "abortRequestMonotonicNanos" to abortRequestedAt,
            "authorizationFailureMonotonicNanos" to authorizationFailedAt,
            "applicationWritesAfterAuthFailure" to writesAfterAuthorizationFailure,
            "unregistered" to socket.isClosed,
        )
    }

    override fun onWritePossible() = drain()
    override fun onError(error: Throwable) = close()
    override fun onComplete(event: AsyncEvent) = close()
    override fun onTimeout(event: AsyncEvent) = close()
    override fun onStartAsync(event: AsyncEvent) = Unit
    override fun onError(event: AsyncEvent) {
        close()
        // Normal Tomcat ERROR handling has already forbidden IO; complete only its async accounting.
        event.asyncContext.complete()
    }
}
