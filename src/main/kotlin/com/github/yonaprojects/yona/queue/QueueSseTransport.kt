package com.github.yonaprojects.yona.queue

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ValveBase
import org.apache.coyote.Processor
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.coyote.http11.Http11Processor
import org.apache.tomcat.util.net.AbstractEndpoint
import org.apache.tomcat.util.net.SocketWrapperBase
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.tomcat.TomcatWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

internal const val QUEUE_SSE_CONNECTION = "yona.queue.events.connection"
internal const val QUEUE_SSE_STREAM = "yona.queue.events.stream"
internal const val QUEUE_SSE_CONTENT_TYPE = "text/event-stream;charset=UTF-8"

/** Capture only this accepted connection; normal Tomcat IO and request processing stay unchanged. */
internal class QueueSseHttp11NioProtocol : Http11NioProtocol() {
    override fun createProcessor(): Processor = QueueSseHttp11Processor(this)

    companion object {
        val currentConnection = ThreadLocal<SocketWrapperBase<*>>()
    }
}

internal class QueueSseHttp11Processor(protocol: QueueSseHttp11NioProtocol) : Http11Processor(protocol, protocol.adapter) {
    override fun service(socket: SocketWrapperBase<*>): AbstractEndpoint.Handler.SocketState {
        val previous = QueueSseHttp11NioProtocol.currentConnection.get()
        QueueSseHttp11NioProtocol.currentConnection.set(socket)
        try {
            return super.service(socket)
        } finally {
            QueueSseHttp11NioProtocol.currentConnection.set(previous)
        }
    }
}

private class QueueSseValve : ValveBase(true) {
    override fun invoke(request: Request, response: Response) {
        if (request.requestURI == request.contextPath + QUEUE_EVENTS) {
            val protocol = request.connector.protocolHandler
            if (protocol.javaClass == QueueSseHttp11NioProtocol::class.java &&
                request.protocol == "HTTP/1.1" && !request.connector.secure &&
                !(protocol as QueueSseHttp11NioProtocol).isSSLEnabled &&
                (protocol.compression == "off" || protocol.compression != "force" &&
                    protocol.compressibleMimeTypes.none { QUEUE_SSE_CONTENT_TYPE.startsWith(it) })) {
                QueueSseHttp11NioProtocol.currentConnection.get()?.takeUnless { it.isClosed }?.let {
                    request.setAttribute(QUEUE_SSE_CONNECTION, it)
                }
            }
        }
        next.invoke(request, response)
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class QueueSseConfiguration {
    @Bean
    fun queueEvents(queries: QueueAdminQueries, applicationContext: ApplicationContext) =
        QueueEvents(queries, applicationContext)

    @Bean
    fun queueEventsServlet(events: QueueEvents) = ServletRegistrationBean(events, QUEUE_EVENTS).apply {
        setName("queueEvents")
        isAsyncSupported = true
    }

    @Bean
    fun queueSseTomcat(): WebServerFactoryCustomizer<TomcatWebServerFactory> = WebServerFactoryCustomizer { factory ->
        if (factory.protocol == TomcatWebServerFactory.DEFAULT_PROTOCOL) {
            factory.protocol = QueueSseHttp11NioProtocol::class.java.name
        }
        factory.addContextValves(QueueSseValve())
    }
}
