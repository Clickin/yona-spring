package com.github.yonaprojects.yona.queue

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import tools.jackson.databind.json.JsonMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class QueueAdminErrors : HandlerExceptionResolver {
    private val json = JsonMapper.builder().build()

    override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
        val path = request.servletPath
        if (path != QUEUE_API && !path.startsWith("$QUEUE_API/")) return null
        if (response.isCommitted) return ModelAndView()
        val failure = when (ex) {
            is QueueHttpFailure -> ex
            is QueueControlException -> when (ex.code) {
                "FORBIDDEN" -> QueueHttpFailure(403, ex.code, "Current site administrator access is required")
                "NOT_FOUND" -> QueueHttpFailure(404, ex.code, "Queue job not found")
                "INVALID_REQUEST", "INVALID_REASON", "INVALID_COMMAND_ID" -> QueueHttpFailure(400, ex.code, "Invalid queue command")
                "QUEUE_FULL" -> QueueHttpFailure(409, ex.code, "The queue has reached its pending-job capacity")
                "COMMAND_ID_CONFLICT", "RECOVERY_REASON_REQUIRED", "RECOVERY_ACK_REQUIRED", "STALE_ATTEMPT", "INVALID_TRANSITION", "UNSUPPORTED_HANDLER", "NOT_DUE" ->
                    QueueHttpFailure(409, ex.code, "The current job state does not permit this command")
                else -> QueueHttpFailure(500, "INTERNAL_ERROR", "Queue request could not be completed")
            }
            is ErrorResponse -> {
                val status = ex.statusCode.value()
                if (status in 400..499) QueueHttpFailure(status, if (status == 404) "NOT_FOUND" else "INVALID_REQUEST", "Invalid queue request")
                else QueueHttpFailure(500, "INTERNAL_ERROR", "Queue request could not be completed")
            }
            else -> QueueHttpFailure(500, "INTERNAL_ERROR", "Queue request could not be completed")
        }
        response.reset()
        response.status = failure.status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        if (ex is HttpRequestMethodNotSupportedException) {
            ex.supportedHttpMethods?.let { response.setHeader("Allow", it.joinToString(", ") { method -> method.name() }) }
        }
        json.writeValue(response.outputStream, QueueApiError(failure.code, failure.message))
        return ModelAndView()
    }
}
