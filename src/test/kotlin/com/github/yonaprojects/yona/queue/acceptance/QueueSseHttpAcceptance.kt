package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.QUEUE_EVENTS
import com.github.yonaprojects.yona.queue.QUEUE_SSE_STREAM
import com.github.yonaprojects.yona.queue.QueueEventHub
import com.github.yonaprojects.yona.queue.QueueEventStream
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@TestConfiguration(proxyBeanMethods = false)
internal class QueueSseHttpAcceptanceConfiguration {
    @Bean
    fun queueSseProbeFilter(probe: QueueSseAcceptanceProbe) = FilterRegistrationBean(probe).apply {
        addUrlPatterns(QUEUE_EVENTS)
        order = -90 // After the real Spring Security filter, never an authentication shortcut.
        isAsyncSupported = true
    }
}

/** Bounded legal comment pressure on real servlet output, without container internals. */
@RestController
@ConditionalOnProperty(name = ["yona.queue.acceptance.enabled"], havingValue = "true")
internal class QueueSseAcceptanceProbe(
    private val events: QueueEventHub,
    @Value("\${yona.queue.acceptance.control-token}") token: String,
) : OncePerRequestFilter(), AutoCloseable {
    private val expectedToken = token.toByteArray(Charsets.UTF_8)
    private val lock = ReentrantLock()
    private val records = LinkedHashMap<String, Record>()
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "queue-test-sse-pressure").apply { isDaemon = true } }
    private val comment = ByteArray(8 * 1024 * 1024) { ' '.code.toByte() }.apply {
        this[0] = ':'.code.toByte()
        this[size - 2] = '\n'.code.toByte()
        this[size - 1] = '\n'.code.toByte()
    }

    init {
        ticker.scheduleWithFixedDelay({
            lock.withLock { records.values.toList() }.forEach { record ->
                if (record.pressure && record.stream?.active == true && record.armed.compareAndSet(false, true)) {
                    record.stream?.heartbeat()
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val pressure = request.getHeader("X-Yona-Queue-Test-Pressure")
        if (pressure != null) {
            authorize(request)
            if (pressure != "bounded-comments") throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        val id = UUID.randomUUID().toString()
        val record = Record(pressure != null)
        response.setHeader("X-Yona-Queue-Test-Stream-ID", id)
        val delegate = response.outputStream
        val output = object : ServletOutputStream() {
            override fun isReady() = delegate.isReady
            override fun setWriteListener(listener: WriteListener) = delegate.setWriteListener(listener)
            override fun flush() = delegate.flush()
            override fun write(value: Int) = delegate.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (record.pressure && length > 0 && bytes[offset] == ':'.code.toByte() && record.started == 0L) {
                    record.started = System.nanoTime()
                    try { delegate.write(comment) } finally { record.finished = System.nanoTime() }
                } else delegate.write(bytes, offset, length)
            }
        }
        val wrapped = object : HttpServletResponseWrapper(response) {
            override fun getOutputStream() = output
        }
        try {
            chain.doFilter(request, wrapped)
        } finally {
            (request.getAttribute(QUEUE_SSE_STREAM) as? QueueEventStream)?.let { stream ->
                record.stream = stream
                lock.withLock {
                    while (records.size >= 128) {
                        val old = records.entries.firstOrNull { it.value.stream?.active != true } ?: break
                        records.remove(old.key)
                    }
                    records[id] = record
                }
            }
        }
    }

    @GetMapping("/__test__/queue/v1/sse/metrics")
    fun metrics(request: HttpServletRequest): Map<String, Any> {
        authorize(request)
        return mapOf(
            "activeStreams" to events.activeStreams(),
            "generationPollCount" to events.generationPollCount.get(),
            "principalAuthCheckCount" to events.principalAuthCheckCount.get(),
            "sessionValidityCheckCount" to events.sessionValidityCheckCount.get(),
            "streams" to lock.withLock { records.toMap() }.mapValues { (_, record) ->
                mapOf("active" to (record.stream?.active == true),
                    "pressureStartedNanos" to record.started, "pressureFinishedNanos" to record.finished)
            },
        )
    }

    private fun authorize(request: HttpServletRequest) {
        val supplied = request.getHeader("X-Yona-Queue-Test-Control")
        if (!InetAddress.getByName(request.remoteAddr).isLoopbackAddress || supplied == null ||
            !MessageDigest.isEqual(expectedToken, supplied.toByteArray(Charsets.UTF_8))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    override fun close() { ticker.shutdownNow() }

    private class Record(val pressure: Boolean) {
        @Volatile var stream: QueueEventStream? = null
        val armed = AtomicBoolean()
        @Volatile var started = 0L
        @Volatile var finished = 0L
    }
}
