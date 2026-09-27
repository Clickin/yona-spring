package com.github.yonaprojects.yona.queue

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ContentDisposition
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

@RestController
@RequestMapping(QUEUE_API)
internal class QueueAdminController(
    private val queries: QueueAdminQueries,
    private val control: QueueControl,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") dataDirectory: String,
) {
    private val root = Path.of(dataDirectory).toAbsolutePath().normalize().toRealPath()
    private val json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    @GetMapping("/jobs")
    fun list(request: HttpServletRequest): Map<String, Any?> {
        parameters(request, setOf("status", "type", "resource", "cursor", "limit"), repeated = "status")
        return queries.list(request.getParameterValues("status")?.toList().orEmpty(), request.getParameter("type"),
            request.getParameter("resource"), request.getParameter("cursor"), limit(request, "limit"))
    }

    @GetMapping("/jobs/{jobId}")
    fun detail(@PathVariable jobId: String, request: HttpServletRequest): Map<String, Any?> {
        parameters(request, setOf("attemptCursor", "attemptLimit"))
        return queries.detail(id(jobId), request.getParameter("attemptCursor"), limit(request, "attemptLimit"))
    }

    @PostMapping("/jobs/{jobId}/cancel", consumes = ["application/json"])
    fun cancel(@PathVariable jobId: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        parameters(request, emptySet())
        val body = command(request, retry = false)
        val result = control.cancel(id(jobId), body.commandId, actor(request), body.reason)
        return response(result, result.changed && result.status == QueueStatus.CANCEL_REQUESTED)
    }

    @PostMapping("/jobs/{jobId}/retry", consumes = ["application/json"])
    fun retry(@PathVariable jobId: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        parameters(request, emptySet())
        val body = command(request, retry = true)
        val result = control.retry(id(jobId), body.commandId, actor(request), body.recoveryAcknowledged, body.reason)
        return response(result, result.changed)
    }

    @PostMapping("/jobs/{jobId}/abandon", consumes = ["application/json"])
    fun abandon(@PathVariable jobId: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        parameters(request, emptySet())
        val body = command(request, retry = true, reasonRequired = true)
        val result = control.abandon(id(jobId), body.commandId, actor(request), body.recoveryAcknowledged, body.reason)
        return response(result, accepted = false)
    }

    @PostMapping("/jobs/{jobId}/prioritize", consumes = ["application/json"])
    fun prioritize(@PathVariable jobId: String, request: HttpServletRequest) = priority(jobId, request, true)

    @PostMapping("/jobs/{jobId}/deprioritize", consumes = ["application/json"])
    fun deprioritize(@PathVariable jobId: String, request: HttpServletRequest) = priority(jobId, request, false)

    private fun priority(jobId: String, request: HttpServletRequest, prioritized: Boolean): ResponseEntity<Map<String, Any?>> {
        parameters(request, emptySet())
        val body = command(request, retry = false, priority = true)
        return response(control.prioritize(id(jobId), body.commandId, actor(request), prioritized), accepted = false)
    }

    @GetMapping("/jobs/{jobId}/result")
    fun result(@PathVariable jobId: String, request: HttpServletRequest, response: HttpServletResponse) {
        parameters(request, emptySet())
        // Metadata transaction ends before filesystem access or a slow client writes any bytes.
        val artifact = queries.result(id(jobId))
        withArtifact(artifact.storagePath) { channel ->
            if (channel.size() != artifact.sizeBytes) resultConflict()
            val buffer = ByteBuffer.allocate(64 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            while (channel.read(buffer) != -1) {
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
            if (HexFormat.of().formatHex(digest.digest()) != artifact.sha256) resultConflict()
            channel.position(0)
            response.contentType = "application/octet-stream"
            response.setContentLengthLong(artifact.sizeBytes)
            response.setHeader("X-Content-SHA256", artifact.sha256)
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(artifact.fileName, Charsets.UTF_8).build().toString())
            response.setHeader("Cache-Control", "no-store")
            while (channel.read(buffer) != -1) {
                response.outputStream.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
        }
    }

    private fun response(result: QueueControlResult, accepted: Boolean): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(if (accepted) 202 else 200).body(linkedMapOf(
            "job" to queries.summary(result.jobId), "commandId" to result.commandId, "changed" to result.changed,
        ))

    private data class Command(val commandId: String, val recoveryAcknowledged: Boolean, val reason: String?)

    private fun command(request: HttpServletRequest, retry: Boolean, reasonRequired: Boolean = false, priority: Boolean = false): Command {
        if (request.contentLengthLong > MAX_COMMAND_BYTES) tooLarge()
        val bytes = request.inputStream.readNBytes(MAX_COMMAND_BYTES + 1)
        if (bytes.size > MAX_COMMAND_BYTES) tooLarge()
        val body: JsonNode = try {
            InputStreamReader(bytes.inputStream(), Charsets.UTF_8.newDecoder()).use { json.readTree(it) } ?: invalid()
        } catch (_: Exception) { invalid() }
        val allowed = when {
            priority -> setOf("commandId")
            retry -> setOf("commandId", "reason", "recoveryAcknowledged")
            else -> setOf("commandId", "reason")
        }
        if (!body.isObject || body.properties().any { it.key !in allowed }) invalid()
        val commandId = body["commandId"]?.takeIf { it.isTextual }?.textValue() ?: invalid()
        val reasonNode = body["reason"]
        if (reasonNode != null && !reasonNode.isNull && !reasonNode.isTextual) invalid()
        val reason = reasonNode?.takeIf { !it.isNull }?.textValue()
        if (reasonRequired && reason.isNullOrBlank()) invalid()
        val acknowledgement = body["recoveryAcknowledged"]
        if (acknowledgement != null && !acknowledgement.isBoolean) invalid()
        val acknowledged = acknowledgement?.booleanValue() ?: false
        if (acknowledged && reason.isNullOrBlank()) invalid()
        return Command(commandId, acknowledged, reason)
    }

    private fun actor(request: HttpServletRequest): Long = request.getAttribute(QUEUE_ACTOR_ATTRIBUTE) as? Long
        ?: throw QueueHttpFailure(403, "FORBIDDEN", "Current site administrator access is required")

    private fun id(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 && it.toString() == value } ?: invalid()
    private fun limit(request: HttpServletRequest, name: String): Int = request.getParameter(name)?.let {
        it.toIntOrNull()?.takeIf { value -> value in 1..100 && value.toString() == it } ?: invalid()
    } ?: 50

    private fun parameters(request: HttpServletRequest, allowed: Set<String>, repeated: String? = null) {
        if (request.parameterMap.any { (key, values) -> key !in allowed || (key != repeated && values.size != 1) }) invalid()
    }

    private fun withArtifact(storagePath: String, consume: (SeekableByteChannel) -> Unit) {
        val relative = Path.of(storagePath)
        if (relative.isAbsolute || relative != relative.normalize() || relative.nameCount < 2 ||
            relative.getName(0).toString() != "artifacts" || relative.any { it.toString() == ".." }) resultConflict()
        try {
            val opened = Files.newDirectoryStream(root)
            if (opened !is SecureDirectoryStream<Path>) {
                opened.close()
                // macOS requires a private immutable namespace; pathname checks cannot defend against a hostile OS writer.
                var component = root
                if (root.toRealPath() != root) resultConflict()
                for (part in relative) {
                    component = component.resolve(part)
                    if (Files.isSymbolicLink(component)) resultConflict()
                }
                val canonical = component.toRealPath()
                if (!canonical.startsWith(root.resolve("artifacts")) ||
                    !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) resultConflict()
                Files.newByteChannel(canonical, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                    if (component.toRealPath() != canonical) resultConflict()
                    consume(channel)
                }
                return
            }
            var directory: SecureDirectoryStream<Path> = opened
            try {
                for (index in 0 until relative.nameCount - 1) {
                    val next = directory.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS)
                    val previous = directory
                    directory = next
                    previous.close()
                }
                directory.newByteChannel(relative.fileName, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use(consume)
            } finally {
                directory.close()
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            throw QueueHttpFailure(404, "NOT_FOUND", "The result file is unavailable")
        }
    }

    private fun resultConflict(): Nothing = throw QueueHttpFailure(409, "RESULT_CONFLICT", "The immutable result failed verification")
    private fun invalid(): Nothing = throw QueueHttpFailure(400, "INVALID_REQUEST", "Invalid queue request")
    private fun tooLarge(): Nothing = throw QueueHttpFailure(413, "REQUEST_TOO_LARGE", "Queue command exceeds its size limit")

    companion object { private const val MAX_COMMAND_BYTES = 4096 }
}
