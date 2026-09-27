package com.github.yonaprojects.yona.queue.acceptance

import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.sql.DataSource

internal object PublishedArtifactReader {
    fun read(dataSource: DataSource, dataDirectory: Path, relativePath: String): PublishedQueueArtifact? {
        val pointers = JdbcTemplate(dataSource).query(
            "SELECT job_id,execution_generation,attempt_no,fence,storage_path,size_bytes,sha256 " +
                "FROM queue_artifact WHERE relative_path = ?",
            { row, _ ->
                listOf(
                    row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4), row.getString(5),
                    row.getLong(6), row.getString(7),
                )
            },
            relativePath,
        )
        val pointer = pointers.maxByOrNull { it[0] as Long } ?: return null
        val storagePath = pointer[4] as String
        val target = dataDirectory.resolve(storagePath).normalize()
        check(target.startsWith(dataDirectory.resolve("artifacts").normalize())) {
            "Queue artifact pointer escapes the configured artifact root"
        }
        check(Files.isRegularFile(target)) { "Committed queue artifact is missing: $storagePath" }
        val bytes = Files.readAllBytes(target)
        check(bytes.size.toLong() == pointer[5] as Long) { "Queue artifact size differs from its committed pointer" }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        check(hash == pointer[6] as String) { "Queue artifact checksum differs from its committed pointer" }
        return PublishedQueueArtifact(
            relativePath = relativePath,
            jobId = pointer[0] as Long,
            executionGeneration = pointer[1] as Long,
            attemptNo = pointer[2] as Long,
            fence = pointer[3] as Long,
            bytes = bytes,
        )
    }
}
