package com.github.yonaprojects.yona.domain.site

import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 백업 아카이브 테스트 지원 — 만들기/읽기 모두 실제 ZIP 형식으로만 다룬다. */

fun Map<String, Any?>.value(key: String): Any? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

object DataBackupArchiveTestSupport {

    fun exportProjectToBytes(service: DataBackupService, owner: String, project: String): ByteArray =
        ByteArrayOutputStream().also { service.exportProject(owner, project, it) }.toByteArray()

    fun exportSiteToBytes(service: DataBackupService): ByteArray =
        ByteArrayOutputStream().also { service.exportSite(it) }.toByteArray()

    fun importSiteBytes(service: DataBackupService, bytes: ByteArray) =
        service.importSite(ByteArrayInputStream(bytes))

    fun importProjectBytes(service: DataBackupService, bytes: ByteArray): String? =
        service.importProject(ByteArrayInputStream(bytes))

    /** 이전 dump(map) 형태의 합성 백업을 실제 ZIP 아카이브로 만든다. 큐 테이블 주입 등에 사용. */
    fun buildSiteArchive(
        objectMapper: ObjectMapper,
        tables: Map<String, List<Map<String, Any?>>>,
        sequences: Map<String, Long> = emptyMap(),
        manifestOverrides: Map<String, Any?> = emptyMap(),
        files: Map<String, ByteArray> = emptyMap(),
        corruptIntegrity: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val index = mutableListOf<Map<String, Any?>>()
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                index.add(mapOf("path" to name, "size" to bytes.size.toLong(),
                    "sha256" to if (corruptIntegrity) "0".repeat(64) else HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(objectMapper.writeValueAsBytes(mapOf(
                "format" to DataBackupService.FORMAT, "formatVersion" to DataBackupService.FORMAT_VERSION,
                "scope" to "site", "project" to null, "createdAt" to "2026-01-01T00:00:00Z",
                "sourceVersion" to "2.0", "targetVersion" to "2.0", "producer" to "yona", "producerVersion" to "2.0",
                "integrity" to DataBackupService.INTEGRITY, "requiredCapabilities" to emptyList<String>(),
            ) + manifestOverrides))
            zip.closeEntry()
            for ((table, rows) in tables) {
                val bytes = ByteArrayOutputStream()
                for (row in rows) {
                    bytes.write(objectMapper.writeValueAsBytes(row))
                    bytes.write('\n'.code)
                }
                entry("db/${table.lowercase()}.ndjson", bytes.toByteArray())
            }
            entry("db/_sequences.json", objectMapper.writeValueAsBytes(sequences))
            for ((name, bytes) in files) entry(name, bytes)
            zip.putNextEntry(ZipEntry("integrity.ndjson"))
            index.forEach { zip.write(objectMapper.writeValueAsBytes(it)); zip.write('\n'.code) }
        }
        return out.toByteArray()
    }

    /** 아카이브의 db 디렉터리 내 NDJSON 엔트리를 테이블별 행 목록으로 읽는다. */
    fun readTables(objectMapper: ObjectMapper, bytes: ByteArray): Map<String, List<Map<String, Any?>>> {
        val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.startsWith("db/") && entry.name.endsWith(".ndjson")) {
                    val table = entry.name.removePrefix("db/").removeSuffix(".ndjson")
                    val rows = mutableListOf<Map<String, Any?>>()
                    zip.readBytes().toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
                        rows.add(objectMapper.readValue(it, Map::class.java) as Map<String, Any?>)
                    }
                    tables[table] = rows
                }
                zip.closeEntry()
            }
        }
        return tables
    }

    fun readEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                names.add(zip.nextEntry?.name ?: break)
                zip.closeEntry()
            }
        }
        return names
    }
}
