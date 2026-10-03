package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.FileNotFoundException

/** Repository-owned JSON, not executable templates. Answers remain normal issue Markdown. */
@Service
class IssueTemplateService(
    private val repositoryService: RepositoryService,
    private val objectMapper: ObjectMapper
) {
    data class Field(val id: String, val label: String, val type: String, val required: Boolean, val options: List<String>)
    data class Template(val id: String, val name: String, val body: String, val fields: List<Field>)
    data class Catalog(val templates: List<Template>, val legacyBody: String, val invalidConfiguration: Boolean = false)

    fun catalog(project: Project): Catalog {
        val repository = try {
            repositoryService.getRepository(project)
        } catch (_: Exception) {
            return Catalog(emptyList(), "") // Preserve the empty/unavailable repository fallback.
        }
        val legacy = try {
            repository.getRawFile("HEAD", "ISSUE_TEMPLATE.md").toString(Charsets.UTF_8)
        } catch (_: Exception) { "" }
        return try {
            val bytes = repository.getRawFile("HEAD", PATH)
            Catalog(parse(bytes), legacy)
        } catch (_: FileNotFoundException) {
            Catalog(emptyList(), legacy)
        } catch (e: Exception) {
            logger.warn("Cannot read issue templates for project {}: {}", project.id, e.message)
            Catalog(emptyList(), legacy, invalidConfiguration = true)
        }
    }

    fun select(catalog: Catalog, templateId: String?): Template? {
        if (templateId.isNullOrBlank()) return null
        return catalog.templates.find { it.id == templateId }
    }

    fun submission(project: Project, templateId: String?, answers: Map<String, String>, body: String): String {
        if (templateId.isNullOrBlank()) {
            if (answers.isNotEmpty()) invalid("Answers require a template")
            return body
        }
        val template = select(catalog(project), templateId)
            ?: invalid("Unknown issue template; review the default form")
        if (answers.keys.any { key -> template.fields.none { it.id == key } }) invalid("Unknown answer field")
        val sections = template.fields.map { field ->
            val answer = answers[field.id].orEmpty()
            if (field.required && answer.isBlank()) invalid("${field.label}: required")
            if (answer.length > MAX_ANSWER) invalid("${field.label}: answer is too long")
            if (field.type == "select" && answer.isNotEmpty() && answer !in field.options) {
                invalid("${field.label}: choose one of the listed options")
            }
            // Indented code preserves literal answers (including HTML/Markdown) without active links or embeds.
            "### ${escapeLabel(field.label)}\n\n" + answer.replace("\r\n", "\n").replace('\r', '\n')
                .split('\n').joinToString("\n") { "    $it" }
        }
        return (listOf(body).filter { it.isNotBlank() } + sections).joinToString("\n\n")
    }

    internal fun parse(bytes: ByteArray): List<Template> {
        require(bytes.size <= 65536) { "Template catalog exceeds 64 KiB" }
        val root = objectMapper.readTree(bytes)
        require(root.isArray && root.size() <= 20) { "Expected up to 20 templates" }
        val templates = root.values().map { node ->
            val id = node.text("id", 64)
            require(ID.matches(id)) { "Invalid template id" }
            val fieldsNode = node.path("fields")
            require(fieldsNode.isMissingNode || (fieldsNode.isArray && fieldsNode.size() <= 20)) { "Invalid fields" }
            val fields = if (fieldsNode.isMissingNode) emptyList() else fieldsNode.values().map { field ->
                val fieldId = field.text("id", 64)
                require(ID.matches(fieldId)) { "Invalid field id" }
                val type = field.text("type", 16)
                require(type == "text" || type == "select") { "Unsupported field type" }
                val required = field.path("required")
                require(required.isMissingNode || required.isBoolean) { "required must be boolean" }
                val optionsNode = field.path("options")
                val options = if (type == "select") {
                    require(optionsNode.isArray && optionsNode.size() in 1..50) { "Invalid select options" }
                    optionsNode.values().map {
                        require(it.isString && it.asString().isNotBlank() && it.asString().length <= 250) { "Invalid option" }
                        it.asString()
                    }.also { require(it.distinct().size == it.size) { "Duplicate option" } }
                } else emptyList()
                Field(fieldId, field.text("label", 250).also {
                    require('\n' !in it && '\r' !in it) { "Labels must be single line" }
                }, type, required.asBoolean(false), options)
            }
            require(fields.map { it.id }.distinct().size == fields.size) { "Duplicate field id" }
            val bodyNode = node.path("body")
            require(bodyNode.isMissingNode || bodyNode.isString) { "body must be text" }
            Template(id, node.text("name", 250), if (bodyNode.isMissingNode) "" else bodyNode.asString(), fields)
        }
        require(templates.map { it.id }.distinct().size == templates.size) { "Duplicate template id" }
        return templates
    }

    private fun JsonNode.text(name: String, max: Int): String {
        val value = path(name)
        require(value.isString && value.asString().isNotBlank() && value.asString().length <= max) { "Invalid $name" }
        return value.asString()
    }

    private fun escapeLabel(label: String): String = buildString {
        label.forEach { char ->
            if (char in "\\`*_{}[]<>()#+-.!|&") append('\\')
            append(char)
        }
    }

    private fun invalid(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    companion object {
        const val PATH = ".yona/issue-templates.json"
        const val MAX_ANSWER = 10000
        private val ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
        private val logger = LoggerFactory.getLogger(IssueTemplateService::class.java)
    }
}
