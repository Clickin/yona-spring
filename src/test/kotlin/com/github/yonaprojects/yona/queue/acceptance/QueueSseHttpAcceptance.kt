package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.QUEUE_EVENTS
import com.github.yonaprojects.yona.queue.QUEUE_SSE_HEARTBEAT
import com.github.yonaprojects.yona.queue.QUEUE_SSE_STREAM
import com.github.yonaprojects.yona.queue.QueueEventStream
import com.github.yonaprojects.yona.queue.QueueEvents
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.apache.catalina.core.StandardContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.tomcat.TomcatWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@TestConfiguration(proxyBeanMethods = false)
internal class QueueSseHttpAcceptanceConfiguration {
    @Bean
    fun queueSseProbeSocketBuffer(): WebServerFactoryCustomizer<TomcatWebServerFactory> = WebServerFactoryCustomizer { factory ->
        factory.addConnectorCustomizers({ connector ->
            check(connector.setProperty("socket.txBufSize", "4096"))
        })
    }

    @Bean
    fun queueSseProbeFilter(probe: QueueSseAcceptanceProbe) = FilterRegistrationBean(probe).apply {
        addUrlPatterns(QUEUE_EVENTS)
        order = -90 // After the real Spring Security filter, never an authentication shortcut.
        isAsyncSupported = true
    }

    @Bean
    fun queueSseBeforeClose(probe: QueueSseAcceptanceProbe): ApplicationListener<ContextClosedEvent> =
        object : ApplicationListener<ContextClosedEvent>, Ordered {
            override fun getOrder() = -1
            override fun supportsAsyncExecution() = false
            override fun onApplicationEvent(event: ContextClosedEvent) {
                if (event.applicationContext === probe.context) probe.beforeClose()
            }
        }

    @Bean
    fun queueSseAfterClose(probe: QueueSseAcceptanceProbe): ApplicationListener<ContextClosedEvent> =
        object : ApplicationListener<ContextClosedEvent>, Ordered {
            override fun getOrder() = 1
            override fun supportsAsyncExecution() = false
            override fun onApplicationEvent(event: ContextClosedEvent) {
                if (event.applicationContext === probe.context) probe.afterClose()
            }
        }
}

@RestController
@ConditionalOnProperty(name = ["yona.queue.acceptance.enabled"], havingValue = "true")
internal class QueueSseAcceptanceProbe(
    private val events: QueueEvents,
    val context: ServletWebServerApplicationContext,
    @Value("\${yona.queue.acceptance.control-token}") token: String,
    @Value("\${yona.queue.acceptance.control-dir}") private val control: String,
    @Value("\${yona.queue.instance-id}") private val instanceId: String,
) : OncePerRequestFilter(), AutoCloseable {
    private val expectedToken = token.toByteArray(Charsets.UTF_8)
    private val records = LinkedHashMap<String, Record>()
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "queue-test-sse-pressure").apply { isDaemon = true } }
    private var before: Map<String, Any>? = null
    private var contextClosedAt = 0L
    private val comment = ByteArray(4096) { ' '.code.toByte() }.apply {
        this[0] = ':'.code.toByte()
        this[size - 2] = '\n'.code.toByte()
        this[size - 1] = '\n'.code.toByte()
    }

    init {
        ticker.scheduleWithFixedDelay({
            val current = synchronized(records) { records.values.toList() }
            current.forEach { record ->
                if (record.pressure && record.bytes.get() < PRESSURE_LIMIT && record.stream?.active == true) {
                    record.stream?.heartbeat()
                }
            }
        }, 1, 1, TimeUnit.MILLISECONDS)
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
        val wrapped = object : HttpServletResponseWrapper(response) {
            private val observedOutput by lazy {
                val delegate = response.outputStream
                object : ServletOutputStream() {
                    override fun isReady() = delegate.isReady
                    override fun setWriteListener(listener: WriteListener) = delegate.setWriteListener(listener)
                    override fun flush() = delegate.flush()
                    override fun write(value: Int) = delegate.write(value)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (record.pressure && bytes === QUEUE_SSE_HEARTBEAT && offset == 0 &&
                            length == QUEUE_SSE_HEARTBEAT.size && record.bytes.get() + comment.size <= PRESSURE_LIMIT) {
                            val attempted = record.bytes.addAndGet(comment.size.toLong())
                            if (attempted < PRESSURE_LIMIT) record.stream?.heartbeat()
                            delegate.write(comment)
                        } else {
                            val attempted = record.bytes.get()
                            if (record.pressure && attempted > 0 && attempted < PRESSURE_LIMIT) record.stream?.heartbeat()
                            delegate.write(bytes, offset, length)
                        }
                    }
                }
            }
            override fun getOutputStream(): ServletOutputStream = observedOutput
        }
        try {
            chain.doFilter(request, wrapped)
        } finally {
            val stream = request.getAttribute(QUEUE_SSE_STREAM) as? QueueEventStream
            if (stream != null) {
                record.stream = stream
                synchronized(records) {
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
        val snapshot = synchronized(records) { records.toMap() }.mapValues { (_, record) ->
            checkNotNull(record.stream).observation() + ("pressureBytesAttempted" to record.bytes.get())
        }
        return mapOf(
            "observedAtMonotonicNanos" to System.nanoTime(),
            "activeStreams" to events.activeStreams(),
            "generationPollCount" to events.generationPollCount.get(),
            "principalAuthCheckCount" to events.principalAuthCheckCount.get(),
            "sessionValidityCheckCount" to events.sessionValidityCheckCount.get(),
            "maxPendingFramesPerStream" to (snapshot.values.maxOfOrNull { it.getValue("pendingFrames") as Int } ?: 0),
            "streams" to snapshot,
        )
    }

    @GetMapping("/__test__/queue/v1/sse/lifecycle")
    fun lifecycle(request: HttpServletRequest): Map<String, Any> {
        authorize(request)
        return lifecycleState()
    }

    private fun lifecycleState(): Map<String, Any> {
        val tomcat = (context.webServer as TomcatWebServer).tomcat
        val servletContext = tomcat.host.findChildren().filterIsInstance<StandardContext>()
            .single { it.path == context.servletContext!!.contextPath }
        return mapOf(
            "serverPid" to ProcessHandle.current().pid(),
            "nodeInstanceId" to instanceId,
            "activeSseStreams" to events.activeStreams(),
            "tomcatInProgressAsyncCount" to servletContext.inProgressAsyncCount,
            "admissionStopped" to events.admissionStopped,
        )
    }

    fun beforeClose() {
        ticker.shutdownNow()
        contextClosedAt = System.nanoTime()
        before = lifecycleState()
    }

    fun afterClose() {
        val state = lifecycleState()
        val observed = System.nanoTime()
        val marker = mapOf(
            "serverPid" to ProcessHandle.current().pid(),
            "nodeInstanceId" to instanceId,
            "contextClosedEventMonotonicNanos" to contextClosedAt,
            "before" to checkNotNull(before),
            "after" to (state + mapOf(
                "tomcatAsyncZeroObservedMonotonicNanos" to if (state["tomcatInProgressAsyncCount"] == 0L) observed else 0L,
                "afterListenerMonotonicNanos" to System.nanoTime(),
            )),
        )
        val directory = Files.createDirectories(Path.of(control, "sse-lifecycle"))
        val temporary = Files.createTempFile(directory, "closed-", ".part")
        try {
            Files.write(temporary, JsonMapper.builder().build().writeValueAsBytes(marker))
            Files.move(temporary, directory.resolve("node-${ProcessHandle.current().pid()}-context-closed.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
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
        val bytes = AtomicLong()
    }

    private companion object { const val PRESSURE_LIMIT = 8L * 1024 * 1024 }
}
