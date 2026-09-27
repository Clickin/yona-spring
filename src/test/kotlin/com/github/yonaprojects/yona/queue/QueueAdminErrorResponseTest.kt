package com.github.yonaprojects.yona.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.io.IOException

class QueueAdminErrorResponseTest {
    @Test
    fun anUncommittedDownloadFailureReturnsJsonWithoutFileRepresentationHeaders() {
        val request = MockHttpServletRequest("GET", "$QUEUE_API/jobs/1/result").apply {
            servletPath = "$QUEUE_API/jobs/1/result"
        }
        val response = MockHttpServletResponse().apply {
            contentType = "application/octet-stream"
            setContentLengthLong(1_048_576)
            setHeader("Content-Disposition", "attachment; filename=archive.tar")
            setHeader("X-Content-SHA256", "a".repeat(64))
        }
        QueueAdminErrors().resolveException(request, response, null, IOException("private-diagnostic-canary"))
        assertEquals(500, response.status)
        assertEquals("application/json", response.contentType?.substringBefore(';'))
        assertNull(response.getHeader("Content-Length"))
        assertNull(response.getHeader("Content-Disposition"))
        assertNull(response.getHeader("X-Content-SHA256"))
        val body = JsonMapper.builder().build().readTree(response.contentAsByteArray)
        assertEquals("INTERNAL_ERROR", body["code"].textValue())
        assertFalse(response.contentAsString.contains("private-diagnostic-canary"))
    }
    @Test
    fun unavailableAdmissionReturnsRetryableServiceFailure() {
        val request = MockHttpServletRequest("POST", "$QUEUE_API/jobs/1/retry").apply {
            servletPath = "$QUEUE_API/jobs/1/retry"
        }
        val response = MockHttpServletResponse()
        QueueAdminErrors().resolveException(request, response, null, QueueControlException("QUEUE_UNAVAILABLE"))
        assertEquals(503, response.status)
        val body = JsonMapper.builder().build().readTree(response.contentAsByteArray)
        assertEquals("QUEUE_UNAVAILABLE", body["code"].textValue())
    }
}
