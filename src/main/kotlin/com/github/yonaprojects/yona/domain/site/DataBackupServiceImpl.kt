package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.queue.isQueueTable
import com.github.yonaprojects.yona.domain.role.RoleType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.HexFormat
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

/** 대기·실행 중인 작업 큐 작업이 있어 import를 거부할 때 던진다(IllegalArgumentException 서브타입이 아니다). */
class UnfinishedQueueJobsException(message: String) : RuntimeException(message)

/** 백업 아카이브가 yona 백업 형식이 아니거나 손상됐을 때 던진다. */
class BadBackupArchiveException(message: String) : RuntimeException(message)

/**
 * 사이트/프로젝트 백업 — data/DataService.java + exchangers 44개의 범용 대응. yona는 테이블별
 * 전용 Exchanger를 손으로 나열해 유지하지만, 여기서는 DB 메타데이터로 테이블·PK·FK를 스스로
 * 찾아낸다 — 엔티티가 추가돼도 이 서비스는 수정할 필요가 없다.
 *
 * 아카이브는 ZIP 한 개를 스트리밍하며(전체를 메모리에 올리지 않는다) 다음 구조를 갖는다:
 *
 *  - manifest.json        형식 버전/범위(site|project)/생성 시각
 *  - db/<table>.ndjson    테이블별 NDJSON(행당 JSON 객체 한 줄). 큐 테이블은 제외.
 *  - db/_sequences.json   (site 범위만) 테이블별 auto-increment "다음 값"
 *  - files/data/...       yona.data 앱 파일(단, 파일 기반 durable queue 상태와 실행 중 H2 파일 DB는 제외)
 *  - files/git/...        yona.git.base-dir 아래 저장소 디렉터리 전체
 *  - files/lfs/...        yona.lfs.base-dir 아래 LFS 객체
 *  - files/uploads/<hash> yona.data 밖에 설정된 경우의 첨부 파일
 *
 * yona.upload.base-dir가 yona.data 아래면 첨부 파일은 files/data 안에 포함된다.
 *
 * 사이트 import는 모든 테이블을 DELETE 후 다시 INSERT하는 완전 교체다. yona처럼 export 시점의
 * auto-increment "다음 값"을 캡처해 복원 시 그대로 되돌려 ID 갭을 보존한다.
 *
 * 프로젝트 export는 해당 프로젝트 행에서 FK로 도달 가능한 모든 행의 폐쇄곡선(출장지 반출용)이다.
 * attachment는 container_id가 FK가 아니라 문자열 컬럼이라 폐쇄곡선에 안 걸리므로, 내보낸 행의
 * (container_type, id) 조합으로 별도 수집한다.
 *
 * 프로젝트 import(merge)는 본사 사이트에 합치기 위한 것으로, 폐쇄곡선 행의 PK를 대상 사이트에서
 * 새로 채번해 재매핑한다. n4user는 login_id로 기존 사용자와 매칭해 재사용하고(중복 생성 금지),
 * attachment.container_id는 컨테이너 테이블의 재매핑을 따라간다. 이름 충돌 시 `<name>-imported-<ts>`로
 * 별도 프로젝트를 만들고, 미완료 큐 작업이 있으면 모든 import를 거부한다(`UnfinishedQueueJobsException`).
 */
@Service
class DataBackupServiceImpl(
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
    @Value("\${yona.data:data}") private val applicationDataDir: File,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") private val queueDataDir: File,
    @Value("\${yona.git.base-dir:/tmp/yona/git}") private val gitBaseDir: File,
    @Value("\${yona.lfs.base-dir:/tmp/yona/lfs}") private val lfsBaseDir: File,
    @Value("\${yona.upload.base-dir:/tmp/yona/uploads}") private val uploadBaseDir: File,
    @Value("\${yona.ldap.enabled:false}") private val ldapEnabled: Boolean = false,
    @Value("\${yona.ldap.fallback-to-local-login:false}") private val ldapFallback: Boolean = false,
    @Value("\${yona.svn.base-dir:/tmp/yona/svn}") private val svnBaseDir: File = File("/tmp/yona/svn"),
    private val searchChanges: com.github.yonaprojects.yona.domain.issue.IssueSearchChanges? = null,
    @Value("\${yona.search.index-dir:\${yona.data:data}/search/issues}") private val searchIndexDir: File = File(applicationDataDir, "search/issues"),
) : DataBackupService {

    private fun isOperationalTable(name: String): Boolean = isQueueTable(name) ||
        name.lowercase() in setOf("issue_search_pending", "issue_search_window")

    private val logger = LoggerFactory.getLogger(DataBackupServiceImpl::class.java)
    private val archiveJson = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val sharedReferenceTables = setOf("n4user", "role", "organization")
    private val jdbcTemplate = JdbcTemplate(dataSource)
    private val insertTypes = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()

    private enum class Dialect { MYSQL_COMPATIBLE, POSTGRES, H2, CUBRID, OTHER }

    override fun exportSite(out: OutputStream, execution: DataBackupExecution?) {
        execution?.stage("export.database")
        ArchiveZip(out).use { zip ->
            writeManifest(zip, DataBackupService.SCOPE_SITE, null)
            // Preserve intentionally empty roots so a full restore can remove stale destination files.
            val uploadsInsideData = isWithin(uploadBaseDir, applicationDataDir)
            val roots = listOf("git", "svn", "lfs", "data") + if (uploadsInsideData) emptyList() else listOf("uploads")
            for (name in roots) {
                zip.putNextEntry(ZipEntry("files/$name/"))
                zip.closeEntry()
            }
            if (uploadsInsideData) {
                zip.putNextEntry(ZipEntry("files/data/uploads/"))
                zip.closeEntry()
            }
            val dialect = detectDialect()
            val sequences = LinkedHashMap<String, Long>()
            // 트랜잭션 안에서 읽어야 MariaDB/MySQL 드라이버가 fetchSize대로 커서 스트리밍한다.
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val tables = listTables(connection).filterNot { isOperationalTable(it) }
                for (table in tables) {
                    execution?.checkpoint()
                    val key = table.lowercase()
                    nextSequenceValue(connection, table, dialect)?.let { sequences[key] = it }
                    zip.putNextEntry(ZipEntry("db/$key.ndjson"))
                    val statement = connection.createStatement().apply { fetchSize = STREAM_FETCH_SIZE }
                    statement.executeQuery("SELECT * FROM ${exportTableRef(table, dialect)}").use { rs ->
                        val columns = columnLabels(rs)
                        while (rs.next()) {
                            zip.write(objectMapper.writeValueAsBytes(rowMap(rs, columns)))
                            zip.write('\n'.code)
                            execution?.row()
                        }
                    }
                    statement.close()
                    zip.closeEntry()
                }
                zipEntry(zip, "db/_sequences.json") { it.write(objectMapper.writeValueAsBytes(sequences)) }
            }
            execution?.stage("export.files")
            addTreeToZip(zip, gitBaseDir, "files/git", execution)
            addTreeToZip(zip, svnBaseDir, "files/svn", execution)
            addTreeToZip(zip, lfsBaseDir, "files/lfs", execution)
            addApplicationDataToZip(zip, execution)
            if (!uploadsInsideData) addTreeToZip(zip, uploadBaseDir, "files/uploads", execution)
        }
        logger.info("사이트 전체 백업 완료")
    }

    override fun exportProject(owner: String, project: String, out: OutputStream, execution: DataBackupExecution?) {
        execution?.stage("export.database")
        val seed = jdbcTemplate.queryForList(
            "SELECT * FROM project WHERE name = ? AND owner = ?", project, owner
        ).firstOrNull() ?: throw NoSuchElementException("Project not found: $owner/$project")

        val tempRoot = createTempDirectory()
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val rows = foreignKeyClosure(connection, seed, tempRoot, execution)
                addReferencedUsers(connection, rows, execution)
                addContainerAttachments(connection, rows, execution)

                ArchiveZip(out).use { zip ->
                    writeManifest(zip, DataBackupService.SCOPE_PROJECT, "$owner/$project")
                    for ((table, file) in rows) {
                        zip.putNextEntry(ZipEntry("db/$table.ndjson"))
                        file.inputStream().use { copyArchiveBytes(it, zip, execution) }
                        zip.closeEntry()
                    }
                    // 파일들: DB 폐쇄곡선의 attachment 해시 + 이 프로젝트의 git/wiki/LFS 디렉터리.
                    execution?.stage("export.files")
                    rows["attachment"]?.let { attachmentFile ->
                        forEachNdjsonRow(attachmentFile, execution) { row ->
                            val hash = row.value("hash")?.toString() ?: return@forEachNdjsonRow
                            val file = File(uploadBaseDir, hash)
                            if (file.isFile) addFileToZip(zip, file, "files/uploads/$hash", execution)
                        }
                    }
                    for (repo in listOf("$owner/$project.git", "$owner/$project.wiki.git")) {
                        addTreeToZip(zip, File(gitBaseDir, repo), "files/git/$repo", execution)
                    }
                    addTreeToZip(zip, File(svnBaseDir, "$owner/$project"), "files/svn/$owner/$project", execution)
                    addTreeToZip(zip, File(lfsBaseDir, "$owner/$project"), "files/lfs/$owner/$project", execution)
                }
                logger.info("프로젝트 백업 완료: $owner/$project (테이블 ${rows.size}개)")
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Transactional
    override fun importSite(input: InputStream, execution: DataBackupExecution?) {
        val archive = readArchive(input, DataBackupService.SCOPE_SITE, execution)
        try {
            validateArchive(archive, execution)
            prepareMigration(archive, execution)
            if (archive.migration) validateArchive(archive, execution)
            val tables = archive.tables
            restoreTables(tables, archive.sequences, execution)
            swapInImportedFiles(archive, execution)
            searchChanges?.requestRebuild()
            logger.info("사이트 전체 복원 완료: 테이블 ${tables.size}개")
        } finally {
            archive.tempRoot.deleteRecursively()
        }
    }

    @Transactional
    override fun importProject(input: InputStream, execution: DataBackupExecution?): String? {
        val archive = readArchive(input, DataBackupService.SCOPE_PROJECT, execution)
        try {
            validateArchive(archive, execution)
            val tables = archive.tables.filterKeys { !isOperationalTable(it) }
            val projectFile = tables["project"] ?: throw BadBackupArchiveException("프로젝트 백업에 project 행이 없습니다")
            val projectName = firstNdjsonRow(projectFile)?.value("name")?.toString()
                ?: throw BadBackupArchiveException("프로젝트 백업의 project 행에 name이 없습니다")

            refuseIfQueueBusy(execution)
            execution?.stage("import.database")
            val nameExists = (jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project WHERE name = ?", Long::class.java, projectName
            ) ?: 0L) > 0
            val effectiveName = if (nameExists) {
                var suffix = Instant.now().toEpochMilli()
                var candidate: String
                do {
                    val postfix = "-imported-${suffix++}"
                    candidate = projectName.take((250 - postfix.length).coerceAtLeast(1)) + postfix
                } while ((jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM project WHERE name = ?", Long::class.java, candidate
                    ) ?: 0L) > 0)
                logger.info("같은 이름의 프로젝트가 있어 '$projectName'를 '$candidate' 이름으로 가져옵니다")
                candidate
            } else projectName
            archive.project = "${archive.project?.substringBefore('/')}/$effectiveName"

            val metadata = dataSource.connection.use { it.metaData }
            val fks = foreignKeys(metadata, tables.keys)
            val referenceTables = sharedReferenceTables
            val offsets = HashMap<String, Long>()
            val reservedNext = HashMap<String, Long>()
            val referenceIds = HashMap<String, HashMap<String, Any?>>()
            // ponytail: only referenced shared identities remain in RAM; use a DB staging table if contributor count grows unusually large.
            val dialect = detectDialect()

            // Cursor-scan numeric PKs once. A constant table offset removes the O(rows) old→new map.
            for ((table, file) in tables) {
                if (table in referenceTables) continue
                val pk = primaryKeyColumns(metadata, table)
                if (pk.size != 1) continue
                if (!isNumericColumn(metadata, table, pk[0])) {
                    throw BadBackupArchiveException("프로젝트 테이블 $table 에 지원하지 않는 단일 비숫자 PK가 있습니다")
                }
                // A dependent table whose PK is also an FK must inherit its parent's offset.
                if (fks[table].orEmpty().any { fk -> fk.fkColumns.any { it.equals(pk[0], true) } }) continue
                var minimum: Long? = null
                var maximum: Long? = null
                forEachNdjsonRow(file, execution) { row ->
                    val id = row.value(pk[0])?.toString()?.toLongOrNull()
                        ?: throw BadBackupArchiveException("$table.${pk[0]} PK가 정수가 아닙니다")
                    minimum = minimum?.coerceAtMost(id) ?: id
                    maximum = maximum?.coerceAtLeast(id) ?: id
                }
                if (minimum != null) {
                    val targetMax = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(${pk[0]}), 0) FROM $table", Long::class.java
                    ) ?: 0L
                    val nextSequence = dataSource.connection.use { nextSequenceValue(it, table, dialect) }
                    val highWater = maxOf(targetMax, (nextSequence ?: Math.addExact(targetMax, 1L) - 1L))
                    val offset = Math.addExact(Math.subtractExact(highWater, minimum!!), 1L)
                    val next = Math.addExact(Math.addExact(maximum!!, offset), 1L)
                    offsets[table] = offset
                    reservedNext[table] = next
                }
            }
            // Natural-key references are the only old→new id map; it scales with project contributors,
            // not with the number of issues/comments. Numeric project-owned ids use table offsets.
            doInForeignKeysOff(dialect) {
                execution?.beginMutation()
                for (table in listOf("role", "organization", "n4user")) {
                    val file = tables[table] ?: continue
                    val pk = primaryKeyColumns(metadata, table)
                    if (pk.size != 1 || !isNumericColumn(metadata, table, pk[0])) {
                        throw BadBackupArchiveException("${table}에는 숫자 단일 PK가 필요합니다")
                    }
                    val naturalKey = if (table == "n4user") "login_id" else "name"
                    val targetMax = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(${pk[0]}), 0) FROM $table", Long::class.java
                    ) ?: 0L
                    val nextSequence = if (table == "role") null else
                        dataSource.connection.use { nextSequenceValue(it, table, dialect) }
                    var nextId = maxOf(targetMax, (nextSequence ?: Math.addExact(targetMax, 1L) - 1L))
                    val idMap = referenceIds.getOrPut(table) { HashMap() }
                    forEachNdjsonRow(file, execution) { row ->
                        val oldId = row.value(pk[0])?.toString()
                            ?: throw BadBackupArchiveException("$table PK가 없습니다")
                        val naturalValue = row.value(naturalKey)?.toString()
                            ?: throw BadBackupArchiveException("$table.${naturalKey}가 없습니다")
                        val existing = jdbcTemplate.queryForList(
                            "SELECT ${pk[0]} FROM $table WHERE LOWER($naturalKey) = ?", naturalValue.lowercase()
                        ).firstOrNull()
                        if (existing != null) {
                            idMap[oldId] = (existing.values.first() as Number).toLong()
                        } else {
                            nextId = Math.addExact(nextId, 1L)
                            val rowToInsert = HashMap(row).apply { putValue(pk[0], nextId) }
                            insertRow(table, rowToInsert, columnTypes(table))
                            idMap[oldId] = nextId
                        }
                    }
                }

                // Pass 2: each NDJSON row is read, remapped and inserted before moving to the next row.
                for ((table, file) in tables) {
                    if (table in referenceTables) continue
                    val dateTimes = columnTypes(table)
                    val tableFks = fks[table].orEmpty()
                    val pk = primaryKeyColumns(metadata, table)
                    forEachNdjsonRow(file, execution) { row ->
                        val remapped = HashMap(row)
                        for (fk in tableFks) {
                            for (column in fk.fkColumns) {
                                val old = remapped.value(column) ?: continue
                                remapped.putValue(column, remapProjectId(fk.pkTable, old, referenceIds, offsets, true))
                            }
                        }
                        for ((column, value) in remapped.toMap()) {
                            val lower = column.lowercase()
                            val userReference = lower == "user_id" || lower.endsWith("_user_id") ||
                                lower == "author_id" || lower.endsWith("_author_id")
                            if (userReference && value is Number && tableFks.none { fk -> fk.fkColumns.any { it.equals(column, true) } }) {
                                remapped.putValue(column, remapProjectId("n4user", value, referenceIds, offsets, true))
                            }
                        }
                        if (table == "project") remapped.putValue("name", effectiveName)
                        if (pk.size == 1) offsets[table]?.let { offset ->
                            val old = remapped.value(pk[0])?.toString()?.toLongOrNull()
                                ?: throw BadBackupArchiveException("$table.${pk[0]} PK가 정수가 아닙니다")
                            remapped.putValue(pk[0], Math.addExact(old, offset))
                        }
                        if (table == "attachment") remapAttachmentContainer(remapped, referenceIds, offsets)
                        insertRow(table, remapped, dateTimes)
                    }
                }

                // Explicit inserts do not advance every dialect's generated-id sequence.
                for ((table, reserved) in reservedNext) {
                    val pk = primaryKeyColumns(metadata, table).single()
                    val afterInsert = (jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX($pk), 0) FROM $table", Long::class.java
                    ) ?: 0L) + 1
                    restoreSequence(table, pk, dialect, maxOf(reserved, afterInsert))
                }
                for (table in listOf("n4user", "organization")) {
                    if (tables.containsKey(table)) {
                        val pk = primaryKeyColumns(metadata, table).single()
                        val next = (jdbcTemplate.queryForObject(
                            "SELECT COALESCE(MAX($pk), 0) FROM $table", Long::class.java
                        ) ?: 0L) + 1
                        restoreSequence(table, pk, dialect, next)
                    }
                }
            }
            swapInImportedFiles(archive, execution)
            searchChanges?.requestRebuild()
            logger.info("프로젝트 가져오기 완료: 테이블 ${tables.size}개")
            return archive.project
        } finally {
            archive.tempRoot.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 아카이브 읽기

    private data class ArchiveFile(val entryName: String, val target: File)

    private data class Archive(
        val scope: String,
        val originalProject: String?,
        var project: String?,
        val tables: LinkedHashMap<String, File>,
        val sequences: MutableMap<String, Long>,
        val manifest: Map<String, Any?>,
        val migration: Boolean,
        val files: List<ArchiveFile>,
        val tempRoot: File,
        val entries: File,
        val integrity: File,
    )

    private fun readArchive(input: InputStream, expectedScope: String, execution: DataBackupExecution?): Archive {
        val tempRoot = createTempDirectory()
        try {
            return readArchiveContents(input, expectedScope, execution, tempRoot)
        } catch (failure: Throwable) {
            tempRoot.deleteRecursively()
            throw failure
        }
    }

    private fun readArchiveContents(
        input: InputStream, expectedScope: String, execution: DataBackupExecution?, tempRoot: File,
    ): Archive {
        execution?.stage("import.extract")
        val tables = LinkedHashMap<String, File>()
        val files = mutableListOf<ArchiveFile>()
        val entries = File(tempRoot, "_entries.ndjson")
        val integrity = File(tempRoot, "_integrity.ndjson")
        val seen = HashSet<String>()
        lateinit var manifest: Map<String, Any?>
        ZipInputStream(input).use { zip ->
            val first = zip.nextEntry ?: throw BadBackupArchiveException("Missing manifest")
            if (first.name != "manifest.json" || first.isDirectory) throw BadBackupArchiveException("Manifest must be first")
            manifest = readJsonObject(boundedBytes(zip, 65_536))
            validateManifest(manifest, expectedScope)
            seen.add(first.name)
            zip.closeEntry()
            entries.bufferedWriter(Charsets.UTF_8).use { index ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    execution?.checkpoint()
                    val name = entry.name
                    validateEntryPath(name)
                    if (!seen.add(name)) throw BadBackupArchiveException("Duplicate archive entry")
                    val target = when {
                        name == "integrity.ndjson" && !entry.isDirectory -> integrity
                        name == ORPHAN_MENU_ENTRY && !entry.isDirectory && manifest.containsKey("migrationProvenance") ->
                            File(tempRoot, "_orphan-project-menu-settings.ndjson")
                        name == "db/_sequences.json" && !entry.isDirectory -> File(tempRoot, "_sequences.json")
                        name.matches(Regex("db/[a-z][a-z0-9_]*\\.ndjson")) && !entry.isDirectory -> {
                            val table = name.removePrefix("db/").removeSuffix(".ndjson")
                            if (isOperationalTable(table)) throw BadBackupArchiveException("Queue tables are operational state")
                            File(tempRoot, name).also { tables[table] = it }
                        }
                        name.startsWith("files/") -> {
                            validateFilePath(name, manifest)
                            File(tempRoot, name.removePrefix("files/")).also {
                                if (!entry.isDirectory) files.add(ArchiveFile(name.removePrefix("files/"), it))
                            }
                        }
                        else -> throw BadBackupArchiveException("Unsupported archive entry")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val measured = object : java.io.FilterInputStream(DigestInputStream(zip, digest)) {
                        override fun read(): Int = super.read().also { if (it >= 0) size++ }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                            `in`.read(bytes, offset, length).also { if (it > 0) size += it }
                    }
                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) throw BadBackupArchiveException("Conflicting directory")
                        copyArchiveBytes(measured, OutputStream.nullOutputStream(), execution)
                        if (size != 0L) throw BadBackupArchiveException("Directory contains data")
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { copyArchiveBytes(measured, it, execution) }
                    }
                    if (name != "integrity.ndjson") {
                        index.write(objectMapper.writeValueAsString(mapOf("path" to name, "size" to size,
                            "sha256" to HexFormat.of().formatHex(digest.digest()))))
                        index.newLine()
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!integrity.isFile) throw BadBackupArchiveException("Missing integrity index")
        if (manifest.containsKey("migrationProvenance") && ORPHAN_MENU_ENTRY !in seen) throw BadBackupArchiveException("Missing declared migration provenance")
        val sequenceFile = File(tempRoot, "_sequences.json")
        val sequences = if (sequenceFile.isFile) sequenceFile.inputStream().use {
            readJsonObject(boundedBytes(it, 1_048_576)).mapValues { (_, value) ->
                val number = value as? Number ?: throw BadBackupArchiveException("Invalid sequence")
                val next = number.toLong()
                if (number.toString() != next.toString() || next < 1) throw BadBackupArchiveException("Invalid sequence")
                next
            }
        } else emptyMap()
        val scope = manifest.getValue("scope").toString()
        val project = manifest["project"] as? String
        return Archive(scope, project, project, tables, sequences.toMutableMap(), manifest,
            (manifest["requiredCapabilities"] as List<*>).isNotEmpty(), files, tempRoot, entries, integrity)
    }

    private fun readJsonObject(bytes: ByteArray): Map<String, Any?> =
        archiveJson.readValue(bytes, Map::class.java) as Map<String, Any?>

    private fun boundedBytes(input: InputStream, maximum: Int): ByteArray =
        input.readNBytes(maximum + 1).also {
            if (it.size > maximum) throw BadBackupArchiveException("Archive metadata is too large")
        }

    private fun validateManifest(manifest: Map<String, Any?>, scope: String) {
        if (manifest["format"] != DataBackupService.FORMAT ||
            manifest["formatVersion"] != DataBackupService.FORMAT_VERSION ||
            manifest["targetVersion"] != DataBackupService.TARGET_VERSION ||
            manifest["integrity"] != DataBackupService.INTEGRITY || manifest["scope"] != scope
        ) throw BadBackupArchiveException("Unsupported archive format, target version, integrity, or scope")
        val capabilities = manifest["requiredCapabilities"] as? List<*>
            ?: throw BadBackupArchiveException("Missing requiredCapabilities")
        if (capabilities != emptyList<String>() && capabilities != listOf(DataBackupService.LEGACY_CREDENTIALS)) {
            throw BadBackupArchiveException("Unsupported required capability")
        }
        val createdAt = manifest["createdAt"] as? String ?: throw BadBackupArchiveException("Missing createdAt")
        if (!createdAt.endsWith('Z') || !manifest.containsKey("project")) throw BadBackupArchiveException("Invalid manifest metadata")
        try { Instant.parse(createdAt) }
        catch (_: DateTimeParseException) { throw BadBackupArchiveException("Invalid createdAt") }
        if ((manifest["producer"] as? String).isNullOrBlank() || (manifest["producerVersion"] as? String).isNullOrBlank()) {
            throw BadBackupArchiveException("Missing producer metadata")
        }
        if (scope == DataBackupService.SCOPE_SITE && manifest["project"] != null) throw BadBackupArchiveException("Invalid site project")
        if (scope == DataBackupService.SCOPE_PROJECT) {
            val project = manifest["project"] as? String ?: throw BadBackupArchiveException("Missing project")
            validateEntryPath(project)
            if (project.split('/').size != 2) throw BadBackupArchiveException("Invalid project")
        }
        if (capabilities.isNotEmpty()) {
            if (scope != DataBackupService.SCOPE_SITE || manifest["sourceVersion"] != "1.16" ||
                manifest["producer"] != "yona2-migrator" || manifest["producerVersion"] != "0.1.0" ||
                manifest["bootstrapSourceLogin"] != "admin"
            ) throw BadBackupArchiveException("Invalid migration marker")
            val targetMode = if (!ldapEnabled) "local" else if (ldapFallback) "ldap-fallback" else "ldap-only"
            if (manifest["sourceAuthMode"] != targetMode) throw BadBackupArchiveException("Incompatible authentication mode")
            if (manifest.containsKey("migrationProvenance")) {
                val expected = mapOf("format" to "yona-legacy-provenance", "formatVersion" to 1, "entries" to listOf(ORPHAN_MENU_ENTRY))
                if (manifest["migrationProvenance"] != expected) throw BadBackupArchiveException("Unsupported migration provenance")
            }
        } else if (manifest["sourceVersion"] != DataBackupService.TARGET_VERSION ||
            manifest.containsKey("sourceAuthMode") || manifest.containsKey("bootstrapSourceLogin") || manifest.containsKey("migrationProvenance")
        ) throw BadBackupArchiveException("Unsupported source version or undeclared migration")
    }

    private fun validateEntryPath(path: String) {
        val segments = path.removeSuffix("/").split('/')
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > 4096 ||
            segments.any { it.isEmpty() || it == "." || it == ".." || it.contains(':') ||
                it.any { character -> character == '\\' || character.code < 32 || character.code == 127 } }
        ) throw BadBackupArchiveException("Unsafe archive path")
    }

    private fun validateFilePath(path: String, manifest: Map<String, Any?>) {
        val relative = path.removePrefix("files/").removeSuffix("/")
        val root = relative.substringBefore('/')
        if (root !in setOf("git", "svn", "lfs", "uploads", "data")) throw BadBackupArchiveException("Unsupported file root")
        if (root == "data") {
            val child = relative.substringAfter('/', "")
            val data = applicationDataDir.canonicalFile.toPath()
            val destination = data.resolve(child).normalize()
            val queue = queueDataDir.canonicalFile.toPath()
            if (destination.startsWith(data.resolve("h2")) || destination.startsWith(queue) || destination.startsWith(searchIndexDir.canonicalFile.toPath())
            ) throw BadBackupArchiveException("Protected operational file")
            if ((manifest["requiredCapabilities"] as List<*>).isNotEmpty()) throw BadBackupArchiveException("Migration cannot restore application data")
        }
        if (manifest["scope"] == DataBackupService.SCOPE_PROJECT) {
            val project = manifest["project"].toString()
            if (root == "data" || root == "git" && !(relative.startsWith("git/$project.git/") ||
                    relative.startsWith("git/$project.wiki.git/")) ||
                root in setOf("lfs", "svn") && !relative.startsWith("$root/$project/")
            ) throw BadBackupArchiveException("File does not belong to the selected project")
        }
    }
    private val migrationExcludedTables = setOf(
        "api_token", "api_token_project", "api_token_scope", "user_known_device", "user_setting", "user_backup_code",
        "user_totp_credential", "user_verification", "user_webauthn_credential",
        "oauth_authorization", "oauth_authorization_consent", "oauth_registered_client",
        "saml2_sso_settings", "oidc_sso_settings", "audit_log", "property",
        "spring_session", "spring_session_attributes",
    )
    private val bootstrapSecurityTables = setOf("email", "ssh_key", "linked_account")

    private fun prepareMigration(archive: Archive, execution: DataBackupExecution?) {
        if (!archive.migration) return
        val users = archive.tables["n4user"] ?: throw BadBackupArchiveException("Migration needs users")
        val target = jdbcTemplate.queryForList("SELECT * FROM n4user").singleOrNull()
            ?: throw BadBackupArchiveException("Migration requires only the bootstrap administrator")
        if (target.value("login_id") != "admin" || target.value("state") != "SITE_ADMIN" ||
            target.value("password") == null
        ) throw BadBackupArchiveException("Migration requires a configured bootstrap administrator")
        val targetId = (target.value("id") as Number).toLong()
        val retainedSecurity = linkedMapOf<String, List<Map<String, Any?>>>()
        dataSource.connection.use { connection ->
            for (table in listTables(connection)) {
                val name = table.lowercase()
                if (isOperationalTable(name) || name in migrationExcludedTables || name in setOf("n4user", "role")) continue
                if (name in bootstrapSecurityTables) {
                    if ((jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id<>? OR user_id IS NULL", Long::class.java, targetId) ?: 0L) != 0L) {
                        throw BadBackupArchiveException("Migration target contains another account's security records")
                    }
                    retainedSecurity[name] = jdbcTemplate.queryForList("SELECT * FROM $table")
                    continue
                }
                if ((jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java) ?: 0L) != 0L) {
                    throw BadBackupArchiveException("Migration requires a fresh target without content")
                }
            }
        }
        if (archive.tables.keys.any { it in migrationExcludedTables }) throw BadBackupArchiveException("Migration contains security or configuration rows")
        var sourceAdmin: Long? = null
        var maximum = targetId
        var targetCollision = false
        forEachNdjsonRow(users, execution) { row ->
            val id = (row.value("id") as? Number)?.toLong() ?: throw BadBackupArchiveException("Invalid user ID")
            maximum = maxOf(maximum, id)
            if (row.value("login_id")?.toString()?.equals("admin", true) == true) {
                if (sourceAdmin != null || row.value("login_id") != "admin") throw BadBackupArchiveException("Ambiguous source administrator")
                sourceAdmin = id
            } else if (id == targetId) targetCollision = true
        }
        val adminId = sourceAdmin ?: throw BadBackupArchiveException("Missing source administrator")
        val remapping = hashMapOf(adminId to targetId)
        if (targetCollision) remapping[targetId] = Math.addExact(maximum, 1)
        val fks = dataSource.connection.use { foreignKeys(it.metaData, archive.tables.keys) }
        for ((table, file) in archive.tables) {
            val replacement = File(file.parentFile, "${file.name}.mapped")
            replacement.bufferedWriter(Charsets.UTF_8).use { output ->
                forEachNdjsonRow(file, execution) { source ->
                    if (table in bootstrapSecurityTables && source.value("user_id")?.toString()?.toLongOrNull() == adminId) return@forEachNdjsonRow
                    // Legacy membership lookups exclude virtual site-manager rows; they carry no project grant.
                    if (table == "project_user" && (source.value("role_id") as? Number)?.toLong() == RoleType.SITEMANAGER.roleType) return@forEachNdjsonRow
                    val row = HashMap(source)
                    if (table == "n4user" && (row.value("id") as Number).toLong() == adminId) {
                        row.clear()
                        row.putAll(target.mapValues { archiveValue(it.value) })
                    } else {
                        val userColumns = fks[table].orEmpty().filter { it.pkTable == "n4user" }.flatMap { it.fkColumns }.map { it.lowercase() }.toSet()
                        for ((column, value) in row.toMap()) {
                            if (value != null && (column.lowercase() in userColumns || value is Number && isUserReference(column) || table == "n4user" && column.equals("id", true))) {
                                value.toString().toLongOrNull()?.let { id -> remapping[id]?.let { row.putValue(column, it) } }
                            }
                        }
                        if (table == "attachment" && row.value("container_type")?.toString()?.lowercase() in setOf("user", "user_avatar")) {
                            row.value("container_id")?.toString()?.toLongOrNull()?.let { id ->
                                remapping[id]?.let { row.putValue("container_id", it.toString()) }
                            }
                        }
                        if (table == "n4user") {
                            if (row.value("state") == "SITE_ADMIN") row.putValue("state", "ACTIVE")
                            for ((column, value) in mapOf("token" to null, "remember_me" to false,
                                "failed_login_attempts" to 0, "locked_until" to null, "is_two_factor_enabled" to false)
                            ) if (row.keys.any { it.equals(column, true) }) row.putValue(column, value)
                            if (archive.manifest["sourceAuthMode"] == "ldap-only") {
                                row.putValue("password", null)
                                row.putValue("password_salt", null)
                            } else {
                                val hash = row.value("password") as? String
                                if (hash != null && !hash.matches(Regex("[A-Za-z0-9+/]{43}="))) {
                                    throw BadBackupArchiveException("Invalid legacy credential encoding")
                                }
                            }
                        }
                    }
                    output.write(objectMapper.writeValueAsString(row))
                    output.newLine()
                }
            }
            if (!file.delete() || !replacement.renameTo(file)) throw IllegalStateException("Cannot prepare migration rows")
        }
        if (targetCollision) archive.sequences["n4user"] = maxOf(archive.sequences["n4user"] ?: 1L, Math.addExact(maximum, 2L))
        for ((table, retained) in retainedSecurity) {
            if (retained.isEmpty()) continue
            val file = archive.tables.getOrPut(table) { File(archive.tempRoot, "db/$table.ndjson").also { it.parentFile.mkdirs(); it.createNewFile() } }
            val reserved = retained.map { (it.value("id") as Number).toLong() }.toSet()
            var highWater = reserved.max()
            forEachNdjsonRow(file, execution) { row -> highWater = maxOf(highWater, (row.value("id") as Number).toLong()) }
            val replacement = File(file.parentFile, "${file.name}.security")
            replacement.bufferedWriter(Charsets.UTF_8).use { output ->
                forEachNdjsonRow(file, execution) { source ->
                    val row = HashMap(source)
                    if ((row.value("id") as Number).toLong() in reserved) row.putValue("id", Math.addExact(highWater, 1).also { highWater = it })
                    output.write(objectMapper.writeValueAsString(row))
                    output.newLine()
                }
                for (row in retained) {
                    output.write(objectMapper.writeValueAsString(row.mapValues { archiveValue(it.value) }))
                    output.newLine()
                }
            }
            if (!file.delete() || !replacement.renameTo(file)) throw IllegalStateException("Cannot preserve bootstrap security")
            archive.sequences[table] = maxOf(archive.sequences[table] ?: 1L, Math.addExact(highWater, 1))
        }
    }

    private data class Column(val name: String, val type: Int, val size: Int, val scale: Int,
        val nullable: Boolean, val defaulted: Boolean, val generated: Boolean, val allowedValues: Set<String> = emptySet())

    private fun columns(connection: Connection, table: String): List<Column> {
        val result = mutableListOf<Column>()
        connection.metaData.getColumns(connection.catalog, schemaOf(connection), table, "%").use { rs ->
            while (rs.next()) result.add(Column(rs.getString("COLUMN_NAME").lowercase(), rs.getInt("DATA_TYPE"),
                rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                rs.getString("COLUMN_DEF") != null, rs.getString("IS_AUTOINCREMENT") == "YES"))
        }
        if (connection.metaData.databaseProductName.let { it.contains("MariaDB", true) || it.contains("MySQL", true) }) {
            connection.prepareStatement("SELECT COLUMN_NAME,COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND DATA_TYPE='enum'").use { statement ->
                statement.setString(1, connection.catalog)
                statement.setString(2, table)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1).lowercase()
                        val values = Regex("'((?:''|[^'])*)'").findAll(rs.getString(2)).map { it.groupValues[1].replace("''", "'") }.toSet()
                        val index = result.indexOfFirst { it.name == name }
                        if (index >= 0) result[index] = result[index].copy(allowedValues = values)
                    }
                }
            }
        }
        return result
    }

    private fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun staged(table: String) = quoted("data_$table")
    private fun isUserReference(column: String): Boolean {
        val name = column.lowercase()
        return name == "user_id" || name.endsWith("_user_id") || name == "author_id" || name.endsWith("_author_id")
    }

    /** Use the installed H2 driver and a disk database; never load the archive's row graph into RAM. */
    private fun validateArchive(archive: Archive, execution: DataBackupExecution?) {
        execution?.stage("import.preflight")
        validateRestoreDestinations(archive)
        val path = File(archive.tempRoot, "_validation-${java.util.UUID.randomUUID()}").absolutePath
        DriverManager.getConnection("jdbc:h2:$path;CACHE_SIZE=8192;MAX_MEMORY_ROWS=1000", "sa", "").use { staging ->
            staging.createStatement().use { sql ->
                sql.execute("CREATE TABLE actual_entries(path VARCHAR(4096) PRIMARY KEY, size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL)")
                sql.execute("CREATE TABLE declared_entries(path VARCHAR(4096) PRIMARY KEY, size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL)")
            }
            for ((name, file) in listOf("actual_entries" to archive.entries, "declared_entries" to archive.integrity)) {
                staging.prepareStatement("INSERT INTO $name VALUES (?,?,?)").use { statement ->
                    forEachNdjsonRow(file, execution) { row ->
                        if (row.keys != setOf("path", "size", "sha256") || row["path"] !is String ||
                            row["size"] !is Number || !row["sha256"].toString().matches(Regex("[0-9a-f]{64}"))
                        ) throw BadBackupArchiveException("Invalid integrity record")
                        val size = (row.getValue("size") as Number).toLong()
                        if (size < 0 || size.toString() != row["size"].toString()) throw BadBackupArchiveException("Invalid integrity size")
                        statement.setString(1, row["path"].toString())
                        statement.setLong(2, size)
                        statement.setString(3, row["sha256"].toString())
                        statement.executeUpdate()
                    }
                }
            }
            staging.createStatement().use { sql ->
                sql.executeQuery("SELECT (SELECT COUNT(*) FROM actual_entries a LEFT JOIN declared_entries d ON a.path=d.path WHERE d.path IS NULL OR a.size<>d.size OR a.sha256<>d.sha256) + (SELECT COUNT(*) FROM declared_entries d WHERE NOT EXISTS (SELECT 1 FROM actual_entries a WHERE a.path=d.path))").use { rs ->
                    rs.next()
                    if (rs.getLong(1) != 0L) throw BadBackupArchiveException("Archive entry integrity mismatch")
                }
            }
            dataSource.connection.use { target ->
                val names = listTables(target).associateBy { it.lowercase() }
                val schema = archive.tables.keys.associateWith { table ->
                    val actual = names[table] ?: throw BadBackupArchiveException("Unknown database table")
                    if (isOperationalTable(table) || archive.migration && table in migrationExcludedTables ||
                        archive.scope == DataBackupService.SCOPE_PROJECT && table in terminalTables && table !in sharedReferenceTables
                    ) throw BadBackupArchiveException("Forbidden database table")
                    columns(target, actual)
                }
                for ((table, file) in archive.tables) {
                    val definitions = schema.getValue(table)
                    if (definitions.isEmpty()) throw BadBackupArchiveException("Missing database column metadata")
                    val pk = primaryKeyColumns(target.metaData, names.getValue(table)).map { it.lowercase() }
                    val columnSql = definitions.joinToString(",") { "${quoted(it.name)} ${stagingType(it)}" }
                    staging.createStatement().use { sql ->
                        sql.execute("CREATE TABLE ${staged(table)} ($columnSql${if (pk.isEmpty()) "" else ", PRIMARY KEY (${pk.joinToString(",", transform = ::quoted)})"})")
                    }
                    forEachNdjsonRow(file, execution) { row ->
                        val lower = row.mapKeys { it.key.lowercase() }
                        if (lower.size != row.size || lower.keys.any { key -> definitions.none { it.name == key } }) {
                            throw BadBackupArchiveException("Unknown or duplicate database column")
                        }
                        if (archive.scope == DataBackupService.SCOPE_PROJECT && table == "n4user") {
                            val allowed = projectUserReference(row).keys.map { it.lowercase() }.toSet()
                            if (lower.keys.any { it !in allowed } || lower["state"] != "ACTIVE") {
                                throw BadBackupArchiveException("Project user references must not contain credentials or administrative state")
                            }
                        }
                        for (column in definitions) {
                            val value = lower[column.name]
                            if (!column.nullable && value == null && (lower.containsKey(column.name) || !column.defaulted)) {
                                throw BadBackupArchiveException("Missing required database value")
                            }
                            validateColumnValue(column, value)
                        }
                        val keys = lower.keys.toList()
                        if (keys.isEmpty()) throw BadBackupArchiveException("Empty database row")
                        staging.prepareStatement("INSERT INTO ${staged(table)} (${keys.joinToString(",", transform = ::quoted)}) VALUES (${keys.joinToString(",") { "?" }})").use { statement ->
                            keys.forEachIndexed { index, key ->
                                val column = definitions.first { it.name == key }
                                statement.setObject(index + 1, coerceColumnValue(lower[key], column.type))
                            }
                            statement.executeUpdate()
                        }
                    }
                    target.metaData.getIndexInfo(target.catalog, schemaOf(target), names.getValue(table), true, false).use { rs ->
                        val indexes = linkedMapOf<String, MutableList<Pair<Int, String>>>()
                        while (rs.next()) {
                            val column = rs.getString("COLUMN_NAME") ?: continue
                            indexes.getOrPut(rs.getString("INDEX_NAME") ?: continue) { mutableListOf() }
                                .add(rs.getInt("ORDINAL_POSITION") to column.lowercase())
                        }
                        for ((index, parts) in indexes.values.withIndex()) {
                            val keys = parts.sortedBy { it.first }.map { quoted(it.second) }.joinToString(",")
                            staging.createStatement().use { it.execute("CREATE UNIQUE INDEX ${quoted("unique_${table}_$index")} ON ${staged(table)} ($keys)") }
                        }
                    }
                }
                val fks = foreignKeys(target.metaData, archive.tables.keys)
                for ((table, definitions) in schema) {
                    for (fk in fks[table].orEmpty()) {
                        validateReference(staging, archive, table, fk.fkColumns, fk.pkTable, fk.pkColumns)
                    }
                    if (table != "n4user") {
                        for (column in definitions) {
                            if (!isUserReference(column.name) || column.type != Types.TINYINT && column.type != Types.SMALLINT &&
                                column.type != Types.INTEGER && column.type != Types.BIGINT) continue
                            if (fks[table].orEmpty().none { fk -> column.name in fk.fkColumns.map { it.lowercase() } }) {
                                validateReference(staging, archive, table, listOf(column.name), "n4user", listOf("id"))
                            }
                        }
                    }
                }
                if (!archive.migration) validateRetainedReferences(target, staging, archive, names)
                for ((table, next) in archive.sequences) {
                    if (table !in schema) throw BadBackupArchiveException("Sequence names an absent table")
                    val pk = primaryKeyColumns(target.metaData, names.getValue(table))
                    val identity = schema.getValue(table).singleOrNull { it.generated }
                    if (identity == null || pk.size != 1 || !identity.name.equals(pk.single(), true)) throw BadBackupArchiveException("Sequence is not an identity")
                    staging.createStatement().use { sql ->
                        sql.executeQuery("SELECT MAX(${quoted(identity.name)}) FROM ${staged(table)}").use { rs ->
                            rs.next()
                            if (next <= rs.getLong(1)) throw BadBackupArchiveException("Sequence does not exceed restored IDs")
                        }
                    }
                }
                verifyContentFiles(staging, archive, execution)
                validateProvenance(staging, archive, execution)
            }
        }
    }
    private fun validateRetainedReferences(target: Connection, staging: Connection, archive: Archive, names: Map<String, String>) {
        val retained = names.keys.filter { it !in archive.tables && !isOperationalTable(it) }
        val fks = foreignKeys(target.metaData, retained)
        for ((table, references) in fks) {
            for (fk in references.filter { it.pkTable in archive.tables }) {
                val where = fk.fkColumns.joinToString(" AND ") { "$it IS NOT NULL" }
                target.createStatement().use { rows ->
                    rows.fetchSize = STREAM_FETCH_SIZE
                    rows.executeQuery("SELECT ${fk.fkColumns.joinToString(",")} FROM ${names.getValue(table)} WHERE $where").use { rs ->
                        val predicate = fk.pkColumns.joinToString(" AND ") { "${quoted(it.lowercase())}=?" }
                        staging.prepareStatement("SELECT COUNT(*) FROM ${staged(fk.pkTable)} WHERE $predicate").use { lookup ->
                            while (rs.next()) {
                                fk.fkColumns.indices.forEach { lookup.setObject(it + 1, rs.getObject(it + 1)) }
                                lookup.executeQuery().use { result ->
                                    result.next()
                                    if (result.getLong(1) != 1L) throw BadBackupArchiveException("Restore would orphan a retained target row")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stagingType(column: Column): String = when (column.type) {
        Types.BOOLEAN, Types.BIT -> "BOOLEAN"
        Types.TINYINT -> "TINYINT"
        Types.SMALLINT -> "SMALLINT"
        Types.INTEGER -> "INTEGER"
        Types.BIGINT -> "BIGINT"
        Types.NUMERIC, Types.DECIMAL -> "DECIMAL(${column.size.coerceIn(1, 100000)},${column.scale.coerceAtLeast(0)})"
        Types.FLOAT, Types.REAL, Types.DOUBLE -> "DOUBLE PRECISION"
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB"
        Types.DATE -> "DATE"
        Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME"
        Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP"
        else -> "VARCHAR(${column.size.coerceIn(1, 1_000_000_000)})"
    }

    private fun isDateTime(type: Int) = type in setOf(Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE)

    private fun validateColumnValue(column: Column, value: Any?) {
        if (value == null) return
        if (value is Map<*, *> || value is List<*>) throw BadBackupArchiveException("Nested database value")
        if (column.allowedValues.isNotEmpty() && value.toString() !in column.allowedValues) throw BadBackupArchiveException("Invalid enumeration value")
        when (column.type) {
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                if (value !is Number || value.toString().toLongOrNull() == null) throw BadBackupArchiveException("Invalid integer")
            Types.BOOLEAN, Types.BIT ->
                if (value !is Boolean && value !in listOf(0, 1)) throw BadBackupArchiveException("Invalid boolean")
        }
    }
    private fun validateRestoreDestinations(archive: Archive) {
        val queue = queueDataDir.canonicalFile.toPath()
        val database = File(applicationDataDir, "h2").canonicalFile.toPath()
        for ((name, root) in mapOf("git" to gitBaseDir, "svn" to svnBaseDir, "lfs" to lfsBaseDir, "uploads" to uploadBaseDir)) {
            if (!File(archive.tempRoot, name).exists()) continue
            val path = root.canonicalFile.toPath()
            if (queue.startsWith(path) || path.startsWith(queue) || database.startsWith(path) || path.startsWith(database) ||
                searchIndexDir.canonicalFile.toPath().startsWith(path) || path.startsWith(searchIndexDir.canonicalFile.toPath())) {
                throw BadBackupArchiveException("Restore destination overlaps operational storage")
            }
        }
        if (archive.scope == DataBackupService.SCOPE_PROJECT) {
            for (file in archive.files.filter { it.entryName.startsWith("uploads/") }) {
                val destination = File(uploadBaseDir, file.entryName.removePrefix("uploads/"))
                if (java.nio.file.Files.isSymbolicLink(destination.toPath()) || destination.exists() &&
                    (!destination.isFile || destination.length() != file.target.length() || fileDigest(destination) != fileDigest(file.target))
                ) throw BadBackupArchiveException("Existing upload does not match archived content")
            }
        }
    }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { copyArchiveBytes(DigestInputStream(it, digest), OutputStream.nullOutputStream(), null) }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun validateReference(staging: Connection, archive: Archive, table: String, columns: List<String>, parent: String, parentColumns: List<String>) {
        val nonNull = columns.joinToString(" AND ") { "c.${quoted(it.lowercase())} IS NOT NULL" }
        val predicate = columns.zip(parentColumns).joinToString(" AND ") { (child, pk) ->
            "c.${quoted(child.lowercase())}=p.${quoted(pk.lowercase())}"
        }
        val query = if (parent in archive.tables) {
            "SELECT COUNT(*) FROM ${staged(table)} c WHERE $nonNull AND NOT EXISTS (SELECT 1 FROM ${staged(parent)} p WHERE $predicate)"
        } else {
            "SELECT COUNT(*) FROM ${staged(table)} c WHERE $nonNull"
        }
        staging.createStatement().use { sql ->
            sql.executeQuery(query).use { rs ->
                rs.next()
                if (rs.getLong(1) != 0L) throw BadBackupArchiveException("Unresolved database reference")
            }
        }
    }

    private fun validateProvenance(staging: Connection, archive: Archive, execution: DataBackupExecution?) {
        val file = File(archive.tempRoot, "_orphan-project-menu-settings.ndjson")
        if (!file.isFile) return
        val flags = listOf("code", "issue", "pull_request", "review", "milestone", "board")
        staging.createStatement().use { it.execute("CREATE TABLE orphan_menu(id BIGINT PRIMARY KEY, project_id BIGINT, ${flags.joinToString(",") { flag -> "${quoted(flag)} BOOLEAN" }})") }
        staging.prepareStatement("INSERT INTO orphan_menu VALUES (?,?,?,?,?,?,?,?)").use { statement ->
            forEachNdjsonRow(file, execution) { row ->
                if (row.keys != (flags + listOf("id", "project_id")).toSet()) throw BadBackupArchiveException("Invalid menu provenance columns")
                val id = (row["id"] as? Number)?.toString()?.toLongOrNull() ?: throw BadBackupArchiveException("Invalid menu provenance identity")
                val project = row["project_id"]?.let { (it as? Number)?.toString()?.toLongOrNull() ?: throw BadBackupArchiveException("Invalid menu provenance project") }
                statement.setLong(1, id)
                statement.setObject(2, project)
                flags.forEachIndexed { index, flag ->
                    val value = row[flag]
                    if (value != null && value !is Boolean && value !in listOf(0, 1)) throw BadBackupArchiveException("Invalid menu provenance flag")
                    statement.setObject(index + 3, value)
                }
                statement.executeUpdate()
            }
        }
        if ("project" in archive.tables) staging.createStatement().use { sql ->
            sql.executeQuery("SELECT COUNT(*) FROM orphan_menu o JOIN ${staged("project")} p ON o.project_id=p.${quoted("id")}").use { rs ->
                rs.next()
                if (rs.getLong(1) != 0L) throw BadBackupArchiveException("Menu provenance is not orphaned")
            }
        }
    }

    private fun verifyContentFiles(staging: Connection, archive: Archive, execution: DataBackupExecution?) {
        if ("attachment" in archive.tables) {
            staging.createStatement().use { sql ->
                sql.executeQuery("SELECT ${quoted("hash")},${quoted("container_type")},${quoted("container_id")},${quoted("size")},${quoted("owner_login_id")} FROM ${staged("attachment")}").use { rows ->
                    while (rows.next()) {
                        execution?.checkpoint()
                        val hash = rows.getString(1) ?: throw BadBackupArchiveException("Missing attachment hash")
                        if (!hash.matches(Regex("[A-Za-z0-9_-]+"))) throw BadBackupArchiveException("Unsafe attachment hash")
                        val file = listOf(File(archive.tempRoot, "uploads/$hash"), File(archive.tempRoot, "data/uploads/$hash"))
                            .firstOrNull { it.isFile } ?: throw BadBackupArchiveException("Missing attachment file")
                        val declaredSize = rows.getLong(4)
                        if (!rows.wasNull() && file.length() != declaredSize) throw BadBackupArchiveException("Attachment size does not match its file")
                        val type = rows.getString(2)?.lowercase()
                        if (type == "not_a_resource") {
                            val owner = rows.getString(5) ?: throw BadBackupArchiveException("Temporary attachment has no owner")
                            if ("n4user" !in archive.tables) throw BadBackupArchiveException("Temporary attachment owner is absent")
                            staging.prepareStatement("SELECT COUNT(*) FROM ${staged("n4user")} WHERE LOWER(${quoted("login_id")})=?").use { lookup ->
                                lookup.setString(1, owner.lowercase())
                                lookup.executeQuery().use { rs -> rs.next(); if (rs.getLong(1) != 1L) throw BadBackupArchiveException("Unresolved temporary attachment owner") }
                            }
                            continue
                        }
                        val container = containerTables.entries.firstOrNull { type in it.value }?.key
                            ?: throw BadBackupArchiveException("Unsupported attachment container")
                        // A full-site snapshot preserves unbound records after resource deletion;
                        // project imports must still carry every referenced container.
                        if (archive.scope == DataBackupService.SCOPE_SITE) continue
                        val id = rows.getString(3) ?: throw BadBackupArchiveException("Missing attachment container")
                        if (container !in archive.tables) throw BadBackupArchiveException("Missing attachment container table")
                        staging.prepareStatement("SELECT COUNT(*) FROM ${staged(container)} WHERE ${quoted("id") }=?").use { lookup ->
                            lookup.setString(1, id)
                            lookup.executeQuery().use { rs -> rs.next(); if (rs.getLong(1) != 1L) throw BadBackupArchiveException("Unresolved attachment container") }
                        }
                    }
                }
            }
        }
        archive.tables["project"]?.let { projects ->
            forEachNdjsonRow(projects, execution) { project ->
                val owner = project.value("owner")?.toString() ?: throw BadBackupArchiveException("Missing repository owner")
                val name = project.value("name")?.toString() ?: throw BadBackupArchiveException("Missing repository name")
                validateEntryPath("$owner/$name")
                if (owner.contains('/') || name.contains('/')) throw BadBackupArchiveException("Unsafe repository identity")
                val vcs = project.value("vcs")?.toString()?.uppercase() ?: "GIT"
                if (archive.migration) {
                    when (vcs) {
                        "GIT" -> if (!File(archive.tempRoot, "git/$owner/$name.git/HEAD").isFile) throw BadBackupArchiveException("Missing Git repository")
                        "SVN", "SUBVERSION" -> if (!File(archive.tempRoot, "svn/$owner/$name/db/current").isFile ||
                            !File(archive.tempRoot, "svn/$owner/$name/format").isFile
                        ) throw BadBackupArchiveException("Missing SVN repository")
                        else -> throw BadBackupArchiveException("Unsupported migration repository type")
                    }
                }
            }
        }
    }

    private fun replaceApplicationData(source: File, execution: DataBackupExecution?, replace: (File, File) -> Unit) {
        val rootPath = applicationDataDir.canonicalFile.toPath()
        val queuePath = queueDataDir.canonicalFile.toPath()
        val protectedRoots = mutableSetOf("h2") // The running H2 database is restored through logical table rows.
        if (queuePath != rootPath && queuePath.startsWith(rootPath)) {
            protectedRoots += rootPath.relativize(queuePath).getName(0).toString()
        }
        val indexPath = searchIndexDir.canonicalFile.toPath()
        if (indexPath != rootPath && indexPath.startsWith(rootPath)) {
            protectedRoots += rootPath.relativize(indexPath).getName(0).toString()
        }
        applicationDataDir.mkdirs()
        val incoming = source.listFiles().orEmpty().associateBy { it.name }
        for (current in applicationDataDir.listFiles().orEmpty()) {
            execution?.checkpoint()
            if (current.name !in protectedRoots && current.name !in incoming) current.deleteRecursively()
        }
        for ((name, file) in incoming) {
            if (name !in protectedRoots) replace(file, File(applicationDataDir, name))
        }
    }

    /** 임시 디렉터리에 받둔 파일들을 실제 위치로 옮긴다. site는 루트 통째 교체, project는 해당 프로젝트만. */
    private fun swapInImportedFiles(archive: Archive, execution: DataBackupExecution?) {
        execution?.stage("import.files")
        if (archive.files.isEmpty() && archive.scope != DataBackupService.SCOPE_SITE) return
        var swapped = 0
        fun replaceDir(source: File, destination: File) {
            val parent = destination.absoluteFile.parentFile ?: throw IllegalStateException("No parent for $destination")
            parent.mkdirs()
            val stage = File(parent, ".${destination.name}.restore-${java.util.UUID.randomUUID()}")
            val backup = File(parent, ".${destination.name}.backup-${java.util.UUID.randomUUID()}")
            if (!source.renameTo(stage)) {
                source.walkTopDown().forEach { file ->
                    execution?.checkpoint()
                    val target = stage.resolve(file.relativeTo(source))
                    if (file.isDirectory) target.mkdirs()
                    else file.inputStream().use { input ->
                        target.outputStream().use { output -> copyArchiveBytes(input, output, execution) }
                    }
                }
            }
            execution?.checkpoint()
            val hadDestination = destination.exists()
            if (hadDestination && !destination.renameTo(backup)) {
                stage.deleteRecursively()
                throw IllegalStateException("Could not stage existing directory: $destination")
            }
            try {
                if (!stage.renameTo(destination)) throw IllegalStateException("Could not install restored directory: $destination")
            } catch (failure: Throwable) {
                if (hadDestination && backup.exists()) backup.renameTo(destination)
                throw failure
            } finally {
                stage.deleteRecursively()
            }
            backup.deleteRecursively()
        }
        when (archive.scope) {
            DataBackupService.SCOPE_SITE -> {
                for (name in listOf("git", "svn", "lfs", "data", "uploads")) {
                    val source = File(archive.tempRoot, name)
                    if (source.exists()) {
                        when (name) {
                            "git" -> replaceDir(source, gitBaseDir)
                            "svn" -> replaceDir(source, svnBaseDir)
                            "lfs" -> replaceDir(source, lfsBaseDir)
                            "data" -> replaceApplicationData(source, execution, ::replaceDir)
                            else -> replaceDir(source, uploadBaseDir)
                        }
                        swapped++
                    }
                }
            }

            DataBackupService.SCOPE_PROJECT -> {
                // 아카이브 엔트리는 원본 owner/name으로 들어온다. DB 프로젝트는 이름 충돌 시
                // 실효 이름으로 바뀌므로, 저장소/LFS 목적지도 실효 이름으로 매핑해야 한다 —
                // 그렇지 않으면 충돌 merge가 기존 본사 저장소를 원본 이름으로 덮어써버린다.
                val originalOwner = archive.originalProject?.substringBefore('/')
                val originalName = archive.originalProject?.substringAfter('/')
                val effectiveOwner = archive.project?.substringBefore('/')
                val effectiveName = archive.project?.substringAfter('/')
                if (originalOwner == null || originalName == null || effectiveOwner == null || effectiveName == null) return

                val gitSource = File(archive.tempRoot, "git/$originalOwner")
                if (gitSource.isDirectory) {
                    for (repo in gitSource.listFiles()!!) {
                        // <name>.git / <name>.wiki.git → 실효 이름 기반 목적지로 치환
                        val withoutGit = repo.name.removeSuffix(".git")
                        val isWiki = withoutGit.endsWith(".wiki")
                        val base = if (isWiki) withoutGit.removeSuffix(".wiki") else withoutGit
                        val destinationName = base.replaceFirst(originalName, effectiveName) + (if (isWiki) ".wiki" else "") + ".git"
                        replaceDir(repo, File(gitBaseDir, "$effectiveOwner/$destinationName"))
                        swapped++
                    }
                }
                val svnSource = File(archive.tempRoot, "svn/$originalOwner/$originalName")
                if (svnSource.isDirectory) {
                    replaceDir(svnSource, File(svnBaseDir, "$effectiveOwner/$effectiveName"))
                    swapped++
                }
                val lfsSource = File(archive.tempRoot, "lfs/$originalOwner/$originalName")
                if (lfsSource.isDirectory) {
                    replaceDir(lfsSource, File(lfsBaseDir, "$effectiveOwner/$effectiveName"))
                    swapped++
                }
            }
        }
        if (archive.scope == DataBackupService.SCOPE_PROJECT) {
            for (file in archive.files.filter { it.entryName.startsWith("uploads/") }) {
                execution?.checkpoint()
                val destination = File(uploadBaseDir, file.entryName.removePrefix("uploads/"))
                destination.parentFile?.mkdirs()
                // 내용 해시명이라 대상이 이미 있으면 같은 내용이다 — 재사용한다.
                if (destination.isFile || file.target.renameTo(destination)) swapped++
            }
        }
        logger.info("백업 파일 복원 완료: ${swapped}개 항목")
    }

    // ---------------------------------------------------------------- 프로젝트 폐쇄곡선

    private data class Fk(val fkTable: String, val pkTable: String, val fkColumns: List<String>, val pkColumns: List<String>)

    /**
     * 공유 참조 데이터(사용자·역할·조직·사용자 보안 행) — 프로젝트 백업에는 "참조된 행"만 담고
     * 이 테이블들에서 더 탐색하지 않는다. n4user를 통해 project_user → project B(다른 프로젝트)
     * 나 사용자 보안/기기 행 전체가 새어드는 것을 막는다(site 격리의 핵심).
     */
    private val terminalTables = setOf(
        "n4user", "role", "organization", "email", "ssh_key", "gpg_key", "gpg_key_verified_email",
        "api_token", "api_token_scope", "user_known_device", "user_setting", "user_backup_code",
        "user_totp_credential", "user_verification", "user_webauthn_credential",
        "oauth_authorization", "oauth_authorization_consent", "oauth_registered_client",
        "saml2_sso_settings", "oidc_sso_settings", "audit_log", "property",
    )

    /** attachment.container_id(문자열 컬럼, FK 아님)가 가리키는 컨테이너 테이블 매핑. */
    private val containerTables: Map<String, List<String>> = mapOf(
        "project" to listOf("project", "project_setting"),
        "issue" to listOf("issue_post"),
        "posting" to listOf("board_post"),
        "milestone" to listOf("milestone"),
        "issue_comment" to listOf("issue_comment"),
        "posting_comment" to listOf("nonissue_comment"),
        "comment_thread" to listOf("comment_thread"),
        "review_comment" to listOf("review_comment"),
        "issue_label" to listOf("issue_label"),
        "label" to listOf("label"),
        "issue_label_category" to listOf("issue_label_category"),
        "webhook" to listOf("webhook"),
        "n4user" to listOf("user", "user_avatar"),
        "organization" to listOf("organization"),
        "commit_comment" to listOf("commit_comment", "code_comment"),
        "pull_request" to listOf("pull_request"),
    )

    private fun foreignKeys(metadata: DatabaseMetaData, tables: Collection<String>): Map<String, List<Fk>> {
        data class Part(val seq: Int, val pkTable: String, val fkColumn: String, val pkColumn: String)

        val grouped = HashMap<String, LinkedHashMap<String, MutableList<Part>>>()
        for (table in tables) {
            for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
                metadata.getImportedKeys(null, null, candidate).use { rs ->
                    while (rs.next()) {
                        val pkTable = rs.getString("PKTABLE_NAME")
                        if (isOperationalTable(pkTable)) continue
                        val name = rs.getString("FK_NAME") ?: "fk_${rs.getInt("KEY_SEQ")}"
                        grouped.getOrPut(table.lowercase()) { LinkedHashMap() }
                            .getOrPut("$candidate/$name") { mutableListOf() }
                            .add(Part(rs.getInt("KEY_SEQ"), pkTable, rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")))
                    }
                }
            }
        }
        val result = HashMap<String, List<Fk>>()
        for ((table, byName) in grouped) {
            result[table] = byName.values.map { parts ->
                val sorted = parts.sortedBy { it.seq }
                Fk(table.lowercase(), sorted.first().pkTable.lowercase(), sorted.map { it.fkColumn }, sorted.map { it.pkColumn })
            }
        }
        return result
    }
    private fun foreignKeyClosure(
        connection: Connection,
        seed: Map<String, Any?>,
        spoolRoot: File,
        execution: DataBackupExecution?,
    ): LinkedHashMap<String, File> {
        val metadata = connection.metaData
        val allTables = listTables(connection).filterNot { isOperationalTable(it) }
        val tableNames = allTables.associateBy { it.lowercase() }
        val fks = foreignKeys(metadata, allTables)
        val files = LinkedHashMap<String, File>()
        val writers = HashMap<String, java.io.BufferedWriter>()
        val seen = HashMap<String, HashSet<String>>()

        fun pkColumnsOf(table: String): List<String> = primaryKeyColumns(metadata, tableNames[table] ?: table)

        fun traversalRow(table: String, row: Map<String, Any?>): Map<String, Any?> {
            val columns = (pkColumnsOf(table) + fks[table].orEmpty().flatMap { it.fkColumns })
                .map { it.lowercase() }.toSet()
            return row.filterKeys { it.lowercase() in columns }
        }

        fun add(table: String, row: Map<String, Any?>): Boolean {
            execution?.row()
            val key = table.lowercase()
            val identity = pkColumnsOf(key).joinToString("\u0000") { row.value(it)?.toString() ?: "" }
            if (!seen.getOrPut(key) { HashSet() }.add(identity)) return false
            val file = files.getOrPut(key) { File(spoolRoot, "db/$key.ndjson").also { it.parentFile?.mkdirs() } }
            val writer = writers.getOrPut(key) { java.io.FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8) }
            writer.write(objectMapper.writeValueAsString(if (key == "n4user") projectUserReference(row) else row))
            writer.newLine()
            return true
        }

        val queue = ArrayDeque<Pair<String, Map<String, Any?>>>()
        if (add("project", seed)) queue.add("project" to traversalRow("project", seed))

        try {
            while (queue.isNotEmpty()) {
                execution?.checkpoint()
                val (table, row) = queue.removeFirst()
                // 공유 참조 데이터는 행만 포함한다 — 자식/부모 탐색을 계속하면 다른 프로젝트와
                // 사용자 보안 행 전체가 따라 들어온다.
                if (table in terminalTables) continue
                for (fk in fks[table].orEmpty()) {
                    // 나가는 방향: 이 행이 참조하는 부모 행도 함께 내보낸다(작성자 사용자 등).
                    val values = fk.fkColumns.map { row.value(it) }
                    if (values.any { it == null }) continue
                    val identity = values.joinToString("\u0000")
                    if (seen.getOrPut(fk.pkTable) { HashSet() }.contains(identity)) continue
                    val parentTable = tableNames[fk.pkTable] ?: continue
                    val where = fk.pkColumns.joinToString(" AND ") { "$it = ?" }
                    jdbcTemplate.queryForList("SELECT * FROM $parentTable WHERE $where", *values.toTypedArray())
                        .forEach { parent ->
                            if (add(fk.pkTable, parent)) queue.add(fk.pkTable to traversalRow(fk.pkTable, parent))
                        }
                }
                for ((childTable, childFks) in fks) {
                    // 들어오는 방향: 이 행을 참조하는 자식 행도 함께 내보낸다(이슈, 댓글 등).
                    for (child in childFks.filter { it.pkTable == table }) {
                        // project → project 자기 참조(fork)는 별개 프로젝트다 — 폐쇄곡선에 넣지 않는다.
                        if (childTable == "project" && table == "project") continue
                        val childValues = child.pkColumns.map { row.value(it) }
                        if (childValues.any { it == null }) continue
                        val childName = tableNames[childTable] ?: continue
                        val where = child.fkColumns.joinToString(" AND ") { "$it = ?" }
                        connection.prepareStatement("SELECT * FROM $childName WHERE $where").use { statement ->
                            statement.fetchSize = STREAM_FETCH_SIZE
                            childValues.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                            statement.executeQuery().use { rs ->
                                val columns = columnLabels(rs)
                                while (rs.next()) {
                                    val childRow = rowMap(rs, columns)
                                    if (add(childTable, childRow)) {
                                        queue.add(childTable to traversalRow(childTable, childRow))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            writers.values.forEach { it.close() }
        }
        return files
    }
    private fun projectUserReference(row: Map<String, Any?>): Map<String, Any?> = HashMap<String, Any?>().apply {
        row.filterKeys { it.lowercase() in setOf("id", "name", "english_name", "login_id", "email", "lang", "created_date") }
            .forEach { (key, value) -> put(key, value) }
        putValue("state", "ACTIVE")
        putValue("remember_me", false)
        putValue("is_guest", false)
        putValue("failed_login_attempts", 0)
    }

    /** FK 폐쇄곡선만으로는 container_id를 따라가지 못하므로 첨부를 별도로 spool한다. */
    private fun addContainerAttachments(connection: Connection, rows: LinkedHashMap<String, File>, execution: DataBackupExecution?) {
        val metadata = connection.metaData
        val existing = HashSet<String>()
        rows["attachment"]?.let { file -> forEachNdjsonRow(file, execution) { it.value("id")?.toString()?.let(existing::add) } }
        for ((table, typeValues) in containerTables) {
            val file = rows[table] ?: continue
            val pkColumn = primaryKeyColumns(metadata, table).firstOrNull() ?: continue
            val ids = LinkedHashSet<String>()
            forEachNdjsonRow(file, execution) { row -> row.value(pkColumn)?.toString()?.let(ids::add) }
            for (chunk in ids.chunked(500)) {
                val typePlaceholders = typeValues.joinToString(",") { "?" }
                val placeholders = chunk.joinToString(",") { "?" }
                val sql = "SELECT * FROM attachment WHERE LOWER(container_type) IN ($typePlaceholders) AND container_id IN ($placeholders)"
                forEachJdbcRow(connection, sql, typeValues + chunk, execution) { row ->
                    val id = row.value("id")?.toString() ?: return@forEachJdbcRow
                    if (existing.add(id)) appendNdjsonRow(rows, "attachment", row)
                }
            }
        }
    }

    /** issue.author_id처럼 FK 제약 없이 비정규화된 사용자 참조도 함께 spool한다. */
    private fun addReferencedUsers(connection: Connection, rows: LinkedHashMap<String, File>, execution: DataBackupExecution?) {
        val userRefPattern = Regex(".*_(author_id|user_id)$|^(author_id|user_id)$")
        val ids = LinkedHashSet<String>()
        for ((table, file) in rows) {
            if (table == "n4user") continue
            val pkColumns = primaryKeyColumns(connection.metaData, table).map { it.lowercase() }.toSet()
            forEachNdjsonRow(file, execution) { row ->
                for ((column, value) in row) {
                    val lower = column.lowercase()
                    if (value !is Number || lower in pkColumns || !userRefPattern.matches(lower)) continue
                    value.toString().toLongOrNull()?.let { ids.add(it.toString()) }
                }
            }
        }
        val owner = rows["project"]?.let { firstNdjsonRow(it)?.value("owner")?.toString() }
            ?.takeIf { it.isNotBlank() }
        val seen = HashSet<String>()
        rows["n4user"]?.let { file -> forEachNdjsonRow(file, execution) { it.value("id")?.toString()?.let(seen::add) } }
        for (chunk in ids.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            forEachJdbcRow(connection, "SELECT * FROM n4user WHERE id IN ($placeholders)", chunk, execution) { row ->
                row.value("id")?.toString()?.let { if (seen.add(it)) appendNdjsonRow(rows, "n4user", row) }
            }
        }
        if (owner != null) {
            forEachJdbcRow(connection, "SELECT * FROM n4user WHERE LOWER(login_id) = ?", listOf(owner.lowercase()), execution) { row ->
                row.value("id")?.toString()?.let { if (seen.add(it)) appendNdjsonRow(rows, "n4user", row) }
            }
        }
    }

    private fun appendNdjsonRow(rows: LinkedHashMap<String, File>, table: String, row: Map<String, Any?>) {
        val directory = rows.values.firstOrNull()?.parentFile ?: File(createTempDirectory(), "db")
        directory.mkdirs()
        val file = rows.getOrPut(table) { File(directory, "$table.ndjson") }
        val archivedRow = if (table == "n4user") projectUserReference(row) else row
        file.appendBytes(objectMapper.writeValueAsBytes(archivedRow) + byteArrayOf('\n'.code.toByte()))
    }

    private fun remapProjectId(
        table: String,
        value: Any?,
        referenceIds: Map<String, Map<String, Any?>>,
        offsets: Map<String, Long>,
        requiredReference: Boolean,
    ): Any? {
        if (value == null) return null
        val key = value.toString()
        if (table in sharedReferenceTables) {
            val mapped = referenceIds[table]?.get(key)
            if (mapped != null) return mapped
            if (requiredReference) throw BadBackupArchiveException("$table 참조 ID가 백업에 없습니다: $key")
            return value
        }
        val offset = offsets[table] ?: return value
        val id = key.toLongOrNull() ?: throw BadBackupArchiveException("$table 참조 ID가 정수가 아닙니다: $key")
        return Math.addExact(id, offset)
    }

    /** merge 시 attachment.container_id를 컨테이너 테이블의 새 식별자로 옮긴다. */
    private fun remapAttachmentContainer(
        row: MutableMap<String, Any?>,
        referenceIds: Map<String, Map<String, Any?>>,
        offsets: Map<String, Long>,
    ) {
        val type = row.value("container_type")?.toString()?.lowercase() ?: return
        val table = containerTables.entries.firstOrNull { type in it.value }?.key ?: return
        val old = row.value("container_id")?.toString() ?: return
        val mapped = remapProjectId(table, old, referenceIds, offsets, requiredReference = false)
        if (mapped.toString() != old) row.putValue("container_id", mapped.toString())
    }

    // ---------------------------------------------------------------- 공용 저수준 유틸

    /** DB별로 대소문자가 다른 컬럼 키를 무시하고 값을 꺼낸다. */
    private fun Map<String, Any?>.value(key: String): Any? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun MutableMap<String, Any?>.putValue(key: String, value: Any?) {
        val existing = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        if (existing != null) existing.setValue(value) else this[key] = value
    }

    private fun forEachJdbcRow(
        connection: Connection,
        sql: String,
        parameters: List<Any?>,
        execution: DataBackupExecution?,
        block: (Map<String, Any?>) -> Unit,
    ) {
        connection.prepareStatement(sql).use { statement ->
            statement.fetchSize = STREAM_FETCH_SIZE
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                val columns = columnLabels(rs)
                while (rs.next()) {
                    execution?.row()
                    block(rowMap(rs, columns))
                }
            }
        }
    }
    private fun writeManifest(zip: ZipOutputStream, scope: String, project: String?) {
        zipEntry(zip, "manifest.json") {
            it.write(objectMapper.writeValueAsBytes(mapOf(
                "format" to DataBackupService.FORMAT, "formatVersion" to DataBackupService.FORMAT_VERSION,
                "scope" to scope, "project" to project, "createdAt" to Instant.now().toString(),
                "sourceVersion" to DataBackupService.TARGET_VERSION, "targetVersion" to DataBackupService.TARGET_VERSION,
                "producer" to "yona", "producerVersion" to DataBackupService.TARGET_VERSION,
                "requiredCapabilities" to emptyList<String>(), "integrity" to DataBackupService.INTEGRITY,
            )))
        }
    }

    /** Spool entry digests to disk: archive size and attachment count do not grow the export heap. */
    private inner class ArchiveZip(output: OutputStream) : ZipOutputStream(output) {
        private val spool = File.createTempFile("yona-integrity", ".ndjson")
        private val index = spool.bufferedWriter(Charsets.UTF_8)
        private var entryName: String? = null
        private var size = 0L
        private var digest = MessageDigest.getInstance("SHA-256")
        private var finished = false

        override fun putNextEntry(entry: ZipEntry) {
            closeEntry()
            super.putNextEntry(entry)
            entryName = entry.name
            size = 0
            digest = MessageDigest.getInstance("SHA-256")
        }

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            super.write(bytes, offset, length)
            digest.update(bytes, offset, length)
            size += length
        }

        override fun closeEntry() {
            val name = entryName ?: return
            super.closeEntry()
            entryName = null
            if (name != "manifest.json" && name != "integrity.ndjson") {
                index.write(objectMapper.writeValueAsString(mapOf("path" to name, "size" to size,
                    "sha256" to HexFormat.of().formatHex(digest.digest()))))
                index.newLine()
            }
        }

        override fun finish() {
            if (finished) return
            closeEntry()
            index.close()
            super.putNextEntry(ZipEntry("integrity.ndjson"))
            spool.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    super.write(buffer, 0, count)
                }
            }
            super.closeEntry()
            super.finish()
            finished = true
        }

        override fun close() {
            try { super.close() } finally {
                index.close()
                spool.delete()
            }
        }
    }
    private fun zipEntry(zip: ZipOutputStream, name: String, write: (OutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        write(zip)
        zip.closeEntry()
    }

    private fun addTreeToZip(zip: ZipOutputStream, root: File, entryPrefix: String, execution: DataBackupExecution?) {
        if (!root.isDirectory) return
        if (java.nio.file.Files.isSymbolicLink(root.toPath())) throw IllegalStateException("Cannot archive a symbolic-link root")
        root.walkTopDown().onEnter { directory ->
            if (java.nio.file.Files.isSymbolicLink(directory.toPath())) throw IllegalStateException("Cannot archive a symbolic-link directory")
            true
        }.filter { it.isFile }.forEach { file ->
            val relative = file.relativeToOrNull(root)?.path ?: return@forEach
            addFileToZip(zip, file, "$entryPrefix/$relative", execution)
        }
    }

    private fun isWithin(child: File, parent: File): Boolean =
        child.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath())

    /** application data는 포함하되 활성 DB 파일과 durable queue의 파일 저장소는 제외한다. */
    private fun addApplicationDataToZip(zip: ZipOutputStream, execution: DataBackupExecution?) {
        if (!applicationDataDir.isDirectory) return
        val root = applicationDataDir.canonicalFile.toPath()
        val excluded = buildList {
            val queuePath = queueDataDir.canonicalFile.toPath()
            if (queuePath.startsWith(root)) add(queuePath)
            val indexPath = searchIndexDir.canonicalFile.toPath()
            if (indexPath.startsWith(root)) add(indexPath)
            val h2Path = File(applicationDataDir, "h2").canonicalFile.toPath()
            if (h2Path.startsWith(root)) add(h2Path)
        }
        applicationDataDir.walkTopDown()
            .onEnter { directory ->
                if (java.nio.file.Files.isSymbolicLink(directory.toPath())) throw IllegalStateException("Cannot archive a symbolic-link directory")
                val path = directory.canonicalFile.toPath()
                excluded.none { path == it || path.startsWith(it) }
            }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeToOrNull(applicationDataDir) ?: return@forEach
                addFileToZip(zip, file, "files/data/${relative.path}", execution)
            }
    }
    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String, execution: DataBackupExecution?) {
        execution?.checkpoint()
        validateEntryPath(entryName)
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) throw IllegalStateException("Cannot archive a symbolic-link file")
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { copyArchiveBytes(it, zip, execution) }
        zip.closeEntry()
    }

    private fun columnLabels(rs: ResultSet): List<String> {
        val meta = rs.metaData
        return (1..meta.columnCount).map { meta.getColumnLabel(it) }
    }

    private fun rowMap(rs: ResultSet, columns: List<String>): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>(columns.size)
        for (column in columns) row[column] = archiveValue(rs.getObject(column))
        return row
    }
    private fun archiveValue(value: Any?): Any? = when (value) {
        is Timestamp -> value.toInstant().toString()
        is java.sql.Date -> value.toLocalDate().toString()
        is java.sql.Time -> value.toLocalTime().toString()
        is java.time.OffsetDateTime -> value.toInstant().toString()
        is java.sql.Blob -> value.binaryStream.use { it.readBytes() }
        is java.sql.Clob -> value.characterStream.use { it.readText() }
        else -> value
    }

    private fun restoreTables(tables: Map<String, File>, sequences: Map<String, Long>, execution: DataBackupExecution?) {
        refuseIfQueueBusy(execution)
        execution?.stage("import.database")
        val dialect = detectDialect()
        doInForeignKeysOff(dialect) {
            execution?.beginMutation()
            for (table in tables.keys) {
                execution?.checkpoint()
                jdbcTemplate.update("DELETE FROM $table")
            }
            for ((table, file) in tables) {
                val dateTimeColumns = columnTypes(table)
                forEachNdjsonRow(file, execution) { row -> insertRow(table, row, dateTimeColumns) }
                val identity = dataSource.connection.use { connection ->
                    val actual = listTables(connection).first { it.equals(table, true) }
                    columns(connection, actual).singleOrNull { it.generated }
                }
                if (identity != null) {
                    val minimum = (jdbcTemplate.queryForObject("SELECT COALESCE(MAX(${identity.name}),0)+1 FROM $table", Long::class.java) ?: 1L)
                    restoreSequence(table, identity.name, dialect, maxOf(sequences[table] ?: minimum, minimum))
                }
            }
        }
    }

    private fun firstNdjsonRow(file: File): Map<String, Any?>? =
        ndjsonReader(file).useLines { lines ->
            lines.firstOrNull { it.isNotBlank() }?.let {
                archiveJson.readValue(it, Map::class.java) as Map<String, Any?>
            }
        }

    private fun forEachNdjsonRow(file: File, execution: DataBackupExecution? = null, block: (Map<String, Any?>) -> Unit) {
        ndjsonReader(file).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                execution?.row()
                block(archiveJson.readValue(line, Map::class.java) as Map<String, Any?>)
            }
        }
    }
    private fun ndjsonReader(file: File) = java.io.InputStreamReader(file.inputStream(),
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)).buffered()

    private fun refuseIfQueueBusy(execution: DataBackupExecution?) {
        // An exclusive queued import excludes only itself. Waiting jobs cannot be claimed until it returns.
        val unfinished = if (execution == null) jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM queue_job WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED','RECOVERY_REQUIRED')",
            Long::class.java,
        ) ?: 0L else jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM queue_job WHERE id <> ? AND status IN ('RUNNING','CANCEL_REQUESTED','RECOVERY_REQUIRED')",
            Long::class.java, execution.jobId,
        ) ?: 0L
        if (unfinished > 0) {
            throw UnfinishedQueueJobsException("완료되지 않은 작업 큐 작업이 ${unfinished}건 있어 import할 수 없습니다")
        }
    }

    private fun doInForeignKeysOff(dialect: Dialect, block: () -> Unit) {
        setForeignKeyChecks(dialect, enabled = false)
        try {
            block()
        } catch (e: Throwable) {
            // 복원 실패 원인을 FK 재활성화 실패(예: PostgreSQL 25P02)가 덮어쓰지 않도록 suppressed로 붙인다.
            try {
                setForeignKeyChecks(dialect, enabled = true)
            } catch (restoreFailure: Exception) {
                e.addSuppressed(restoreFailure)
            }
            throw e
        }
        setForeignKeyChecks(dialect, enabled = true)
    }

    // CUBRID JDBC 드라이버는 Connection.getSchema()(JDBC 4.1)를 지원하지 않아 UnsupportedOperationException을
    // (SQLException으로 감싸) 던진다. CUBRID는 접속 사용자가 곧 테이블 소유자(스키마)이므로 사용자 이름으로 대신한다.
    private fun schemaOf(connection: java.sql.Connection): String? =
        try {
            connection.schema
        } catch (e: UnsupportedOperationException) {
            connection.metaData.userName
        } catch (e: java.sql.SQLException) {
            if (e is java.sql.SQLFeatureNotSupportedException || e.cause is UnsupportedOperationException) {
                connection.metaData.userName
            } else {
                throw e
            }
        }

    private fun listTables(connection: Connection): List<String> {
        val tables = mutableListOf<String>()
        connection.metaData.getTables(connection.catalog, schemaOf(connection), "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"))
        }
        return tables.sorted()
    }

    private fun insertRow(table: String, row: Map<String, Any?>, columnTypes: Map<String, Int>) {
        if (row.isEmpty()) return
        val columns = row.keys.toList()
        val placeholders = columns.joinToString(",") { "?" }
        val sql = "INSERT INTO $table (${columns.joinToString(",")}) VALUES ($placeholders)"
        val values = columns.map { column -> coerceColumnValue(row[column], columnTypes.getValue(column.lowercase())) }
        jdbcTemplate.update(sql, *values.toTypedArray())
    }

    // export가 직렬화한 Instant 값은 import에서 타입 없는 Map으로 역직렬화되어 String이 된다.
    // MariaDB는 ISO-8601('T'/'Z')을 datetime으로 파싱하지 못하므로 TIMESTAMP/DATE/TIME 계열이면
    // String -> Instant -> Timestamp로 되돌려 바인딩한다.
    private fun coerceForInsert(value: Any?, isDateTimeColumn: Boolean): Any? {
        if (!isDateTimeColumn || value !is String) return value
        return try {
            Timestamp.from(Instant.parse(value))
        } catch (e: DateTimeParseException) {
            value
        }
    }

    private fun coerceColumnValue(value: Any?, type: Int): Any? {
        if (value == null) return null
        return when (type) {
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                if (value is String) java.util.Base64.getDecoder().decode(value) else value
            Types.DATE -> if (value is String) java.sql.Date.valueOf(value) else value
            Types.TIME -> if (value is String) java.sql.Time.valueOf(value) else value
            else -> coerceForInsert(value, isDateTime(type))
        }
    }

    private fun columnTypes(table: String): Map<String, Int> = insertTypes.computeIfAbsent(table.lowercase()) {
        dataSource.connection.use { connection ->
            val actual = listTables(connection).first { it.equals(table, true) }
            columns(connection, actual).associate { it.name to it.type }
        }
    }

    private fun primaryKeyColumns(metadata: DatabaseMetaData, table: String): List<String> {
        // H2는 unquoted 식별자를 대문자로 저장해 소문자 이름으로는 메타데이터가 비어 있다 — 후보 케이스로 재시도한다.
        for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
            metadata.getPrimaryKeys(null, null, candidate).use { rs ->
                val columns = sortedMapOf<Int, String>()
                while (rs.next()) columns[rs.getInt("KEY_SEQ")] = rs.getString("COLUMN_NAME")
                if (columns.isNotEmpty()) return columns.values.toList()
            }
        }
        return emptyList()
    }

    private fun isNumericColumn(metadata: DatabaseMetaData, table: String, column: String): Boolean {
        dataSource.connection.use { connection ->
            for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
                connection.metaData.getColumns(connection.catalog, schemaOf(connection), candidate, null).use { rs ->
                    while (rs.next()) {
                        if (rs.getString("COLUMN_NAME").equals(column, ignoreCase = true)) {
                            return when (rs.getInt("DATA_TYPE")) {
                                Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT -> true
                                else -> false
                            }
                        }
                    }
                }
            }
            // H2 2.x 등에서 getColumns가 빈 결과를 돌려주는 환경이 있다 — 실제 SELECT의 컬럼 타입으로 판정한다.
            connection.createStatement().use { statement ->
                statement.maxRows = 1
                statement.executeQuery("SELECT $column FROM $table").use { rs ->
                    return when (rs.metaData.getColumnType(1)) {
                        Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT -> true
                        else -> false
                    }
                }
            }
        }
    }

    private fun nextSequenceValue(connection: Connection, table: String, dialect: Dialect): Long? {
        val metadata = connection.metaData
        return when (dialect) {
            Dialect.MYSQL_COMPATIBLE -> jdbcTemplate.queryForObject(
                "SELECT AUTO_INCREMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                Long::class.java, connection.catalog, table
            )?.toLong()
            // 조인 테이블처럼 숫자 id가 없는 테이블에 pg_get_serial_sequence()를 호출하면 예외가 나므로
            // 먼저 PK가 숫자 단일 컬럼인지 확인한다.
            Dialect.POSTGRES -> {
                val pk = primaryKeyColumns(metadata, table)
                if (pk.size != 1 || !columns(connection, table).any { it.generated && it.name.equals(pk[0], true) }) return null
                val sequenceName = jdbcTemplate.queryForObject(
                    "SELECT pg_get_serial_sequence(?, ?)", String::class.java, table, pk[0]
                ) ?: return null
                jdbcTemplate.queryForObject(
                    "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM $sequenceName",
                    Long::class.java
                )?.toLong()
            }
            // H2 identity 컬럼의 "다음 값"은 표준 조회가 없어 MAX+1로 근사한다(갭 보존 불가 — 문서화된 한계).
            Dialect.H2 -> {
                val pk = primaryKeyColumns(metadata, table)
                if (pk.size != 1 || !columns(connection, table).any { it.generated && it.name.equals(pk[0], true) }) return null
                jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(${pk[0]}), 0) + 1 FROM $table", Long::class.java
                )?.toLong()
            }
            Dialect.CUBRID, Dialect.OTHER -> null
        }
    }

    private fun restoreSequence(table: String, pkColumn: String, dialect: Dialect, nextValue: Long) {
        when (dialect) {
            Dialect.MYSQL_COMPATIBLE -> jdbcTemplate.execute("ALTER TABLE $table AUTO_INCREMENT = $nextValue")
            Dialect.POSTGRES -> {
                val sequenceName = jdbcTemplate.queryForObject(
                    "SELECT pg_get_serial_sequence(?, ?)", String::class.java, table, pkColumn
                ) ?: return
                // setval(seq, nextValue, false): 다음 nextval()이 정확히 nextValue를 반환한다.
                jdbcTemplate.queryForObject("SELECT setval(?, ?, false)", Long::class.java, sequenceName, nextValue)
            }
            Dialect.H2 -> jdbcTemplate.execute("ALTER TABLE $table ALTER COLUMN $pkColumn RESTART WITH $nextValue")
            Dialect.CUBRID, Dialect.OTHER -> logger.warn("알 수 없는 DB 방언이라 $table 의 auto-increment/시퀀스를 재설정하지 않습니다")
        }
    }

    // role 같은 이름이 CUBRID 예약어라(테이블 이름을 만드는 YonaCubridNamingStrategy 참고) 인용하지 않으면
    // SELECT가 문법 오류가 된다. 다른 DBMS는 기존처럼 이름을 그대로 쓴다.
    private fun exportTableRef(table: String, dialect: Dialect): String =
        if (dialect == Dialect.CUBRID) "\"$table\"" else table

    private fun detectDialect(): Dialect {
        dataSource.connection.use { connection ->
            val product = connection.metaData.databaseProductName ?: ""
            return when {
                product.contains("MySQL", ignoreCase = true) || product.contains("MariaDB", ignoreCase = true) ->
                    Dialect.MYSQL_COMPATIBLE
                product.contains("PostgreSQL", ignoreCase = true) -> Dialect.POSTGRES
                product.contains("H2", ignoreCase = true) -> Dialect.H2
                product.contains("CUBRID", ignoreCase = true) -> Dialect.CUBRID
                else -> Dialect.OTHER
            }
        }
    }

    private fun setForeignKeyChecks(dialect: Dialect, enabled: Boolean) {
        when (dialect) {
            Dialect.MYSQL_COMPATIBLE -> jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = ${if (enabled) 1 else 0}")
            Dialect.POSTGRES -> jdbcTemplate.execute("SET session_replication_role = '${if (enabled) "origin" else "replica"}'")
            // SET REFERENTIAL_INTEGRITY — H2 고유 구문.
            Dialect.H2 -> jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY ${if (enabled) "TRUE" else "FALSE"}")
            Dialect.CUBRID, Dialect.OTHER -> logger.warn("알 수 없는 DB 방언이라 외래키 제약을 토글하지 않습니다")
        }
    }

    private fun createTempDirectory(): File = java.nio.file.Files.createTempDirectory("yona-import-").toFile()

    companion object {
        private const val STREAM_FETCH_SIZE = 1000
        private const val ORPHAN_MENU_ENTRY = "migration/orphan-project-menu-settings.ndjson"
    }
}
