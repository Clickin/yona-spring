package com.github.yonaprojects.yona.queue

import org.springframework.stereotype.Component
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/** Application-owned schemas only; storing an unknown type never loads or executes code. */
data class TaskDefinition(
    val type: String,
    val payloadVersion: Int,
    val validate: (JsonNode) -> Unit,
    val resourceKeys: (JsonNode) -> List<String> = { emptyList() },
)

@Component
class TaskRegistry(definitions: List<TaskDefinition>) {
    private val types = ConcurrentHashMap<String, ConcurrentHashMap<Int, TaskDefinition>>()
    private val json = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    init { definitions.forEach(::register) }

    fun register(definition: TaskDefinition) {
        require(TYPE.matches(definition.type) && definition.payloadVersion > 0)
        check(types.computeIfAbsent(definition.type) { ConcurrentHashMap() }
            .putIfAbsent(definition.payloadVersion, definition) == null) { "Duplicate queue task registration" }
    }

    fun find(type: String, version: Int): TaskDefinition? = types[type]?.get(version)

    internal fun validate(definition: TaskDefinition, payload: ByteArray): List<String> {
        require(!(payload.size >= 3 && payload[0] == 0xef.toByte() &&
            payload[1] == 0xbb.toByte() && payload[2] == 0xbf.toByte())) { "Payload BOM is not allowed" }
        val node = InputStreamReader(payload.inputStream(), Charsets.UTF_8.newDecoder()).use { json.readTree(it) }
        require(node != null && node.isObject) { "Payload must be a JSON object" }
        definition.validate(node)
        val keys = definition.resourceKeys(node)
        require(keys.all { it.length <= 300 && RESOURCE.matches(it) }) { "Invalid queue resource identity" }
        return if (keys.size < 2) keys else keys.toSortedSet().toList()
    }

    companion object {
        internal val TYPE = Regex("[a-z0-9._-]{1,120}")
        private val RESOURCE = Regex("[a-z][a-z0-9-]*:[a-z0-9]+")
    }
}
